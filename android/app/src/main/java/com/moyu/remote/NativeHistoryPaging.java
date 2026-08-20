package com.moyu.remote;

/** Bounded validation for the server-owned native-history discovery cursor. */
final class NativeHistoryPaging {
    static final int PAGE_SIZE = 100;
    static final int MAX_OFFSET = 4000;
    static final int MAX_ITEMS = 4000;
    static final int MAX_PAGES = 41;

    private NativeHistoryPaging() { }

    static int next(int currentOffset, int serverNextOffset, boolean hasMore) {
        if (!hasMore) return currentOffset;
        if (serverNextOffset <= currentOffset || serverNextOffset > MAX_OFFSET) {
            throw new IllegalArgumentException("invalid native history nextOffset");
        }
        return serverNextOffset;
    }
}
