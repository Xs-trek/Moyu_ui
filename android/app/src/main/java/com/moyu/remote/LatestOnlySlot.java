package com.moyu.remote;

/** One in-flight value plus one replaceable pending value. Access is serialized on the UI thread. */
final class LatestOnlySlot<T> {
    private boolean inFlight;
    private T pending;

    T offer(T value) {
        if (!inFlight) {
            inFlight = true;
            return value;
        }
        pending = value;
        return null;
    }

    T complete() {
        if (pending != null) {
            T next = pending;
            pending = null;
            return next;
        }
        inFlight = false;
        return null;
    }

    void clear() {
        inFlight = false;
        pending = null;
    }
}
