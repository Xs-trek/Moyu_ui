package com.moyu.remote;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class WebViewInteractionPolicyTest {
    @Test public void onlyLiteralJavascriptTrueConsumesSystemBack() {
        assertTrue(WebViewInteractionPolicy.wasHandled("true"));
        assertTrue(WebViewInteractionPolicy.wasHandled(" TRUE "));
        assertFalse(WebViewInteractionPolicy.wasHandled("false"));
        assertFalse(WebViewInteractionPolicy.wasHandled("null"));
        assertFalse(WebViewInteractionPolicy.wasHandled("{}"));
        assertFalse(WebViewInteractionPolicy.wasHandled(null));
    }
}
