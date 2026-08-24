package com.moyu.remote;

import org.json.JSONArray;
import org.json.JSONObject;

/** Pure backend-to-WebView projection for adapter/account/config metadata. It never performs I/O. */
final class ControlViewMapper {
    private ControlViewMapper() {}

    static JSONObject mapServer(JSONObject raw) throws Exception {
        Integer protocolVersion = reportedProtocolVersion(raw);
        int maxMessageBytes = raw.optInt("maxMessageBytes", 0);
        JSONObject rawFeatures = raw.optJSONObject("features");
        JSONObject view = new JSONObject()
                .put("version", raw.optString("version", "unknown"))
                .put("features", rawFeatures == null ? new JSONObject() : new JSONObject(rawFeatures.toString()));
        if (protocolVersion != null) view.put("protocolVersion", protocolVersion);
        if (maxMessageBytes > 0) view.put("maxMessageBytes", maxMessageBytes);
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
            JSONArray approvalChoices = array(approval, "choices");
            boolean modelConfigurable = configuration != null && configuration.optBoolean("model");
            String modelSelection = configuration == null ? "none"
                    : configuration.optString("modelSelection", modelConfigurable ? "freeform" : "none");
            JSONObject rawHistory = capabilities == null ? null : capabilities.optJSONObject("history");
            boolean legacyResume = capabilities != null && capabilities.optBoolean("resume", false);
            JSONObject history = rawHistory == null
                    ? new JSONObject().put("list", legacyResume).put("messages", legacyResume).put("resume", legacyResume)
                    : new JSONObject(rawHistory.toString());

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
                    .put("approval", new JSONObject().put("choices", new JSONArray(approvalChoices.toString())))
                    .put("diff", booleanCapability(capabilities, rawFeatures, "diff"))
                    .put("interrupt", booleanCapability(capabilities, rawFeatures, "interrupt"))
                    .put("resume", history.optBoolean("resume", false))
                    .put("history", history);
            String approvalSemantics = approval == null ? null : nonEmpty(approval.optString("semantics", null));
            if (approvalSemantics != null) caps.put("description", approvalSemantics);
            JSONObject streaming = capabilities == null ? null : capabilities.optJSONObject("streaming");
            if (streaming != null) caps.put("streaming", new JSONObject(streaming.toString()));
            JSONObject runtime = capabilities == null ? null : capabilities.optJSONObject("runtime");
            if (runtime != null) caps.put("runtime", new JSONObject(runtime.toString()));
            JSONObject attachments = capabilities == null ? null : capabilities.optJSONObject("attachments");
            if (attachments != null) caps.put("attachments", new JSONObject(attachments.toString()));

            JSONObject compatibility = adapter.optJSONObject("compatibility");
            String unavailableReason = nonEmpty(adapter.optString("unavailableReason", null));
            if (unavailableReason == null && compatibility != null) {
                unavailableReason = nonEmpty(compatibility.optString("reason", null));
            }
            JSONObject item = new JSONObject()
                    .put("adapter", adapter.optString("kind"))
                    .put("displayName", adapter.optString("displayName", adapter.optString("kind")))
                    .put("available", adapter.optBoolean("available"))
                    .put("capabilities", caps);
            if (unavailableReason != null) item.put("unavailableReason", unavailableReason);
            if (adapter.has("protocolVersion")) item.put("protocolVersion", adapter.optInt("protocolVersion", -1));
            if (compatibility != null) item.put("compatibility", new JSONObject(compatibility.toString()));
            copyString(adapter, item, "cliDefaultModel");
            copyString(adapter, item, "effectiveModel");
            copyString(adapter, item, "modelOverride");
            mapped.put(item);
        }
        return view.put("adapters", mapped);
    }

    /** Missing protocol is accepted only for the explicitly supported v0.0.3 compatibility window. */
    static boolean isSupportedProtocol(JSONObject raw) {
        Integer reported = reportedProtocolVersion(raw);
        if (reported != null) return reported == 1;
        String version = raw == null ? "" : raw.optString("version", "");
        return version.equals("0.0.3") || version.startsWith("0.0.3-") || version.startsWith("0.0.3+");
    }

    static Integer reportedProtocolVersion(JSONObject raw) {
        if (raw == null || !raw.has("protocolVersion") || raw.isNull("protocolVersion")) return null;
        Object value = raw.opt("protocolVersion");
        if (!(value instanceof Number)) return null;
        int version = ((Number) value).intValue();
        return version > 0 ? version : null;
    }

    private static boolean booleanCapability(JSONObject adapter, JSONObject global, String key) {
        if (adapter != null && adapter.has(key)) return adapter.optBoolean(key, false);
        return global != null && global.optBoolean(key, false);
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
        String adapter = nonEmpty(raw.optString("defaultAdapter", null));
        if (adapter == null) adapter = firstAdapter(array(server, "adapters"));
        if (adapter == null) adapter = "";
        JSONObject adapterConfigs = raw.optJSONObject("adapters");
        JSONObject current = adapterConfigs == null ? null : adapterConfigs.optJSONObject(adapter);
        JSONObject adapterStatus = findAdapter(array(server, "adapters"), adapter);
        JSONObject caps = adapterStatus == null ? null : adapterStatus.optJSONObject("capabilities");
        JSONArray sandboxModes = array(caps, "sandboxModes");
        JSONArray reviewers = array(caps, "reviewers");
        JSONArray approvalPolicies = array(caps, "approvalPolicies");
        JSONArray effortLevels = array(caps, "effortLevels");
        JSONArray permissionModes = array(caps, "permissionModes");

        JSONObject view = new JSONObject()
                .put("defaultAdapter", adapter)
                .put("availableModels", new JSONArray())
                .put("sandboxModes", sandboxModes)
                .put("reviewers", reviewers)
                .put("approvalPolicies", approvalPolicies)
                .put("effortLevels", effortLevels)
                .put("permissionModes", permissionModes)
                .put("modelSelection", caps == null ? "none" : caps.optString("modelSelection", "none"));
        JSONObject attachments = caps == null ? null : caps.optJSONObject("attachments");
        if (attachments != null) view.put("attachments", new JSONObject(attachments.toString()));

        String modelOverride = current == null ? null : nonEmpty(current.optString("model", null));
        view.put("explicitModel", modelOverride != null);
        if (modelOverride != null) {
            view.put("model", modelOverride);
            view.put("modelOverride", modelOverride);
        }
        if (current != null) {
            String approvalPolicy = validatedOption(current.optString("approvalPolicy", null), approvalPolicies);
            if (approvalPolicy != null) view.put("approvalPolicy", approvalPolicy);
            String sandbox = validatedOption(current.optString("sandbox", null), sandboxModes);
            if (sandbox != null) view.put("sandbox", sandbox);
            String reviewer = validatedOption(current.optString("approvalsReviewer", null), reviewers);
            if (reviewer != null) view.put("approvalsReviewer", reviewer);
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
        String adapter = nonEmpty(config.optString("defaultAdapter", null));
        if (adapter == null) adapter = firstAdapter(array(accounts, "adapters"));
        if (adapter == null) return;
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

    private static String firstAdapter(JSONArray adapters) {
        for (int i = 0; i < adapters.length(); i++) {
            JSONObject item = adapters.optJSONObject(i);
            String kind = item == null ? null : nonEmpty(item.optString("adapter", item.optString("kind", null)));
            if (kind != null) return kind;
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

    static String validatedOption(String value, JSONArray allowed) {
        String candidate = nonEmpty(value);
        if (candidate == null || allowed == null) return null;
        for (int i = 0; i < allowed.length(); i++) if (candidate.equals(allowed.optString(i))) return candidate;
        return null;
    }
}
