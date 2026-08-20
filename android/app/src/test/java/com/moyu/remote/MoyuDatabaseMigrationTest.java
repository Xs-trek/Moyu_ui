package com.moyu.remote;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;

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
public final class MoyuDatabaseMigrationTest {
    private Context context;
    private MoyuDatabase db;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        ((MoyuApplication) context).database().close();
        context.deleteDatabase("moyu.db");
    }

    @After public void tearDown() {
        if (db != null) db.close();
        context.deleteDatabase("moyu.db");
    }

    @Test public void versionThreeMigrationBackfillsRecentPreviewAndSkipsMalformedRows() throws Exception {
        SQLiteDatabase legacy = context.openOrCreateDatabase("moyu.db", Context.MODE_PRIVATE, null);
        legacy.execSQL("CREATE TABLE nodes(node_id TEXT PRIMARY KEY,display_name TEXT NOT NULL,relay_node TEXT NOT NULL,network_name TEXT NOT NULL,mobile_vip TEXT NOT NULL,backend_vip TEXT NOT NULL,gateway_port INTEGER NOT NULL,socks_port INTEGER NOT NULL,last_connected_at TEXT)");
        legacy.execSQL("CREATE TABLE sessions(local_id TEXT PRIMARY KEY,remote_id TEXT,native_id TEXT,node_id TEXT NOT NULL,kind TEXT NOT NULL,title TEXT NOT NULL,cwd TEXT,profile_id TEXT,model TEXT,effort TEXT,state TEXT NOT NULL,updated_at TEXT NOT NULL,last_seq INTEGER NOT NULL DEFAULT 0,unread INTEGER NOT NULL DEFAULT 0,draft TEXT NOT NULL DEFAULT '',native_message_count INTEGER NOT NULL DEFAULT 0,native_cached_seq INTEGER NOT NULL DEFAULT 0,UNIQUE(node_id,remote_id))");
        legacy.execSQL("CREATE UNIQUE INDEX sessions_native_identity ON sessions(node_id,kind,native_id) WHERE native_id IS NOT NULL");
        legacy.execSQL("CREATE TABLE timeline(item_key TEXT PRIMARY KEY,session_id TEXT NOT NULL,local_seq INTEGER NOT NULL,remote_seq INTEGER,body TEXT NOT NULL,created_at TEXT NOT NULL,FOREIGN KEY(session_id) REFERENCES sessions(local_id) ON DELETE CASCADE,UNIQUE(session_id,local_seq))");
        legacy.execSQL("CREATE INDEX timeline_session_seq ON timeline(session_id,local_seq)");
        legacy.execSQL("CREATE TABLE settings(key TEXT PRIMARY KEY,value TEXT NOT NULL)");
        legacy.execSQL("INSERT INTO sessions(local_id,node_id,kind,title,state,updated_at) VALUES(?,?,?,?,?,?)",
                new Object[]{"legacy", "node", "claude", "Legacy", "localOnly", "2026-08-01T00:00:00Z"});
        legacy.execSQL("INSERT INTO timeline(item_key,session_id,local_seq,remote_seq,body,created_at) VALUES(?,?,?,?,?,?)",
                new Object[]{"old", "legacy", 1, 1, message("user", "old question").toString(), "2026-08-01T00:00:00Z"});
        legacy.execSQL("INSERT INTO timeline(item_key,session_id,local_seq,remote_seq,body,created_at) VALUES(?,?,?,?,?,?)",
                new Object[]{"latest", "legacy", 2, 2, message("assistant", "  newest\nanswer ").toString(), "2026-08-01T00:00:01Z"});
        legacy.execSQL("INSERT INTO timeline(item_key,session_id,local_seq,remote_seq,body,created_at) VALUES(?,?,?,?,?,?)",
                new Object[]{"malformed", "legacy", 3, 3, "{", "2026-08-01T00:00:02Z"});
        legacy.setVersion(3);
        legacy.close();

        db = new MoyuDatabase(context);
        db.getWritableDatabase();

        MoyuDatabase.SessionRecord migrated = db.getSession("legacy");
        assertEquals("newest answer", migrated.preview);
        assertEquals(2, migrated.previewSeq);
        assertNull(migrated.permissionMode);

        migrated.permissionMode = "acceptEdits";
        db.saveSession(migrated);
        assertEquals("acceptEdits", db.getSession("legacy").permissionMode);

        db.putTimeline("legacy", "legacy-approval-copy", 4, message("system", "[approval:command] run it"));
        db.putTimeline("legacy", "real-system", 5, message("system", "Native session resumed"));
        db.deleteLegacySyntheticSystemMessages("legacy");
        assertEquals(3, db.timeline("legacy", 20).length());
    }

    private static JSONObject message(String role, String text) throws Exception {
        return new JSONObject().put("kind", "message").put("role", role).put("text", text)
                .put("createdAt", "2026-08-01T00:00:00Z");
    }
}
