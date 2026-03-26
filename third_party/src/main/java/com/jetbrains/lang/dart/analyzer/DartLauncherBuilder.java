// Copyright 2025 the Dart project authors. Use of this source code is governed by a BSD-style license
// that can be found in the LICENSE file.
package com.jetbrains.lang.dart.analyzer;

import com.intellij.openapi.project.Project;
import com.redhat.devtools.lsp4ij.client.features.LSPClientFeatures;
import com.redhat.devtools.lsp4ij.server.DefaultLauncherBuilder;
import org.eclipse.lsp4j.jsonrpc.MessageIssueHandler;
import org.eclipse.lsp4j.jsonrpc.MessageProducer;
import org.eclipse.lsp4j.jsonrpc.json.MessageJsonHandler;
import org.eclipse.lsp4j.services.LanguageServer;
import org.jetbrains.annotations.NotNull;

import java.io.InputStream;

/**
 * Custom launcher builder that injects {@link DartMessageProducer} into
 * lsp4j's message pipeline, replacing the default stream-based producer.
 *
 * <p>This allows DAS responses to be fed directly into lsp4j as parsed
 * {@code Message} objects via a {@link java.util.concurrent.LinkedBlockingQueue},
 * eliminating the need for Content-Length framing on the response path.</p>
 */
class DartLauncherBuilder<S extends LanguageServer> extends DefaultLauncherBuilder<S> {

  DartLauncherBuilder(@NotNull LSPClientFeatures clientFeatures) {
    super(clientFeatures);
  }

  @Override
  protected @NotNull MessageProducer createMessageProducer(@NotNull InputStream input,
                                                            @NotNull MessageJsonHandler jsonHandler,
                                                            @NotNull MessageIssueHandler issueHandler) {
    DartMessageProducer producer = new DartMessageProducer(jsonHandler);

    // Register the producer so DartVirtualStreamConnectionProvider can find it
    // and enqueue DAS responses into it.
    Project project = getClientFeatures().getProject();
    DartMessageProducerRegistry.register(project, producer);

    return producer;
  }
}
