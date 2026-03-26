// Copyright 2025 the Dart project authors. Use of this source code is governed by a BSD-style license
// that can be found in the LICENSE file.
package com.jetbrains.lang.dart.analyzer;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.util.xmlb.XmlSerializerUtil;
import org.jetbrains.annotations.NotNull;

/**
 * Persistent settings that control which LSP features are handled via the
 * LSP-over-legacy proxy instead of the legacy Dart Analysis Server protocol.
 *
 * <p>By default all toggles are {@code false}, meaning the legacy analyzer
 * handles everything. Users can opt in to individual LSP features as they
 * are migrated and proven stable.</p>
 */
@State(name = "DartLspSettings", storages = @Storage("dart.lsp.xml"))
public class DartLspSettings implements PersistentStateComponent<DartLspSettings> {

  /**
   * When {@code true}, code completion is handled via LSP instead of the
   * legacy Dart Analysis Server protocol.
   */
  public boolean useLspCompletion = false;

  /**
   * When {@code true}, diagnostics are handled via LSP instead of the
   * legacy Dart Analysis Server protocol.
   */
  public boolean useLspDiagnostics = false;

  public static DartLspSettings getInstance() {
    return ApplicationManager.getApplication().getService(DartLspSettings.class);
  }

  @Override
  public DartLspSettings getState() {
    return this;
  }

  @Override
  public void loadState(@NotNull DartLspSettings state) {
    XmlSerializerUtil.copyBean(state, this);
  }
}
