package com.moyu.remote;

/** Pure key construction kept outside Android APIs so node/session isolation is unit-testable. */
final class NativeHistoryKeys {
    private NativeHistoryKeys() { }

    static String prefix(String localSessionId, String kind, String nativeSessionId) {
        if (localSessionId == null || kind == null || nativeSessionId == null) throw new IllegalArgumentException("native history identity required");
        return "native:" + localSessionId + ":" + kind + ":" + nativeSessionId + ":";
    }

    static String item(String localSessionId, String kind, String nativeSessionId, int seq, String part) {
        return prefix(localSessionId, kind, nativeSessionId) + seq + ":" + part;
    }
}
