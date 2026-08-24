package com.moyu.remote;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class NativeHistoryIdentityTest {
    @Test public void bindsOpaqueNonUuidReferencesWithoutPlatformBranches() {
        assertTrue(NativeHistoryIdentity.shouldBind("workspace/thread:alpha.v2"));
        assertTrue(NativeHistoryIdentity.shouldBind("opaque ref with spaces"));
        assertFalse(NativeHistoryIdentity.shouldBind(""));
        assertFalse(NativeHistoryIdentity.shouldBind(" leading-space"));
        assertFalse(NativeHistoryIdentity.shouldBind("line\nbreak"));
    }

    @Test public void liveAndHistoryRowsMergeWithoutLosingCacheOrOpaqueIdentity() {
        MoyuDatabase.SessionRecord historyRow = new MoyuDatabase.SessionRecord();
        historyRow.localSessionId = "history-local";
        historyRow.resumeRef = "project/thread:alpha";
        historyRow.nativeMessageCount = 1400;
        historyRow.nativeCachedSeq = 1400;
        historyRow.lastSeq = 1400;
        historyRow.draft = "";
        historyRow.unread = 2;
        MoyuDatabase.SessionRecord liveRow = new MoyuDatabase.SessionRecord();
        liveRow.localSessionId = "live-local";
        liveRow.remoteSessionId = "server-session:beta";
        liveRow.lastSeq = 37;
        liveRow.draft = "unfinished prompt";
        liveRow.unread = 5;
        liveRow.model = "actual-model";

        NativeHistoryIdentity.mergeLiveState(historyRow, liveRow);

        assertEquals("history-local", historyRow.localSessionId);
        assertEquals("project/thread:alpha", historyRow.resumeRef);
        assertEquals(1400, historyRow.nativeCachedSeq);
        assertEquals("server-session:beta", historyRow.remoteSessionId);
        assertEquals(37, historyRow.lastSeq);
        assertEquals(5, historyRow.unread);
        assertEquals("unfinished prompt", historyRow.draft);
        assertEquals("actual-model", historyRow.model);
    }
}
