package com.moyu.remote;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class NativeHistoryIdentityTest {
    private static final String REMOTE = "11111111-1111-4111-8111-111111111111";
    private static final String NATIVE = "22222222-2222-4222-8222-222222222222";

    @Test public void claudeLiveSummaryCanBeMatchedWhenNativeListArrivesLater() {
        assertTrue(NativeHistoryIdentity.shouldBind("claude", REMOTE, REMOTE, false));
    }

    @Test public void existingNativeRowCanBindToNewRemoteSummary() {
        assertTrue(NativeHistoryIdentity.shouldBind("codex", REMOTE, NATIVE, true));
    }

    @Test public void codexWaitsForAuthoritativeThreadId() {
        assertFalse(NativeHistoryIdentity.shouldBind("codex", REMOTE, REMOTE, false));
        assertTrue(NativeHistoryIdentity.shouldBind("codex", REMOTE, NATIVE, false));
    }

    @Test public void legacyLiveAndNativeRowsMergeWithoutLosingNativeCacheOrLiveCursor() {
        MoyuDatabase.SessionRecord nativeRow = new MoyuDatabase.SessionRecord();
        nativeRow.localSessionId = "native-local";
        nativeRow.nativeSessionId = NATIVE;
        nativeRow.nativeMessageCount = 1400;
        nativeRow.nativeCachedSeq = 1400;
        nativeRow.lastSeq = 1400;
        nativeRow.draft = "";
        nativeRow.unread = 2;
        MoyuDatabase.SessionRecord liveRow = new MoyuDatabase.SessionRecord();
        liveRow.localSessionId = "live-local";
        liveRow.remoteSessionId = REMOTE;
        liveRow.lastSeq = 37;
        liveRow.draft = "unfinished prompt";
        liveRow.unread = 5;
        liveRow.model = "actual-model";

        NativeHistoryIdentity.mergeLiveState(nativeRow, liveRow);

        assertEquals("native-local", nativeRow.localSessionId);
        assertEquals(NATIVE, nativeRow.nativeSessionId);
        assertEquals(1400, nativeRow.nativeCachedSeq);
        assertEquals(REMOTE, nativeRow.remoteSessionId);
        assertEquals(37, nativeRow.lastSeq);
        assertEquals(5, nativeRow.unread);
        assertEquals("unfinished prompt", nativeRow.draft);
        assertEquals("actual-model", nativeRow.model);
    }
}
