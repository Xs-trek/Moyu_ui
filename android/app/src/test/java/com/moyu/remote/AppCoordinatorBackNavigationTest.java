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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public final class AppCoordinatorBackNavigationTest {
    private MoyuApplication app;
    private AppCoordinator coordinator;
    private TestHost host;

    @Before public void setUp() {
        app = (MoyuApplication) RuntimeEnvironment.getApplication();
        app.database().putSetting("route", "conversation");
        host = new TestHost(app);
        coordinator = new AppCoordinator(app, host);
        coordinator.postIntent("{\"version\":1,\"type\":\"app.ready\",\"requestId\":\"ready-back-test\",\"payload\":{\"uiVersion\":\"test\"}}");
    }

    @After public void tearDown() {
        if (coordinator != null) coordinator.close();
        app.database().putSetting("route", "console");
    }

    @Test public void systemBackLeavesConversationBeforeFinishingApp() throws Exception {
        assertNotNull(host.awaitRoute("conversation"));

        coordinator.onSystemBack();

        assertNotNull(host.awaitRoute("sessions"));
        assertEquals("sessions", app.database().setting("route", "missing"));
        assertFalse(host.finished.await(100, TimeUnit.MILLISECONDS));

        coordinator.onSystemBack();

        assertTrue(host.finished.await(2, TimeUnit.SECONDS));
    }

    @Test public void closedCoordinatorIgnoresLateSystemBack() throws Exception {
        assertNotNull(host.awaitRoute("conversation"));
        coordinator.close();

        coordinator.onSystemBack();

        assertFalse(host.finished.await(100, TimeUnit.MILLISECONDS));
        assertEquals("conversation", app.database().setting("route", "missing"));
        coordinator = null;
    }

    private static final class TestHost implements AppCoordinator.Host {
        private final Context context;
        private final LinkedBlockingQueue<JSONObject> envelopes = new LinkedBlockingQueue<>();
        private final CountDownLatch finished = new CountDownLatch(1);

        TestHost(Context context) { this.context = context; }

        JSONObject awaitRoute(String expected) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (System.nanoTime() < deadline) {
                JSONObject envelope = envelopes.poll(100, TimeUnit.MILLISECONDS);
                if (envelope == null || !"view.full".equals(envelope.optString("type"))) continue;
                JSONObject view = envelope.optJSONObject("view");
                if (view != null && expected.equals(view.optString("route"))) return envelope;
            }
            return null;
        }

        @Override public void dispatch(JSONObject envelope) { envelopes.offer(envelope); }
        @Override public void showManualSetup(MoyuDatabase.NodeRecord existing) {}
        @Override public void pickImage() {}
        @Override public void showMessage(String message) {}
        @Override public void openExternal(String url) {}
        @Override public void finishApp() { finished.countDown(); }
        @Override public Context context() { return context; }
    }
}
