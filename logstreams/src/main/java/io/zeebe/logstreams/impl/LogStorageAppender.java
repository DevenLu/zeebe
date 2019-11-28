/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.0. You may not use this file
 * except in compliance with the Zeebe Community License 1.0.
 */
package io.zeebe.logstreams.impl;

import com.netflix.concurrency.limits.limit.AbstractLimit;
import com.netflix.concurrency.limits.limit.WindowedLimit;
import io.zeebe.dispatcher.BlockPeek;
import io.zeebe.dispatcher.Subscription;
import io.zeebe.logstreams.impl.backpressure.AlgorithmCfg;
import io.zeebe.logstreams.impl.backpressure.AppendBackpressureMetrics;
import io.zeebe.logstreams.impl.backpressure.AppendEntryLimiter;
import io.zeebe.logstreams.impl.backpressure.AppendLimiter;
import io.zeebe.logstreams.impl.backpressure.AppenderGradient2Cfg;
import io.zeebe.logstreams.impl.backpressure.AppenderVegasCfg;
import io.zeebe.logstreams.impl.backpressure.BackpressureConstants;
import io.zeebe.logstreams.impl.backpressure.NoopAppendLimiter;
import io.zeebe.logstreams.spi.LogStorage;
import io.zeebe.logstreams.spi.LogStorage.AppendListener;
import io.zeebe.util.Environment;
import io.zeebe.util.sched.Actor;
import io.zeebe.util.sched.future.ActorFuture;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.agrona.concurrent.UnsafeBuffer;
import org.slf4j.Logger;

/** Consume the write buffer and append the blocks to the distributedlog. */
public class LogStorageAppender extends Actor {

  public static final Logger LOG = Loggers.LOGSTREAMS_LOGGER;
  private static final Map<String, AlgorithmCfg> ALGORITHM_CFG =
      Map.of("vegas", new AppenderVegasCfg(), "gradient2", new AppenderGradient2Cfg());
  private final AtomicBoolean isFailed = new AtomicBoolean(false);

  private final BlockPeek blockPeek = new BlockPeek();
  private final String name;
  private final Subscription writeBufferSubscription;
  private final int maxAppendBlockSize;
  private final LogStorage logStorage;
  private final AppendLimiter appendEntryLimiter;
  private final AppendBackpressureMetrics appendBackpressureMetrics;
  private final Environment env;
  private final LoggedEventImpl positionReader = new LoggedEventImpl();

  private boolean isAppending = false;

  public LogStorageAppender(
      final String name,
      final int partitionId,
      final LogStorage logStorage,
      final Subscription writeBufferSubscription,
      final int maxBlockSize) {
    this.env = new Environment();
    this.name = name;
    this.logStorage = logStorage;
    this.writeBufferSubscription = writeBufferSubscription;
    this.maxAppendBlockSize = maxBlockSize;

    appendBackpressureMetrics = new AppendBackpressureMetrics(partitionId);

    final boolean isBackpressureEnabled =
        env.getBool(BackpressureConstants.ENV_BP_APPENDER).orElse(true);
    appendEntryLimiter =
        isBackpressureEnabled ? initBackpressure(partitionId) : initNoBackpressure(partitionId);
  }

  private AppendLimiter initBackpressure(final int partitionId) {
    final String algorithmName =
        env.get(BackpressureConstants.ENV_BP_APPENDER_ALGORITHM).orElse("vegas").toLowerCase();
    final AlgorithmCfg algorithmCfg =
        ALGORITHM_CFG.getOrDefault(algorithmName, new AppenderVegasCfg());
    algorithmCfg.applyEnvironment(env);

    final AbstractLimit abstractLimit = algorithmCfg.get();
    final boolean windowedLimiter =
        env.getBool(BackpressureConstants.ENV_BP_APPENDER_WINDOWED).orElse(false);

    LOG.debug(
        "Configured log appender back pressure at partition {} as {}. Window limiting is {}",
        partitionId,
        algorithmCfg,
        windowedLimiter ? "enabled" : "disabled");
    return AppendEntryLimiter.builder()
        .limit(windowedLimiter ? WindowedLimit.newBuilder().build(abstractLimit) : abstractLimit)
        .partitionId(partitionId)
        .build();
  }

