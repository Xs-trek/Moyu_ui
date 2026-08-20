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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Lightweight local source of truth. Remote synchronization only upserts; it never erases older local history. */
public final class MoyuDatabase extends SQLiteOpenHelper {
    private static final String NAME = "moyu.db";
    private static final int VERSION = 5;
    private static final int PREVIEW_MAX_CODE_POINTS = 160;
    private static final int MIGRATION_PREVIEW_ROWS = 2000;
    private static final String SESSION_COLUMNS = "local_id,remote_id,native_id,node_id,kind,title,cwd,profile_id,model,effort,permission_mode,state,updated_at,last_seq,unread,draft,native_message_count,native_cached_seq,preview,preview_seq";

    public static final class NodeRecord {
        public String nodeId, displayName, relayNode, networkName, mobileVip, backendVip, lastConnectedAt;
        public int gatewayPort = 18081, socksPort = 1080;
    }

    public static final class SessionRecord {
        public String localSessionId, remoteSessionId, nativeSessionId, nodeId, kind, title, cwd, profileId, model, effort, permissionMode, state, updatedAt, draft, preview;
        public int lastSeq, unread, nativeMessageCount, nativeCachedSeq, previewSeq;
    }

    MoyuDatabase(Context context) { super(context, NAME, null, VERSION); }

