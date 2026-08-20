package com.moyu.remote;

/** Promotion retention policy shared by coordinator logic and plain JVM tests. */
final class NativeHistoryRetention {
    private NativeHistoryRetention() { }

    static boolean backendContainsFullHistory(int truncatedBeforeSeq) { return truncatedBeforeSeq <= 0; }
    static boolean keepNativeRow(int nativeSeq, int truncatedBeforeSeq) {
        return truncatedBeforeSeq > 0 && nativeSeq <= truncatedBeforeSeq;
    }
}
