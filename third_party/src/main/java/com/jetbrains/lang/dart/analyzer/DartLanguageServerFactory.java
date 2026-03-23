// Copyright 2025 the Dart project authors. Use of this source code is governed by a BSD-style license
// that can be found in the LICENSE file.
package com.jetbrains.lang.dart.analyzer;

import com.intellij.openapi.project.Project;
import com.redhat.devtools.lsp4ij.LanguageServerFactory;
import com.redhat.devtools.lsp4ij.client.features.LSPClientFeatures;
import com.redhat.devtools.lsp4ij.server.StreamConnectionProvider;
import org.jetbrains.annotations.NotNull;

/**
 * Factory for creating the Dart LSP-over-legacy language server connection.
 *
 * <p>This class follows the lsp4ij recommended pattern (see
 * <a href="https://github.com/redhat-developer/lsp4ij/blob/main/docs/DeveloperGuide.md#languageserverfactory">
 * lsp4ij Developer Guide</a>) by implementing {@link LanguageServerFactory}
 * instead of extending {@code LanguageServerDefinition} directly.</p>
 *
 * <p>The server and language mapping are declared in {@code plugin.xml}
 * via the {@code com.redhat.devtools.lsp4ij.server} and
 * {@code com.redhat.devtools.lsp4ij.languageMapping} extension points.</p>
 */
public class DartLanguageServerFactory implements LanguageServerFactory {

  @Override
  public @NotNull StreamConnectionProvider createConnectionProvider(@NotNull Project project) {
    return new DartVirtualStreamConnectionProvider(project);
  }

  @Override
  public @NotNull LSPClientFeatures createClientFeatures() {
    return new DartLSPClientFeatures();
  }
}
