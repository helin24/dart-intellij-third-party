// Copyright 2025 the Dart project authors. Use of this source code is governed by a BSD-style license
// that can be found in the LICENSE file.
package com.jetbrains.lang.dart.analyzer;

import com.intellij.psi.PsiFile;
import com.redhat.devtools.lsp4ij.client.features.LSPDiagnosticFeature;
import org.jetbrains.annotations.NotNull;

/**
 * Disables LSP diagnostics for Dart files.
 *
 * <p>Diagnostics are still handled by the legacy Dart Analysis Server protocol
 * and have not yet been migrated to the LSP-over-legacy proxy.</p>
 */
public class DartLSPDiagnosticFeature extends LSPDiagnosticFeature {

  @Override
  public boolean isEnabled(@NotNull PsiFile file) {
    return false;
  }
}
