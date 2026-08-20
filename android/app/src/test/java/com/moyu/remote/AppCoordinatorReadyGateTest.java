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

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public final class AppCoordinatorReadyGateTest {
    private MoyuApplication app;
    private AppCoordinator coordinator;
    private TestHost host;

    @Before public void setUp() {
        app = (MoyuApplication) RuntimeEnvironment.getApplication();
        host = new TestHost(app);
        coordinator = new AppCoordinator(app, host);
    }

    @After public void tearDown() {
        if (coordinator != null) coordinator.close();
    }

    @Test public void fullViewWaitsForAValidAppReadyIntent() throws Exception {
        coordinator.postIntent("{\"version\":1,\"type\":\"view.reload\",\"requestId\":\"reload-before-ready\",\"payload\":{}}");
        assertNull(host.awaitFull(250));

        coordinator.postIntent("{\"version\":1,\"type\":\"app.ready\",\"requestId\":\"ready-gate-test\",\"payload\":{\"uiVersion\":\"test\"}}");
        JSONObject full = host.awaitFull(2_000);

        assertNotNull(full);
        assertEquals("view.full", full.getString("type"));
        assertEquals(1, full.getLong("revision"));
    }

    private static final class TestHost implements AppCoordinator.Host {
        private final Context context;
        private final LinkedBlockingQueue<JSONObject> envelopes = new LinkedBlockingQueue<>();

        TestHost(Context context) { this.context = context; }

        JSONObject awaitFull(long timeoutMs) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
            while (System.nanoTime() < deadline) {
                long remaining = Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
                JSONObject envelope = envelopes.poll(remaining, TimeUnit.MILLISECONDS);
                if (envelope == null) return null;
                if ("view.full".equals(envelope.optString("type"))) return envelope;
            }
            return null;
        }

        @Override public void dispatch(JSONObject envelope) { envelopes.offer(envelope); }
        @Override public void showManualSetup(MoyuDatabase.NodeRecord existing) {}
        @Override public void pickImage() {}
        @Override public void showMessage(String message) {}
        @Override public void openExternal(String url) {}
        @Override public void finishApp() {}
        @Override public Context context() { return context; }
    }
}
