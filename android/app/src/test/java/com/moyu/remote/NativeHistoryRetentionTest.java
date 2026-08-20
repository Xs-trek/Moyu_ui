package com.moyu.remote;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class NativeHistoryRetentionTest {
    @Test public void longHistoryKeepsOnlyRowsMissingFromHotWindow() {
        int truncatedBefore = 4000;
        assertTrue(NativeHistoryRetention.keepNativeRow(1, truncatedBefore));
        assertTrue(NativeHistoryRetention.keepNativeRow(4000, truncatedBefore));
        assertFalse(NativeHistoryRetention.keepNativeRow(4001, truncatedBefore));
        assertFalse(NativeHistoryRetention.backendContainsFullHistory(truncatedBefore));
    }

    @Test public void untruncatedHistoryCanDropNativeDuplicateRows() {
        assertTrue(NativeHistoryRetention.backendContainsFullHistory(0));
        assertFalse(NativeHistoryRetention.keepNativeRow(1, 0));
    }
}
