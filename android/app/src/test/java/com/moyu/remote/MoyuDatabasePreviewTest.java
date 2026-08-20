package com.moyu.remote;

import android.content.Context;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public final class MoyuDatabasePreviewTest {
    private Context context;
    private MoyuDatabase db;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        context.deleteDatabase("moyu.db");
        db = new MoyuDatabase(context);
        db.getWritableDatabase();
    }

    @After public void tearDown() {
        if (db != null) db.close();
        context.deleteDatabase("moyu.db");
    }

    @Test public void latestUserOrAssistantTextIsPersistedWithoutTimelineQueriesAtReadTime() throws Exception {
        MoyuDatabase.SessionRecord session = session("local-1");
        db.saveSession(session);

        db.putTimeline(session.localSessionId, "tool-1", 1,
                new JSONObject().put("kind", "tool").put("role", "tool").put("text", "ignore me"));
        assertNull(db.getSession(session.localSessionId).preview);

        db.putTimeline(session.localSessionId, "user-1", 2, message("user", "  first\n\tprompt  "));
        assertEquals("first prompt", db.getSession(session.localSessionId).preview);

        db.putTimeline(session.localSessionId, "system-1", 3, message("system", "not a conversation preview"));
        db.putTimeline(session.localSessionId, "assistant-empty", 4, message("assistant", " \r\n "));
        assertEquals("first prompt", db.listSessions().get(0).preview);

        String emoji = "🙂";
        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 170; i++) longText.append(emoji);
        db.putTimeline(session.localSessionId, "assistant-2", 5, message("assistant", longText.toString()));
        String preview = db.getSession(session.localSessionId).preview;
        assertEquals(160, preview.codePointCount(0, preview.length()));
        assertEquals(emoji, preview.substring(preview.offsetByCodePoints(0, 159)));

        // AppCoordinator saves stale SessionRecord instances after timeline writes. Preview is a
        // DB-derived field and must not be erased by that metadata save.
        db.saveSession(session);
        assertEquals(preview, db.getSession(session.localSessionId).preview);
    }

    @Test public void canonicalRefreshOfAnOlderMessageCannotRegressThePreview() throws Exception {
        MoyuDatabase.SessionRecord session = session("local-2");
        db.saveSession(session);
        db.putCanonicalUserTimeline(session.localSessionId, "canonical-user", 1, message("user", "first"));
        db.putTimeline(session.localSessionId, "assistant", 2, message("assistant", "latest answer"));

        db.putCanonicalUserTimeline(session.localSessionId, "canonical-user", 1, message("user", "refreshed old prompt"));

        assertEquals("latest answer", db.getSession(session.localSessionId).preview);
    }

    @Test public void streamingAssistantTextDoesNotRewriteSessionPreview() throws Exception {
        MoyuDatabase.SessionRecord session = session("local-stream");
        db.saveSession(session);
        db.putTimeline(session.localSessionId, "user", 1, message("user", "stable question"));

        JSONObject live = message("assistant", "partial answer").put("streaming", true);
        db.putTimeline(session.localSessionId, "live:text:local-stream", 2, live);
        assertEquals("stable question", db.getSession(session.localSessionId).preview);

        db.putTimeline(session.localSessionId, "live:text:local-stream", 3,
                message("assistant", "final answer").put("streaming", false));
        assertEquals("final answer", db.getSession(session.localSessionId).preview);
    }

    @Test public void deletingThePreviewRowFallsBackAndDeletingTimelineClearsIt() throws Exception {
        MoyuDatabase.SessionRecord session = session("local-3");
        db.saveSession(session);
        db.putTimeline(session.localSessionId, "user", 1, message("user", "question"));
        db.putTimeline(session.localSessionId, "assistant", 2, message("assistant", "answer"));

        db.deleteTimelineItem("assistant");
        assertEquals("question", db.getSession(session.localSessionId).preview);

        db.deleteTimeline(session.localSessionId);
        assertNull(db.getSession(session.localSessionId).preview);
    }

    private static MoyuDatabase.SessionRecord session(String localId) {
        MoyuDatabase.SessionRecord session = new MoyuDatabase.SessionRecord();
        session.localSessionId = localId;
        session.nodeId = "node";
        session.kind = "claude";
        session.title = "Session";
        session.state = "localOnly";
        session.updatedAt = "2026-08-01T00:00:00Z";
        session.draft = "";
        return session;
    }

    private static JSONObject message(String role, String text) throws Exception {
        return new JSONObject().put("kind", "message").put("role", role).put("text", text)
                .put("createdAt", "2026-08-01T00:00:00Z");
    }
}
