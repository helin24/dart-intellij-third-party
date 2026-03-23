// Copyright 2025 the Dart project authors. Use of this source code is governed by a BSD-style license
// that can be found in the LICENSE file.
package com.jetbrains.lang.dart.analyzer;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.jetbrains.lang.dart.logging.PluginLogger;
import com.redhat.devtools.lsp4ij.server.StreamConnectionProvider;
import org.eclipse.lsp4j.jsonrpc.MessageConsumer;
import org.eclipse.lsp4j.jsonrpc.json.MessageJsonHandler;
import org.eclipse.lsp4j.jsonrpc.json.StreamMessageConsumer;
import org.eclipse.lsp4j.jsonrpc.messages.Message;
import org.eclipse.lsp4j.jsonrpc.messages.NotificationMessage;
import org.eclipse.lsp4j.jsonrpc.messages.RequestMessage;
import org.eclipse.lsp4j.jsonrpc.messages.ResponseMessage;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.HashMap;

/**
 * A virtual stream connection provider that bridges lsp4ij (LSP client) and
 * the Dart Analysis Server (legacy protocol) via piped streams.
 *
 * <h3>Architecture</h3>
 * <p>lsp4ij reads LSP responses from {@link #getInputStream()} and writes
 * LSP requests to {@link #getOutputStream()}. This provider intercepts
 * outgoing requests by wrapping the {@link MessageConsumer} in
 * {@link #createMessageConsumer(MessageConsumer)}, routing each parsed
 * {@link Message} to the Dart Analysis Server's legacy {@code lsp.handle}
 * protocol. Responses from the server are written back into the pipe that
 * feeds {@link #getInputStream()} so lsp4ij can consume them.</p>
 *
 * <p>All LSP base protocol framing (Content-Length headers, JSON-RPC
 * serialization) is handled entirely by lsp4j's built-in infrastructure.
 * This class does NOT parse headers manually.</p>
 */
class DartVirtualStreamConnectionProvider implements StreamConnectionProvider {

    private static final Logger LOG = PluginLogger.INSTANCE.createLogger(DartVirtualStreamConnectionProvider.class);

    // lsp4ij reads LSP responses from ideInputStream.
    // We write responses into serverOutputStream, which is piped to ideInputStream.
    private final PipedInputStream ideInputStream = new PipedInputStream(1024 * 1024);
    private final PipedOutputStream serverOutputStream = new PipedOutputStream();

    // lsp4ij writes LSP requests into ideOutputStream.
    // lsp4ij's own StreamMessageProducer reads from serverInputStream,
    // which is piped to ideOutputStream.
    private final PipedInputStream serverInputStream = new PipedInputStream(1024 * 1024);
    private final PipedOutputStream ideOutputStream = new PipedOutputStream();

    private final Project project;
    private volatile boolean alive = false;

