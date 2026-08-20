package com.moyu.remote;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public final class LatestOnlySlotTest {
    @Test public void retainsOnlyNewestValueWhileOneIsInFlight() {
        LatestOnlySlot<String> slot = new LatestOnlySlot<>();

        assertEquals("first", slot.offer("first"));
        assertNull(slot.offer("second"));
        assertNull(slot.offer("latest"));
        assertEquals("latest", slot.complete());
        assertNull(slot.complete());
        assertEquals("next", slot.offer("next"));
    }

    @Test public void clearDropsPendingAndInFlightState() {
        LatestOnlySlot<String> slot = new LatestOnlySlot<>();
        slot.offer("first");
        slot.offer("pending");

        slot.clear();

        assertEquals("replacement", slot.offer("replacement"));
    }
}
