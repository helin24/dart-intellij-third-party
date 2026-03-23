// Copyright 2025 the Dart project authors. Use of this source code is governed by a BSD-style license
// that can be found in the LICENSE file.
package com.jetbrains.lang.dart.analyzer;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.jetbrains.lang.dart.logging.PluginLogger;
import com.redhat.devtools.lsp4ij.server.StreamConnectionProvider;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * A virtual stream connection provider that bridges lsp4ij (LSP client) and
 * the Dart Analysis Server (legacy protocol) via piped streams.
 *
 * <h3>Architecture</h3>
 * <p>lsp4ij creates a {@code Launcher} that reads LSP responses from
 * {@link #getInputStream()} and writes LSP requests to {@link #getOutputStream()}.
 * A background reader thread reads the outgoing requests from the piped stream,
 * handles protocol-level messages (initialize, shutdown) locally, and forwards
 * supported methods (e.g. {@code textDocument/hover}) to the Dart Analysis
 * Server via its legacy {@code lsp.handle} protocol. Responses from the server
 * containing {@code lspMessage} or {@code lspResponse} payloads are written back
 * into the pipe that feeds {@link #getInputStream()} so lsp4ij can consume them.</p>
 *
 * <h3>Why manual Content-Length framing?</h3>
 * <p>lsp4ij's {@code Launcher} writes properly framed LSP messages (with
 * {@code Content-Length} headers) to {@link #getOutputStream()}. Since we read
 * from the piped end of that stream in our own thread (outside lsp4j's
 * infrastructure), we parse the framing manually. Similarly, responses written
 * back into {@link #getInputStream()} must be properly framed so lsp4ij's
 * internal {@code StreamMessageProducer} can decode them.</p>
 */
class DartVirtualStreamConnectionProvider implements StreamConnectionProvider {

  private static final Logger LOG = PluginLogger.INSTANCE.createLogger(DartVirtualStreamConnectionProvider.class);

  // lsp4ij reads LSP responses from ideInputStream.
  // We write responses into serverOutputStream, which is piped to ideInputStream.
  private final PipedInputStream ideInputStream = new PipedInputStream(1024 * 1024);
  private final PipedOutputStream serverOutputStream = new PipedOutputStream();

  // lsp4ij writes LSP requests into ideOutputStream.
  // Our background thread reads from serverInputStream, which is piped to ideOutputStream.
  private final PipedInputStream serverInputStream = new PipedInputStream(1024 * 1024);
  private final PipedOutputStream ideOutputStream = new PipedOutputStream();

  private final Project project;
  private volatile boolean alive = false;

  DartVirtualStreamConnectionProvider(Project project) {
    this.project = project;
    try {
      ideInputStream.connect(serverOutputStream);
      serverInputStream.connect(ideOutputStream);
    } catch (IOException e) {
      LOG.warn("Failed to connect dart virtual streams", e);
    }
  }

  @Override
  public InputStream getInputStream() {
    return ideInputStream;
  }

  @Override
  public OutputStream getOutputStream() {
    return ideOutputStream;
  }

  @Override
  public void start() {
    alive = true;
    LOG.info("★★★ LSP-over-legacy: Virtual server starting for project: " + project.getName() + " ★★★");
    ApplicationManager.getApplication().executeOnPooledThread(this::runVirtualServer);
  }

  @Override
  public void stop() {
    alive = false;
  }

  @Override
  public boolean isAlive() {
    return alive;
  }

  /**
   * Main loop that reads LSP messages from lsp4ij, handles protocol-level
   * messages locally (initialize, shutdown), and forwards supported LSP
   * methods (textDocument/hover) to the Dart Analysis Server.
   */
  private void runVirtualServer() {
    DartAnalysisServerService dasService = DartAnalysisServerService.getInstance(project);

    com.google.dart.server.ResponseListener dasListener = response -> {
      try {
        JsonObject jsonObject = JsonParser.parseString(response).getAsJsonObject();
        JsonObject lspPayload = extractLspPayload(jsonObject);

        if (lspPayload != null && alive) {
          LOG.info("★★★ LSP-over-legacy: Sending hover response back to lsp4ij ★★★");
          LOG.debug("Dart server sent lsp payload: " + lspPayload);
          writeMessage(serverOutputStream, lspPayload.toString());
        }
      } catch (JsonParseException e) {
        LOG.warn("Failed to parse lsp payload from Dart server", e);
      } catch (IOException e) {
        if (alive) {
          LOG.warn("Failed to write lsp payload to stream", e);
        }
      }
    };

    dasService.addResponseListener(dasListener);

    try {
      while (alive) {
        String jsonPayload = readNextMessage(serverInputStream);
        if (jsonPayload == null) break;

        try {
          JsonObject lspMessage = JsonParser.parseString(jsonPayload).getAsJsonObject();
          String method = lspMessage.has("method") ? lspMessage.get("method").getAsString() : null;

          if ("initialize".equals(method)) {
            handleInitialize(lspMessage);
          } else if ("shutdown".equals(method)) {
            handleShutdown(lspMessage);
          } else if ("textDocument/hover".equals(method)) {
            LOG.info("★★★ LSP-over-legacy: Received hover request via lsp4ij ★★★");
            forwardToDas(dasService, lspMessage);
          } else {
            LOG.debug("Ignored lsp4ij request: " + method);
          }
        } catch (JsonParseException e) {
          LOG.warn("Failed to parse lsp4ij message", e);
        }
      }
    } catch (Exception e) {
      if (alive) {
        LOG.warn("Virtual Dart Server stream exception", e);
      }
    } finally {
      dasService.removeResponseListener(dasListener);
    }
  }

  /**
   * Extracts the LSP payload from a Dart Analysis Server response.
   * The server wraps LSP payloads in either {@code params.lspMessage}
   * (for notifications) or {@code result.lspResponse} (for responses).
   */
  static JsonObject extractLspPayload(JsonObject jsonObject) {
    if (jsonObject.has("params")) {
      JsonObject params = jsonObject.getAsJsonObject("params");
      if (params.has("lspMessage")) {
        return params.getAsJsonObject("lspMessage");
      }
    }
    if (jsonObject.has("result")) {
      JsonObject result = jsonObject.getAsJsonObject("result");
      if (result.has("lspResponse")) {
        return result.getAsJsonObject("lspResponse");
      }
    }
    return null;
  }

  /**
   * Sends a fake {@code initialize} response to lsp4ij with the capabilities
   * we currently proxy (hover). Uses Gson objects to construct the JSON safely,
   * avoiding JSON injection via raw string concatenation.
   */
  private void handleInitialize(JsonObject lspMessage) throws IOException {
    JsonObject response = new JsonObject();
    response.addProperty("jsonrpc", "2.0");
    response.add("id", lspMessage.get("id"));

    JsonObject capabilities = new JsonObject();
    capabilities.addProperty("hoverProvider", true);

    JsonObject completionProvider = new JsonObject();
    completionProvider.addProperty("resolveProvider", false);
    capabilities.add("completionProvider", completionProvider);

    JsonObject result = new JsonObject();
    result.add("capabilities", capabilities);
    response.add("result", result);

    writeMessage(serverOutputStream, response.toString());
    LOG.info("★★★ LSP-over-legacy: Sent fake initialize response to lsp4ij ★★★");
  }

  /**
   * Sends a fake {@code shutdown} response to lsp4ij.
   */
  private void handleShutdown(JsonObject lspMessage) throws IOException {
    if (lspMessage.has("id") && !lspMessage.get("id").isJsonNull()) {
      JsonObject response = new JsonObject();
      response.addProperty("jsonrpc", "2.0");
      response.add("id", lspMessage.get("id"));
      response.add("result", null);

      writeMessage(serverOutputStream, response.toString());
      LOG.debug("Sent fake shutdown response to lsp4ij via streams");
    }
  }

  /**
   * Forwards an LSP request to the Dart Analysis Server using the legacy
   * {@code lsp.handle} protocol method.
   */
  private void forwardToDas(DartAnalysisServerService dasService, JsonObject lspMessage) {
    LOG.debug("Forwarding hover request to Dart server");

    JsonObject legacyRequest = new JsonObject();
    String legacyId = dasService.generateUniqueId();
    legacyRequest.addProperty("id", legacyId);
    legacyRequest.addProperty("method", "lsp.handle");

    JsonObject params = new JsonObject();
    params.add("lspMessage", lspMessage);
    legacyRequest.add("params", params);

    dasService.sendRequest(legacyId, legacyRequest);
  }

  /**
   * Reads the next LSP message from the input stream using the standard
   * LSP base protocol: reads {@code Content-Length} header, then reads
   * exactly that many bytes for the JSON body.
   *
   * @return the JSON string body, or {@code null} if the stream ended.
   */
  private String readNextMessage(InputStream in) throws IOException {
    StringBuilder headers = new StringBuilder();
    int c;
    // Limit header size to 8KB to prevent memory exhaustion
    final int MAX_HEADER_SIZE = 8192;
    while ((c = in.read()) != -1) {
      headers.append((char) c);
      if (headers.length() > MAX_HEADER_SIZE) {
        LOG.warn("LSP message headers exceeded maximum size of " + MAX_HEADER_SIZE + " bytes");
        return null;
      }
      if (headers.toString().endsWith("\r\n\r\n")) {
        break;
      }
    }
    if (c == -1) return null;

    int contentLength = -1;
    for (String header : headers.toString().split("\r\n")) {
      if (header.startsWith("Content-Length: ")) {
        try {
          contentLength = Integer.parseInt(header.substring("Content-Length: ".length()).trim());
        } catch (NumberFormatException e) {
          LOG.warn("Invalid Content-Length header: " + header, e);
          return null;
        }
      }
    }

    if (contentLength == -1) return null;

    // Limit message body size to 10MB to prevent memory exhaustion
    final int MAX_BODY_SIZE = 10 * 1024 * 1024;
    if (contentLength > MAX_BODY_SIZE) {
      LOG.warn("LSP message body size " + contentLength + " exceeds maximum of " + MAX_BODY_SIZE + " bytes");
      return null;
    }
    if (contentLength < 0) {
      LOG.warn("Invalid negative Content-Length: " + contentLength);
      return null;
    }

    byte[] body = new byte[contentLength];
    int read = 0;
    while (read < contentLength) {
      int r = in.read(body, read, contentLength - read);
      if (r == -1) return null;
      read += r;
    }

    return new String(body, StandardCharsets.UTF_8);
  }

  /**
   * Writes an LSP message (with {@code Content-Length} header) to the output stream.
   * Synchronized to prevent interleaving when multiple threads write responses.
   */
  private synchronized void writeMessage(OutputStream out, String json) throws IOException {
    byte[] body = json.getBytes(StandardCharsets.UTF_8);
    String header = "Content-Length: " + body.length + "\r\n\r\n";
    out.write(header.getBytes(StandardCharsets.UTF_8));
    out.write(body);
    out.flush();
  }
}
