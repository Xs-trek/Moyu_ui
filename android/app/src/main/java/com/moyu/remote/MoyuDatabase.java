package com.moyu.remote;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Lightweight local source of truth. Remote synchronization only upserts; it never erases older local history. */
public final class MoyuDatabase extends SQLiteOpenHelper {
    private static final String NAME = "moyu.db";
    private static final int VERSION = 2;

    public static final class NodeRecord {
        public String nodeId, displayName, relayNode, networkName, mobileVip, backendVip, lastConnectedAt;
        public int gatewayPort = 18081, socksPort = 1080;
    }

    public static final class SessionRecord {
        public String localSessionId, remoteSessionId, nodeId, kind, title, cwd, profileId, model, effort, state, updatedAt, draft;
        public int lastSeq, unread;
    }

    MoyuDatabase(Context context) { super(context, NAME, null, VERSION); }

    @Override public void onConfigure(SQLiteDatabase db) {
        super.onConfigure(db);
        db.setForeignKeyConstraintsEnabled(true);
        db.enableWriteAheadLogging();
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE nodes(node_id TEXT PRIMARY KEY,display_name TEXT NOT NULL,relay_node TEXT NOT NULL,network_name TEXT NOT NULL,mobile_vip TEXT NOT NULL,backend_vip TEXT NOT NULL,gateway_port INTEGER NOT NULL,socks_port INTEGER NOT NULL,last_connected_at TEXT)");
        db.execSQL("CREATE TABLE sessions(local_id TEXT PRIMARY KEY,remote_id TEXT,node_id TEXT NOT NULL,kind TEXT NOT NULL,title TEXT NOT NULL,cwd TEXT,profile_id TEXT,model TEXT,effort TEXT,state TEXT NOT NULL,updated_at TEXT NOT NULL,last_seq INTEGER NOT NULL DEFAULT 0,unread INTEGER NOT NULL DEFAULT 0,draft TEXT NOT NULL DEFAULT '',UNIQUE(node_id,remote_id))");
        db.execSQL("CREATE TABLE timeline(item_key TEXT PRIMARY KEY,session_id TEXT NOT NULL,local_seq INTEGER NOT NULL,remote_seq INTEGER,body TEXT NOT NULL,created_at TEXT NOT NULL,FOREIGN KEY(session_id) REFERENCES sessions(local_id) ON DELETE CASCADE,UNIQUE(session_id,local_seq))");
        db.execSQL("CREATE INDEX timeline_session_seq ON timeline(session_id,local_seq)");
        db.execSQL("CREATE TABLE settings(key TEXT PRIMARY KEY,value TEXT NOT NULL)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) db.execSQL("ALTER TABLE sessions ADD COLUMN effort TEXT");
    }

    public synchronized List<NodeRecord> listNodes() {
        ArrayList<NodeRecord> result = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT node_id,display_name,relay_node,network_name,mobile_vip,backend_vip,gateway_port,socks_port,last_connected_at FROM nodes ORDER BY display_name", null)) {
            while (cursor.moveToNext()) result.add(readNode(cursor));
        }
        return result;
    }

