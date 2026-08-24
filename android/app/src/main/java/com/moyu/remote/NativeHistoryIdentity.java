package com.moyu.remote;

/** Opaque adapter-owned resume identity and live/history row merge rules. */
final class NativeHistoryIdentity {
    private NativeHistoryIdentity() { }

    static boolean shouldBind(String resumeRef) {
        return AdapterCapabilityPolicy.validResumeRef(resumeRef) != null;
    }

    /** Keeps the native row's stable local identity/cache while adopting live-only state. */
    static void mergeLiveState(MoyuDatabase.SessionRecord nativeRow, MoyuDatabase.SessionRecord liveRow) {
        nativeRow.remoteSessionId = liveRow.remoteSessionId;
        if (nativeRow.resumeRef == null) nativeRow.resumeRef = liveRow.resumeRef;
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
