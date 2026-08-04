package com.moyu.remote;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class BackendClientTest {
    @Test public void pairingUsesBearerExemptRootRoute() {
        String origin = BackendClient.buildOrigin("10.1.1.11", 47123);
        assertEquals("http://10.1.1.11:47123/pair", BackendClient.buildPairUrl(origin));
        assertEquals("http://10.1.1.11:47123/api/v1", BackendClient.buildApiBase(origin));
    }
}