    public synchronized NodeRecord getNode(String nodeId) {
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT node_id,display_name,relay_node,network_name,mobile_vip,backend_vip,gateway_port,socks_port,last_connected_at FROM nodes WHERE node_id=?", new String[]{nodeId})) {
            return cursor.moveToFirst() ? readNode(cursor) : null;
        }
    }

    public synchronized void saveNode(NodeRecord node) {
        ContentValues values = new ContentValues();
        values.put("node_id", node.nodeId); values.put("display_name", node.displayName); values.put("relay_node", node.relayNode);
        values.put("network_name", node.networkName); values.put("mobile_vip", node.mobileVip); values.put("backend_vip", node.backendVip);
        values.put("gateway_port", node.gatewayPort); values.put("socks_port", node.socksPort); values.put("last_connected_at", node.lastConnectedAt);
        getWritableDatabase().insertWithOnConflict("nodes", null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public synchronized void deleteNode(String nodeId) {
        getWritableDatabase().delete("nodes", "node_id=?", new String[]{nodeId});
    }

    public synchronized List<SessionRecord> listSessions() {
        ArrayList<SessionRecord> result = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT local_id,remote_id,node_id,kind,title,cwd,profile_id,model,effort,state,updated_at,last_seq,unread,draft FROM sessions ORDER BY updated_at DESC", null)) {
            while (cursor.moveToNext()) result.add(readSession(cursor));
        }
        return result;
    }

    public synchronized SessionRecord getSession(String localId) {
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT local_id,remote_id,node_id,kind,title,cwd,profile_id,model,effort,state,updated_at,last_seq,unread,draft FROM sessions WHERE local_id=?", new String[]{localId})) {
            return cursor.moveToFirst() ? readSession(cursor) : null;
        }
    }

    public synchronized SessionRecord findRemoteSession(String nodeId, String remoteId) {
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT local_id,remote_id,node_id,kind,title,cwd,profile_id,model,effort,state,updated_at,last_seq,unread,draft FROM sessions WHERE node_id=? AND remote_id=?", new String[]{nodeId, remoteId})) {
            return cursor.moveToFirst() ? readSession(cursor) : null;
        }
    }

    public synchronized void saveSession(SessionRecord session) {
        if (session.localSessionId == null) session.localSessionId = UUID.randomUUID().toString();
        if (session.updatedAt == null) session.updatedAt = Instant.now().toString();
        if (session.state == null) session.state = session.remoteSessionId == null ? "localOnly" : "idle";
        ContentValues values = new ContentValues();
        values.put("local_id", session.localSessionId); values.put("remote_id", session.remoteSessionId); values.put("node_id", session.nodeId);
        values.put("kind", session.kind); values.put("title", session.title); values.put("cwd", session.cwd); values.put("profile_id", session.profileId);
        values.put("model", session.model); values.put("effort", session.effort); values.put("state", session.state); values.put("updated_at", session.updatedAt); values.put("last_seq", session.lastSeq);
        values.put("unread", session.unread); values.put("draft", session.draft == null ? "" : session.draft);
        SQLiteDatabase db = getWritableDatabase();
        int updated = db.update("sessions", values, "local_id=?", new String[]{session.localSessionId});
        if (updated == 0) db.insertWithOnConflict("sessions", null, values, SQLiteDatabase.CONFLICT_ABORT);
    }

    public synchronized void deleteSession(String localId) { getWritableDatabase().delete("sessions", "local_id=?", new String[]{localId}); }

    public synchronized void setDraft(String localId, String draft) {
        ContentValues values = new ContentValues(); values.put("draft", draft == null ? "" : draft);
        getWritableDatabase().update("sessions", values, "local_id=?", new String[]{localId});
    }

    public synchronized void setSessionState(String localId, String state, int lastSeq) {
        ContentValues values = new ContentValues(); values.put("state", state); values.put("last_seq", lastSeq); values.put("updated_at", Instant.now().toString());
        getWritableDatabase().update("sessions", values, "local_id=?", new String[]{localId});
    }

    public synchronized void putTimeline(String sessionId, String itemKey, Integer remoteSeq, JSONObject body) {
        SQLiteDatabase db = getWritableDatabase();
        int localSeq;
        try (Cursor existing = db.rawQuery("SELECT local_seq FROM timeline WHERE item_key=?", new String[]{itemKey})) {
            if (existing.moveToFirst()) localSeq = existing.getInt(0);
            else try (Cursor maximum = db.rawQuery("SELECT COALESCE(MAX(local_seq),0)+1 FROM timeline WHERE session_id=?", new String[]{sessionId})) {
                maximum.moveToFirst(); localSeq = maximum.getInt(0);
            }
        }
        try { body.put("localSeq", localSeq); } catch (Exception ignored) { }
        ContentValues values = new ContentValues(); values.put("item_key", itemKey); values.put("session_id", sessionId); values.put("local_seq", localSeq);
        values.put("remote_seq", remoteSeq); values.put("body", body.toString()); values.put("created_at", body.optString("createdAt", Instant.now().toString()));
        db.insertWithOnConflict("timeline", null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public synchronized void deleteTimelineItem(String itemKey) { getWritableDatabase().delete("timeline", "item_key=?", new String[]{itemKey}); }

    public synchronized JSONObject timelineItem(String itemKey) {
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT body FROM timeline WHERE item_key=?", new String[]{itemKey})) {
            if (!cursor.moveToFirst()) return null;
            try { return new JSONObject(cursor.getString(0)); } catch (Exception ignored) { return null; }
        }
    }

    public synchronized JSONArray timeline(String sessionId, int limit) {
        JSONArray result = new JSONArray();
        String sql = "SELECT body FROM (SELECT body,local_seq FROM timeline WHERE session_id=? ORDER BY local_seq DESC LIMIT ?) ORDER BY local_seq";
        try (Cursor cursor = getReadableDatabase().rawQuery(sql, new String[]{sessionId, String.valueOf(limit)})) {
            while (cursor.moveToNext()) try { result.put(new JSONObject(cursor.getString(0))); } catch (Exception ignored) { }
        }
        return result;
    }

    public synchronized void putSetting(String key, String value) {
        ContentValues values = new ContentValues(); values.put("key", key); values.put("value", value);
        getWritableDatabase().insertWithOnConflict("settings", null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public synchronized String setting(String key, String fallback) {
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT value FROM settings WHERE key=?", new String[]{key})) {
            return cursor.moveToFirst() ? cursor.getString(0) : fallback;
        }
    }

    private NodeRecord readNode(Cursor c) {
        NodeRecord n = new NodeRecord(); n.nodeId=c.getString(0); n.displayName=c.getString(1); n.relayNode=c.getString(2); n.networkName=c.getString(3);
        n.mobileVip=c.getString(4); n.backendVip=c.getString(5); n.gatewayPort=c.getInt(6); n.socksPort=c.getInt(7); n.lastConnectedAt=c.isNull(8)?null:c.getString(8); return n;
    }

    private SessionRecord readSession(Cursor c) {
        SessionRecord s = new SessionRecord(); s.localSessionId=c.getString(0); s.remoteSessionId=c.isNull(1)?null:c.getString(1); s.nodeId=c.getString(2); s.kind=c.getString(3);
        s.title=c.getString(4); s.cwd=c.isNull(5)?null:c.getString(5); s.profileId=c.isNull(6)?null:c.getString(6); s.model=c.isNull(7)?null:c.getString(7); s.effort=c.isNull(8)?null:c.getString(8);
        s.state=c.getString(9); s.updatedAt=c.getString(10); s.lastSeq=c.getInt(11); s.unread=c.getInt(12); s.draft=c.getString(13); return s;
    }
}
