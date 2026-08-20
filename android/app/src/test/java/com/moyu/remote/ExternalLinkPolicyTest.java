package com.moyu.remote;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public final class ExternalLinkPolicyTest {
    @Test public void acceptsOnlyFixedOfficialHttpsHosts() {
        assertEquals("https://docs.anthropic.com/en/docs", ExternalLinkPolicy.allowedUrl("https://docs.anthropic.com/en/docs"));
        assertEquals("https://developers.openai.com/resources/", ExternalLinkPolicy.allowedUrl("https://developers.openai.com/resources/"));
        assertEquals("https://help.openai.com:443/", ExternalLinkPolicy.allowedUrl("https://help.openai.com:443/"));
    }

    @Test public void rejectsCleartextCredentialsAndLookalikeHosts() {
        assertNull(ExternalLinkPolicy.allowedUrl("http://docs.anthropic.com/en/docs"));
        assertNull(ExternalLinkPolicy.allowedUrl("https://docs.anthropic.com@evil.example/"));
        assertNull(ExternalLinkPolicy.allowedUrl("https://docs.anthropic.com.evil.example/"));
        assertNull(ExternalLinkPolicy.allowedUrl("https://developers.openai.com:8443/"));
        assertNull(ExternalLinkPolicy.allowedUrl("javascript:alert(1)"));
    }
}
