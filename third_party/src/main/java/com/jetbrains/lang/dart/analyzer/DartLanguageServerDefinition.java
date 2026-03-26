// Copyright 2025 the Dart project authors. Use of this source code is governed by a BSD-style license
// that can be found in the LICENSE file.
package com.jetbrains.lang.dart.analyzer;

import com.intellij.openapi.project.Project;
import com.redhat.devtools.lsp4ij.client.features.LSPClientFeatures;
import com.redhat.devtools.lsp4ij.internal.ExtendedConcurrentMessageProcessor;
import com.redhat.devtools.lsp4ij.server.StreamConnectionProvider;
import com.redhat.devtools.lsp4ij.server.definition.LanguageServerDefinition;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.jsonrpc.MessageConsumer;
import org.eclipse.lsp4j.jsonrpc.MessageProducer;
import org.eclipse.lsp4j.jsonrpc.json.ConcurrentMessageProcessor;
import org.eclipse.lsp4j.jsonrpc.json.MessageJsonHandler;
import org.eclipse.lsp4j.jsonrpc.json.StreamMessageProducer;
import org.eclipse.lsp4j.services.LanguageServer;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <b>Temporary test class</b> — validates Angelo's suggestion to override
 * {@code createLauncherBuilder} to inject a custom {@link MessageProducer}
 * into lsp4j's message pipeline.
 *
 * <h3>Purpose</h3>
 * <p>This class extends {@link LanguageServerDefinition} (instead of using the
 * simpler {@link com.redhat.devtools.lsp4ij.LanguageServerFactory} pattern)
 * <b>only</b> because lsp4ij does not yet expose {@code createLauncherBuilder}
 * through {@link LSPClientFeatures}. Once lsp4ij adds that capability (via a
 * PR we plan to submit), this class will be removed and the custom message
 * producer will be wired through {@link LSPClientFeatures} instead.</p>
 *
 * <h3>What it does</h3>
 * <p>Overrides {@link #createLauncherBuilder(LSPClientFeatures)} to replace
 * lsp4j's default {@link StreamMessageProducer} with our
 * {@link DartMessageProducer}. This allows DAS responses to be fed directly
 * into lsp4j's pipeline as parsed {@code Message} objects, eliminating the
 * need for Content-Length framing on the response path.</p>
 *
 * <h3>Registration</h3>
 * <p>Because this extends {@link LanguageServerDefinition}, the {@code plugin.xml}
 * entry must use the {@code class} attribute instead of {@code factoryClass}:</p>
 * <pre>{@code
 *   <server id="dartLanguageServer"
 *           name="Dart Language Server"
 *           class="com.jetbrains.lang.dart.analyzer.DartLanguageServerDefinition">
 * }</pre>
 *
 * @see DartMessageProducer
 * @see DartMessageProducerRegistry
 */
public class DartLanguageServerDefinition extends LanguageServerDefinition {

  public DartLanguageServerDefinition() {
    super(
        "dartLanguageServer",
        "Dart Language Server",
        "Dart Language Server powered by the Dart Analysis Server via LSP-over-legacy protocol proxy.",
        /* isSingleton */ true,
        /* lastDocumentDisconnectedTimeout */ 5,
        /* supportsLightEdit */ false
    );
  }

  @Override
  public @NotNull StreamConnectionProvider createConnectionProvider(@NotNull Project project) {
    return new DartVirtualStreamConnectionProvider(project);
  }

  @Override
  public @NotNull LSPClientFeatures createClientFeatures() {
    return new DartLSPClientFeatures();
  }

  /**
   * Overrides the default launcher builder to use {@link DartMessageProducer}
   * instead of the standard {@link StreamMessageProducer}.
   *
   * <p>The standard flow reads from an {@code InputStream} via
   * {@code StreamMessageProducer}, which requires Content-Length framed bytes.
   * Our custom producer receives JSON payloads via a blocking queue from
   * the DAS response listener, parses them into lsp4j {@code Message} objects,
   * and feeds them directly to the remote endpoint.</p>
   *
   * <p>The connection provider ({@link DartVirtualStreamConnectionProvider})
   * looks up the {@link DartMessageProducer} from
   * {@link DartMessageProducerRegistry} so that DAS responses can be enqueued
   * into it directly (instead of writing Content-Length framed bytes to a pipe).</p>
   */
  @Override
  public <S extends LanguageServer> Launcher.Builder<S> createLauncherBuilder(
      @NotNull LSPClientFeatures clientFeatures) {

    return new Launcher.Builder<S>() {

      @Override
      public Launcher<S> create() {
        // Validate inputs (same as parent)
        if (input == null)
          throw new IllegalStateException("Input stream must be configured.");
        if (output == null)
          throw new IllegalStateException("Output stream must be configured.");
        if (localServices == null)
          throw new IllegalStateException("Local service must be configured.");
        if (remoteInterfaces == null)
          throw new IllegalStateException("Remote interface must be configured.");

        // Create the JSON handler, remote endpoint and remote proxy
        MessageJsonHandler jsonHandler = createJsonHandler();
        var remoteEndpoint = createRemoteEndpoint(jsonHandler);
        S remoteProxy = createProxy(remoteEndpoint);

        // Create our custom message producer.
        // This replaces StreamMessageProducer — no more Content-Length parsing on the response side.
        DartMessageProducer dartProducer = new DartMessageProducer(jsonHandler);

        // Register the producer so DartVirtualStreamConnectionProvider can find it
        // and enqueue DAS responses into it.
        Project project = clientFeatures.getProject();
        DartMessageProducerRegistry.register(project, dartProducer);

        MessageConsumer messageConsumer = wrapMessageConsumer(remoteEndpoint);
        ConcurrentMessageProcessor msgProcessor = createMessageProcessor(dartProducer, messageConsumer, remoteProxy);
        ExecutorService execService = executorService != null ? executorService : Executors.newCachedThreadPool();
        return createLauncher(execService, remoteProxy, remoteEndpoint, msgProcessor);
      }

      @Override
      protected ConcurrentMessageProcessor createMessageProcessor(
          MessageProducer reader, MessageConsumer messageConsumer, S remoteProxy) {
        return new ExtendedConcurrentMessageProcessor(reader, messageConsumer);
      }
    };
  }
}