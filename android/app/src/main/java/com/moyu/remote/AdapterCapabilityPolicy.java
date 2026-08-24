package com.moyu.remote;

import org.json.JSONArray;
import org.json.JSONObject;

/** Pure, platform-neutral capability and opaque resume-reference policy. */
final class AdapterCapabilityPolicy {
    private static final int LOCAL_ATTACHMENT_LIMIT = 4;
    private static final int MAX_RESUME_REF_CODE_POINTS = 2048;

    private AdapterCapabilityPolicy() { }

    static JSONObject adapter(JSONObject server, String kind) {
        if (server == null || kind == null) return null;
        JSONArray adapters = server.optJSONArray("adapters");
        if (adapters == null) return null;
        for (int i = 0; i < adapters.length(); i++) {
            JSONObject item = adapters.optJSONObject(i);
            if (item != null && kind.equals(item.optString("adapter"))) return item;
        }
        return null;
    }

    static JSONObject capabilities(JSONObject server, String kind) {
        JSONObject adapter = adapter(server, kind);
        return adapter == null ? null : adapter.optJSONObject("capabilities");
    }

    static boolean isAvailable(JSONObject server, String kind) {
        JSONObject adapter = adapter(server, kind);
        return adapter != null && adapter.optBoolean("available", false);
    }

    static boolean supportsHistory(JSONObject server, String kind, String operation) {
        JSONObject capabilities = capabilities(server, kind);
        if (capabilities == null) return false;
        JSONObject history = capabilities.optJSONObject("history");
        if (history != null) return history.optBoolean(operation, false);
        return capabilities.optBoolean("resume", false);
    }

    static boolean supportsAnyHistory(JSONObject server, String operation) {
        JSONArray adapters = server == null ? null : server.optJSONArray("adapters");
        if (adapters == null) return false;
        for (int i = 0; i < adapters.length(); i++) {
            JSONObject adapter = adapters.optJSONObject(i);
            if (adapter != null && supportsHistory(server, adapter.optString("adapter"), operation)) return true;
        }
        return false;
    }

    static boolean supportsModel(JSONObject server, String kind) {
        JSONObject capabilities = capabilities(server, kind);
        return capabilities != null && capabilities.optBoolean("models", false);
    }

    static boolean supportsProfiles(JSONObject server, String kind) {
        JSONObject capabilities = capabilities(server, kind);
        return capabilities != null && capabilities.optBoolean("profiles", false);
    }

    static boolean supportsBoolean(JSONObject server, String kind, String capability) {
        JSONObject capabilities = capabilities(server, kind);
        return capabilities != null && capabilities.optBoolean(capability, false);
    }

    static boolean supportsValue(JSONObject server, String kind, String capability, String value) {
        if (value == null) return true;
        JSONObject capabilities = capabilities(server, kind);
        JSONArray values = capabilities == null ? null : capabilities.optJSONArray(capability);
        if (values == null) return false;
        for (int i = 0; i < values.length(); i++) if (value.equals(values.optString(i))) return true;
        return false;
    }

    static boolean supportsAttachments(JSONObject server, String kind) {
        JSONObject capabilities = capabilities(server, kind);
        JSONObject attachments = capabilities == null ? null : capabilities.optJSONObject("attachments");
        return attachments != null && attachments.optBoolean("images", false) && maxAttachments(server, kind) > 0;
    }

    static int maxAttachments(JSONObject server, String kind) {
        JSONObject capabilities = capabilities(server, kind);
        JSONObject attachments = capabilities == null ? null : capabilities.optJSONObject("attachments");
        if (attachments == null || !attachments.optBoolean("images", false)) return 0;
        return Math.max(0, Math.min(LOCAL_ATTACHMENT_LIMIT, attachments.optInt("maxCount", 0)));
    }

    /** v0.0.4 name first, followed by one-window wire aliases. Values are opaque strings. */
    static String resumeRef(JSONObject source) {
        if (source == null) return null;
        for (String key : new String[]{"resumeRef", "nativeSessionId", "cliSessionRef"}) {
            if (!source.has(key) || source.isNull(key)) continue;
            return validResumeRef(source.optString(key, null));
        }
        return null;
    }

    /** Resume references are opaque, but bounded and safe to persist or URL-encode. */
    static String validResumeRef(String value) {
        return validOpaqueId(value);
    }

    static String validOpaqueId(String value) {
        if (value == null || value.isEmpty() || !value.equals(value.trim())) return null;
        int codePoints = value.codePointCount(0, value.length());
        if (codePoints < 1 || codePoints > MAX_RESUME_REF_CODE_POINTS) return null;
        for (int offset = 0; offset < value.length(); ) {
            int codePoint = value.codePointAt(offset);
            if (Character.isISOControl(codePoint)) return null;
            offset += Character.charCount(codePoint);
        }
        return value;
    }

    static JSONObject createSessionBody(JSONObject payload) throws Exception {
        JSONObject body = new JSONObject().put("kind", payload.optString("kind"));
        for (String key : new String[]{"cwd", "title", "profileId", "model", "effort", "permissionMode"}) {
            if (!payload.has(key) || payload.isNull(key)) continue;
            String value = payload.optString(key, "").trim();
            if (!value.isEmpty()) body.put(key, value);
        }
        return body;
    }
}
