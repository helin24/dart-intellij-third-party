// Copyright 2025 the Dart project authors. Use of this source code is governed by a BSD-style license
// that can be found in the LICENSE file.
package com.jetbrains.lang.dart.analyzer;

import com.intellij.psi.PsiFile;
import com.redhat.devtools.lsp4ij.client.features.LSPCompletionFeature;
import org.jetbrains.annotations.NotNull;

/**
 * Disables LSP completion for Dart files.
 *
 * <p>Completion is still handled by the legacy Dart Analysis Server protocol
 * and has not yet been migrated to the LSP-over-legacy proxy.</p>
 */
public class DartLSPCompletionFeature extends LSPCompletionFeature {

  @Override
  public boolean isEnabled(@NotNull PsiFile file) {
    return false;
  }
}
