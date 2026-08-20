package com.moyu.remote;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public final class NativeHistoryKeysTest {
    @Test public void sameNativeHistoryOnTwoNodesCannotShareTimelineKey() {
        String nativeId = "11111111-1111-4111-8111-111111111111";
        String first = NativeHistoryKeys.item("local-node-a", "claude", nativeId, 1, "text");
        String second = NativeHistoryKeys.item("local-node-b", "claude", nativeId, 1, "text");
        assertNotEquals(first, second);
        assertTrue(first.startsWith(NativeHistoryKeys.prefix("local-node-a", "claude", nativeId)));
        assertFalse(second.startsWith(NativeHistoryKeys.prefix("local-node-a", "claude", nativeId)));
    }
}
