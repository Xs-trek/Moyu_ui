package com.moyu.remote;

import android.content.Context;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public final class MoyuDatabaseApprovalStateTest {
    private Context context;
    private MoyuDatabase db;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        context.deleteDatabase("moyu.db");
        db = new MoyuDatabase(context);
        db.getWritableDatabase();
    }

    @After public void tearDown() {
        if (db != null) db.close();
        context.deleteDatabase("moyu.db");
    }

    @Test public void approvalCanEnterSubmittingOnlyOnceUntilItIsResolved() throws Exception {
        MoyuDatabase.SessionRecord session = new MoyuDatabase.SessionRecord();
        session.localSessionId = "local-approval";
        session.remoteSessionId = "remote-approval";
        session.nodeId = "node";
        session.kind = "claude";
        session.title = "Approval";
        session.state = "running";
        session.updatedAt = "2026-08-20T00:00:00Z";
        session.draft = "";
        db.saveSession(session);

        String itemKey = "approval:remote-approval:approval-1";
        JSONObject approval = new JSONObject().put("approvalId", "approval-1")
                .put("kind", "command").put("summary", "Run command").put("state", "pending");
        db.putTimeline(session.localSessionId, itemKey, 7,
                new JSONObject().put("kind", "approval").put("approval", approval)
                        .put("createdAt", "2026-08-20T00:00:00Z"));

        assertTrue(db.transitionApprovalState(session.localSessionId, itemKey, "pending", "submitting"));
        assertFalse(db.transitionApprovalState(session.localSessionId, itemKey, "pending", "submitting"));
        assertEquals("submitting", db.timelineItem(itemKey).getJSONObject("approval").getString("state"));
        assertTrue(db.transitionApprovalState(session.localSessionId, itemKey, "submitting", "allowed"));
        assertFalse(db.transitionApprovalState(session.localSessionId, itemKey, "pending", "submitting"));
        assertEquals("allowed", db.timelineItem(itemKey).getJSONObject("approval").getString("state"));
    }
}
