package com.moyu.remote;

import java.util.ArrayDeque;

/** Ordered WebView state delivery with a bounded recovery path. */
final class ViewDispatchQueue<T> {
    private final int maximumPending;
    private final ArrayDeque<T> pending = new ArrayDeque<>();
    private boolean inFlight;
    private boolean recovering;
    private boolean recoveryRequested;

    ViewDispatchQueue(int maximumPending) {
        if (maximumPending < 1) throw new IllegalArgumentException("maximumPending");
        this.maximumPending = maximumPending;
    }

    T offer(T value, boolean fullSnapshot) {
        if (fullSnapshot) {
            pending.clear();
            recovering = false;
        } else if (recovering) return null;
        if (!inFlight) {
            inFlight = true;
            return value;
        }
        if (pending.size() >= maximumPending) {
            pending.clear();
            recovering = true;
            recoveryRequested = true;
            return null;
        }
        pending.addLast(value);
        return null;
    }

    T complete() {
        T next = pending.pollFirst();
        if (next != null) return next;
        inFlight = false;
        return null;
    }

    boolean consumeRecoveryRequest() {
        boolean value = recoveryRequested;
        recoveryRequested = false;
        return value;
    }

    void clear() {
        pending.clear();
        inFlight = false;
        recovering = false;
        recoveryRequested = false;
    }
}
