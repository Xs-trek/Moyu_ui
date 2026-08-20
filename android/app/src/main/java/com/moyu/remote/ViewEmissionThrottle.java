package com.moyu.remote;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Coalesces a burst into one delayed emission while retaining an immediate flush path. */
final class ViewEmissionThrottle {
    private final ScheduledExecutorService executor;
    private final Runnable emission;
    private final long delayMs;
    private ScheduledFuture<?> pending;

    ViewEmissionThrottle(ScheduledExecutorService executor, long delayMs, Runnable emission) {
        this.executor = executor;
        this.delayMs = delayMs;
        this.emission = emission;
    }

    void request() {
        if (pending != null && !pending.isDone()) return;
        pending = executor.schedule(() -> {
            pending = null;
            emission.run();
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    void emitNow() {
        cancelPending();
        emission.run();
    }

    void close() {
        cancelPending();
    }

    private void cancelPending() {
        if (pending != null) {
            pending.cancel(false);
            pending = null;
        }
    }
}
