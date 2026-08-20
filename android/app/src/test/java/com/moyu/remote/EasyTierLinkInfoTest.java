package com.moyu.remote;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public final class EasyTierLinkInfoTest {
    @Test public void routeCostClassificationMatchesEasyTierSemantics() {
        assertEquals("p2p", EasyTierLinkInfo.classifyCost(1));
        assertEquals("relay", EasyTierLinkInfo.classifyCost(2));
        assertEquals("relay", EasyTierLinkInfo.classifyCost(7));
        assertEquals("unknown", EasyTierLinkInfo.classifyCost(0));
        assertEquals("unknown", EasyTierLinkInfo.classifyCost(-1));
    }

    @Test public void protobufUnsignedIpv4UsesNetworkByteOrder() {
        assertEquals("10.144.144.2", EasyTierLinkInfo.ipv4FromUnsigned(177246210L));
        assertEquals("", EasyTierLinkInfo.ipv4FromUnsigned(-1L));
        assertEquals("", EasyTierLinkInfo.ipv4FromUnsigned(0x1_0000_0000L));
    }

    @Test public void mappedBackendVipFindsPcRouteAfterPublicRelayRoute() {
        EasyTierLinkInfo info = EasyTierLinkInfo.inspect("{\"map\":{\"moyu-node-1\":{\"peer_route_pairs\":["
                + "{\"route\":{\"peer_id\":7,\"ipv4_addr\":{\"address\":{\"addr\":16843009}},\"cost_latency_first\":1,\"proxy_cidrs\":[]}},"
                + "{\"route\":{\"peer_id\":42,\"ipv4_addr\":{\"address\":{\"addr\":177246209}},\"cost_latency_first\":1,\"proxy_cidrs\":[\"10.1.1.10/32\"]}}"
                + "]}}}", "moyu-node-1", "10.1.1.10", "2026-08-13T00:00:00Z");

        assertTrue(info.peerConnected);
        assertEquals("p2p", info.linkMode);
        assertEquals("2026-08-13T00:00:00Z", info.observedAt);
    }

    @Test public void mappedBackendVipUsesSelectedLatencyFirstRelayPath() {
        EasyTierLinkInfo info = EasyTierLinkInfo.inspect("{\"map\":{\"moyu-node-1\":{\"peer_route_pairs\":["
                + "{\"route\":{\"peer_id\":42,\"ipv4_addr\":{\"address\":{\"addr\":177246209}},\"cost\":1,\"cost_latency_first\":2,\"proxy_cidrs\":[\"10.1.1.10/32\"]}}"
                + "]}}}", "moyu-node-1", "10.1.1.10/32", "2026-08-13T00:00:00Z");

        assertTrue(info.peerConnected);
        assertEquals("relay", info.linkMode);
    }

    @Test public void unrelatedPeerRouteDoesNotClaimPcConnection() {
        EasyTierLinkInfo info = EasyTierLinkInfo.inspect("{\"map\":{\"moyu-node-1\":{\"peer_route_pairs\":["
                + "{\"route\":{\"peer_id\":7,\"ipv4_addr\":{\"address\":{\"addr\":177246209}},\"cost_latency_first\":1,\"proxy_cidrs\":[\"10.2.2.20/32\"]}}"
                + "]}}}", "moyu-node-1", "10.1.1.10", "2026-08-13T00:00:00Z");

        assertFalse(info.peerConnected);
        assertEquals("unknown", info.linkMode);
    }
}
