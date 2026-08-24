package com.moyu.remote;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public final class ViewDispatchQueueTest {
    @Test public void preservesPatchOrderAndFullSnapshotSupersedesPendingWork() {
        ViewDispatchQueue<String> queue = new ViewDispatchQueue<>(3);
        assertEquals("full-1", queue.offer("full-1", true));
        assertNull(queue.offer("patch-2", false));
        assertNull(queue.offer("patch-3", false));
        assertEquals("patch-2", queue.complete());
        assertNull(queue.offer("full-4", true));
        assertEquals("full-4", queue.complete());
        assertNull(queue.complete());
    }

    @Test public void overflowRequestsOneFullRecoveryAndIgnoresPatchesUntilItArrives() {
        ViewDispatchQueue<String> queue = new ViewDispatchQueue<>(1);
        assertEquals("full", queue.offer("full", true));
        assertNull(queue.offer("patch-1", false));
        assertNull(queue.offer("patch-2", false));
        assertTrue(queue.consumeRecoveryRequest());
        assertFalse(queue.consumeRecoveryRequest());
        assertNull(queue.offer("patch-3", false));
        assertNull(queue.complete());
        assertEquals("recovery", queue.offer("recovery", true));
    }
}
