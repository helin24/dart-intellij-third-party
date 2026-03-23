// Copyright 2025 the Dart project authors. Use of this source code is governed by a BSD-style license
// that can be found in the LICENSE file.
package com.jetbrains.lang.dart.analyzer;

import com.intellij.openapi.project.Project;
import com.redhat.devtools.lsp4ij.LanguageServerFactory;
import com.redhat.devtools.lsp4ij.client.features.LSPClientFeatures;
import com.redhat.devtools.lsp4ij.client.features.LSPCompletionFeature;
import com.redhat.devtools.lsp4ij.client.features.LSPDiagnosticFeature;
import com.redhat.devtools.lsp4ij.client.features.LSPHoverFeature;
import com.redhat.devtools.lsp4ij.server.StreamConnectionProvider;
import com.intellij.psi.PsiFile;
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
    LSPClientFeatures features = new LSPClientFeatures();

    // Enable hover — this is the primary LSP feature we're proxying.
    features.setHoverFeature(new LSPHoverFeature() {
      @Override
      public boolean isEnabled(@NotNull PsiFile file) {
        return true;
      }
    });

    // Disable completion — still handled by legacy protocol.
    features.setCompletionFeature(new LSPCompletionFeature() {
      @Override
      public boolean isEnabled(@NotNull PsiFile file) {
        return false;
      }
    });

    // Disable diagnostics — still handled by legacy protocol.
    features.setDiagnosticFeature(new LSPDiagnosticFeature() {
      @Override
      public boolean isEnabled(@NotNull PsiFile file) {
        return false;
      }
    });

    return features;
  }
}
