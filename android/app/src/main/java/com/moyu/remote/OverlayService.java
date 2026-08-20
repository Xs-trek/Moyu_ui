package com.moyu.remote;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;

import com.easytier.jni.EasyTierJNI;

import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Foreground owner for EasyTier no-tun + smoltcp, implemented as a standard service. */
public final class OverlayService extends Service {
    public static final String ACTION_STATE = "com.moyu.remote.OVERLAY_STATE";
    public static final String EXTRA_STATE = "state";
    public static final String EXTRA_ERROR = "error";
    public static final String EXTRA_PEER_CONNECTED = "peerConnected";
    public static final String EXTRA_LINK_MODE = "linkMode";
    public static final String EXTRA_LINK_OBSERVED_AT = "linkObservedAt";
    private static final String ACTION_START = "com.moyu.remote.START_OVERLAY";
    private static final String ACTION_STOP = "com.moyu.remote.STOP_OVERLAY";
    private static final String EXTRA_NODE = "nodeId";
    private static final int NOTIFICATION_ID = 22;
    private static final String CHANNEL_ID = "moyu-overlay";
    private static volatile PairingConfig pendingPairing;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> networkInfoTask;

    static final class PairingConfig {
        String relay, code;
        int socksPort;
    }

    public static void startNode(Context context, String nodeId) {
        pendingPairing = null;
        Intent intent = new Intent(context, OverlayService.class).setAction(ACTION_START).putExtra(EXTRA_NODE, nodeId);
        context.startForegroundService(intent);
    }

    public static void startPairing(Context context, String relay, String code, int socksPort) {
        PairingConfig config = new PairingConfig(); config.relay = relay; config.code = code; config.socksPort = socksPort;
        pendingPairing = config;
        context.startForegroundService(new Intent(context, OverlayService.class).setAction(ACTION_START));
    }

    public static void stop(Context context) { context.startService(new Intent(context, OverlayService.class).setAction(ACTION_STOP)); }

