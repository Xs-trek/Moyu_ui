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

import java.lang.reflect.Method;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public final class AppCoordinatorStreamProjectionTest {
    private MoyuApplication app;
    private AppCoordinator coordinator;
    private MoyuDatabase.SessionRecord session;
    private Method processEvent;

    @Before public void setUp() throws Exception {
        app = (MoyuApplication) RuntimeEnvironment.getApplication();
        String suffix = UUID.randomUUID().toString();
        MoyuDatabase.NodeRecord node = new MoyuDatabase.NodeRecord();
        node.nodeId = "node-" + suffix;
        node.displayName = "Node";
        node.relayNode = "tcp://127.0.0.1:11010";
        node.networkName = "network";
        node.mobileVip = "10.1.0.2";
        node.backendVip = "10.1.0.1";
        node.gatewayPort = 39871;
        node.socksPort = 0;
        app.database().saveNode(node);
        app.database().putSetting("activeNodeId", node.nodeId);

        session = new MoyuDatabase.SessionRecord();
        session.localSessionId = "local-" + suffix;
        session.remoteSessionId = "remote-" + suffix;
        session.nodeId = node.nodeId;
        session.kind = "claude";
        session.title = "Stream";
        session.state = "idle";
        session.updatedAt = "2026-01-01T00:00:00Z";
        session.draft = "";
        app.database().saveSession(session);

        coordinator = new AppCoordinator(app, new NoOpHost(app));
        processEvent = AppCoordinator.class.getDeclaredMethod("processEventEnvelope", JSONObject.class, boolean.class);
        processEvent.setAccessible(true);
    }

    @After public void tearDown() {
        if (coordinator != null) coordinator.close();
    }

    @Test public void liveRowsKeepFirstTimestampAndDeltasDoNotReorderSession() throws Exception {
        project(1, "turn.started", null);
        String turnStartedAt = app.database().getSession(session.localSessionId).updatedAt;
        assertNotEquals("2026-01-01T00:00:00Z", turnStartedAt);

        project(2, "text.delta", "one");
        JSONObject firstText = app.database().timelineItem("live:text:" + session.localSessionId);
        assertTrue(firstText.optBoolean("streaming"));
        String textCreatedAt = firstText.getString("createdAt");
        Thread.sleep(10);
        project(3, "text.delta", " two");
        JSONObject secondText = app.database().timelineItem("live:text:" + session.localSessionId);
        assertEquals(textCreatedAt, secondText.getString("createdAt"));
        assertEquals("one two", secondText.getString("text"));
        assertEquals(turnStartedAt, app.database().getSession(session.localSessionId).updatedAt);

        project(4, "text.done", "one two");
        JSONObject finalText = app.database().timelineItem("m:" + session.remoteSessionId + ":4");
        assertFalse(finalText.optBoolean("streaming", true));
        assertEquals(textCreatedAt, finalText.getString("createdAt"));

        project(5, "thinking.delta", "think");
        JSONObject firstThinking = app.database().timelineItem("live:thinking:" + session.localSessionId);
        String thinkingCreatedAt = firstThinking.getString("createdAt");
        Thread.sleep(10);
        project(6, "thinking.delta", " more");
        project(7, "thinking.done", null);
        JSONObject finalThinking = app.database().timelineItem("live:thinking:" + session.localSessionId);
        assertEquals(thinkingCreatedAt, finalThinking.getString("createdAt"));
        assertFalse(finalThinking.optBoolean("streaming", true));

        Thread.sleep(10);
        project(8, "turn.completed", null);
        assertNotEquals(turnStartedAt, app.database().getSession(session.localSessionId).updatedAt);
    }

    private void project(int seq, String type, String text) throws Exception {
        JSONObject event = new JSONObject().put("type", type);
        if (text != null) event.put("text", text);
        JSONObject envelope = new JSONObject().put("seq", seq).put("sessionId", session.remoteSessionId).put("event", event);
        processEvent.invoke(coordinator, envelope, false);
    }

    private static final class NoOpHost implements AppCoordinator.Host {
        private final Context context;
        NoOpHost(Context context) { this.context = context; }
        @Override public void dispatch(JSONObject envelope) { }
        @Override public void showManualSetup(MoyuDatabase.NodeRecord existing) { }
        @Override public void pickImage() { }
        @Override public void showMessage(String message) { }
        @Override public void openExternal(String url) { }
        @Override public void finishApp() { }
        @Override public Context context() { return context; }
    }
}
