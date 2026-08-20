package com.moyu.remote;

import org.json.JSONArray;
import org.json.JSONObject;

/** Pure backend-to-WebView projection for adapter/account/config metadata. It never performs I/O. */
final class ControlViewMapper {
    private ControlViewMapper() {}

    static JSONObject mapServer(JSONObject raw) throws Exception {
        JSONObject view = new JSONObject()
                .put("version", raw.optString("version", "0.0.3"))
                .put("protocolVersion", 1)
                .put("maxMessageBytes", 1048576)
                .put("features", new JSONObject()
                        .put("diff", true).put("resume", true)
                        .put("eventGapSync", true).put("sessionEffort", true)
                        .put("sessionModel", true).put("sessionPermissionMode", true));
        JSONArray mapped = new JSONArray();
        JSONArray adapters = array(raw, "adapters");
        for (int i = 0; i < adapters.length(); i++) {
            JSONObject adapter = adapters.optJSONObject(i);
            if (adapter == null) continue;
            JSONObject capabilities = adapter.optJSONObject("capabilities");
            JSONObject configuration = capabilities == null ? null : capabilities.optJSONObject("configuration");
            JSONObject approval = capabilities == null ? null : capabilities.optJSONObject("approval");
            JSONArray effortLevels = array(configuration, "effortLevels");
            JSONArray permissionModes = array(configuration, "permissionModes");
            JSONArray sandboxModes = array(configuration, "sandboxModes");
            JSONArray reviewers = array(configuration, "reviewers");
            JSONArray approvalPolicies = array(approval, "policies");
            JSONArray approvalChoices = new JSONArray().put("allow").put("deny").put("cancel");
            boolean modelConfigurable = configuration != null && configuration.optBoolean("model");
            String modelSelection = configuration == null ? "none"
                    : configuration.optString("modelSelection", modelConfigurable ? "freeform" : "none");

            JSONObject caps = new JSONObject()
                    .put("profiles", capabilities != null && capabilities.optBoolean("accountProfiles"))
                    .put("models", modelConfigurable)
                    .put("modelSelection", modelSelection)
                    .put("effortLevels", effortLevels)
                    .put("permissionModes", permissionModes)
                    .put("sandboxModes", sandboxModes)
                    .put("reviewers", reviewers)
                    .put("approvalPolicies", approvalPolicies)
                    .put("sandbox", sandboxModes.length() > 0)
                    .put("approvalsReviewer", reviewers.length() > 0)
                    .put("approvalChoices", approvalChoices)
                    .put("diff", true)
                    .put("interrupt", capabilities == null || capabilities.optBoolean("interrupt", true))
                    .put("resume", capabilities == null || capabilities.optBoolean("resume", true))
                    .put("description", approval == null ? "原生 CLI 事件流"
                            : approval.optString("semantics", "原生 CLI 事件流"));
            JSONObject streaming = capabilities == null ? null : capabilities.optJSONObject("streaming");
            if (streaming != null) caps.put("streaming", new JSONObject(streaming.toString()));

            JSONObject item = new JSONObject()
                    .put("adapter", adapter.optString("kind"))
                    .put("displayName", adapter.optString("displayName", adapter.optString("kind")))
                    .put("available", adapter.optBoolean("available"))
                    .put("unavailableReason", adapter.optString("unavailableReason"))
                    .put("capabilities", caps);
            copyString(adapter, item, "cliDefaultModel");
            copyString(adapter, item, "effectiveModel");
            copyString(adapter, item, "modelOverride");
            mapped.put(item);
        }
        return view.put("adapters", mapped);
    }

    static JSONObject mapAccounts(JSONObject raw, JSONObject server, String nodeId) throws Exception {
        JSONObject out = new JSONObject().put("nodeId", nodeId);
        JSONArray adapters = new JSONArray();
        JSONObject rawAdapters = raw.optJSONObject("adapters");
        JSONArray serverAdapters = array(server, "adapters");
        for (int i = 0; i < serverAdapters.length(); i++) {
            JSONObject base = new JSONObject(serverAdapters.getJSONObject(i).toString());
            String kind = base.optString("adapter");
            JSONObject status = rawAdapters == null ? null : rawAdapters.optJSONObject(kind);
            JSONArray profiles = new JSONArray();
            JSONArray list = status == null ? null : status.optJSONArray("profiles");
            if (list != null) {
                for (int j = 0; j < list.length(); j++) {
                    JSONObject profile = list.optJSONObject(j);
                    if (profile == null) continue;
                    JSONObject fields = profile.optJSONObject("fields");
                    JSONObject mapped = new JSONObject()
                            .put("profileId", profile.optString("id"))
                            .put("displayName", profile.optString("name", profile.optString("id")))
                            .put("nativeDefault", "nativeDefault".equals(profile.optString("sourceKind")))
                            .put("hasCredentials", fields != null && fields.optBoolean("hasCredentials"))
                            .put("active", profile.optBoolean("active"));
                    copyString(profile, mapped, "cliDefaultModel");
                    copyString(profile, mapped, "effectiveModel");
                    copyString(profile, mapped, "modelOverride");
                    profiles.put(mapped);
                }
            }
            base.put("profiles", profiles);
            adapters.put(base);
        }
        return out.put("adapters", adapters);
    }