    @Override public void onConfigure(SQLiteDatabase db) {
        super.onConfigure(db);
        db.setForeignKeyConstraintsEnabled(true);
        db.enableWriteAheadLogging();
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE nodes(node_id TEXT PRIMARY KEY,display_name TEXT NOT NULL,relay_node TEXT NOT NULL,network_name TEXT NOT NULL,mobile_vip TEXT NOT NULL,backend_vip TEXT NOT NULL,gateway_port INTEGER NOT NULL,socks_port INTEGER NOT NULL,last_connected_at TEXT)");
        db.execSQL("CREATE TABLE sessions(local_id TEXT PRIMARY KEY,remote_id TEXT,native_id TEXT,node_id TEXT NOT NULL,kind TEXT NOT NULL,title TEXT NOT NULL,cwd TEXT,profile_id TEXT,model TEXT,effort TEXT,permission_mode TEXT,state TEXT NOT NULL,updated_at TEXT NOT NULL,last_seq INTEGER NOT NULL DEFAULT 0,unread INTEGER NOT NULL DEFAULT 0,draft TEXT NOT NULL DEFAULT '',native_message_count INTEGER NOT NULL DEFAULT 0,native_cached_seq INTEGER NOT NULL DEFAULT 0,preview TEXT,preview_seq INTEGER NOT NULL DEFAULT 0,UNIQUE(node_id,remote_id))");
        db.execSQL("CREATE UNIQUE INDEX sessions_native_identity ON sessions(node_id,kind,native_id) WHERE native_id IS NOT NULL");
        db.execSQL("CREATE TABLE timeline(item_key TEXT PRIMARY KEY,session_id TEXT NOT NULL,local_seq INTEGER NOT NULL,remote_seq INTEGER,body TEXT NOT NULL,created_at TEXT NOT NULL,FOREIGN KEY(session_id) REFERENCES sessions(local_id) ON DELETE CASCADE,UNIQUE(session_id,local_seq))");
        db.execSQL("CREATE INDEX timeline_session_seq ON timeline(session_id,local_seq)");
        db.execSQL("CREATE TABLE settings(key TEXT PRIMARY KEY,value TEXT NOT NULL)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) db.execSQL("ALTER TABLE sessions ADD COLUMN effort TEXT");
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE sessions ADD COLUMN native_id TEXT");
            db.execSQL("ALTER TABLE sessions ADD COLUMN native_message_count INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE sessions ADD COLUMN native_cached_seq INTEGER NOT NULL DEFAULT 0");
            db.execSQL("CREATE UNIQUE INDEX sessions_native_identity ON sessions(node_id,kind,native_id) WHERE native_id IS NOT NULL");
        }
        if (oldVersion < 4) {
            db.execSQL("ALTER TABLE sessions ADD COLUMN preview TEXT");
            db.execSQL("ALTER TABLE sessions ADD COLUMN preview_seq INTEGER NOT NULL DEFAULT 0");
            backfillPreviews(db);
        }
        if (oldVersion < 5) db.execSQL("ALTER TABLE sessions ADD COLUMN permission_mode TEXT");
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
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT " + SESSION_COLUMNS + " FROM sessions ORDER BY updated_at DESC", null)) {
            while (cursor.moveToNext()) result.add(readSession(cursor));
        }
        return result;
    }

    public synchronized SessionRecord getSession(String localId) {
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT " + SESSION_COLUMNS + " FROM sessions WHERE local_id=?", new String[]{localId})) {
            return cursor.moveToFirst() ? readSession(cursor) : null;
        }
    }

    public synchronized SessionRecord findRemoteSession(String nodeId, String remoteId) {
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT " + SESSION_COLUMNS + " FROM sessions WHERE node_id=? AND remote_id=?", new String[]{nodeId, remoteId})) {
            return cursor.moveToFirst() ? readSession(cursor) : null;
        }
    }

    public synchronized SessionRecord findNativeSession(String nodeId, String kind, String nativeId) {
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT " + SESSION_COLUMNS + " FROM sessions WHERE node_id=? AND kind=? AND native_id=?", new String[]{nodeId, kind, nativeId})) {
            return cursor.moveToFirst() ? readSession(cursor) : null;
        }
    }

    public synchronized void saveSession(SessionRecord session) {
        normalizeSession(session);
        ContentValues values = sessionValues(session);
        SQLiteDatabase db = getWritableDatabase();
        int updated = db.update("sessions", values, "local_id=?", new String[]{session.localSessionId});
        if (updated == 0) {
            values.put("preview", session.preview);
            values.put("preview_seq", session.previewSeq);
            db.insertWithOnConflict("sessions", null, values, SQLiteDatabase.CONFLICT_ABORT);
        }
    }

    /**
     * Atomically folds an obsolete live-only row into its matching native-history row. Timeline
     * rows are appended in their existing order before the obsolete row is deleted, so the
     * foreign-key cascade cannot discard cached or live messages. The caller supplies the final
     * merged metadata (including the current remote id) on {@code survivor}.
     */
    public synchronized void mergeSessions(SessionRecord survivor, SessionRecord obsolete) {
        if (survivor == null || obsolete == null
                || survivor.localSessionId == null || obsolete.localSessionId == null
                || survivor.localSessionId.equals(obsolete.localSessionId)
                || !same(survivor.nodeId, obsolete.nodeId)
                || !same(survivor.kind, obsolete.kind)) {
            throw new IllegalArgumentException("Sessions must be distinct rows for the same node and adapter");
        }
        normalizeSession(survivor);
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            int nextLocalSeq = 0;
            try (Cursor maximum = db.rawQuery(
                    "SELECT COALESCE(MAX(local_seq),0) FROM timeline WHERE session_id=?",
                    new String[]{survivor.localSessionId})) {
                maximum.moveToFirst();
                nextLocalSeq = maximum.getInt(0);
            }
            ArrayList<String> movedKeys = new ArrayList<>();
            ArrayList<String> movedBodies = new ArrayList<>();
            try (Cursor rows = db.rawQuery(
                    "SELECT item_key,body FROM timeline WHERE session_id=? ORDER BY local_seq ASC",
                    new String[]{obsolete.localSessionId})) {
                while (rows.moveToNext()) {
                    movedKeys.add(rows.getString(0));
                    movedBodies.add(rows.getString(1));
                }
            }
            for (int i = 0; i < movedKeys.size(); i++) {
                int localSeq = ++nextLocalSeq;
                String body = movedBodies.get(i);
                try {
                    JSONObject parsed = new JSONObject(body);
                    parsed.put("localSeq", localSeq);
                    body = parsed.toString();
                } catch (Exception ignored) { }
                ContentValues timeline = new ContentValues();
                timeline.put("session_id", survivor.localSessionId);
                timeline.put("local_seq", localSeq);
                timeline.put("body", body);
                int moved = db.update("timeline", timeline, "session_id=? AND item_key=?",
                        new String[]{obsolete.localSessionId, movedKeys.get(i)});
                if (moved != 1) throw new IllegalStateException("Timeline row changed during merge");
            }

            ContentValues active = new ContentValues();
            active.put("value", survivor.localSessionId);
            db.update("settings", active, "key=? AND value=?",
                    new String[]{"activeSessionId", obsolete.localSessionId});
            if (db.delete("sessions", "local_id=?", new String[]{obsolete.localSessionId}) != 1) {
                throw new IllegalStateException("Obsolete session disappeared during merge");
            }
            if (db.update("sessions", sessionValues(survivor), "local_id=?",
                    new String[]{survivor.localSessionId}) != 1) {
                throw new IllegalStateException("Surviving session disappeared during merge");
            }
            refreshPreview(db, survivor.localSessionId);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
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
        updatePreview(db, sessionId, localSeq, body);
    }

    /**
     * Replaces exactly one optimistic user row with its canonical remote row.
     * The lookup is deliberately scoped to this session, the local:user key namespace,
     * exact message text, and the earliest local sequence. A replay of an already stored
     * canonical key only refreshes that key and cannot consume another identical prompt.
     */
    public synchronized void putCanonicalUserTimeline(String sessionId, String itemKey, Integer remoteSeq, JSONObject body) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            Integer localSeq = null;
            boolean canonicalExists = false;
            try (Cursor canonical = db.rawQuery(
                    "SELECT local_seq FROM timeline WHERE session_id=? AND item_key=?",
                    new String[]{sessionId, itemKey})) {
                if (canonical.moveToFirst()) { localSeq = canonical.getInt(0); canonicalExists = true; }
            }

            if (localSeq == null) {
                String canonicalText = body.optString("text", "");
                String pendingKey = null;
                try (Cursor pending = db.rawQuery(
                        "SELECT item_key,local_seq,body FROM timeline WHERE session_id=? AND substr(item_key,1,11)=? ORDER BY local_seq ASC",
                        new String[]{sessionId, "local:user:"})) {
                    while (pending.moveToNext()) {
                        try {
                            JSONObject candidate = new JSONObject(pending.getString(2));
                            if ("message".equals(candidate.optString("kind"))
                                    && "user".equals(candidate.optString("role"))
                                    && canonicalText.equals(candidate.optString("text", ""))) {
                                pendingKey = pending.getString(0);
                                localSeq = pending.getInt(1);
                                break;
                            }
                        } catch (Exception ignored) { }
                    }
                }
                if (pendingKey != null) {
                    db.delete("timeline", "session_id=? AND item_key=?", new String[]{sessionId, pendingKey});
                }
            }

            if (localSeq == null) {
                try (Cursor maximum = db.rawQuery(
                        "SELECT COALESCE(MAX(local_seq),0)+1 FROM timeline WHERE session_id=?",
                        new String[]{sessionId})) {
                    maximum.moveToFirst();
                    localSeq = maximum.getInt(0);
                }
            }

            try { body.put("localSeq", localSeq); } catch (Exception ignored) { }
            ContentValues values = new ContentValues();
            values.put("item_key", itemKey); values.put("session_id", sessionId); values.put("local_seq", localSeq);
            values.put("remote_seq", remoteSeq); values.put("body", body.toString());
            values.put("created_at", body.optString("createdAt", Instant.now().toString()));
            if (canonicalExists) db.update("timeline", values, "session_id=? AND item_key=?", new String[]{sessionId, itemKey});
            else db.insertWithOnConflict("timeline", null, values, SQLiteDatabase.CONFLICT_ABORT);
            updatePreview(db, sessionId, localSeq, body);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public synchronized void deleteTimelineItem(String itemKey) {
        SQLiteDatabase db = getWritableDatabase();
        String sessionId = null;
        int localSeq = -1;
        try (Cursor cursor = db.rawQuery("SELECT session_id,local_seq FROM timeline WHERE item_key=?", new String[]{itemKey})) {
            if (cursor.moveToFirst()) { sessionId = cursor.getString(0); localSeq = cursor.getInt(1); }
        }
        db.delete("timeline", "item_key=?", new String[]{itemKey});
        if (sessionId != null && previewSeq(db, sessionId) == localSeq) refreshPreview(db, sessionId);
    }

    public synchronized void deleteTimeline(String sessionId) {
        SQLiteDatabase db = getWritableDatabase();
        db.delete("timeline", "session_id=?", new String[]{sessionId});
        clearPreview(db, sessionId);
    }

    public synchronized void deleteTimelinePrefix(String sessionId, String prefix) {
        SQLiteDatabase db = getWritableDatabase();
        db.delete("timeline", "session_id=? AND substr(item_key,1,?)=?",
                new String[]{sessionId, String.valueOf(prefix.length()), prefix});
        refreshPreview(db, sessionId);
    }

    /** Removes only rows also retained by the hot backend window; older native-only rows stay offline. */
    public synchronized void deleteTimelinePrefixAfterRemoteSeq(String sessionId, String prefix, int truncatedBeforeSeq) {
        SQLiteDatabase db = getWritableDatabase();
        db.delete("timeline", "session_id=? AND substr(item_key,1,?)=? AND remote_seq>?",
                new String[]{sessionId, String.valueOf(prefix.length()), prefix, String.valueOf(truncatedBeforeSeq)});
        refreshPreview(db, sessionId);
    }

    /** Removes legacy gateway-authored duplicates. Approval and failure events already have
     * structured timeline cards; keeping their old [approval:*]/[error:*] system messages made
     * reconnect backfills appear as yellow boxes below newer assistant output. */
    public synchronized void deleteLegacySyntheticSystemMessages(String sessionId) {
        SQLiteDatabase db = getWritableDatabase();
        ArrayList<String> keys = new ArrayList<>();
        try (Cursor cursor = db.rawQuery("SELECT item_key,body FROM timeline WHERE session_id=?", new String[]{sessionId})) {
            while (cursor.moveToNext()) {
                try {
                    JSONObject body = new JSONObject(cursor.getString(1));
                    if (!"message".equals(body.optString("kind")) || !"system".equals(body.optString("role"))) continue;
                    String text = body.optString("text", "");
                    if (text.startsWith("[approval:") || text.startsWith("[error:")) keys.add(cursor.getString(0));
                } catch (Exception ignored) { }
            }
        }
        for (String key : keys) db.delete("timeline", "session_id=? AND item_key=?", new String[]{sessionId, key});
        if (!keys.isEmpty()) refreshPreview(db, sessionId);
    }

    public synchronized JSONObject timelineItem(String itemKey) {
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT body FROM timeline WHERE item_key=?", new String[]{itemKey})) {
            if (!cursor.moveToFirst()) return null;
            try { return new JSONObject(cursor.getString(0)); } catch (Exception ignored) { return null; }
        }
    }

    /** Atomically changes a persisted approval only while it is in the expected state. */
    public synchronized boolean transitionApprovalState(String sessionId, String itemKey, String expectedState, String nextState) {
        if (sessionId == null || itemKey == null || expectedState == null || nextState == null) return false;
        SQLiteDatabase db = getWritableDatabase();
        JSONObject body;
        try (Cursor cursor = db.rawQuery("SELECT body FROM timeline WHERE session_id=? AND item_key=?", new String[]{sessionId, itemKey})) {
            if (!cursor.moveToFirst()) return false;
            try { body = new JSONObject(cursor.getString(0)); } catch (Exception ignored) { return false; }
        }
        JSONObject approval = body.optJSONObject("approval");
        if (approval == null || !expectedState.equals(approval.optString("state"))) return false;
        try { approval.put("state", nextState); } catch (Exception ignored) { return false; }
        ContentValues values = new ContentValues();
        values.put("body", body.toString());
        return db.update("timeline", values, "session_id=? AND item_key=?", new String[]{sessionId, itemKey}) == 1;
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
        SessionRecord s = new SessionRecord(); s.localSessionId=c.getString(0); s.remoteSessionId=c.isNull(1)?null:c.getString(1); s.nativeSessionId=c.isNull(2)?null:c.getString(2); s.nodeId=c.getString(3); s.kind=c.getString(4);
        s.title=c.getString(5); s.cwd=c.isNull(6)?null:c.getString(6); s.profileId=c.isNull(7)?null:c.getString(7); s.model=c.isNull(8)?null:c.getString(8); s.effort=c.isNull(9)?null:c.getString(9); s.permissionMode=c.isNull(10)?null:c.getString(10);
        s.state=c.getString(11); s.updatedAt=c.getString(12); s.lastSeq=c.getInt(13); s.unread=c.getInt(14); s.draft=c.getString(15); s.nativeMessageCount=c.getInt(16); s.nativeCachedSeq=c.getInt(17);
        s.preview=c.isNull(18)?null:c.getString(18); s.previewSeq=c.getInt(19); return s;
    }

    private static boolean same(String left, String right) {
        return left == null ? right == null : left.equals(right);
    }

    private static void normalizeSession(SessionRecord session) {
        if (session.localSessionId == null) session.localSessionId = UUID.randomUUID().toString();
        if (session.updatedAt == null) session.updatedAt = Instant.now().toString();
        if (session.state == null) session.state = session.remoteSessionId == null ? "localOnly" : "idle";
        if (session.draft == null) session.draft = "";
    }

    private static ContentValues sessionValues(SessionRecord session) {
        ContentValues values = new ContentValues();
        values.put("local_id", session.localSessionId); values.put("remote_id", session.remoteSessionId); values.put("native_id", session.nativeSessionId); values.put("node_id", session.nodeId);
        values.put("kind", session.kind); values.put("title", session.title); values.put("cwd", session.cwd); values.put("profile_id", session.profileId);
        values.put("model", session.model); values.put("effort", session.effort); values.put("permission_mode", session.permissionMode); values.put("state", session.state); values.put("updated_at", session.updatedAt); values.put("last_seq", session.lastSeq);
        values.put("unread", session.unread); values.put("draft", session.draft);
        values.put("native_message_count", session.nativeMessageCount); values.put("native_cached_seq", session.nativeCachedSeq);
        return values;
    }

    private static void updatePreview(SQLiteDatabase db, String sessionId, int localSeq, JSONObject body) {
        String preview = previewText(body);
        if (preview == null) return;
        ContentValues values = new ContentValues();
        values.put("preview", preview);
        values.put("preview_seq", localSeq);
        db.update("sessions", values, "local_id=? AND preview_seq<=?", new String[]{sessionId, String.valueOf(localSeq)});
    }

    private static String previewText(JSONObject body) {
        if (body == null || !"message".equals(body.optString("kind"))) return null;
        if (body.optBoolean("streaming", false)) return null;
        String role = body.optString("role");
        if (!("user".equals(role) || "assistant".equals(role))) return null;
        String text = collapseWhitespace(body.optString("text", ""));
        return text.isEmpty() ? null : text;
    }

    private static String collapseWhitespace(String text) {
        StringBuilder out = new StringBuilder(Math.min(text.length(), PREVIEW_MAX_CODE_POINTS));
        boolean pendingSpace = false;
        int outputPoints = 0;
        for (int offset = 0; offset < text.length();) {
            int point = text.codePointAt(offset);
            offset += Character.charCount(point);
            if (Character.isWhitespace(point) || Character.isSpaceChar(point)) {
                pendingSpace = out.length() > 0;
                continue;
            }
            if (pendingSpace) {
                if (outputPoints + 1 >= PREVIEW_MAX_CODE_POINTS) break;
                out.append(' ');
                outputPoints++;
            }
            out.appendCodePoint(point);
            outputPoints++;
            pendingSpace = false;
            if (outputPoints >= PREVIEW_MAX_CODE_POINTS) break;
        }
        return out.toString();
    }

    private static int previewSeq(SQLiteDatabase db, String sessionId) {
        try (Cursor cursor = db.rawQuery("SELECT preview_seq FROM sessions WHERE local_id=?", new String[]{sessionId})) {
            return cursor.moveToFirst() ? cursor.getInt(0) : -1;
        }
    }

    private static void clearPreview(SQLiteDatabase db, String sessionId) {
        ContentValues values = new ContentValues();
        values.putNull("preview");
        values.put("preview_seq", 0);
        db.update("sessions", values, "local_id=?", new String[]{sessionId});
    }

    private static void refreshPreview(SQLiteDatabase db, String sessionId) {
        try (Cursor cursor = db.rawQuery("SELECT local_seq,body FROM timeline WHERE session_id=? ORDER BY local_seq DESC", new String[]{sessionId})) {
            while (cursor.moveToNext()) {
                try {
                    JSONObject body = new JSONObject(cursor.getString(1));
                    String preview = previewText(body);
                    if (preview == null) continue;
                    ContentValues values = new ContentValues();
                    values.put("preview", preview);
                    values.put("preview_seq", cursor.getInt(0));
                    db.update("sessions", values, "local_id=?", new String[]{sessionId});
                    return;
                } catch (Exception ignored) { }
            }
        }
        clearPreview(db, sessionId);
    }

    /** Upgrade work is globally bounded; sessions outside the recent window remain safely empty
     * until their next message write rather than making app startup scale with all history. */
    private static void backfillPreviews(SQLiteDatabase db) {
        Set<String> filled = new HashSet<>();
        try (Cursor cursor = db.rawQuery(
                "SELECT session_id,local_seq,body FROM timeline ORDER BY created_at DESC,local_seq DESC LIMIT ?",
                new String[]{String.valueOf(MIGRATION_PREVIEW_ROWS)})) {
            while (cursor.moveToNext()) {
                String sessionId = cursor.getString(0);
                if (filled.contains(sessionId)) continue;
                try {
                    JSONObject body = new JSONObject(cursor.getString(2));
                    String preview = previewText(body);
                    if (preview == null) continue;
                    ContentValues values = new ContentValues();
                    values.put("preview", preview);
                    values.put("preview_seq", cursor.getInt(1));
                    db.update("sessions", values, "local_id=?", new String[]{sessionId});
                    filled.add(sessionId);
                } catch (Exception ignored) { }
            }
        }
    }
}
