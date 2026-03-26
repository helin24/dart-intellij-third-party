// Copyright 2025 the Dart project authors. Use of this source code is governed by a BSD-style license
// that can be found in the LICENSE file.
package com.jetbrains.lang.dart.analyzer;

import com.intellij.openapi.diagnostic.Logger;
import com.jetbrains.lang.dart.logging.PluginLogger;
import org.eclipse.lsp4j.jsonrpc.JsonRpcException;
import org.eclipse.lsp4j.jsonrpc.MessageConsumer;
import org.eclipse.lsp4j.jsonrpc.MessageProducer;
import org.eclipse.lsp4j.jsonrpc.json.MessageJsonHandler;
import org.eclipse.lsp4j.jsonrpc.messages.Message;

import java.io.Closeable;
import java.io.StringReader;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * A custom {@link MessageProducer} that feeds LSP messages from the Dart
 * Analysis Server (DAS) into lsp4j's message processing pipeline.
 *
 * <h3>How it works</h3>
 * <p>Instead of reading from a raw {@link java.io.InputStream} like
 * {@link org.eclipse.lsp4j.jsonrpc.json.StreamMessageProducer}, this producer
 * receives already-extracted JSON payloads from the DAS response listener.
 * It converts them to lsp4j {@link Message} objects and forwards them to the
 * message consumer (lsp4j's {@code RemoteEndpoint}).</p>
 *
 * <p>This eliminates the need for Content-Length framing on the response path
 * (DAS → lsp4ij). DAS responses containing {@code lspMessage} or
 * {@code lspResponse} payloads are enqueued via {@link #enqueueResponse(String)}
 * and consumed in the {@link #listen(MessageConsumer)} loop.</p>
 */
class DartMessageProducer implements MessageProducer, Closeable {

  private static final Logger LOG = PluginLogger.INSTANCE.createLogger(DartMessageProducer.class);

  /**
   * Poison pill sentinel to signal the listen loop to terminate.
   */
  private static final String POISON_PILL = "__STOP__";

  private final MessageJsonHandler jsonHandler;

  /**
   * Queue of JSON strings representing LSP response/notification payloads
   * from the DAS. The {@link #listen(MessageConsumer)} method blocks on
   * this queue, waiting for new messages.
   */
  private final LinkedBlockingQueue<String> responseQueue = new LinkedBlockingQueue<>();

  private volatile boolean keepRunning = true;

  DartMessageProducer(MessageJsonHandler jsonHandler) {
    this.jsonHandler = jsonHandler;
  }

  /**
   * Enqueue a raw JSON string representing an LSP message (response or
   * notification) from the Dart Analysis Server. This will be picked up
   * by the {@link #listen(MessageConsumer)} loop and forwarded to lsp4j.
   *
   * @param json the LSP JSON payload (without Content-Length framing).
   */
  void enqueueResponse(String json) {
    responseQueue.offer(json);
  }

  /**
   * Blocks and listens for incoming LSP messages (from DAS), forwarding
   * each to the given {@code messageConsumer} (lsp4j's RemoteEndpoint).
   *
   * <p>This method is called by lsp4j's {@code ConcurrentMessageProcessor}
   * on a dedicated thread. It blocks until {@link #close()} is called.</p>
   */
  @Override
  public void listen(MessageConsumer messageConsumer) throws JsonRpcException {
    try {
      while (keepRunning) {
        String json = responseQueue.take(); // blocks until a message is available
        if (POISON_PILL.equals(json)) {
          break;
        }

        try {
          // Parse the JSON string into an lsp4j Message object
          Message message = jsonHandler.parseMessage(new StringReader(json));
          LOG.info("★★★ DartMessageProducer: Forwarding DAS response to lsp4ij ★★★");
          LOG.debug("Parsed message: " + message);
          messageConsumer.consume(message);
        } catch (Exception e) {
          LOG.warn("Failed to parse or consume DAS response", e);
        }
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      if (keepRunning) {
        LOG.info("DartMessageProducer listen loop interrupted");
      }
    }
  }

  @Override
  public void close() {
    keepRunning = false;
    responseQueue.offer(POISON_PILL);
  }
}
