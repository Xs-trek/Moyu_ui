package com.moyu.remote;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

/** Conservative projection of EasyTier 2.6.4 network info for one configured PC backend VIP. */
final class EasyTierLinkInfo {
    static final String MODE_P2P = "p2p";
    static final String MODE_RELAY = "relay";
    static final String MODE_UNKNOWN = "unknown";

    final boolean peerConnected;
    final String linkMode;
    final String observedAt;

    EasyTierLinkInfo(boolean peerConnected, String linkMode, String observedAt) {
        this.peerConnected = peerConnected;
        this.linkMode = linkMode;
        this.observedAt = observedAt;
    }

    static EasyTierLinkInfo inspect(String collectedJson, String instanceName, String backendVip, String observedAt) {
        if (collectedJson == null || instanceName == null || backendVip == null) return unknown(observedAt);
        try {
            JSONObject map = new JSONObject(collectedJson).optJSONObject("map");
            JSONObject instance = map == null ? null : map.optJSONObject(instanceName);
            JSONArray pairs = instance == null ? null : instance.optJSONArray("peer_route_pairs");
            String target = stripCidr(backendVip);
            if (pairs == null || target.isEmpty()) return unknown(observedAt);

            for (int i = 0; i < pairs.length(); i++) {
                JSONObject pair = pairs.optJSONObject(i);
                JSONObject route = pair == null ? null : pair.optJSONObject("route");
                if (route == null || !routeAdvertisesTarget(route, target)) continue;

                long peerId = route.optLong("peer_id", 0L);
                boolean connected = peerId > 0L;
                /* This app starts EasyTier with latency_first=true, so only the selected
                 * latency-first path length is authoritative for the path actually used. */
                int cost = route.has("cost_latency_first") && !route.isNull("cost_latency_first")
                        ? route.optInt("cost_latency_first", -1) : -1;
                return new EasyTierLinkInfo(connected, connected ? classifyCost(cost) : MODE_UNKNOWN, observedAt);
            }
        } catch (Exception ignored) { }
        return unknown(observedAt);
    }

    static String classifyCost(int cost) {
        if (cost == 1) return MODE_P2P;
        if (cost > 1) return MODE_RELAY;
        return MODE_UNKNOWN;
    }

    static String ipv4FromUnsigned(long address) {
        if (address < 0L || address > 0xffff_ffffL) return "";
        return String.format(Locale.ROOT, "%d.%d.%d.%d",
                (address >>> 24) & 0xffL, (address >>> 16) & 0xffL,
                (address >>> 8) & 0xffL, address & 0xffL);
    }

    /**
     * The configured backend VIP is a mapped proxy address (normally 10.1.1.10/32),
     * not the PC peer's EasyTier interface address (normally 10.144.144.1). EasyTier
     * therefore exposes it in Route.proxy_cidrs. Keep the interface-address match as
     * a compatibility fallback for manually configured nodes that target the peer VIP.
     */
    private static boolean routeAdvertisesTarget(JSONObject route, String target) {
        if (target.equals(routeIpv4(route))) return true;
        JSONArray proxyCidrs = route.optJSONArray("proxy_cidrs");
        if (proxyCidrs == null) return false;
        for (int i = 0; i < proxyCidrs.length(); i++) {
            Object raw = proxyCidrs.opt(i);
            if (raw instanceof String && target.equals(stripCidr((String) raw))) return true;
        }
        return false;
    }

    private static String routeIpv4(JSONObject route) {
        Object raw = route.opt("ipv4_addr");
        if (raw instanceof String) return stripCidr((String) raw);
        if (!(raw instanceof JSONObject)) return "";
        JSONObject inet = (JSONObject) raw;
        Object address = inet.opt("address");
        if (address instanceof JSONObject) return ipv4FromUnsigned(((JSONObject) address).optLong("addr", -1L));
        if (address instanceof Number) return ipv4FromUnsigned(((Number) address).longValue());
        return "";
    }

    private static String stripCidr(String value) {
        if (value == null) return "";
        String trimmed = value.trim();
        int slash = trimmed.indexOf('/');
        return slash < 0 ? trimmed : trimmed.substring(0, slash);
    }

    private static EasyTierLinkInfo unknown(String observedAt) {
        return new EasyTierLinkInfo(false, MODE_UNKNOWN, observedAt);
    }
}
