// Copyright 2025 the Dart project authors. Use of this source code is governed by a BSD-style license
// that can be found in the LICENSE file.
package com.jetbrains.lang.dart.analyzer;

import com.redhat.devtools.lsp4ij.client.features.LSPClientFeatures;

/**
 * Custom LSP client features for the Dart LSP-over-legacy integration.
 *
 * <p>Configures which LSP features are enabled when proxying requests through
 * the Dart Analysis Server. Currently only hover is enabled; completion and
 * diagnostics are disabled because they are still handled by the legacy
 * protocol.</p>
 */
public class DartLSPClientFeatures extends LSPClientFeatures {

  public DartLSPClientFeatures() {
    // Completion is still handled by the legacy Dart Analysis Server protocol.
    super.setCompletionFeature(new DartLSPCompletionFeature());
    // Diagnostics are still handled by the legacy Dart Analysis Server protocol.
    super.setDiagnosticFeature(new DartLSPDiagnosticFeature());
    // Hover is enabled — this is the primary LSP feature we're proxying.
    // The default LSPHoverFeature is enabled by default, so no override needed.
  }
}