    private MessageJsonHandler jsonHandler;
    private MessageConsumer responseConsumer;

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
        // lsp4ij reads LSP responses from here
        return ideInputStream;
    }

    @Override
    public OutputStream getOutputStream() {
        // lsp4ij writes LSP requests here;
        // lsp4j's StreamMessageProducer (inside lsp4ij's ConcurrentMessageProcessor)
        // reads from serverInputStream which is piped to this stream.
        return ideOutputStream;
    }

    @Override
    public void start() {
        alive = true;
        LOG.info("★★★ LSP-over-legacy: Virtual server starting for project: " + project.getName() + " ★★★");

        jsonHandler = new MessageJsonHandler(new HashMap<>());
        // StreamMessageConsumer writes properly framed LSP responses into serverOutputStream,
        // which lsp4ij reads from ideInputStream.
        responseConsumer = new StreamMessageConsumer(serverOutputStream, jsonHandler);

        // Register a Dart Analysis Server response listener to receive lsp payloads
        DartAnalysisServerService dasService = DartAnalysisServerService.getInstance(project);
        dasService.addResponseListener(response -> {
            try {
                JsonObject jsonObject = JsonParser.parseString(response).getAsJsonObject();
                JsonObject lspPayload = extractLspPayload(jsonObject);
                if (lspPayload != null && alive) {
                    LOG.info("★★★ LSP-over-legacy: Forwarding DAS response back to lsp4ij ★★★");
                    Message message = jsonHandler.getGson().fromJson(lspPayload, Message.class);
                    sendResponse(message);
                }
            } catch (JsonParseException e) {
                LOG.warn("Failed to parse lsp payload from Dart server", e);
            } catch (Exception e) {
                if (alive) {
                    LOG.warn("Failed to send lsp payload to lsp4ij", e);
                }
            }
        });
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
     * Called by lsp4ij to wrap the outgoing message consumer.
     *
     * <p>This is the correct interception point. lsp4ij calls this method
     * and passes each fully-parsed {@link Message} through the returned
     * consumer. We route requests here instead of calling listen() manually.</p>
     *
     * <p>The {@code nextConsumer} would normally forward messages to the
     * language server process — we intercept before that and route to DAS.</p>
     */
    @Override
    public MessageConsumer createMessageConsumer(MessageConsumer nextConsumer) {
        return message -> {
            if (message instanceof RequestMessage request) {
                routeRequest(request);
            } else if (message instanceof NotificationMessage notification) {
                LOG.debug("Ignored LSP notification: " + notification.getMethod());
            } else {
                LOG.debug("Ignored LSP message type: " + message.getClass().getSimpleName());
            }
        };
    }

    private void routeRequest(RequestMessage request) {
        String method = request.getMethod();
        switch (method) {
            case "initialize" -> handleInitialize(request);
            case "shutdown"   -> handleShutdown(request);
            case "textDocument/hover" -> {
                LOG.info("★★★ LSP-over-legacy: Received hover request via lsp4ij ★★★");
                forwardToDas(request);
            }
            default -> LOG.debug("Ignored lsp4ij request: " + method);
        }
    }

    /**
     * Extracts the LSP payload from a Dart Analysis Server response.
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

    private void handleInitialize(RequestMessage request) {
        ResponseMessage response = new ResponseMessage();
        response.setJsonrpc("2.0");
        response.setId(request.getId());

        JsonObject capabilities = new JsonObject();
        capabilities.addProperty("hoverProvider", true);

        JsonObject completionProvider = new JsonObject();
        completionProvider.addProperty("resolveProvider", false);
        capabilities.add("completionProvider", completionProvider);

        JsonObject result = new JsonObject();
        result.add("capabilities", capabilities);
        response.setResult(result);

        sendResponse(response);
        LOG.info("★★★ LSP-over-legacy: Sent fake initialize response to lsp4ij ★★★");
    }

    private void handleShutdown(RequestMessage request) {
        if (request.getId() != null) {
            ResponseMessage response = new ResponseMessage();
            response.setJsonrpc("2.0");
            response.setId(request.getId());
            response.setResult(null);
            sendResponse(response);
            LOG.debug("Sent fake shutdown response to lsp4ij");
        }
    }

    private void forwardToDas(RequestMessage request) {
        DartAnalysisServerService dasService = DartAnalysisServerService.getInstance(project);
        LOG.debug("Forwarding hover request to Dart server");

        JsonObject lspMessage = jsonHandler.getGson().toJsonTree(request).getAsJsonObject();

        JsonObject legacyRequest = new JsonObject();
        String legacyId = dasService.generateUniqueId();
        legacyRequest.addProperty("id", legacyId);
        legacyRequest.addProperty("method", "lsp.handle");

        JsonObject params = new JsonObject();
        params.add("lspMessage", lspMessage);
        legacyRequest.add("params", params);

        dasService.sendRequest(legacyId, legacyRequest);
    }

    private synchronized void sendResponse(Message message) {
        responseConsumer.consume(message);
    }
}