package com.moyu.remote;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public final class JsonViewDiffTest {
    @Test public void emitsSmallNestedPatchAndEscapesPointerKeys() throws Exception {
        JSONObject before = new JSONObject("{\"activeSession\":{\"messages\":[{\"text\":\"a\"}]},\"a/b\":1}");
        JSONObject after = new JSONObject("{\"activeSession\":{\"messages\":[{\"text\":\"ab\"}]},\"a/b\":2}");
        JSONArray patch = JsonViewDiff.between(before, after, 8);
        assertEquals(2, patch.length());
        assertEquals("/a~1b", patch.getJSONObject(0).getString("path"));
        assertEquals("/activeSession/messages/0/text", patch.getJSONObject(1).getString("path"));
    }

    @Test public void replacesArrayWhenItsShapeChangesAndRejectsOversizeDiff() throws Exception {
        JSONObject before = new JSONObject("{\"items\":[1]}");
        JSONObject after = new JSONObject("{\"items\":[1,2]}");
        assertEquals("/items", JsonViewDiff.between(before, after, 2).getJSONObject(0).getString("path"));
        assertNull(JsonViewDiff.between(new JSONObject("{\"a\":1,\"b\":1}"), new JSONObject("{\"a\":2,\"b\":2}"), 1));
    }
}
