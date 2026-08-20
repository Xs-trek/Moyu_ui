package com.moyu.remote;

/** Decides when a live summary's CLI reference is authoritative enough to persist as native identity. */
final class NativeHistoryIdentity {
    private NativeHistoryIdentity() { }

    static boolean shouldBind(String kind, String remoteSessionId, String cliSessionRef, boolean nativeRowExists) {
        if (!ArtifactCache.isValidId(cliSessionRef)) return false;
        if ("claude".equals(kind) || nativeRowExists) return true;
        // A fresh Codex handle uses the backend id only until thread.started supplies the real thread id.
        return "codex".equals(kind) && !cliSessionRef.equals(remoteSessionId);
    }

    /** Keeps the native row's stable local identity/cache while adopting live-only state. */
    static void mergeLiveState(MoyuDatabase.SessionRecord nativeRow, MoyuDatabase.SessionRecord liveRow) {
        nativeRow.remoteSessionId = liveRow.remoteSessionId;
        nativeRow.lastSeq = liveRow.lastSeq;
        nativeRow.unread = Math.max(nativeRow.unread, liveRow.unread);
        if (liveRow.draft != null && !liveRow.draft.isEmpty()) nativeRow.draft = liveRow.draft;
        if (liveRow.profileId != null) nativeRow.profileId = liveRow.profileId;
        if (liveRow.model != null) nativeRow.model = liveRow.model;
        if (liveRow.effort != null) nativeRow.effort = liveRow.effort;
        if (liveRow.permissionMode != null) nativeRow.permissionMode = liveRow.permissionMode;
        if (liveRow.state != null) nativeRow.state = liveRow.state;
        if (liveRow.updatedAt != null) nativeRow.updatedAt = liveRow.updatedAt;
    }
}
