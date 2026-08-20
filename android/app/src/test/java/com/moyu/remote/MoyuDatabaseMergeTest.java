package com.moyu.remote;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public final class MoyuDatabaseMergeTest {
    private static final String NODE = "node-1";
    private static final String REMOTE = "11111111-1111-4111-8111-111111111111";
    private static final String NATIVE = "22222222-2222-4222-8222-222222222222";
    private Context context;
    private MoyuDatabase db;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        context.deleteDatabase("moyu.db");
        db = new MoyuDatabase(context);
        db.getWritableDatabase();
    }

    @After public void tearDown() {
        db.close();
        context.deleteDatabase("moyu.db");
    }

    @Test public void mergesPreexistingLiveAndNativeRowsWithoutIndexCollisionOrTimelineLoss() throws Exception {
        MoyuDatabase.SessionRecord nativeRow = session("native-local", null, NATIVE, "cached history", 1400);
        nativeRow.nativeMessageCount = 1400;
        nativeRow.nativeCachedSeq = 1400;
        db.saveSession(nativeRow);
        db.putTimeline(nativeRow.localSessionId, "native:native-local:claude:" + NATIVE + ":1:text", 1,
                message("cached", "2026-08-01T00:00:00Z"));

        MoyuDatabase.SessionRecord liveRow = session("live-local", REMOTE, null, "live session", 37);
        liveRow.draft = "unfinished prompt";
        liveRow.model = "actual-model";
        db.saveSession(liveRow);
        db.putTimeline(liveRow.localSessionId, "m:" + REMOTE + ":37", 37,
                message("live", "2026-08-02T00:00:00Z"));
        db.putSetting("activeSessionId", liveRow.localSessionId);

        NativeHistoryIdentity.mergeLiveState(nativeRow, liveRow);
        db.mergeSessions(nativeRow, liveRow);

        List<MoyuDatabase.SessionRecord> sessions = db.listSessions();
        assertEquals(1, sessions.size());
        MoyuDatabase.SessionRecord merged = sessions.get(0);
        assertEquals("native-local", merged.localSessionId);
        assertEquals(REMOTE, merged.remoteSessionId);
        assertEquals(NATIVE, merged.nativeSessionId);
        assertEquals(37, merged.lastSeq);
        assertEquals(1400, merged.nativeCachedSeq);
        assertEquals("unfinished prompt", merged.draft);
        assertEquals("actual-model", merged.model);
        assertEquals("live", merged.preview);
        assertEquals("native-local", db.setting("activeSessionId", ""));
        assertNull(db.getSession("live-local"));
        assertEquals("native-local", db.findRemoteSession(NODE, REMOTE).localSessionId);
        assertEquals("native-local", db.findNativeSession(NODE, "claude", NATIVE).localSessionId);

        JSONArray timeline = db.timeline("native-local", 10);
        assertEquals(2, timeline.length());
        assertEquals("cached", timeline.getJSONObject(0).getString("text"));
        assertEquals(1, timeline.getJSONObject(0).getInt("localSeq"));
        assertEquals("live", timeline.getJSONObject(1).getString("text"));
        assertEquals(2, timeline.getJSONObject(1).getInt("localSeq"));
    }

    private static MoyuDatabase.SessionRecord session(String localId, String remoteId, String nativeId,
                                                       String title, int lastSeq) {
        MoyuDatabase.SessionRecord row = new MoyuDatabase.SessionRecord();
        row.localSessionId = localId;
        row.remoteSessionId = remoteId;
        row.nativeSessionId = nativeId;
        row.nodeId = NODE;
        row.kind = "claude";
        row.title = title;
        row.state = remoteId == null ? "localOnly" : "idle";
        row.updatedAt = "2026-08-09T00:00:00Z";
        row.draft = "";
        row.lastSeq = lastSeq;
        return row;
    }

    private static JSONObject message(String text, String createdAt) throws Exception {
        return new JSONObject().put("kind", "message").put("role", "assistant")
                .put("text", text).put("createdAt", createdAt);
    }
}
