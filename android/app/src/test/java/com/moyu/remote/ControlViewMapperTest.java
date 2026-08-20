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
    @Test public void preservesCapabilityArraysAndProjectsProfileLocalModels() throws Exception {
        JSONObject server = ControlViewMapper.mapServer(rawServer());
        JSONObject claude = find(server.getJSONArray("adapters"), "claude");
        JSONObject codex = find(server.getJSONArray("adapters"), "codex");

        assertEquals("freeform", claude.getJSONObject("capabilities").getString("modelSelection"));
        assertEquals(0, claude.getJSONObject("capabilities").getJSONArray("sandboxModes").length());
        assertEquals(0, claude.getJSONObject("capabilities").getJSONArray("reviewers").length());
        assertFalse(claude.getJSONObject("capabilities").getBoolean("sandbox"));
        assertEquals("workspace-write", codex.getJSONObject("capabilities").getJSONArray("sandboxModes").getString(1));
        assertEquals("guardian_subagent", codex.getJSONObject("capabilities").getJSONArray("reviewers").getString(2));
        assertEquals("on-request", claude.getJSONObject("capabilities").getJSONArray("approvalPolicies").getString(1));
        assertEquals("auto", claude.getJSONObject("capabilities").getJSONArray("permissionModes").getString(1));

        JSONObject accounts = ControlViewMapper.mapAccounts(rawAccounts(), server, "node-1");
        JSONObject profile = findProfile(find(accounts.getJSONArray("adapters"), "claude").getJSONArray("profiles"), "claude:native");
        assertEquals("glm-cli-default", profile.getString("cliDefaultModel"));
        assertEquals("glm-cli-default", profile.getString("effectiveModel"));

        JSONObject config = ControlViewMapper.mapConfig(rawConfig(false), server, accounts);
        assertFalse(config.getBoolean("explicitModel"));
        assertEquals("glm-cli-default", config.getString("cliDefaultModel"));
        assertEquals("glm-cli-default", config.getString("effectiveModel"));
        assertEquals("cli-default", config.getString("modelSource"));
        assertEquals(0, config.getJSONArray("availableModels").length());
        assertFalse(config.has("sandbox"));
        assertFalse(config.has("approvalsReviewer"));
    }

    @Test public void explicitOverrideRemainsDistinctFromCliDefault() throws Exception {
        JSONObject server = ControlViewMapper.mapServer(rawServer());
        JSONObject accounts = ControlViewMapper.mapAccounts(rawAccounts(), server, "node-1");
        JSONObject config = ControlViewMapper.mapConfig(rawConfig(true), server, accounts);

        assertTrue(config.getBoolean("explicitModel"));
        assertEquals("forced-model", config.getString("model"));
        assertEquals("forced-model", config.getString("modelOverride"));
        assertEquals("glm-cli-default", config.getString("cliDefaultModel"));
        assertEquals("forced-model", config.getString("effectiveModel"));
        assertEquals("override", config.getString("modelSource"));
    }

    @Test public void activeProfileChangeUpdatesLocalDefaultsWithoutInventingCatalog() throws Exception {
        JSONObject server = ControlViewMapper.mapServer(rawServer());
        JSONObject accounts = ControlViewMapper.mapAccounts(rawAccounts(), server, "node-1");
        JSONObject config = ControlViewMapper.mapConfig(rawConfig(false), server, accounts);
        JSONArray profiles = find(accounts.getJSONArray("adapters"), "claude").getJSONArray("profiles");
        profiles.getJSONObject(0).put("active", false);
        profiles.getJSONObject(1).put("active", true);

        ControlViewMapper.applyActiveProfileModels(config, accounts);

        assertEquals("glm-second-profile", config.getString("cliDefaultModel"));
        assertEquals("glm-second-profile", config.getString("effectiveModel"));
        assertEquals(0, config.getJSONArray("availableModels").length());
    }

    @Test public void approvalPoliciesRoundTripAsNativeValues() throws Exception {
        JSONObject server = ControlViewMapper.mapServer(rawServer());
        JSONObject accounts = ControlViewMapper.mapAccounts(rawAccounts(), server, "node-1");
        String[] policies = {"untrusted", "on-failure", "on-request", "never"};

        for (String policy : policies) {
            JSONObject config = ControlViewMapper.mapConfig(rawConfig(false, policy), server, accounts);
            assertEquals(policy, config.getString("approvalPolicy"));
            assertEquals(policy, ControlViewMapper.validatedApprovalPolicy(policy));
        }
        assertNull(ControlViewMapper.validatedApprovalPolicy("ask"));
        assertNull(ControlViewMapper.validatedApprovalPolicy("deny"));
        assertNull(ControlViewMapper.validatedApprovalPolicy("allow_session"));
    }

    private static JSONObject rawServer() throws Exception {
        return new JSONObject().put("version", "0.0.3").put("adapters", new JSONArray()
                .put(rawAdapter("claude", new JSONArray(), new JSONArray()))
                .put(rawAdapter("codex",
                        new JSONArray().put("read-only").put("workspace-write").put("danger-full-access"),
                        new JSONArray().put("user").put("auto_review").put("guardian_subagent"))));
    }

    private static JSONObject rawAdapter(String kind, JSONArray sandboxModes, JSONArray reviewers) throws Exception {
        return new JSONObject().put("kind", kind).put("displayName", kind).put("available", true)
                .put("cliDefaultModel", "claude".equals(kind) ? "glm-cli-default" : "gpt-cli-default")
                .put("effectiveModel", "claude".equals(kind) ? "glm-cli-default" : "gpt-cli-default")
                .put("capabilities", new JSONObject()
                        .put("streaming", new JSONObject().put("text", true).put("thinking", true).put("tools", true))
                        .put("resume", true).put("interrupt", true).put("accountProfiles", true)
                        .put("approval", new JSONObject().put("semantics", "remote-every-tool-or-never")
                                .put("policies", new JSONArray().put("untrusted").put("on-request").put("never")))
                        .put("configuration", new JSONObject().put("model", true).put("modelSelection", "freeform")
                                .put("effortLevels", new JSONArray().put("low").put("high"))
                                .put("permissionModes", new JSONArray().put("plan").put("auto").put("acceptEdits"))
                                .put("sandboxModes", sandboxModes).put("reviewers", reviewers)));
    }

    private static JSONObject rawAccounts() throws Exception {
        JSONArray profiles = new JSONArray()
                .put(new JSONObject().put("id", "claude:native").put("name", "Native")
                        .put("sourceKind", "nativeDefault").put("active", true)
                        .put("fields", new JSONObject().put("hasCredentials", true))
                        .put("cliDefaultModel", "glm-cli-default").put("effectiveModel", "glm-cli-default"))
                .put(new JSONObject().put("id", "claude:env:second").put("name", "Second")
                        .put("sourceKind", "envFile").put("active", false)
                        .put("fields", new JSONObject().put("hasCredentials", true))
                        .put("cliDefaultModel", "glm-second-profile").put("effectiveModel", "glm-second-profile"));
        return new JSONObject().put("adapters", new JSONObject()
                .put("claude", new JSONObject().put("profiles", profiles))
                .put("codex", new JSONObject().put("profiles", new JSONArray())));
    }

    private static JSONObject rawConfig(boolean override) throws Exception {
        return rawConfig(override, "on-request");
    }

    private static JSONObject rawConfig(boolean override, String approvalPolicy) throws Exception {
        JSONObject claude = new JSONObject().put("approvalPolicy", approvalPolicy)
                .put("sandbox", "workspace-write").put("approvalsReviewer", "user");
        if (override) claude.put("model", "forced-model");
        return new JSONObject().put("defaultAdapter", "claude").put("adapters", new JSONObject()
                .put("claude", claude)
                .put("codex", new JSONObject().put("approvalPolicy", "on-request")
                        .put("sandbox", "workspace-write").put("approvalsReviewer", "user")));
    }

    private static JSONObject find(JSONArray adapters, String kind) throws Exception {
        for (int i = 0; i < adapters.length(); i++) {
            JSONObject item = adapters.getJSONObject(i);
            if (kind.equals(item.optString("adapter"))) return item;
        }
        throw new AssertionError("missing adapter " + kind);
    }

    private static JSONObject findProfile(JSONArray profiles, String id) throws Exception {
        for (int i = 0; i < profiles.length(); i++) {
            JSONObject item = profiles.getJSONObject(i);
            if (id.equals(item.optString("profileId"))) return item;
        }
        throw new AssertionError("missing profile " + id);
    }
}