    static JSONObject mapConfig(JSONObject raw, JSONObject server, JSONObject accounts) throws Exception {
        String adapter = raw.optString("defaultAdapter", "claude");
        JSONObject adapterConfigs = raw.optJSONObject("adapters");
        JSONObject current = adapterConfigs == null ? null : adapterConfigs.optJSONObject(adapter);
        JSONObject adapterStatus = findAdapter(array(server, "adapters"), adapter);
        JSONObject caps = adapterStatus == null ? null : adapterStatus.optJSONObject("capabilities");
        JSONArray sandboxModes = array(caps, "sandboxModes");
        JSONArray reviewers = array(caps, "reviewers");
        JSONArray approvalPolicies = array(caps, "approvalPolicies");
        JSONArray effortLevels = array(caps, "effortLevels");

        JSONObject view = new JSONObject()
                .put("defaultAdapter", adapter)
                .put("availableModels", new JSONArray())
                .put("sandboxModes", sandboxModes)
                .put("reviewers", reviewers)
                .put("approvalPolicies", approvalPolicies)
                .put("effortLevels", effortLevels)
                .put("modelSelection", caps == null ? "none" : caps.optString("modelSelection", "none"));

        String modelOverride = current == null ? null : nonEmpty(current.optString("model", null));
        view.put("explicitModel", modelOverride != null);
        if (modelOverride != null) {
            view.put("model", modelOverride);
            view.put("modelOverride", modelOverride);
        }
        if (current != null) {
            String approvalPolicy = validatedApprovalPolicy(current.optString("approvalPolicy", "on-request"));
            view.put("approvalPolicy", approvalPolicy == null ? "on-request" : approvalPolicy);
            if (sandboxModes.length() > 0) view.put("sandbox", current.optString("sandbox", "workspace-write"));
            if (reviewers.length() > 0) view.put("approvalsReviewer", current.optString("approvalsReviewer", "user"));
        }

        applyActiveProfileModels(view, accounts);
        if (!view.has("cliDefaultModel") && adapterStatus != null) copyString(adapterStatus, view, "cliDefaultModel");
        if (!view.has("effectiveModel") && adapterStatus != null) copyString(adapterStatus, view, "effectiveModel");
        if (!view.has("modelSource")) {
            view.put("modelSource", modelOverride != null ? "override"
                    : view.has("cliDefaultModel") ? "cli-default" : "unknown");
        }
        return view;
    }

    static void applyActiveProfileModels(JSONObject config, JSONObject accounts) throws Exception {
        if (config == null || accounts == null) return;
        String adapter = config.optString("defaultAdapter", "claude");
        JSONObject accountAdapter = findAdapter(array(accounts, "adapters"), adapter);
        JSONArray profiles = accountAdapter == null ? null : accountAdapter.optJSONArray("profiles");
        JSONObject active = null;
        if (profiles != null) {
            for (int i = 0; i < profiles.length(); i++) {
                JSONObject candidate = profiles.optJSONObject(i);
                if (candidate != null && candidate.optBoolean("active")) { active = candidate; break; }
            }
        }
        if (active == null) return;
        config.remove("cliDefaultModel");
        config.remove("effectiveModel");
        copyString(active, config, "cliDefaultModel");
        copyString(active, config, "effectiveModel");
        if (config.optBoolean("explicitModel")) {
            String override = nonEmpty(config.optString("model", null));
            String projectedOverride = nonEmpty(active.optString("modelOverride", null));
            if (override != null && !override.equals(projectedOverride)) config.put("effectiveModel", override);
        }
        config.put("modelSource", config.optBoolean("explicitModel") ? "override"
                : config.has("cliDefaultModel") ? "cli-default" : "unknown");
    }

    private static JSONObject findAdapter(JSONArray adapters, String kind) {
        for (int i = 0; i < adapters.length(); i++) {
            JSONObject item = adapters.optJSONObject(i);
            if (item != null && (kind.equals(item.optString("adapter")) || kind.equals(item.optString("kind")))) return item;
        }
        return null;
    }

    private static JSONArray array(JSONObject source, String key) throws Exception {
        JSONArray value = source == null ? null : source.optJSONArray(key);
        return value == null ? new JSONArray() : new JSONArray(value.toString());
    }

    private static void copyString(JSONObject from, JSONObject to, String key) throws Exception {
        String value = from == null ? null : nonEmpty(from.optString(key, null));
        if (value != null) to.put(key, value);
    }

    private static String nonEmpty(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    static String validatedApprovalPolicy(String value) {
        if ("untrusted".equals(value) || "on-failure".equals(value)
                || "on-request".equals(value) || "never".equals(value)) return value;
        return null;
    }
}
