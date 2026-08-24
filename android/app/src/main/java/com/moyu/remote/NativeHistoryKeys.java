package com.moyu.remote;

/** Pure key construction kept outside Android APIs so node/session isolation is unit-testable. */
final class NativeHistoryKeys {
    private NativeHistoryKeys() { }

    static String prefix(String localSessionId, String kind, String resumeRef) {
        if (localSessionId == null || kind == null || resumeRef == null) throw new IllegalArgumentException("history resume identity required");
        return "native:" + localSessionId + ":" + kind + ":" + resumeRef + ":";
    }

    static String item(String localSessionId, String kind, String resumeRef, int seq, String part) {
        return prefix(localSessionId, kind, resumeRef) + seq + ":" + part;
    }
}
