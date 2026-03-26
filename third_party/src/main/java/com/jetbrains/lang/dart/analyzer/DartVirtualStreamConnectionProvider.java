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
import org.eclipse.lsp4j.CompletionOptions;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.jsonrpc.json.MessageJsonHandler;
import org.eclipse.lsp4j.jsonrpc.json.StreamMessageProducer;
import org.eclipse.lsp4j.jsonrpc.messages.NotificationMessage;
import org.eclipse.lsp4j.jsonrpc.messages.RequestMessage;
import org.eclipse.lsp4j.jsonrpc.messages.ResponseMessage;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * A virtual stream connection provider that bridges lsp4ij (LSP client) and
 * the Dart Analysis Server (legacy protocol) via piped streams.
 *
 * <h3>Architecture</h3>
 * <p>lsp4ij creates a {@code Launcher} that reads LSP responses from
 * {@link #getInputStream()} and writes LSP requests to {@link #getOutputStream()}.
 * A background thread uses lsp4j's {@link StreamMessageProducer} to read the
 * outgoing requests from the piped stream, handles protocol-level messages
 * (initialize, shutdown) locally, and forwards supported methods
 * (e.g. {@code textDocument/hover}) to the Dart Analysis Server via its
 * legacy {@code lsp.handle} protocol.</p>
 *
 * <h3>Response path — DartMessageProducer</h3>
 * <p>When a {@link DartMessageProducer} is registered (via
 * {@link DartMessageProducerRegistry}), DAS responses are enqueued directly
 * as JSON strings into the producer, bypassing the piped streams for the
 * response direction. The producer feeds parsed {@code Message} objects to
 * lsp4j's pipeline, eliminating Content-Length framing on the response path.</p>
 *
 * <p>If no producer is registered (fallback mode), responses are written as
 * Content-Length framed bytes to the pipe that feeds {@link #getInputStream()},
 * which lsp4ij's {@code StreamMessageProducer} can read normally.</p>
 */
class DartVirtualStreamConnectionProvider implements StreamConnectionProvider {

  private static final Logger LOG = PluginLogger.INSTANCE.createLogger(DartVirtualStreamConnectionProvider.class);

  /**
   * JSON handler with LSP4J type adapters for parsing incoming LSP requests
   * and serializing outgoing LSP responses. Uses an empty method map so that
   * request params are preserved as raw {@code JsonElement} (no type coercion).
   */
  private static final MessageJsonHandler JSON_HANDLER = new MessageJsonHandler(Map.of());

  // lsp4ij reads LSP responses from ideInputStream.
  // We write responses into serverOutputStream, which is piped to ideInputStream.
  // (Used only in fallback mode when DartMessageProducer is not available)
  private final PipedInputStream ideInputStream = new PipedInputStream(1024 * 1024);
  private final PipedOutputStream serverOutputStream = new PipedOutputStream();

  // lsp4ij writes LSP requests into ideOutputStream.
  // Our background thread reads from serverInputStream via StreamMessageProducer.
  private final PipedInputStream serverInputStream = new PipedInputStream(1024 * 1024);
  private final PipedOutputStream ideOutputStream = new PipedOutputStream();

  private final Project project;
  private volatile boolean alive = false;
  private volatile StreamMessageProducer requestReader;

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
    // Stop the StreamMessageProducer to unblock the listen() loop
    if (requestReader != null) {
      requestReader.close();
    }
    try {
      serverInputStream.close();
    } catch (IOException ignored) {
    }
    // Clean up the producer registration
    DartMessageProducer producer = DartMessageProducerRegistry.get(project);
    if (producer != null) {
      producer.close();
      DartMessageProducerRegistry.unregister(project);
    }
  }

  @Override
  public boolean isAlive() {
    return alive;
  }

  /**
   * Sends an LSP response JSON payload back to lsp4ij.
   *
   * <p>If a {@link DartMessageProducer} is registered, the JSON is enqueued
   * directly (no framing needed). Otherwise, falls back to writing
   * Content-Length framed bytes to the pipe.</p>
   */
  private void sendResponseToLsp4ij(String json) throws IOException {
    DartMessageProducer producer = DartMessageProducerRegistry.get(project);
    if (producer != null) {
      // Direct path: enqueue the JSON into the producer's blocking queue.
      // The producer's listen() loop will parse it and feed it to lsp4j.
      producer.enqueueResponse(json);
    } else {
      // Fallback: write Content-Length framed bytes to the pipe.
      writeMessage(serverOutputStream, json);
    }
  }

  /**
   * Main loop that reads LSP messages from lsp4ij using lsp4j's
   * {@link StreamMessageProducer}, handles protocol-level messages locally
   * (initialize, shutdown), and forwards supported LSP methods
   * (textDocument/hover) to the Dart Analysis Server.
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
          sendResponseToLsp4ij(lspPayload.toString());
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
      requestReader = new StreamMessageProducer(serverInputStream, JSON_HANDLER);
      requestReader.listen(message -> {
        try {
          if (message instanceof RequestMessage request) {
            String method = request.getMethod();
            if ("initialize".equals(method)) {
              handleInitialize(request);
            } else if ("shutdown".equals(method)) {
              handleShutdown(request);
            } else if ("textDocument/hover".equals(method)) {
              LOG.info("★★★ LSP-over-legacy: Received hover request via lsp4ij ★★★");
              forwardToDas(dasService, request);
            } else {
              LOG.debug("Ignored lsp4ij request: " + method);
            }
          } else if (message instanceof NotificationMessage notification) {
            LOG.debug("Ignored lsp4ij notification: " + notification.getMethod());
          }
        } catch (IOException e) {
          if (alive) {
            LOG.warn("Error handling LSP message", e);
          }
        }
      });
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
   * we currently proxy (hover). Uses LSP4J POJOs for type-safe construction.
   */
  private void handleInitialize(RequestMessage request) throws IOException {
    ServerCapabilities capabilities = new ServerCapabilities();
    capabilities.setHoverProvider(true);

    CompletionOptions completionOptions = new CompletionOptions();
    completionOptions.setResolveProvider(false);
    capabilities.setCompletionProvider(completionOptions);

    InitializeResult initResult = new InitializeResult(capabilities);

    ResponseMessage response = new ResponseMessage();
    response.setJsonrpc("2.0");
    response.setId(request.getId());
    response.setResult(initResult);

    sendResponseToLsp4ij(JSON_HANDLER.getGson().toJson(response));
    LOG.info("★★★ LSP-over-legacy: Sent fake initialize response to lsp4ij ★★★");
  }

  /**
   * Sends a fake {@code shutdown} response to lsp4ij.
   */
  private void handleShutdown(RequestMessage request) throws IOException {
    ResponseMessage response = new ResponseMessage();
    response.setJsonrpc("2.0");
    response.setId(request.getId());
    response.setResult(null);

    sendResponseToLsp4ij(JSON_HANDLER.getGson().toJson(response));
    LOG.debug("Sent fake shutdown response to lsp4ij");
  }

  /**
   * Forwards an LSP request to the Dart Analysis Server using the legacy
   * {@code lsp.handle} protocol method. Converts the {@link RequestMessage}
   * to a {@link JsonObject} for embedding in the DAS legacy envelope.
   */
  private void forwardToDas(DartAnalysisServerService dasService, RequestMessage request) {
    LOG.debug("Forwarding hover request to Dart server");

    // Convert the LSP request to JsonObject for the DAS legacy protocol envelope.
    // Since params were parsed with an empty method map, they remain as raw
    // JsonElement — no number type coercion occurs during this round-trip.
    JsonObject lspMessage = JSON_HANDLER.getGson().toJsonTree(request).getAsJsonObject();

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
   * Writes an LSP message (with {@code Content-Length} header) to the output stream.
   * Synchronized to prevent interleaving when multiple threads write responses.
   * Used only in fallback mode when DartMessageProducer is not available.
   */
  private synchronized void writeMessage(OutputStream out, String json) throws IOException {
    byte[] body = json.getBytes(StandardCharsets.UTF_8);
    String header = "Content-Length: " + body.length + "\r\n\r\n";
    out.write(header.getBytes(StandardCharsets.UTF_8));
    out.write(body);
    out.flush();
  }
}
