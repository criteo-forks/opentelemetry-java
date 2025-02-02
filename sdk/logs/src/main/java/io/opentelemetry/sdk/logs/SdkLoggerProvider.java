/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.sdk.logs;

import io.opentelemetry.sdk.common.Clock;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.internal.ComponentRegistry;
import io.opentelemetry.sdk.resources.Resource;
import java.io.Closeable;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.logging.Level;

/** SDK registry for creating {@link Logger}s. */
public final class SdkLoggerProvider implements Closeable {

  static final String DEFAULT_LOGGER_NAME = "unknown";
  private static final java.util.logging.Logger LOGGER =
      java.util.logging.Logger.getLogger(SdkLoggerProvider.class.getName());

  private final LoggerSharedState sharedState;
  private final ComponentRegistry<SdkLogger> loggerComponentRegistry;
  private final boolean isNoopLogProcessor;

  /**
   * Returns a new {@link SdkLoggerProviderBuilder} for {@link SdkLoggerProvider}.
   *
   * @return a new builder instance
   */
  public static SdkLoggerProviderBuilder builder() {
    return new SdkLoggerProviderBuilder();
  }

  SdkLoggerProvider(
      Resource resource,
      Supplier<LogLimits> logLimitsSupplier,
      List<LogProcessor> processors,
      Clock clock) {
    LogProcessor logProcessor = LogProcessor.composite(processors);
    this.sharedState = new LoggerSharedState(resource, logLimitsSupplier, logProcessor, clock);
    this.loggerComponentRegistry =
        new ComponentRegistry<>(
            instrumentationScopeInfo -> new SdkLogger(sharedState, instrumentationScopeInfo));
    this.isNoopLogProcessor = logProcessor instanceof NoopLogProcessor;
  }

  /**
   * Gets or creates a named logger instance.
   *
   * @param instrumentationScopeName A name uniquely identifying the instrumentation scope, such as
   *     the instrumentation library, package, or fully qualified class name. Must not be null.
   * @return a logger instance
   */
  public Logger get(String instrumentationScopeName) {
    return loggerBuilder(instrumentationScopeName).build();
  }

  /**
   * Creates a {@link LoggerBuilder} instance.
   *
   * @param instrumentationScopeName the name of the instrumentation scope
   * @return a logger builder instance
   */
  public LoggerBuilder loggerBuilder(String instrumentationScopeName) {
    if (isNoopLogProcessor) {
      return NoopLoggerBuilder.getInstance();
    }
    if (instrumentationScopeName == null || instrumentationScopeName.isEmpty()) {
      LOGGER.fine("Logger requested without instrumentation scope name.");
      instrumentationScopeName = DEFAULT_LOGGER_NAME;
    }
    return new SdkLoggerBuilder(loggerComponentRegistry, instrumentationScopeName);
  }

  /**
   * Request the active log processor to process all logs that have not yet been processed.
   *
   * @return a {@link CompletableResultCode} which is completed when the flush is finished
   */
  public CompletableResultCode forceFlush() {
    return sharedState.getLogProcessor().forceFlush();
  }

  /**
   * Attempt to shut down the active log processor.
   *
   * @return a {@link CompletableResultCode} which is completed when the active log process has been
   *     shut down.
   */
  public CompletableResultCode shutdown() {
    if (sharedState.hasBeenShutdown()) {
      LOGGER.log(Level.WARNING, "Calling shutdown() multiple times.");
      return CompletableResultCode.ofSuccess();
    }
    return sharedState.shutdown();
  }

  @Override
  public void close() {
    shutdown().join(10, TimeUnit.SECONDS);
  }
}
