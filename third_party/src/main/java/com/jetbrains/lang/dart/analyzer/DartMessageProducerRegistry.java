// Copyright 2025 the Dart project authors. Use of this source code is governed by a BSD-style license
// that can be found in the LICENSE file.
package com.jetbrains.lang.dart.analyzer;

import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe registry that maps {@link Project} instances to their
 * {@link DartMessageProducer}.
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

  private static final Map<String, DartMessageProducer> PRODUCERS = new ConcurrentHashMap<>();

  private DartMessageProducerRegistry() {}

  /**
   * Registers a {@link DartMessageProducer} for the given project.
   */
  static void register(@NotNull Project project, @NotNull DartMessageProducer producer) {
    PRODUCERS.put(key(project), producer);
  }

  /**
   * Returns the registered {@link DartMessageProducer} for the given project,
   * or {@code null} if none has been registered yet.
   */
  static @Nullable DartMessageProducer get(@NotNull Project project) {
    return PRODUCERS.get(key(project));
  }

  /**
   * Removes the registration for the given project (e.g. on shutdown).
   */
  static void unregister(@NotNull Project project) {
    PRODUCERS.remove(key(project));
  }

  private static String key(@NotNull Project project) {
    return project.getLocationHash();
  }
}
