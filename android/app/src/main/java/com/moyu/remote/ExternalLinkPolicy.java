package com.moyu.remote;

import java.net.URI;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Fixed native allowlist for links requested by the untrusted WebView presentation layer. */
final class ExternalLinkPolicy {
    private static final Set<String> ALLOWED_HOSTS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "anthropic.com",
            "www.anthropic.com",
            "docs.anthropic.com",
            "support.anthropic.com",
            "openai.com",
            "www.openai.com",
            "developers.openai.com",
            "platform.openai.com",
            "help.openai.com"
    )));

    private ExternalLinkPolicy() {}

    /** Returns a canonical allowed HTTPS URL, or {@code null} when it must stay blocked. */
    static String allowedUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.length() == 0 || rawUrl.length() > 4096) return null;
        try {
            URI uri = new URI(rawUrl);
            String host = uri.getHost();
            int port = uri.getPort();
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || host == null
                    || uri.getUserInfo() != null
                    || (port != -1 && port != 443)
                    || !ALLOWED_HOSTS.contains(host.toLowerCase(Locale.ROOT))) return null;
            return uri.toASCIIString();
        } catch (Exception invalid) {
            return null;
        }
    }
}
