package com.moyu.remote;

import org.junit.Test;

import java.io.ByteArrayInputStream;

import okhttp3.Request;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public final class BackendClientTest {
    @Test public void pairingUsesBearerExemptRootRoute() {
        String origin = BackendClient.buildOrigin("10.1.1.11", 47123);
        assertEquals("http://10.1.1.11:47123/pair", BackendClient.buildPairUrl(origin));
        assertEquals("http://10.1.1.11:47123/api/v1", BackendClient.buildApiBase(origin));
    }

    @Test public void artifactDownloadReaderStopsAtBoundBeforeAllocatingWholeBody() throws Exception {
        assertArrayEquals(new byte[]{1, 2, 3, 4}, BackendClient.readBounded(new ByteArrayInputStream(new byte[]{1, 2, 3, 4}), 4));
        try {
            BackendClient.readBounded(new ByteArrayInputStream(new byte[]{1, 2, 3, 4, 5}), 4);
            fail("expected bounded reader to reject the fifth byte");
        } catch (BackendClient.ArtifactTooLargeException expected) {
            assertEquals("artifact exceeds size limit", expected.getMessage());
        }
    }

    @Test public void websocketAuthenticatesByHeaderWithoutTokenInUrl() {
        Request request = BackendClient.buildWebSocketRequest("http://10.1.1.10:18081/api/v1", "long-lived-secret");
        // OkHttp normalizes ws:// to http:// internally before newWebSocket performs the upgrade.
        assertEquals("http://10.1.1.10:18081/api/v1/ws", request.url().toString());
        assertEquals("Bearer long-lived-secret", request.header("Authorization"));
        assertEquals(-1, request.url().toString().indexOf("token"));
        assertEquals(-1, request.url().toString().indexOf("secret"));
    }
}
