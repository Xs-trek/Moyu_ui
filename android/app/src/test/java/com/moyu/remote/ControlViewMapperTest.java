package com.moyu.remote;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public final class ControlViewMapperTest {
    @Test public void forwardsRealProtocolFeaturesAndGenericCapabilities() throws Exception {
        JSONObject server = ControlViewMapper.mapServer(rawServer());
        JSONObject openCode = find(server.getJSONArray("adapters"), "opencode");
        JSONObject caps = openCode.getJSONObject("capabilities");

        assertEquals(1, server.getInt("protocolVersion"));
        assertEquals(262144, server.getInt("maxMessageBytes"));
        assertTrue(server.getJSONObject("features").getBoolean("eventGapSync"));
        assertTrue(caps.getBoolean("diff"));
        assertFalse(caps.getBoolean("interrupt"));
        assertEquals("local-server", caps.getJSONObject("runtime").getString("source"));
        assertTrue(caps.getJSONObject("runtime").getBoolean("shared"));
        assertEquals(0, caps.getJSONObject("attachments").getInt("maxCount"));
        assertEquals("allow", caps.getJSONArray("approvalChoices").getString(0));
        assertTrue(caps.getJSONObject("history").getBoolean("messages"));
        assertFalse(caps.getJSONObject("history").getBoolean("resume"));
        assertFalse(caps.has("description"));
    }

    @Test public void compatibilityReasonAndUnknownAdaptersAreDataDriven() throws Exception {
        JSONObject server = ControlViewMapper.mapServer(rawServer());
        JSONObject future = find(server.getJSONArray("adapters"), "future-agent");

        assertFalse(future.getBoolean("available"));
        assertEquals("protocol probe failed", future.getString("unavailableReason"));
        assertEquals("incompatible", future.getJSONObject("compatibility").getString("state"));
        assertEquals("future-jsonl", future.getJSONObject("compatibility").getString("protocol"));
    }

    @Test public void genericProfileAndConfigProjectionUsesAdvertisedValuesOnly() throws Exception {
        JSONObject server = ControlViewMapper.mapServer(rawServer());
        JSONObject accounts = ControlViewMapper.mapAccounts(rawAccounts(), server, "node-1");
        JSONObject config = ControlViewMapper.mapConfig(rawConfig(), server, accounts);

        assertEquals("opencode", config.getString("defaultAdapter"));
        assertEquals("shared-default", config.getString("cliDefaultModel"));
        assertEquals("shared-default", config.getString("effectiveModel"));
        assertEquals("cli-default", config.getString("modelSource"));
        assertFalse(config.has("sandbox"));
        assertFalse(config.has("approvalsReviewer"));
        assertFalse(config.has("approvalPolicy"));
        assertEquals(0, config.getJSONArray("availableModels").length());
    }

    @Test public void protocolGateHasOneExplicitLegacyWindow() throws Exception {
        assertTrue(ControlViewMapper.isSupportedProtocol(new JSONObject().put("version", "0.0.4").put("protocolVersion", 1)));
        assertFalse(ControlViewMapper.isSupportedProtocol(new JSONObject().put("version", "0.0.4").put("protocolVersion", 2)));
        assertFalse(ControlViewMapper.isSupportedProtocol(new JSONObject().put("version", "0.0.4")));
        assertTrue(ControlViewMapper.isSupportedProtocol(new JSONObject().put("version", "0.0.3")));
        assertFalse(ControlViewMapper.mapServer(new JSONObject().put("version", "0.0.3")).has("protocolVersion"));
    }

    @Test public void optionValidationNeverInventsOrExpandsValues() throws Exception {
        JSONArray allowed = new JSONArray().put("prompt").put("reject");
        assertEquals("prompt", ControlViewMapper.validatedOption("prompt", allowed));
        assertNull(ControlViewMapper.validatedOption("allow", allowed));
        assertEquals("prompt", ControlViewMapper.validatedOption(" prompt ", allowed));
    }

    private static JSONObject rawServer() throws Exception {
        JSONObject features = new JSONObject().put("diff", true).put("interrupt", true)
                .put("eventGapSync", true).put("resume", true);
        return new JSONObject().put("version", "0.0.4").put("protocolVersion", 1)
                .put("maxMessageBytes", 262144).put("features", features)
                .put("adapters", new JSONArray().put(openCode()).put(futureAdapter()));
    }

    private static JSONObject openCode() throws Exception {
        JSONObject configuration = new JSONObject().put("model", true).put("modelSelection", "freeform")
                .put("effortLevels", new JSONArray()).put("permissionModes", new JSONArray())
                .put("sandboxModes", new JSONArray()).put("reviewers", new JSONArray());
        JSONObject capabilities = new JSONObject()
                .put("accountProfiles", true).put("interrupt", false)
                .put("configuration", configuration)
                .put("history", new JSONObject().put("list", true).put("messages", true).put("resume", false))
                .put("runtime", new JSONObject().put("source", "local-server").put("shared", true))
                .put("attachments", new JSONObject().put("images", false).put("maxCount", 0))
                .put("approval", new JSONObject().put("choices", new JSONArray().put("allow").put("deny"))
                        .put("policies", new JSONArray()));
        return new JSONObject().put("kind", "opencode").put("displayName", "OpenCode")
                .put("available", true).put("capabilities", capabilities)
                .put("compatibility", new JSONObject().put("state", "compatible")
                        .put("protocol", "opencode-server-api").put("version", "1.2.3"));
    }

    private static JSONObject futureAdapter() throws Exception {
        return new JSONObject().put("kind", "future-agent").put("displayName", "Future Agent")
                .put("available", false).put("capabilities", new JSONObject())
                .put("compatibility", new JSONObject().put("state", "incompatible")
                        .put("protocol", "future-jsonl").put("reason", "protocol probe failed"));
    }

    private static JSONObject rawAccounts() throws Exception {
        JSONArray profiles = new JSONArray().put(new JSONObject().put("id", "shared-default")
                .put("name", "Shared Default").put("sourceKind", "nativeDefault").put("active", true)
                .put("fields", new JSONObject().put("hasCredentials", true))
                .put("cliDefaultModel", "shared-default").put("effectiveModel", "shared-default"));
        return new JSONObject().put("adapters", new JSONObject()
                .put("opencode", new JSONObject().put("profiles", profiles))
                .put("future-agent", new JSONObject().put("profiles", new JSONArray())));
    }

    private static JSONObject rawConfig() throws Exception {
        return new JSONObject().put("defaultAdapter", "opencode").put("adapters", new JSONObject()
                .put("opencode", new JSONObject().put("sandbox", "not-advertised")
                        .put("approvalsReviewer", "not-advertised").put("approvalPolicy", "not-advertised")));
    }

    private static JSONObject find(JSONArray adapters, String kind) throws Exception {
        for (int i = 0; i < adapters.length(); i++) {
            JSONObject item = adapters.getJSONObject(i);
            if (kind.equals(item.optString("adapter"))) return item;
        }
        throw new AssertionError("missing adapter " + kind);
    }
}
