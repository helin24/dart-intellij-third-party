// Copyright 2025 the Dart project authors. Use of this source code is governed by a BSD-style license
// that can be found in the LICENSE file.
package com.jetbrains.lang.dart.analyzer;

import com.intellij.openapi.project.Project;
import com.redhat.devtools.lsp4ij.LanguageServersRegistry;
import com.redhat.devtools.lsp4ij.templates.ServerMappingSettings;

import java.util.List;

/**
 * Programmatically registers the {@link DartLanguageServerDefinition} with
 * lsp4ij's {@link LanguageServersRegistry}.
 *
 * <h3>Why programmatic registration?</h3>
 * <p>lsp4ij's {@code <server factoryClass="...">} extension point wraps the
 * factory in {@code ExtensionLanguageServerDefinition}, which does <b>not</b>
 * delegate {@code createLauncherBuilder} to the factory. By registering
 * programmatically via {@link LanguageServersRegistry#addServerDefinition},
 * our {@link DartLanguageServerDefinition} goes directly into the registry.
 * This allows the overridden {@code createLauncherBuilder} to inject
 * {@link DartMessageProducer} into lsp4j's message pipeline.</p>
 *
 * <p><b>This is temporary</b> — once lsp4ij exposes
 * {@code createMessageProducer} or {@code createLauncherBuilder} through
 * {@code LSPClientFeatures}, we can switch back to the standard extension
 * point registration and remove this class.</p>
 */
public final class DartLspServerRegistrar {

  private DartLspServerRegistrar() {}

  /**
   * Registers the Dart LSP server definition if not already present.
   * Should be called once per project at startup.
   */
  public static void registerIfNeeded(Project project) {
    LanguageServersRegistry registry = LanguageServersRegistry.getInstance();
    if (registry.getServerDefinition("dartLanguageServer") != null) {
      return; // Already registered (e.g. project reopened)
    }

    registry.addServerDefinition(
        project,
        new DartLanguageServerDefinition(),
        List.of(ServerMappingSettings.createLanguageMappingSettings("Dart", "dart"))
    );
  }
}
