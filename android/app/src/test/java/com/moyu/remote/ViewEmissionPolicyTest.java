package com.moyu.remote;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class ViewEmissionPolicyTest {
    @Test public void onlyHighFrequencyProjectionEventsAreThrottled() {
        assertTrue(ViewEmissionPolicy.shouldThrottle("text.delta"));
        assertTrue(ViewEmissionPolicy.shouldThrottle("thinking.delta"));
        assertTrue(ViewEmissionPolicy.shouldThrottle("tool.output"));
        assertTrue(ViewEmissionPolicy.shouldThrottle("transport.metrics"));

        assertFalse(ViewEmissionPolicy.shouldThrottle("text.done"));
        assertFalse(ViewEmissionPolicy.shouldThrottle("tool.done"));
        assertFalse(ViewEmissionPolicy.shouldThrottle("approval.request"));
        assertFalse(ViewEmissionPolicy.shouldThrottle("approval.resolved"));
        assertFalse(ViewEmissionPolicy.shouldThrottle("turn.completed"));
        assertFalse(ViewEmissionPolicy.shouldThrottle("turn.failed"));
    }

    @Test public void sessionOrderingChangesOnlyAtTurnBoundaries() {
        assertTrue(ViewEmissionPolicy.updatesSessionTimestamp("turn.started"));
        assertTrue(ViewEmissionPolicy.updatesSessionTimestamp("turn.completed"));
        assertTrue(ViewEmissionPolicy.updatesSessionTimestamp("turn.failed"));
        assertFalse(ViewEmissionPolicy.updatesSessionTimestamp("text.delta"));
        assertFalse(ViewEmissionPolicy.updatesSessionTimestamp("thinking.delta"));
        assertFalse(ViewEmissionPolicy.updatesSessionTimestamp("tool.output"));
    }
}
