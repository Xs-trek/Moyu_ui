package com.moyu.remote;

/** Normalizes the JSON scalar returned by WebView.evaluateJavascript. */
final class WebViewInteractionPolicy {
    private WebViewInteractionPolicy() { }

    static boolean wasHandled(String javascriptResult) {
        return "true".equalsIgnoreCase(javascriptResult == null ? "" : javascriptResult.trim());
    }
}
