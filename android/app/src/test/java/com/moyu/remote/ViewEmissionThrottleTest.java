package com.moyu.remote;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class ViewEmissionThrottleTest {
    @Test public void burstProducesOneDelayedEmission() throws Exception {
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        try {
            AtomicInteger count = new AtomicInteger();
            CountDownLatch emitted = new CountDownLatch(1);
            ViewEmissionThrottle throttle = new ViewEmissionThrottle(executor, 30, () -> {
                count.incrementAndGet();
                emitted.countDown();
            });

            throttle.request();
            throttle.request();
            throttle.request();

            assertTrue(emitted.await(2, TimeUnit.SECONDS));
            assertEquals(1, count.get());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test public void immediateEmissionCancelsPendingDelay() throws Exception {
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        try {
            AtomicInteger count = new AtomicInteger();
            ViewEmissionThrottle throttle = new ViewEmissionThrottle(executor, 100, count::incrementAndGet);

            throttle.request();
            throttle.emitNow();

            assertEquals(1, count.get());
            CountDownLatch afterOriginalDeadline = new CountDownLatch(1);
            executor.schedule(afterOriginalDeadline::countDown, 150, TimeUnit.MILLISECONDS);
            assertTrue(afterOriginalDeadline.await(2, TimeUnit.SECONDS));
            assertEquals(1, count.get());
        } finally {
            executor.shutdownNow();
        }
    }
}