  private AppendLimiter initNoBackpressure(final int partition) {
    LOG.warn(
        "No back pressure for the log appender (partition = {}) configured! This might cause problems.",
        partition);
    return new NoopAppendLimiter();
  }

  private void appendBlock(final BlockPeek blockPeek) {
    final ByteBuffer rawBuffer = blockPeek.getRawBuffer();
    final int bytes = rawBuffer.remaining();
    final ByteBuffer copiedBuffer = ByteBuffer.allocate(bytes).put(rawBuffer).flip();
    final Positions positions = readPositions(copiedBuffer);

    // Commit position is the position of the last event.
    appendBackpressureMetrics.newEntryToAppend();
    if (appendEntryLimiter.tryAcquire(positions.highest)) {
      final var listener = new Listener(positions, copiedBuffer);
      appendToStorage(copiedBuffer, positions, listener);
    } else {
      appendBackpressureMetrics.deferred();
      LOG.trace(
          "Backpressure happens: in flight {} limit {}",
          appendEntryLimiter.getInflight(),
          appendEntryLimiter.getLimit());
      actor.submit(() -> appendBlock(blockPeek));
    }
  }

  private void appendToStorage(
      final ByteBuffer buffer, final Positions positions, final Listener listener) {
    logStorage.append(positions.lowest, positions.highest, buffer, listener);
  }

  public ActorFuture<Void> close() {
    return actor.close();
  }

  public long getCurrentAppenderPosition() {
    return writeBufferSubscription.getPosition();
  }

  @Override
  public String getName() {
    return name;
  }

  @Override
  protected void onActorStarting() {

    actor.consume(writeBufferSubscription, this::onWriteBufferAvailable);
  }

  private void onWriteBufferAvailable() {
    if (isAppending) {
      actor.yield();
      return;
    }

    if (writeBufferSubscription.peekBlock(blockPeek, maxAppendBlockSize, true) > 0) {
      isAppending = true;
      appendBlock(blockPeek);
    } else {
      actor.yield();
    }
  }

  public boolean isFailed() {
    return isFailed.get();
  }

  private Positions readPositions(final ByteBuffer buffer) {
    final var view = new UnsafeBuffer(buffer);
    final var positions = new Positions();
    var offset = 0;
    do {
      positionReader.wrap(view, offset);
      positions.accept(positionReader.getPosition());
      offset += positionReader.getLength();
    } while (offset < view.capacity());

    return positions;
  }

  private static final class Positions {
    private long lowest = Long.MAX_VALUE;
    private long highest = Long.MIN_VALUE;

    private void accept(final long position) {
      lowest = Math.min(lowest, position);
      highest = Math.max(highest, position);
    }
  }

  private final class Listener implements AppendListener {
    private final Positions positions;
    private final ByteBuffer buffer;

    private Listener(final Positions positions, final ByteBuffer buffer) {
      this.positions = positions;
      this.buffer = buffer;
    }

    @Override
    public void onWrite(final long address) {
      actor.run(
          () -> {
            isAppending = false;
            blockPeek.markCompleted();
          });
    }

    @Override
    public void onWriteError(final Throwable error) {
      LOG.error("Failed to append block with last event position {}, retry.", positions.highest);
      actor.run(() -> appendToStorage(buffer, positions, this));
    }

    @Override
    public void onCommit(final long address) {
      releaseBackPressure();
    }

    @Override
    public void onCommitError(final long address, final Throwable error) {
      releaseBackPressure();
    }

    private void releaseBackPressure() {
      actor.run(() -> appendEntryLimiter.onCommit(positions.highest));
    }
  }
}
