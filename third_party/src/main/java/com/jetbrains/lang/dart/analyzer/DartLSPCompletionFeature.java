// Copyright 2025 the Dart project authors. Use of this source code is governed by a BSD-style license
// that can be found in the LICENSE file.
package com.jetbrains.lang.dart.analyzer;

import com.intellij.psi.PsiFile;
import com.redhat.devtools.lsp4ij.client.features.LSPCompletionFeature;
import org.jetbrains.annotations.NotNull;

/**
 * Controls whether LSP completion is enabled for Dart files.
 *
 * <p>By default, completion is handled by the legacy Dart Analysis Server
 * protocol. Users can opt in to LSP-based completion via the
 * "Use LSP for completion" setting in {@link DartLspSettings}.</p>
 */
public class DartLSPCompletionFeature extends LSPCompletionFeature {

  @Override
  public boolean isEnabled(@NotNull PsiFile file) {
    return DartLspSettings.getInstance().useLspCompletion;
  }
}
