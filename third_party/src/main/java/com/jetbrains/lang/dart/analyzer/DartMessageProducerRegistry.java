// Copyright 2025 the Dart project authors. Use of this source code is governed by a BSD-style license
// that can be found in the LICENSE file.
package com.jetbrains.lang.dart.analyzer;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Stores and retrieves the {@link DartMessageProducer} for a project
 * using {@link Project#getUserData(Key)}.
 *
 * <h3>Why this exists</h3>
 * <p>The {@link DartMessageProducer} is created inside
 * {@link DartLauncherBuilder#createMessageProducer}, but the
 * {@link DartVirtualStreamConnectionProvider} (which needs to enqueue
 * DAS responses into the producer) is created separately via
 * {@link DartLanguageServerFactory#createConnectionProvider}.
 * This registry bridges the two.</p>
 */
final class DartMessageProducerRegistry {

  private static final Key<DartMessageProducer> KEY = Key.create("dart.message.producer");

  private DartMessageProducerRegistry() {}

  static void register(@NotNull Project project, @NotNull DartMessageProducer producer) {
    project.putUserData(KEY, producer);
  }

  static @Nullable DartMessageProducer get(@NotNull Project project) {
    return project.getUserData(KEY);
  }

  static void unregister(@NotNull Project project) {
    project.putUserData(KEY, null);
  }
}
