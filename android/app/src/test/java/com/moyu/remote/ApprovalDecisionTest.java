package com.moyu.remote;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public final class ApprovalDecisionTest {
    @Test public void forwardsBoundedAskUserQuestionAnswersWithoutStringifying() throws Exception {
        JSONObject answers = new JSONObject().put("single?", "B")
                .put("multi?", new JSONArray().put("X").put("Y"));
        JSONObject structured = new JSONObject().put("allowWithModification", new JSONObject().put("answers", answers));
        Object decision = invoke(new JSONObject().put("decision", structured));
        assertTrue(decision instanceof JSONObject);
        JSONObject actual = ((JSONObject) decision).getJSONObject("allowWithModification").getJSONObject("answers");
        assertEquals("B", actual.getString("single?"));
        assertEquals(2, actual.getJSONArray("multi?").length());
    }

    @Test public void keepsExitPlanAndOrdinaryChoicesAsStrings() throws Exception {
        assertEquals("allow", invoke(new JSONObject().put("decision", "allow")));
        assertEquals("deny", invoke(new JSONObject().put("decision", "deny")));
    }

    @Test public void rejectsArbitraryStructuredApprovalPayload() throws Exception {
        try {
            invoke(new JSONObject().put("decision", new JSONObject().put("allowWithModification", new JSONObject().put("questions", new JSONArray()))));
            fail("Expected invalid structured decision to be rejected");
        } catch (InvocationTargetException expected) {
            assertEquals("UiFailure", expected.getCause().getClass().getSimpleName());
        }
    }

    private static Object invoke(JSONObject payload) throws Exception {
        Method method = AppCoordinator.class.getDeclaredMethod("approvalDecision", JSONObject.class);
        method.setAccessible(true);
        return method.invoke(null, payload);
    }
}
