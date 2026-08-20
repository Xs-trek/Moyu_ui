package com.moyu.remote;

/** Event-level policy for keeping full WebView projections responsive during streaming. */
final class ViewEmissionPolicy {
    private ViewEmissionPolicy() { }

    static boolean shouldThrottle(String eventType) {
        return "text.delta".equals(eventType)
                || "thinking.delta".equals(eventType)
                || "tool.output".equals(eventType)
                || "transport.metrics".equals(eventType);
    }

    static boolean updatesSessionTimestamp(String eventType) {
        return "turn.started".equals(eventType)
                || "turn.completed".equals(eventType)
                || "turn.failed".equals(eventType);
    }
}
