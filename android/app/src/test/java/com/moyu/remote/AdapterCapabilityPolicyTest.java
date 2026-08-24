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
public final class AdapterCapabilityPolicyTest {
    @Test public void historyOperationsAreIndependentAndUnknownAdaptersFailClosed() throws Exception {
        JSONObject server = server();
        assertTrue(AdapterCapabilityPolicy.supportsHistory(server, "opencode", "list"));
        assertTrue(AdapterCapabilityPolicy.supportsHistory(server, "opencode", "messages"));
        assertFalse(AdapterCapabilityPolicy.supportsHistory(server, "opencode", "resume"));
        assertFalse(AdapterCapabilityPolicy.supportsHistory(server, "future-agent", "list"));
        assertTrue(AdapterCapabilityPolicy.supportsAnyHistory(server, "messages"));
    }

    @Test public void opaqueResumeReferencesAcceptPathsAndRejectUnsafeBounds() throws Exception {
        assertEquals("project/thread:alpha.v2", AdapterCapabilityPolicy.resumeRef(
                new JSONObject().put("resumeRef", "project/thread:alpha.v2")));
        assertEquals("legacy/opaque-ref", AdapterCapabilityPolicy.resumeRef(
                new JSONObject().put("nativeSessionId", "legacy/opaque-ref")));
        assertNull(AdapterCapabilityPolicy.resumeRef(new JSONObject().put("resumeRef", " leading")));
        assertNull(AdapterCapabilityPolicy.resumeRef(new JSONObject().put("resumeRef", "line\nbreak")));
        StringBuilder oversized = new StringBuilder();
        for (int i = 0; i < 2049; i++) oversized.append('x');
        assertNull(AdapterCapabilityPolicy.validResumeRef(oversized.toString()));
    }

    @Test public void createBodyContainsOnlyGenericPublicFields() throws Exception {
        JSONObject payload = new JSONObject().put("kind", "future-agent").put("cwd", " D:/work ")
                .put("title", "Task").put("profileId", "profile-a").put("model", "model-a")
                .put("effort", "deep").put("permissionMode", "review")
                .put("resumeRef", "must-not-cross-create-boundary")
                .put("nativeSessionId", "legacy-must-not-cross").put("extra", "ignored");
        JSONObject body = AdapterCapabilityPolicy.createSessionBody(payload);

        assertEquals("future-agent", body.getString("kind"));
        assertEquals("D:/work", body.getString("cwd"));
        assertEquals("deep", body.getString("effort"));
        assertEquals("review", body.getString("permissionMode"));
        assertFalse(body.has("resumeRef"));
        assertFalse(body.has("nativeSessionId"));
        assertFalse(body.has("extra"));
    }

    @Test public void attachmentsUseBackendCapabilityAndLocalSafetyCeiling() throws Exception {
        assertTrue(AdapterCapabilityPolicy.supportsAttachments(server(), "opencode"));
        assertEquals(4, AdapterCapabilityPolicy.maxAttachments(server(), "opencode"));
        assertEquals(0, AdapterCapabilityPolicy.maxAttachments(server(), "future-agent"));
    }

    private static JSONObject server() throws Exception {
        JSONObject openCodeCaps = new JSONObject()
                .put("history", new JSONObject().put("list", true).put("messages", true).put("resume", false))
                .put("attachments", new JSONObject().put("images", true).put("maxCount", 12));
        JSONObject futureCaps = new JSONObject().put("history",
                new JSONObject().put("list", false).put("messages", false).put("resume", false));
        return new JSONObject().put("adapters", new JSONArray()
                .put(new JSONObject().put("adapter", "opencode").put("available", true).put("capabilities", openCodeCaps))
                .put(new JSONObject().put("adapter", "future-agent").put("available", true).put("capabilities", futureCaps)));
    }
}