    @Override public void onCreate() { super.onCreate(); createChannel(); }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, notification("正在启动 overlay"));
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            worker.execute(this::stopNative);
            return START_NOT_STICKY;
        }
        String nodeId = intent == null ? null : intent.getStringExtra(EXTRA_NODE);
        PairingConfig pairing = pendingPairing;
        worker.execute(() -> startNative(nodeId, pairing));
        return START_NOT_STICKY;
    }

    private void startNative(String nodeId, PairingConfig pairing) {
        broadcast("starting", null);
        try {
            cancelNetworkInfo();
            try { EasyTierJNI.stopAllInstances(); } catch (Throwable ignored) { }
            String config;
            String instanceName;
            String backendVip = null;
            if (pairing != null) {
                instanceName = "moyu-pair";
                config = config(instanceName, "rd-pair", pairing.code, "10.144.144.4", pairing.relay, pairing.socksPort);
            }
            else {
                MoyuApplication app = (MoyuApplication) getApplication();
                MoyuDatabase.NodeRecord node = app.database().getNode(nodeId);
                if (node == null) throw new IllegalStateException("节点不存在");
                String secret = app.secrets().get(node.nodeId, "networkSecret");
                if (secret == null) throw new IllegalStateException("节点网络密钥不可用");
                instanceName = "moyu-" + node.nodeId;
                backendVip = node.backendVip;
                config = config(instanceName, node.networkName, secret, node.mobileVip, node.relayNode, node.socksPort);
            }
            int parsed = EasyTierJNI.parseConfig(config);
            if (parsed != 0) throw new IllegalStateException(error("EasyTier 配置无效"));
            int result = EasyTierJNI.runNetworkInstance(config);
            if (result != 0) throw new IllegalStateException(error("EasyTier 启动失败"));
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(NOTIFICATION_ID, notification("Overlay 已启动"));
            broadcast("running", null);
            if (backendVip != null) scheduleNetworkInfo(instanceName, backendVip);
        } catch (Throwable error) {
            broadcast("failed", safeMessage(error));
            pendingPairing = null;
            stopForeground(true);
            stopSelf();
        }
    }

    private void stopNative() {
        cancelNetworkInfo();
        try { EasyTierJNI.stopAllInstances(); } catch (Throwable ignored) { }
        pendingPairing = null;
        broadcast("stopped", null);
        stopForeground(true);
        stopSelf();
    }

    private static String config(String instance, String network, String secret, String vip, String relay, int socksPort) {
        String peer = relay.contains("://") ? relay : "tcp://" + relay;
        return "instance_name = \"" + toml(instance) + "\"\n" +
                "ipv4 = \"" + toml(vip) + "\"\n" +
                "listeners = []\n" +
                "mapped_listeners = []\n" +
                "rpc_portal = \"127.0.0.2:0\"\n" +
                "socks5_proxy = \"socks5://127.0.0.1:" + socksPort + "\"\n\n" +
                "[network_identity]\nnetwork_name = \"" + toml(network) + "\"\nnetwork_secret = \"" + toml(secret) + "\"\n\n" +
                "[[peer]]\nuri = \"" + toml(peer) + "\"\n\n" +
                "[flags]\nno_tun = true\nuse_smoltcp = true\nprivate_mode = true\nlatency_first = true\n" +
                "enable_encryption = true\nencryption_algorithm = \"aes-256-gcm\"\nenable_ipv6 = false\ndev_name = \"no_tun\"\n";
    }

    private static String toml(String value) { return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", "").replace("\n", ""); }
    private static String safeMessage(Throwable error) { String value = error.getMessage(); return value == null ? error.getClass().getSimpleName() : value.substring(0, Math.min(value.length(), 240)); }
    private static String error(String fallback) { try { String value = EasyTierJNI.getLastError(); return value == null || value.isEmpty() ? fallback : value; } catch (Throwable ignored) { return fallback; } }

    private void scheduleNetworkInfo(String instanceName, String backendVip) {
        networkInfoTask = worker.scheduleWithFixedDelay(() -> {
            String observedAt = Instant.now().toString();
            String raw = null;
            try { raw = EasyTierJNI.collectNetworkInfos(4); } catch (Throwable ignored) { }
            EasyTierLinkInfo info = EasyTierLinkInfo.inspect(raw, instanceName, backendVip, observedAt);
            broadcastLinkInfo(info);
            String text = !info.peerConnected ? "Overlay 已启动 · PC 组网节点未确认"
                    : EasyTierLinkInfo.MODE_P2P.equals(info.linkMode) ? "Overlay 已启动 · P2P"
                    : EasyTierLinkInfo.MODE_RELAY.equals(info.linkMode) ? "Overlay 已启动 · Relay"
                    : "Overlay 已启动 · 链路未知";
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(NOTIFICATION_ID, notification(text));
        }, 0, 3, TimeUnit.SECONDS);
    }

    private void cancelNetworkInfo() {
        if (networkInfoTask != null) networkInfoTask.cancel(false);
        networkInfoTask = null;
    }

    private void broadcast(String state, String error) {
        Intent intent = new Intent(ACTION_STATE).setPackage(getPackageName()).putExtra(EXTRA_STATE, state);
        if (error != null) intent.putExtra(EXTRA_ERROR, error);
        sendBroadcast(intent);
    }

    private void broadcastLinkInfo(EasyTierLinkInfo info) {
        Intent intent = new Intent(ACTION_STATE).setPackage(getPackageName())
                .putExtra(EXTRA_PEER_CONNECTED, info.peerConnected)
                .putExtra(EXTRA_LINK_MODE, info.linkMode)
                .putExtra(EXTRA_LINK_OBSERVED_AT, info.observedAt);
        sendBroadcast(intent);
    }

    private void createChannel() {
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, getString(R.string.overlay_channel), NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("维持 EasyTier no-tun 节点连接");
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(channel);
    }

    private Notification notification(String text) {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL_ID).setSmallIcon(R.drawable.ic_notification).setContentTitle("Moyu").setContentText(text)
                .setOngoing(true).setCategory(Notification.CATEGORY_SERVICE).setContentIntent(open).build();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onDestroy() { cancelNetworkInfo(); worker.shutdownNow(); super.onDestroy(); }
}
