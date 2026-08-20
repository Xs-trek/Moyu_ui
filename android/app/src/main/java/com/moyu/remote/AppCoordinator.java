package com.moyu.remote;

import android.content.Context;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Native application coordinator. The HTML receives only a presentation view model; this class owns
 * credentials, persistence, overlay lifecycle, REST/WS recovery, acknowledgements and stable errors.
 */
public final class AppCoordinator implements AutoCloseable {
    public interface Host {
        void dispatch(JSONObject envelope);
        void showManualSetup(MoyuDatabase.NodeRecord existing);
        void pickImage();
        void showMessage(String message);
        void openExternal(String url);
        void finishApp();
        Context context();
    }

    private static final int MAX_INTENT_BYTES = 1024 * 1024;
    private static final String PAIR_DRAFT_ID = "__pair_draft__";
    private static final long PAIR_CONNECT_TIMEOUT_MS = 60_000L;
    private static final String LOCAL_TEST_NODE_ID = "__local_test_node__";
    private static final int NATIVE_PREFETCH_LIMIT = 10;
    private static final long VIEW_EMIT_DELAY_MS = 100L;
    private final MoyuDatabase db;
    private final SecretStore secrets;
    private final ArtifactCache artifacts;
    private final Host host;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();
    private final Map<String, Integer> historyLimits = new HashMap<>();
    private final Map<String, LiveStream> textStreams = new HashMap<>();
    private final Map<String, LiveStream> thinkingStreams = new HashMap<>();
    private final Map<String, JSONObject> toolItems = new HashMap<>();
    private final Map<String, JSONObject> diffViews = new HashMap<>();
    private final Map<String, List<JSONObject>> pendingAttachments = new HashMap<>();
    private final ViewEmissionThrottle viewEmitter;
    private volatile boolean closed;

    private boolean uiReady;
    private long revision;
    private String route;
    private String theme;
    private String activeNodeId;
    private String activeSessionId;
    private String pendingPickerSessionId;
    private String connectionState = "offline";
    private String connectionSummary = "本地历史可用；尚未连接节点";
    private String connectionErrorCode;
    private String connectionErrorSummary;
    private String overlayState = "stopped";
    private boolean overlayPeerConnected;
    private String overlayLinkMode = "unknown";
    private String overlayLinkObservedAt;
    private String syncState = "idle";
    private String lastSyncAt;
    private boolean online;
    private long phoneBackendRttMs = -1;
    private long lastPingAt;
    private BackendClient backend;
    private boolean localTestDirect;
    private ScheduledFuture<?> pingTask;
    private int reconnectAttempt;
    private JSONObject serverView, accountsView, configView, netView, transportView;

    private boolean pairing;
    private String pairCode, pairRelay, pairDisplayName;
    private int pairGatewayPort;
    private int pairAttempt;
    private long pairDeadlineMs, pairGeneration;
    private boolean preserveConnectionOnStop;
    private String pairDraftDisplayName, pairDraftRelay, pairDraftString;

    AppCoordinator(MoyuApplication app, Host host) {
        this.db = app.database(); this.secrets = app.secrets(); this.artifacts = app.artifacts(); this.host = host;
        viewEmitter = new ViewEmissionThrottle(worker, VIEW_EMIT_DELAY_MS, this::emitViewNow);
        route = db.setting("route", "console");
        theme = db.setting("theme", "system");
        activeNodeId = emptyToNull(db.setting("activeNodeId", ""));
        activeSessionId = emptyToNull(db.setting("activeSessionId", ""));
        lastSyncAt = emptyToNull(db.setting("lastSyncAt", ""));
        pairDraftDisplayName = valueOrEmpty(secrets.get(PAIR_DRAFT_ID, "displayName"));
        pairDraftRelay = valueOrEmpty(secrets.get(PAIR_DRAFT_ID, "relayNode"));
        pairDraftString = valueOrEmpty(secrets.get(PAIR_DRAFT_ID, "pairString"));
    }

    public void postIntent(String raw) {
        if (raw == null || raw.getBytes(StandardCharsets.UTF_8).length > MAX_INTENT_BYTES) return;
        worker.execute(() -> handleRawIntent(raw));
    }

    /** Serializes Android system-back navigation with all other native route changes. */
    public void onSystemBack() {
        if (closed) return;
        worker.execute(() -> {
            if (closed) return;
            if ("conversation".equals(route)) {
                route = "sessions";
                db.putSetting("route", route);
                emitView();
                return;
            }
            host.finishApp();
        });
    }

    /** Receives a bounded copy from Android's system picker; the WebView never receives content:// access. */
    public void onImagePicked(String name, String mime, byte[] data) {
        worker.execute(() -> {
            try {
                String localId = pendingPickerSessionId;
                pendingPickerSessionId = null;
                requireOnline();
                if (localId == null || db.getSession(localId) == null) throw new UiFailure("session_not_found", "当前会话不存在", false);
                List<JSONObject> pending = pendingAttachments.computeIfAbsent(localId, ignored -> new ArrayList<>());
                if (pending.size() >= 4) throw new UiFailure("artifact_limit", "每次最多选择 4 张图片", false);
                JSONObject ref = backend.uploadArtifact(name, mime, data);
                String artifactId = required(ref, "artifactId");
                String refMime = required(ref, "mime");
                // The PC trust boundary strips EXIF/XMP/text/device metadata before storage.
                // Cache that authoritative sanitized copy rather than retaining the original
                // picker bytes (whose hash intentionally no longer matches the response).
                byte[] sanitized = backend.downloadArtifact(artifactId);
                artifacts.store(artifactId, sanitized, refMime, nullable(ref, "sha256"));
                pending.add(safeArtifact(ref, true));
                host.showMessage("图片已添加");
                emitView();
            } catch (Exception error) {
                host.showMessage(safe(error.getMessage()));
            }
        });
    }

    public void onImagePickCancelled() { worker.execute(() -> pendingPickerSessionId = null); }

    private void removePendingAttachment(String localId, String artifactId) {
        List<JSONObject> pending = pendingAttachments.get(localId);
        if (pending == null) return;
        pending.removeIf(item -> artifactId.equals(item.optString("artifactId")));
        if (pending.isEmpty()) pendingAttachments.remove(localId);
    }

    /** Connect the real Android glue to a backend forwarded onto emulator/device loopback.
     * This entry exists only in explicitly-built debug integration APKs. */
    public void connectLocalTest(String backendHost, int gatewayPort, String token) {
        if (!BuildConfig.DEBUG || !BuildConfig.MOYU_LOCAL_TEST) return;
        worker.execute(() -> {
            try {
                if (backendHost == null || backendHost.trim().isEmpty() || gatewayPort < 1 || gatewayPort > 65535 || token == null || token.isEmpty()) {
                    throw new UiFailure("bad_local_test_config", "本机联调参数无效", false);
                }
                closeBackend();
                localTestDirect = true;
                MoyuDatabase.NodeRecord node = db.getNode(LOCAL_TEST_NODE_ID);
                if (node == null) node = new MoyuDatabase.NodeRecord();
                node.nodeId = LOCAL_TEST_NODE_ID;
                node.displayName = "本机联调后端";
                node.relayNode = "adb://127.0.0.1:" + gatewayPort;
                node.networkName = "local-test";
                node.mobileVip = "127.0.0.1";
                node.backendVip = backendHost.trim();
                node.gatewayPort = gatewayPort;
                node.socksPort = 0;
                db.saveNode(node);
                secrets.put(node.nodeId, "token", token);
                secrets.put(node.nodeId, "networkSecret", "local-test-not-used");
                activeNodeId = node.nodeId;
                db.putSetting("activeNodeId", activeNodeId);
                resetOverlayLinkInfo();
                overlayState = "running";
                connectionState = "backendConnecting";
                connectionSummary = "本机联调：正在连接真实 Moyu 后端";
                emitView();
                connectBackend();
            } catch (Exception error) {
                setConnectionError("bad_local_test_config", safe(error.getMessage()), false);
            }
        });
    }

    private void handleRawIntent(String raw) {
        String requestId = "unknown";
        boolean refreshView = true;
        try {
            JSONObject intent = new JSONObject(raw);
            requestId = intent.optString("requestId", "");
            if (intent.optInt("version", -1) != 1 || requestId.isEmpty()) throw new UiFailure("bad_intent", "Bridge 消息版本或 requestId 无效", false);
            String type = intent.optString("type", "");
            refreshView = !("session.saveDraft".equals(type) || "node.pairDraft.save".equals(type));
            JSONObject payload = intent.optJSONObject("payload"); if (payload == null) payload = new JSONObject();
            JSONObject data = handleIntent(type, payload);
            result(requestId, true, data, null);
        } catch (UiFailure error) {
            result(requestId, false, null, error);
        } catch (BackendClient.BackendException error) {
            result(requestId, false, null, new UiFailure(error.code, safe(error.getMessage()), error.retryable));
        } catch (Exception error) {
            result(requestId, false, null, new UiFailure("internal", safe(error.getMessage()), false));
        }
        if (refreshView) emitView();
    }

    private JSONObject handleIntent(String type, JSONObject p) throws Exception {
        switch (type) {
            case "app.ready":
                required(p, "uiVersion");
                uiReady = true;
                return null;
            case "view.reload": return null;
            case "appearance.set":
                String nextTheme = required(p, "theme");
                if (!nextTheme.matches("system|light|dark")) throw new UiFailure("bad_intent", "未知主题", false);
                theme = nextTheme; db.putSetting("theme", theme); return null;
            case "nav.open":
                route = required(p, "route"); db.putSetting("route", route); return null;
            case "session.open":
                activeSessionId = required(p, "localSessionId");
                MoyuDatabase.SessionRecord opened = db.getSession(activeSessionId);
                if (opened == null) throw new UiFailure("session_not_found", "本地会话不存在", false);
                db.putSetting("activeSessionId", activeSessionId);
                route = "conversation";
                db.putSetting("route", route);
                if (online && activeNodeId != null && activeNodeId.equals(opened.nodeId)) {
                    if (opened.remoteSessionId != null) { if(opened.nativeSessionId!=null&&opened.nativeCachedSeq>0)completeNativePromotion(opened);else syncOne(opened); subscribe(opened); }
                    else if (opened.nativeSessionId != null) syncNativeMessages(opened, true);
                }
                return null;
            case "session.create": return createSession(p);
            case "session.send": sendInput(required(p, "localSessionId"), p.optString("text", "")); return null;
            case "attachment.pick":
                requireOnline();
                String pickSession = required(p, "localSessionId");
                if (!pickSession.equals(activeSessionId) || db.getSession(pickSession) == null) throw new UiFailure("session_not_found", "当前会话不存在", false);
                if (pendingAttachments.getOrDefault(pickSession, new ArrayList<>()).size() >= 4) throw new UiFailure("artifact_limit", "每次最多选择 4 张图片", false);
                pendingPickerSessionId = pickSession;
                host.pickImage(); return null;
            case "attachment.remove": removePendingAttachment(required(p, "localSessionId"), required(p, "artifactId")); return null;
            case "session.effort.set": setSessionEffort(p); return null;
            case "session.model.set": setSessionModel(p); return null;
            case "session.permissionMode.set": setSessionPermissionMode(p); return null;
            case "session.saveDraft": saveDraft(p); return null;
            case "session.interrupt": interrupt(required(p, "localSessionId")); return null;
            case "session.deleteLocal":
                String deleteId = required(p, "localSessionId"); db.deleteSession(deleteId);
                diffViews.remove(deleteId); pendingAttachments.remove(deleteId);
                if (deleteId.equals(activeSessionId)) activeSessionId = null; return null;
            case "session.loadOlder":
                String olderId = required(p, "localSessionId"); historyLimits.put(olderId, historyLimits.getOrDefault(olderId, 200) + 200); return null;
            case "approval.decide": approval(p); return null;
            case "fs.list": return listFiles(p);
            case "node.connect": connectNode(required(p, "nodeId")); return null;
            case "node.disconnect": disconnectNode(required(p, "nodeId")); return null;
            case "node.save": host.showManualSetup(null); return null;
            case "node.delete": deleteNode(required(p, "nodeId")); return null;
            case "node.pairDraft.save": savePairDraft(p); return null;
            case "node.pair": pairNode(p); return null;
            case "node.manualSetup.open": host.showManualSetup(findNodeForEdit(p)); return null;
            case "node.diagnose": diagnose(required(p, "nodeId")); return null;
            case "accounts.activate": activateAccount(p); return null;
            case "config.patch": patchConfig(p); return null;
            case "external.open": openExternal(required(p, "url")); return null;
            case "diff.open": loadDiff(required(p, "localSessionId")); return null;
            default: throw new UiFailure("unknown_intent", "不支持的 UI intent: " + type, false);
        }
    }

    public void saveManualNode(MoyuDatabase.NodeRecord existing, String displayName, String relay, String networkName,
                               String mobileVip, String backendVip, int gatewayPort, int socksPort, String token, String networkSecret) {
        worker.execute(() -> {
            try {
                if (!validIpLike(mobileVip) || !validIpLike(backendVip)) throw new UiFailure("bad_node_config", "Overlay IP 格式无效", false);
                MoyuDatabase.NodeRecord node = existing == null ? new MoyuDatabase.NodeRecord() : existing;
                if (node.nodeId == null) node.nodeId = UUID.randomUUID().toString();
                node.displayName=displayName; node.relayNode=relay; node.networkName=networkName; node.mobileVip=mobileVip; node.backendVip=backendVip;
                node.gatewayPort=gatewayPort; node.socksPort=socksPort; db.saveNode(node);
                secrets.put(node.nodeId, "token", token); secrets.put(node.nodeId, "networkSecret", networkSecret);
                activeNodeId=node.nodeId; db.putSetting("activeNodeId", activeNodeId); resetOverlayLinkInfo();
                connectionSummary = isConfigured(node) ? "节点已保存，可开始连接" : "节点已保存，但仍缺少 token 或 network secret";
            } catch (Exception error) {
                connectionState="error"; connectionErrorCode="bad_node_config"; connectionErrorSummary=safe(error.getMessage());
            }
            emitView();
        });
    }

    public void onOverlayState(String state, String error, Boolean peerConnected, String linkMode, String observedAt) {
        worker.execute(() -> {
            if (closed) return;
            if (observedAt != null && "running".equals(overlayState)) {
                overlayPeerConnected = Boolean.TRUE.equals(peerConnected);
                overlayLinkMode = "p2p".equals(linkMode) || "relay".equals(linkMode) ? linkMode : "unknown";
                overlayLinkObservedAt = observedAt;
            }
            if (state == null) { emitView(); return; }
            overlayState = state;
            if ("starting".equals(state) || "failed".equals(state) || "stopped".equals(state)) resetOverlayLinkInfo();
            if ("running".equals(state)) {
                if (pairing) {
                    long generation = pairGeneration;
                    worker.schedule(() -> completePairing(generation), 250, TimeUnit.MILLISECONDS);
                }
                else worker.schedule(this::connectBackend, 1200, TimeUnit.MILLISECONDS);
            } else if ("failed".equals(state)) {
                if (pairing) { pairing=false; pairGeneration++; clearPairRuntime(); }
                setConnectionError("overlay_failed", error == null ? "EasyTier overlay 启动失败" : error, true);
            } else if ("stopped".equals(state) && !pairing) {
                if (preserveConnectionOnStop) preserveConnectionOnStop=false;
                else { online=false; connectionState="offline"; connectionSummary="节点已断开，本地历史仍可用"; }
            }
            emitView();
        });
    }

    private void connectNode(String nodeId) throws UiFailure {
        MoyuDatabase.NodeRecord node = db.getNode(nodeId);
        if (node == null) throw new UiFailure("node_not_found", "节点不存在", false);
        if (!isConfigured(node)) throw new UiFailure("node_not_configured", "节点缺少 token 或 network secret", false);
        closeBackend(); pairing=false; pairGeneration++; activeNodeId=nodeId; db.putSetting("activeNodeId", nodeId); resetOverlayLinkInfo();
        if (localTestDirect && LOCAL_TEST_NODE_ID.equals(nodeId)) {
            connectionState="backendConnecting"; connectionSummary="本机联调：正在重新连接真实 Moyu 后端"; overlayState="running"; syncState="idle";
            clearConnectionError(); worker.execute(this::connectBackend); return;
        }
        connectionState="overlayStarting"; connectionSummary="正在启动 EasyTier no-tun overlay"; overlayState="starting"; syncState="idle";
        clearConnectionError(); OverlayService.startNode(host.context(), nodeId);
    }

    private void connectBackend() {
        if (closed || pairing || activeNodeId == null) return;
        MoyuDatabase.NodeRecord node = db.getNode(activeNodeId);
        if (node == null) return;
        connectionState="backendConnecting"; connectionSummary=localTestDirect?"本机联调：正在连接真实 Moyu 后端":"Overlay 已启动，正在连接 Moyu 后端"; emitView();
        try {
            String token = secrets.get(node.nodeId, "token");
            closeBackend();
            backend = localTestDirect && LOCAL_TEST_NODE_ID.equals(node.nodeId)
                    ? BackendClient.directForLocalTest(node.backendVip, node.gatewayPort, token)
                    : new BackendClient(node.backendVip, node.gatewayPort, node.socksPort, token);
            long started=SystemClock.elapsedRealtime();
            JSONObject serverRaw=backend.getObject("/server/info");
            phoneBackendRttMs=SystemClock.elapsedRealtime()-started;
            serverView=mapServer(serverRaw);
            accountsView=mapAccounts(backend.getObject("/accounts"), serverView, node.nodeId);
            configView=mapConfig(backend.getObject("/config"), serverRaw);
            netView=backend.getObject("/net/status");
            connectionState="syncing"; connectionSummary="正在合并本地历史与后端事件"; syncState="syncing"; emitView();
            syncAllSessions(node.nodeId);
            node.lastConnectedAt=Instant.now().toString(); db.saveNode(node);
            openWebSocket();
        } catch (Exception error) {
            setConnectionError(error instanceof BackendClient.BackendException ? ((BackendClient.BackendException) error).code : "network_unreachable", safe(error.getMessage()), true);
            scheduleReconnect();
        }
    }

    private void openWebSocket() {
        BackendClient client=backend; if (client==null) return;
        client.openWebSocket(new BackendClient.Events() {
            @Override public void onOpen() { worker.execute(() -> {
                if (backend != client || closed) return;
                online=true; reconnectAttempt=0; connectionState="online"; connectionSummary="节点已连接并完成同步"; syncState="current"; clearConnectionError();
                MoyuDatabase.SessionRecord active=activeSessionId==null?null:db.getSession(activeSessionId);
                if(active!=null&&activeNodeId!=null&&activeNodeId.equals(active.nodeId)&&active.remoteSessionId!=null)subscribe(active);
                startPing(); emitView();
            }); }
            @Override public void onMessage(JSONObject message) { worker.execute(() -> handleWs(message)); }
            @Override public void onClosed(String summary) { worker.execute(() -> {
                if (backend != client || closed) return;
                online=false; connectionState="degraded"; connectionSummary="实时连接中断，本地历史仍可用"; syncState="error"; emitView(); scheduleReconnect();
            }); }
        });
    }

    private void syncAllSessions(String nodeId) throws Exception {
        JSONObject snapshot=backend.getObject("/sessions/snapshot?limit=100");
        JSONArray items=snapshot.optJSONArray("items"); if(items==null) items=new JSONArray();
        boolean complete=true;
        for(int i=0;i<items.length();i++) {
            JSONObject remote=items.optJSONObject(i); if(remote==null) continue;
            MoyuDatabase.SessionRecord local=upsertSessionSummary(nodeId,remote);
            complete=syncOne(local)&&complete;
        }
        try { syncNativeSummaries(nodeId); } catch (Exception ignored) { /* Native history is additive; it must not block live sessions. */ }
        if(complete)markSynced();
    }

    private void syncNativeSummaries(String nodeId) throws Exception {
        int offset = 0, processed = 0, prefetched = 0;
        for (int page = 0; page < NativeHistoryPaging.MAX_PAGES && processed < NativeHistoryPaging.MAX_ITEMS; page++) {
            JSONObject snapshot = backend.getObject("/native-sessions?limit=" + NativeHistoryPaging.PAGE_SIZE + "&offset=" + offset);
            JSONArray items = snapshot.optJSONArray("items");
            if (items == null) items = new JSONArray();
            for (int i = 0; i < items.length() && processed < NativeHistoryPaging.MAX_ITEMS; i++, processed++) {
                JSONObject nativeItem = items.optJSONObject(i);
                if (nativeItem == null) continue;
                String kind = nativeItem.optString("kind");
                String nativeId = nativeItem.optString("nativeSessionId");
                if (!("claude".equals(kind) || "codex".equals(kind)) || !ArtifactCache.isValidId(nativeId)) continue;
                MoyuDatabase.SessionRecord local = db.findNativeSession(nodeId, kind, nativeId);
                if (local == null) {
                    local = new MoyuDatabase.SessionRecord();
                    local.localSessionId = UUID.randomUUID().toString();
                    local.nodeId = nodeId;
                    local.nativeSessionId = nativeId;
                    local.kind = kind;
                    local.draft = "";
                    local.state = "localOnly";
                }
                boolean nativeOnly = local.remoteSessionId == null;
                if (nativeOnly) {
                    local.title = nativeItem.optString("title", local.title == null ? kind + " " + nativeId.substring(0, 8) : local.title);
                    local.cwd = nullable(nativeItem, "cwd");
                    local.model = nullable(nativeItem, "model");
                    local.updatedAt = nativeItem.optString("updatedAt", local.updatedAt == null ? Instant.now().toString() : local.updatedAt);
                    local.state = "localOnly";
                }
                local.nativeMessageCount = Math.max(0, nativeItem.optInt("messageCount", local.nativeMessageCount));
                db.saveSession(local);
                if (prefetched < NATIVE_PREFETCH_LIMIT && local.remoteSessionId == null) {
                    prefetched++;
                    try { syncNativeMessages(local, false); } catch (Exception ignored) { /* Keep the summary and any older cache. */ }
                }
            }
            boolean hasMore = snapshot.optBoolean("hasMore", false);
            if (!hasMore) return;
            offset = NativeHistoryPaging.next(offset, snapshot.optInt("nextOffset", -1), true);
        }
    }

    private void syncNativeMessages(MoyuDatabase.SessionRecord session, boolean allPages) throws Exception {
        if (session.nativeSessionId == null || session.remoteSessionId != null) return;
        if (session.nativeMessageCount < session.nativeCachedSeq) {
            db.deleteTimeline(session.localSessionId);
            session.nativeCachedSeq = 0;
            session.lastSeq = 0;
        }
        int after = session.nativeCachedSeq;
        int maxPages = allPages ? 50 : 1;
        for (int page = 0; page < maxPages; page++) {
            JSONObject response = backend.getObject("/native-sessions/" + session.kind + "/" + path(session.nativeSessionId) + "/messages?after=" + after + "&limit=100");
            JSONArray messages = response.optJSONArray("items");
            if (messages != null) for (int i = 0; i < messages.length(); i++) persistNativeMessage(session, messages.optJSONObject(i));
            int next = response.optInt("nextAfter", after);
            if (next <= after && response.optBoolean("hasMore", false)) break;
            after = next;
            session.nativeCachedSeq = Math.max(session.nativeCachedSeq, after);
            session.nativeMessageCount = Math.max(session.nativeMessageCount, response.optInt("latestSeq", session.nativeMessageCount));
            session.lastSeq = session.nativeCachedSeq;
            db.saveSession(session);
            if (!response.optBoolean("hasMore", false)) break;
        }
    }

    private void persistNativeMessage(MoyuDatabase.SessionRecord session, JSONObject message) throws Exception {
        if (message == null) return;
        int seq = message.optInt("seq");
        if (seq < 1) return;
        String prefix = NativeHistoryKeys.prefix(session.localSessionId, session.kind, session.nativeSessionId) + seq;
        String created = message.optString("createdAt", session.updatedAt == null ? Instant.now().toString() : session.updatedAt);
        String thinking = message.optString("thinking", "");
        if (!thinking.isEmpty()) {
            db.putTimeline(session.localSessionId, prefix + ":thinking", seq,
                    new JSONObject().put("kind", "thinking").put("text", thinking).put("streaming", false).put("createdAt", created));
        }
        if ("tool".equals(message.optString("role"))) {
            JSONObject item = new JSONObject().put("kind", "tool")
                    .put("toolCallId", message.optString("toolCallId", "tool-" + seq))
                    .put("tool", message.optString("tool", "tool"))
                    .put("output", message.optString("toolOutput", ""))
                    .put("state", "done").put("createdAt", created);
            if (message.has("toolInput")) item.put("input", message.opt("toolInput"));
            attachCachedArtifacts(item, message.optJSONArray("artifacts"));
            db.putTimeline(session.localSessionId, prefix + ":tool", seq, item);
        } else if (!message.optString("text", "").isEmpty() || (message.optJSONArray("artifacts") != null && message.optJSONArray("artifacts").length() > 0)) {
            String role = message.optString("role", "system");
            if (!role.matches("user|assistant|system")) role = "system";
            JSONObject item = new JSONObject().put("kind", "message").put("role", role)
                    .put("text", message.optString("text", "")).put("createdAt", created);
            attachCachedArtifacts(item, message.optJSONArray("artifacts"));
            db.putTimeline(session.localSessionId, prefix + ":text", seq, item);
        }
    }

    private MoyuDatabase.SessionRecord upsertSessionSummary(String nodeId, JSONObject remote) {
        String remoteId=remote.optString("sessionId");String kind=remote.optString("kind","claude");String cliRef=nullable(remote,"cliSessionRef");boolean validCliRef=ArtifactCache.isValidId(cliRef);
        MoyuDatabase.SessionRecord local=db.findRemoteSession(nodeId,remoteId);MoyuDatabase.SessionRecord nativeMatch=validCliRef?db.findNativeSession(nodeId,kind,cliRef):null;
        MoyuDatabase.SessionRecord obsolete=null;
        if(local!=null&&nativeMatch!=null&&!local.localSessionId.equals(nativeMatch.localSessionId)){obsolete=local;local=nativeMatch;NativeHistoryIdentity.mergeLiveState(local,obsolete);}
        else if(local==null&&nativeMatch!=null)local=nativeMatch;
        if(local==null){local=new MoyuDatabase.SessionRecord();local.localSessionId=UUID.randomUUID().toString();local.nodeId=nodeId;local.draft="";}
        local.remoteSessionId=remoteId;local.kind=kind;
        if(validCliRef&&NativeHistoryIdentity.shouldBind(kind,remoteId,cliRef,nativeMatch!=null))local.nativeSessionId=cliRef;
        local.title=remote.optString("title",local.title==null?"未命名会话":local.title); local.cwd=nullable(remote,"cwd");
        local.profileId=nullable(remote,"profileId");String requestedModel=nullable(remote,"requestedModel");local.model=requestedModel!=null?requestedModel:nullable(remote,"model");local.effort=nullable(remote,"effort");local.permissionMode=nullable(remote,"permissionMode");local.updatedAt=remote.optString("updatedAt",Instant.now().toString());
        local.state=mapTurnState(remote.optString("turnState","idle"));
        if(obsolete==null)db.saveSession(local);else{db.mergeSessions(local,obsolete);remapMergedSession(obsolete.localSessionId,local.localSessionId);}
        return local;
    }

    private void remapMergedSession(String obsoleteId, String survivorId) {
        if(obsoleteId.equals(activeSessionId)){activeSessionId=survivorId;db.putSetting("activeSessionId",survivorId);}
        if(obsoleteId.equals(pendingPickerSessionId))pendingPickerSessionId=survivorId;
        Integer oldLimit=historyLimits.remove(obsoleteId);if(oldLimit!=null)historyLimits.put(survivorId,Math.max(oldLimit,historyLimits.getOrDefault(survivorId,0)));
        List<JSONObject> oldAttachments=pendingAttachments.remove(obsoleteId);if(oldAttachments!=null&&!oldAttachments.isEmpty())pendingAttachments.computeIfAbsent(survivorId,ignored->new ArrayList<>()).addAll(oldAttachments);
        LiveStream oldText=textStreams.remove(obsoleteId);if(oldText!=null&&!textStreams.containsKey(survivorId))textStreams.put(survivorId,oldText);
        LiveStream oldThinking=thinkingStreams.remove(obsoleteId);if(oldThinking!=null&&!thinkingStreams.containsKey(survivorId))thinkingStreams.put(survivorId,oldThinking);
        JSONObject oldDiff=diffViews.remove(obsoleteId);if(oldDiff!=null&&!diffViews.containsKey(survivorId))diffViews.put(survivorId,oldDiff);
        ArrayList<String> oldToolKeys=new ArrayList<>();for(String key:toolItems.keySet())if(key.startsWith(obsoleteId+":"))oldToolKeys.add(key);
        for(String oldKey:oldToolKeys){JSONObject item=toolItems.remove(oldKey);if(item!=null)toolItems.put(survivorId+oldKey.substring(obsoleteId.length()),item);}
    }

    private boolean syncOne(MoyuDatabase.SessionRecord session) throws Exception { return syncOne(session, null); }

    private boolean syncOne(MoyuDatabase.SessionRecord session, int[] truncatedBeforeSeq) throws Exception {
        db.deleteLegacySyntheticSystemMessages(session.localSessionId);
        int after=session.lastSeq;
        Integer messageAfter=0;
        for(int page=0;page<24;page++) {
            String cursor=messageAfter==null?"":"&messageAfter="+messageAfter;
            JSONObject sync=backend.getObject("/sessions/"+path(session.remoteSessionId)+"/sync?after="+after+cursor+"&limit=256");
            if(truncatedBeforeSeq!=null)truncatedBeforeSeq[0]=Math.max(truncatedBeforeSeq[0],sync.optInt("messagesTruncatedBeforeSeq",0));
            JSONArray messages=sync.optJSONArray("messages"); if(messages!=null) for(int i=0;i<messages.length();i++) persistCanonicalMessage(session,messages.optJSONObject(i));
            JSONArray events=sync.optJSONArray("events"); if(events!=null) for(int i=0;i<events.length();i++) processEventEnvelope(events.optJSONObject(i),false);
            JSONObject summary=sync.optJSONObject("session");if(summary!=null){String requested=nullable(summary,"requestedModel");session.model=requested!=null?requested:nullable(summary,"model");session.effort=nullable(summary,"effort");session.permissionMode=nullable(summary,"permissionMode");session.state=mapTurnState(summary.optString("turnState",session.state));session.updatedAt=summary.optString("updatedAt",session.updatedAt);}
            after=sync.optInt("nextAfterSeq",after);
            messageAfter=sync.optInt("nextMessageAfterSeq",messageAfter==null?after:messageAfter);
            session.lastSeq=Math.max(session.lastSeq,after); db.saveSession(session);
            if(!sync.optBoolean("hasMoreEvents",false)&&!sync.optBoolean("hasMoreMessages",false)) return true;
        }
        return false;
    }

    private void persistCanonicalMessage(MoyuDatabase.SessionRecord session, JSONObject message) throws Exception {
        if(message==null)return;int seq=message.optInt("seq");String created=message.optString("createdAt",Instant.now().toString());String thinking=message.optString("thinking","");
        if(!thinking.isEmpty())db.putTimeline(session.localSessionId,"thinking:"+session.remoteSessionId+":"+seq,seq,new JSONObject().put("kind","thinking").put("text",thinking).put("streaming",false).put("createdAt",created));
        if("tool".equals(message.optString("role"))){JSONObject item=new JSONObject().put("kind","tool").put("toolCallId",message.optString("toolCallId","tool-"+seq)).put("tool",message.optString("tool","tool"));if(message.has("toolInput"))item.put("input",message.opt("toolInput"));item.put("output",message.optString("toolOutput",message.optString("text",""))).put("state","done").put("createdAt",created);attachCachedArtifacts(item,message.optJSONArray("artifacts"));db.putTimeline(session.localSessionId,"tool:"+session.remoteSessionId+":"+message.optString("toolCallId","tool-"+seq),seq,item);return;}
        String text=message.optString("text","");JSONArray refs=message.optJSONArray("artifacts");if(text.isEmpty()&&(refs==null||refs.length()==0))return;
        String role=message.optString("role","system");if(!role.matches("user|assistant|system"))role="system";
        if("system".equals(role)&&(text.startsWith("[approval:")||text.startsWith("[error:")))return;
        JSONObject item=new JSONObject().put("kind","message").put("role",role).put("text",text).put("createdAt",created);attachCachedArtifacts(item,refs);String key="m:"+session.remoteSessionId+":"+seq;
        if("user".equals(role))db.putCanonicalUserTimeline(session.localSessionId,key,seq,item);else db.putTimeline(session.localSessionId,key,seq,item);
    }

    private void handleWs(JSONObject message) {
        try {
            String type=message.optString("type");
            if("event".equals(type)){processEventEnvelope(message,true);}
            else if("pong".equals(type)){long sent=message.optLong("clientTs",0);if(sent>0)phoneBackendRttMs=Math.max(0,SystemClock.elapsedRealtime()-sent);}
            else if("net_change".equals(type)){JSONObject snap=message.optJSONObject("snapshot");if(snap!=null)netView=snap.optJSONObject("net");emitView();}
            else if("error".equals(type)){connectionErrorCode=message.optString("code","ws_error");connectionErrorSummary=message.optString("summary",connectionErrorCode);emitView();}
            else if("ack".equals(type)&&"subscribed".equals(message.optString("ackType"))){JSONObject replay=message.optJSONObject("replay");if(replay!=null&&replay.optBoolean("gap")){MoyuDatabase.SessionRecord s=findByRemote(message.optString("sessionId"));if(s!=null&&syncOne(s))markSynced();}}
        } catch(Exception error){connectionErrorCode="event_parse_error";connectionErrorSummary="收到无法投影的后端事件";emitView();}
    }

    private void processEventEnvelope(JSONObject envelope, boolean acknowledge) throws Exception {
        if(envelope==null)return; int seq=envelope.optInt("seq"); String remoteId=envelope.optString("sessionId"); MoyuDatabase.SessionRecord session=findByRemote(remoteId);if(session==null)return;
        if(seq>0&&seq<=session.lastSeq){if(acknowledge)ack(seq);return;}
        JSONObject event=envelope.optJSONObject("event");if(event==null)return; String type=event.optString("type");String now=Instant.now().toString();
        if("turn.started".equals(type)){session.state="running";textStreams.remove(session.localSessionId);thinkingStreams.remove(session.localSessionId);}
        else if("thinking.delta".equals(type)){LiveStream stream=thinkingStreams.computeIfAbsent(session.localSessionId,k->new LiveStream(now));stream.text.append(event.optString("text"));
            db.putTimeline(session.localSessionId,"live:thinking:"+session.localSessionId,seq,new JSONObject().put("kind","thinking").put("text",stream.text.toString()).put("streaming",true).put("createdAt",stream.createdAt));}
        else if("thinking.done".equals(type)){LiveStream stream=thinkingStreams.remove(session.localSessionId);if(stream!=null)db.putTimeline(session.localSessionId,"live:thinking:"+session.localSessionId,seq,new JSONObject().put("kind","thinking").put("text",stream.text.toString()).put("streaming",false).put("createdAt",stream.createdAt));}
        else if("text.delta".equals(type)){LiveStream stream=textStreams.computeIfAbsent(session.localSessionId,k->new LiveStream(now));stream.text.append(event.optString("text"));
            db.putTimeline(session.localSessionId,"live:text:"+session.localSessionId,seq,new JSONObject().put("kind","message").put("role","assistant").put("text",stream.text.toString()).put("streaming",true).put("createdAt",stream.createdAt));}
        else if("text.done".equals(type)){LiveStream stream=textStreams.remove(session.localSessionId);String text=event.optString("text",stream==null?"":stream.text.toString());String createdAt=stream==null?now:stream.createdAt;
            db.deleteTimelineItem("live:text:"+session.localSessionId);db.putTimeline(session.localSessionId,"m:"+remoteId+":"+seq,seq,new JSONObject().put("kind","message").put("role","assistant").put("text",text).put("streaming",false).put("createdAt",createdAt));}
        else if("tool.start".equals(type)){String id=event.optString("toolCallId","tool-"+seq);JSONObject item=new JSONObject().put("kind","tool").put("toolCallId",id).put("tool",event.optString("tool","tool")).put("state","running").put("createdAt",now);if(event.has("input"))item.put("input",event.opt("input"));toolItems.put(session.localSessionId+":"+id,item);db.putTimeline(session.localSessionId,"tool:"+remoteId+":"+id,seq,item);}
        else if("tool.output".equals(type)){String id=event.optString("toolCallId");String key="tool:"+remoteId+":"+id;JSONObject item=toolItems.get(session.localSessionId+":"+id);if(item==null)item=db.timelineItem(key);if(item==null)item=new JSONObject().put("kind","tool").put("toolCallId",id).put("tool","tool").put("state","running").put("createdAt",now);item.put("output",item.optString("output")+event.optString("text"));JSONObject artifact=event.optJSONObject("artifact");if(artifact!=null){JSONArray refs=item.optJSONArray("artifacts");if(refs==null)refs=new JSONArray();refs.put(artifact);attachCachedArtifacts(item,refs);}toolItems.put(session.localSessionId+":"+id,item);db.putTimeline(session.localSessionId,key,seq,item);}
        else if("tool.done".equals(type)){String id=event.optString("toolCallId");String key="tool:"+remoteId+":"+id;JSONObject item=toolItems.remove(session.localSessionId+":"+id);if(item==null)item=db.timelineItem(key);if(item==null)item=new JSONObject().put("kind","tool").put("toolCallId",id).put("tool","tool").put("createdAt",now);item.put("state",event.optBoolean("isError")?"error":"done");db.putTimeline(session.localSessionId,key,seq,item);}
        else if("approval.request".equals(type)){db.deleteTimelineItem("m:"+remoteId+":"+seq);JSONObject approval=new JSONObject().put("approvalId",event.optString("approvalId")).put("kind",event.optString("kind","permission")).put("summary",event.optString("summary","需要审批")).put("choices",event.optJSONArray("choices")==null?new JSONArray():event.optJSONArray("choices")).put("state","pending");if(event.has("tool"))approval.put("tool",event.opt("tool"));if(event.has("input"))approval.put("input",event.opt("input"));db.putTimeline(session.localSessionId,"approval:"+remoteId+":"+event.optString("approvalId"),seq,new JSONObject().put("kind","approval").put("approval",approval).put("createdAt",now));}
        else if("approval.resolved".equals(type)){String id=event.optString("approvalId");String key="approval:"+remoteId+":"+id;JSONObject existing=db.timelineItem(key);JSONObject approval=existing==null?null:existing.optJSONObject("approval");if(approval==null)approval=new JSONObject().put("approvalId",id).put("kind","permission").put("summary","审批已处理");String decision=event.optString("decision");approval.put("choices",new JSONArray()).put("state",("deny".equals(decision)||"cancel".equals(decision))?"denied":"allowed");db.putTimeline(session.localSessionId,key,seq,new JSONObject().put("kind","approval").put("approval",approval).put("createdAt",existing==null?now:existing.optString("createdAt",now)));}
        else if("turn.completed".equals(type)){session.state="completed";String runtimeModel=event.optString("model","").trim();String runtimeEffort=event.optString("effort","").trim();if(!runtimeEffort.isEmpty())session.effort=runtimeEffort;JSONObject meta=new JSONObject().put("kind","usage").put("usage",event.optJSONObject("usage")==null?new JSONObject():event.optJSONObject("usage")).put("createdAt",now);if(!runtimeModel.isEmpty())meta.put("model",runtimeModel);if(!runtimeEffort.isEmpty())meta.put("effort",runtimeEffort);JSONObject performance=event.optJSONObject("performance");if(performance!=null){long observedDurationMs=performance.optLong("observedDurationMs",-1);if(observedDurationMs>=0)meta.put("performance",new JSONObject().put("observedDurationMs",observedDurationMs));}db.putTimeline(session.localSessionId,"usage:"+remoteId+":"+seq,seq,meta);}
        else if("turn.failed".equals(type)){session.state="failed";String fallbackMode=event.optString("permissionMode","");if("plan".equals(fallbackMode)||"auto".equals(fallbackMode)||"acceptEdits".equals(fallbackMode))session.permissionMode=fallbackMode;db.deleteTimelineItem("m:"+remoteId+":"+seq);db.putTimeline(session.localSessionId,"error:"+remoteId+":"+seq,seq,new JSONObject().put("kind","error").put("error",uiError(event.optString("category","unknown"),event.optString("summary","CLI 执行失败"),false)).put("createdAt",now));}
        else if("transport.metrics".equals(type)){transportView=event.optJSONObject("metrics");}
        session.lastSeq=Math.max(session.lastSeq,seq);if(ViewEmissionPolicy.updatesSessionTimestamp(type))session.updatedAt=now;db.saveSession(session);
        if(acknowledge){ack(seq);if(ViewEmissionPolicy.shouldThrottle(type))viewEmitter.request();else emitView();}
    }

    private void ack(int seq){try{if(backend!=null)backend.send(new JSONObject().put("type","ack").put("seq",seq));}catch(Exception ignored){}}
    private void subscribe(MoyuDatabase.SessionRecord s){try{backend.send(new JSONObject().put("type","subscribe").put("sessionId",s.remoteSessionId).put("afterSeq",s.lastSeq));}catch(Exception ignored){}}

    private JSONObject createSession(JSONObject p) throws Exception {
        requireOnline(); String nodeId=required(p,"nodeId");if(!nodeId.equals(activeNodeId))throw new UiFailure("wrong_node","请先连接所选节点",true);
        String kind=required(p,"kind");JSONObject body=new JSONObject().put("kind",kind);copyNonEmpty(p,body,"cwd");copyNonEmpty(p,body,"title");copyNonEmpty(p,body,"profileId");copyNonEmpty(p,body,"model");copyNonEmpty(p,body,"effort");
        if("claude".equals(kind))body.put("permissionMode",p.optString("permissionMode","acceptEdits"));
        JSONObject created=backend.post("/sessions",body);JSONObject summary=created.optJSONObject("session");if(summary==null)summary=created;
        if(!summary.has("sessionId")&&created.has("sessionId"))summary.put("sessionId",created.opt("sessionId"));
        MoyuDatabase.SessionRecord session=upsertSessionSummary(nodeId,summary);activeSessionId=session.localSessionId;db.putSetting("activeSessionId",activeSessionId);route="conversation";db.putSetting("route",route);subscribe(session);
        return new JSONObject().put("localSessionId",session.localSessionId).put("remoteSessionId",session.remoteSessionId);
    }

    private void sendInput(String localId,String text)throws Exception{
        requireOnline();
        if(text.length()>100000)throw new UiFailure("input_too_large","输入内容过长",false);
        MoyuDatabase.SessionRecord s=db.getSession(localId);
        if(s==null)throw new UiFailure("session_not_found","会话不存在",false);
        if("running".equals(s.state))throw new UiFailure("session_busy","当前回合仍在运行，请先等待完成或中断",false);
        if(activeNodeId==null||!activeNodeId.equals(s.nodeId))throw new UiFailure("wrong_node","请先连接该会话所属节点",true);
        List<JSONObject> pending=pendingAttachments.getOrDefault(localId,new ArrayList<>());
        if(text.isEmpty()&&pending.isEmpty())throw new UiFailure("bad_intent","请输入消息或选择图片",false);
        if(s.remoteSessionId==null){if(s.nativeSessionId==null)throw new UiFailure("session_not_found","远端会话不存在或已结束",false);resumeNativeSession(s);}
        else if(s.nativeSessionId!=null&&s.nativeCachedSeq>0)completeNativePromotion(s);
        JSONArray ids=new JSONArray();JSONArray localArtifacts=new JSONArray();
        for(JSONObject ref:pending){ids.put(ref.optString("artifactId"));localArtifacts.put(ref);}
        JSONObject accepted=backend.post("/sessions/"+path(s.remoteSessionId)+"/input",new JSONObject().put("text",text).put("attachments",ids));
        int seq=accepted.optInt("seq",0);JSONObject item=new JSONObject().put("kind","message").put("role","user").put("text",text).put("createdAt",Instant.now().toString());if(localArtifacts.length()>0)item.put("artifacts",localArtifacts);
        if(seq>0)db.putCanonicalUserTimeline(localId,"m:"+s.remoteSessionId+":"+seq,seq,item);else db.putTimeline(localId,"local:user:"+UUID.randomUUID(),null,item);
        pendingAttachments.remove(localId);db.setDraft(localId,"");db.setSessionState(localId,"running",s.lastSeq);
    }

    private void resumeNativeSession(MoyuDatabase.SessionRecord session)throws Exception{
        if(session.nativeCachedSeq<session.nativeMessageCount)syncNativeMessages(session,true);
        JSONObject response=backend.post("/native-sessions/"+session.kind+"/"+path(session.nativeSessionId)+"/resume",new JSONObject());
        JSONObject summary=response.optJSONObject("session");if(summary==null)summary=response;
        String remoteId=response.optString("sessionId",summary.optString("sessionId"));
        if(remoteId.isEmpty())throw new UiFailure("bad_response","后端未返回恢复后的会话标识",false);
        session.remoteSessionId=remoteId;session.profileId=nullable(summary,"profileId");
        String resumedModel=nullable(summary,"model");if(resumedModel!=null)session.model=resumedModel;
        session.effort=nullable(summary,"effort");session.permissionMode=nullable(summary,"permissionMode");session.state=mapTurnState(summary.optString("turnState","idle"));
        session.lastSeq=0;db.saveSession(session);
        completeNativePromotion(session);subscribe(session);
    }

    private void completeNativePromotion(MoyuDatabase.SessionRecord session)throws Exception{
        session.lastSeq=0;db.saveSession(session);
        int[] truncatedBeforeSeq=new int[]{0};
        if(!syncOne(session,truncatedBeforeSeq))throw new UiFailure("history_sync_incomplete","原生历史尚未完整同步，请重试",true);
        String prefix=NativeHistoryKeys.prefix(session.localSessionId,session.kind,session.nativeSessionId);
        if(NativeHistoryRetention.backendContainsFullHistory(truncatedBeforeSeq[0]))db.deleteTimelinePrefix(session.localSessionId,prefix);
        else db.deleteTimelinePrefixAfterRemoteSeq(session.localSessionId,prefix,truncatedBeforeSeq[0]);
        session.nativeCachedSeq=0;db.saveSession(session);
    }
    private void saveDraft(JSONObject p)throws Exception{String id=nullable(p,"localSessionId");if(id!=null)db.setDraft(id,p.optString("text",""));}
    private void setSessionEffort(JSONObject p)throws Exception{requireOnline();String localId=required(p,"localSessionId");MoyuDatabase.SessionRecord s=db.getSession(localId);if(s==null)throw new UiFailure("session_not_found","会话不存在",false);if(activeNodeId==null||!activeNodeId.equals(s.nodeId))throw new UiFailure("wrong_node","请先连接该会话所属节点",true);if(s.remoteSessionId==null&&s.nativeSessionId!=null)resumeNativeSession(s);if(s.remoteSessionId==null)throw new UiFailure("session_not_found","远端会话不存在或已结束",false);String effort=nullable(p,"effort");JSONObject body=new JSONObject().put("effort",effort==null?JSONObject.NULL:effort);JSONObject response=backend.post("/sessions/"+path(s.remoteSessionId)+"/effort",body);JSONObject summary=response.optJSONObject("session");if(summary!=null){s.effort=nullable(summary,"effort");s.updatedAt=summary.optString("updatedAt",Instant.now().toString());db.saveSession(s);}}
    private void setSessionModel(JSONObject p)throws Exception{requireOnline();String localId=required(p,"localSessionId");MoyuDatabase.SessionRecord s=sessionForConfiguration(localId);String model=nullable(p,"model");JSONObject response=backend.post("/sessions/"+path(s.remoteSessionId)+"/model",new JSONObject().put("model",model==null?JSONObject.NULL:model));JSONObject summary=response.optJSONObject("session");if(summary!=null){s.model=nullable(summary,"model");s.updatedAt=summary.optString("updatedAt",Instant.now().toString());db.saveSession(s);}}
    private void setSessionPermissionMode(JSONObject p)throws Exception{requireOnline();String localId=required(p,"localSessionId");MoyuDatabase.SessionRecord s=sessionForConfiguration(localId);String mode=required(p,"permissionMode");if(!mode.matches("plan|auto|acceptEdits"))throw new UiFailure("bad_intent","未知会话模式",false);JSONObject response=backend.post("/sessions/"+path(s.remoteSessionId)+"/permission-mode",new JSONObject().put("permissionMode",mode));JSONObject summary=response.optJSONObject("session");if(summary!=null){s.permissionMode=nullable(summary,"permissionMode");s.updatedAt=summary.optString("updatedAt",Instant.now().toString());db.saveSession(s);}}
    private MoyuDatabase.SessionRecord sessionForConfiguration(String localId)throws Exception{MoyuDatabase.SessionRecord s=db.getSession(localId);if(s==null)throw new UiFailure("session_not_found","会话不存在",false);if("running".equals(s.state))throw new UiFailure("session_busy","当前回合运行中，完成或中断后再切换",false);if(activeNodeId==null||!activeNodeId.equals(s.nodeId))throw new UiFailure("wrong_node","请先连接该会话所属节点",true);if(s.remoteSessionId==null&&s.nativeSessionId!=null)resumeNativeSession(s);if(s.remoteSessionId==null)throw new UiFailure("session_not_found","远端会话不存在或已结束",false);return s;}
    private void interrupt(String localId)throws Exception{requireOnline();MoyuDatabase.SessionRecord s=sessionRemote(localId);backend.post("/sessions/"+path(s.remoteSessionId)+"/interrupt",new JSONObject());}
    private void approval(JSONObject p)throws Exception{
        requireOnline();
        MoyuDatabase.SessionRecord s=sessionRemote(required(p,"localSessionId"));
        String approvalId=required(p,"approvalId");
        Object decision=approvalDecision(p);
        String itemKey="approval:"+s.remoteSessionId+":"+approvalId;
        if(!db.transitionApprovalState(s.localSessionId,itemKey,"pending","submitting"))throw new UiFailure("approval_not_pending","审批已不再等待",false);
        JSONObject msg=new JSONObject().put("type","approval").put("sessionId",s.remoteSessionId).put("approvalId",approvalId).put("decision",decision);
        boolean sent;
        try{sent=backend.send(msg);}catch(Exception error){
            db.transitionApprovalState(s.localSessionId,itemKey,"submitting","pending");
            throw error;
        }
        if(!sent){
            db.transitionApprovalState(s.localSessionId,itemKey,"submitting","pending");
            throw new UiFailure("network_unreachable","审批未发送，请刷新会话确认状态",true);
        }
    }

    private JSONObject listFiles(JSONObject p)throws Exception{requireOnline();requireActiveNode(p);JSONArray files=backend.getArray("/fs/list?path="+query(p.optString("path",".")));JSONArray nodes=new JSONArray();for(int i=0;i<files.length();i++){JSONObject f=files.optJSONObject(i);if(f==null)continue;nodes.put(new JSONObject().put("nodeId",activeNodeId).put("name",f.optString("name")).put("path",f.optString("path")).put("kind",f.optBoolean("isDir")?"directory":"file"));}return new JSONObject().put("fileNodes",nodes);}
    private void activateAccount(JSONObject p)throws Exception{requireOnline();requireActiveNode(p);JSONObject rawAccounts=backend.post("/accounts/activate",new JSONObject().put("adapter",required(p,"adapter")).put("profileId",required(p,"profileId")));MoyuDatabase.NodeRecord n=db.getNode(activeNodeId);accountsView=mapAccounts(rawAccounts,serverView,n.nodeId);ControlViewMapper.applyActiveProfileModels(configView,accountsView);try{syncAllSessions(activeNodeId);}catch(Exception ignored){/* Account switch succeeded; history discovery remains additive. */}}

    private void patchConfig(JSONObject p)throws Exception{requireOnline();requireActiveNode(p);JSONObject patch=p.optJSONObject("patch");if(patch==null)throw new UiFailure("bad_intent","缺少配置 patch",false);JSONObject remote=new JSONObject();String adapter=configView==null?"claude":configView.optString("defaultAdapter","claude");
        if(patch.has("defaultAdapter")){remote.put("defaultAdapter",patch.optString("defaultAdapter"));adapter=patch.optString("defaultAdapter");}
        JSONObject adapterPatch=new JSONObject();if(patch.has("model"))adapterPatch.put("model",patch.optString("model"));if(patch.has("sandbox"))adapterPatch.put("sandbox",patch.optString("sandbox"));if(patch.has("approvalsReviewer"))adapterPatch.put("approvalsReviewer",patch.optString("approvalsReviewer"));
        if(patch.has("approvalPolicy")){String v=ControlViewMapper.validatedApprovalPolicy(patch.optString("approvalPolicy"));if(v==null)throw new UiFailure("bad_intent","Approval Policy 无效",false);adapterPatch.put("approvalPolicy",v);}
        if(adapterPatch.length()>0)remote.put("adapters",new JSONObject().put(adapter,adapterPatch));JSONObject raw=backend.patch("/config",remote);configView=mapConfig(raw,serverView==null?new JSONObject():serverView);}
    private void loadDiff(String localId)throws Exception{requireOnline();MoyuDatabase.SessionRecord s=sessionRemote(localId);if(!s.nodeId.equals(activeNodeId))throw new UiFailure("wrong_node","请先连接该会话所属节点",true);diffViews.put(localId,mapDiff(backend.getObject("/sessions/"+path(s.remoteSessionId)+"/diff")));}

    private void diagnose(String nodeId)throws Exception{if(!nodeId.equals(activeNodeId)||backend==null)throw new UiFailure("node_offline","请先连接该节点再诊断",true);long t=SystemClock.elapsedRealtime();JSONObject status=backend.getObject("/net/status");phoneBackendRttMs=SystemClock.elapsedRealtime()-t;netView=status;MoyuDatabase.SessionRecord active=activeSessionId==null?null:db.getSession(activeSessionId);String suffix=active==null||!nodeId.equals(active.nodeId)||active.remoteSessionId==null?"":"?sessionId="+query(active.remoteSessionId);transportView=backend.getObject("/transport/metrics"+suffix);route="diagnostics";db.putSetting("route",route);}

    private void savePairDraft(JSONObject p) {
        String displayName=p.optString("displayName", "");
        String relay=p.optString("relayNode", "");
        String pairString=p.optString("pairString", "");
        if(!displayName.equals(pairDraftDisplayName)){pairDraftDisplayName=displayName;savePairDraftValue("displayName",displayName);}
        if(!relay.equals(pairDraftRelay)){pairDraftRelay=relay;savePairDraftValue("relayNode",relay);}
        if(!pairString.equals(pairDraftString)){pairDraftString=pairString;savePairDraftValue("pairString",pairString);}
    }

    private void savePairDraftValue(String name, String value) {
        if (value == null || value.isEmpty()) secrets.remove(PAIR_DRAFT_ID, name);
        else secrets.put(PAIR_DRAFT_ID, name, value);
    }

    private JSONObject pairDraftJson() throws Exception {
        return new JSONObject()
                .put("displayName", pairDraftDisplayName)
                .put("relayNode", pairDraftRelay)
                .put("pairString", pairDraftString);
    }

    private void clearPairDraft() {
        for (String name : new String[]{"displayName", "relayNode", "pairString"}) secrets.remove(PAIR_DRAFT_ID, name);
        pairDraftDisplayName=""; pairDraftRelay=""; pairDraftString="";
    }

    private void clearPairRuntime(){pairCode=null;pairRelay=null;pairDisplayName=null;pairGatewayPort=0;pairDeadlineMs=0;pairAttempt=0;}

    private void pairNode(JSONObject p)throws Exception{
        savePairDraft(p);
        String pairString=required(p,"pairString").trim().toUpperCase(Locale.ROOT);
        int split=pairString.lastIndexOf(':');
        if(split<1)throw new UiFailure("bad_pair_code","配对串格式应为 CODE:端口",false);
        String code=pairString.substring(0,split);
        if(!code.matches("[0-9A-HJKMNP-TV-Z]{8}"))throw new UiFailure("bad_pair_code","配对码必须是 8 位 Crockford Base32",false);
        int port;
        try{port=Integer.parseInt(pairString.substring(split+1));}catch(Exception e){throw new UiFailure("bad_pair_code","配对端口无效",false);}
        if(port<1||port>65535)throw new UiFailure("bad_pair_code","配对端口无效",false);
        closeBackend(); pairing=true; pairGeneration++; pairAttempt=0;
        pairDeadlineMs=SystemClock.elapsedRealtime()+PAIR_CONNECT_TIMEOUT_MS;
        pairCode=code; pairRelay=required(p,"relayNode"); pairDisplayName=required(p,"displayName"); pairGatewayPort=port;
        connectionState="overlayStarting"; connectionSummary="正在加入一次性配对 overlay"; overlayState="starting"; resetOverlayLinkInfo();
        clearConnectionError(); OverlayService.startPairing(host.context(),pairRelay,pairCode,1080);
    }

    private void completePairing(long generation){
        if(!pairing||closed||generation!=pairGeneration)return;
        connectionState="backendConnecting";
        connectionSummary=pairAttempt==0?"正在交换一次性配对凭据":"配对网络仍在建立，正在自动重试";
        emitView();
        BackendClient pairClient=null;
        try{
            pairClient=new BackendClient("10.1.1.11",pairGatewayPort,1080,"");
            JSONObject handoff=pairClient.postPair(new JSONObject().put("code",pairCode));
            MoyuDatabase.NodeRecord node=new MoyuDatabase.NodeRecord();
            node.nodeId=UUID.randomUUID().toString(); node.displayName=pairDisplayName;
            node.relayNode=handoff.optString("publicNode",pairRelay); node.networkName=required(handoff,"networkName");
            node.mobileVip=handoff.optString("mobileVip","10.144.144.3"); node.backendVip=handoff.optString("backendVip","10.1.1.10");
            node.gatewayPort=handoff.optInt("gatewayPort",pairGatewayPort); node.socksPort=1080;
            db.saveNode(node);
            secrets.put(node.nodeId,"token",required(handoff,"token"));
            secrets.put(node.nodeId,"networkSecret",required(handoff,"networkSecret"));
            activeNodeId=node.nodeId; db.putSetting("activeNodeId",activeNodeId);
            pairing=false; pairGeneration++; clearPairDraft(); clearPairRuntime();
            connectionState="overlayStarting"; connectionSummary="配对完成，正在切换到正式网络"; overlayState="starting"; resetOverlayLinkInfo();
            clearConnectionError();
            OverlayService.startNode(host.context(),node.nodeId);
        }catch(Exception error){
            boolean retryable=error instanceof BackendClient.BackendException&&((BackendClient.BackendException)error).retryable;
            if(retryable&&SystemClock.elapsedRealtime()<pairDeadlineMs&&pairing&&generation==pairGeneration){
                long delay=Math.min(3000L,400L*(1L<<Math.min(pairAttempt++,3)));
                connectionState="backendConnecting"; connectionSummary="配对网络仍在建立，"+delay+"ms 后重试";
                worker.schedule(()->completePairing(generation),delay,TimeUnit.MILLISECONDS);
            }else failPairing(error);
        }finally{if(pairClient!=null)pairClient.close();}
        emitView();
    }

    private void failPairing(Exception error){
        pairing=false; pairGeneration++; preserveConnectionOnStop=true; clearPairRuntime();
        OverlayService.stop(host.context());
        String code=error instanceof BackendClient.BackendException?((BackendClient.BackendException)error).code:"pair_failed";
        setConnectionError(code,safe(error.getMessage()),error instanceof BackendClient.BackendException&&((BackendClient.BackendException)error).retryable);
    }

    private void disconnectNode(String nodeId)throws UiFailure{if(activeNodeId==null||!activeNodeId.equals(nodeId))throw new UiFailure("wrong_node","只能断开当前连接节点",false);disconnectActiveNode();}
    private void disconnectActiveNode(){pairing=false;pairGeneration++;clearPairRuntime();preserveConnectionOnStop=false;closeBackend();if(!localTestDirect)OverlayService.stop(host.context());online=false;connectionState="offline";connectionSummary="节点已断开，本地历史仍可用";overlayState="stopped";syncState="idle";resetOverlayLinkInfo();}
    private void deleteNode(String nodeId){if(nodeId.equals(activeNodeId))disconnectActiveNode();db.deleteNode(nodeId);secrets.deleteNode(nodeId);if(nodeId.equals(activeNodeId)){activeNodeId=null;db.putSetting("activeNodeId","");}}

    private MoyuDatabase.NodeRecord findNodeForEdit(JSONObject p)throws UiFailure{String nodeId=p.optString("nodeId","").trim();if(nodeId.isEmpty()){if(p.has("displayName")||p.has("relayNode"))throw new UiFailure("bad_intent","编辑节点必须提供 nodeId",false);return null;}MoyuDatabase.NodeRecord node=db.getNode(nodeId);if(node==null)throw new UiFailure("node_not_found","节点不存在",false);return node;}
    private void openExternal(String url)throws Exception{String allowed=ExternalLinkPolicy.allowedUrl(url);if(allowed==null)throw new UiFailure("bad_url","该链接不在 Moyu 允许的 HTTPS 域名列表中",false);host.openExternal(allowed);}

    private JSONObject mapServer(JSONObject raw)throws Exception{return ControlViewMapper.mapServer(raw);}

    private JSONObject mapAccounts(JSONObject raw,JSONObject server,String nodeId)throws Exception{return ControlViewMapper.mapAccounts(raw,server,nodeId);}

    private JSONObject mapConfig(JSONObject raw,JSONObject serverRaw)throws Exception{return ControlViewMapper.mapConfig(raw,serverView==null?mapServer(serverRaw):serverView,accountsView);}

    private JSONObject mapDiff(JSONObject raw)throws Exception{JSONObject out=new JSONObject().put("isGitRepo",raw.optBoolean("repo"));JSONArray files=new JSONArray();appendDiff(files,raw.optJSONArray("staged"),"staged");appendDiff(files,raw.optJSONArray("unstaged"),"unstaged");JSONArray untracked=raw.optJSONArray("untracked");if(untracked!=null)for(int i=0;i<untracked.length();i++){Object v=untracked.opt(i);if(v instanceof JSONObject){JSONObject f=(JSONObject)v;files.put(new JSONObject().put("path",f.optString("path")).put("status","untracked").put("patch",f.optString("patch")));}else files.put(new JSONObject().put("path",String.valueOf(v)).put("status","untracked"));}out.put("files",files).put("summary",new JSONObject().put("staged",count(raw.optJSONArray("staged"))).put("unstaged",count(raw.optJSONArray("unstaged"))).put("untracked",count(untracked)));return out;}
    private void appendDiff(JSONArray out,JSONArray input,String status)throws Exception{if(input==null)return;for(int i=0;i<input.length();i++){Object v=input.opt(i);if(v instanceof JSONObject){JSONObject f=(JSONObject)v;out.put(new JSONObject().put("path",f.optString("path")).put("status",status).put("patch",f.optString("patch",f.optString("diff"))));}else out.put(new JSONObject().put("path",String.valueOf(v)).put("status",status));}}

    private void attachCachedArtifacts(JSONObject item, JSONArray refs) throws Exception {
        if (refs == null || refs.length() == 0) return;
        JSONArray cached = new JSONArray();
        ArrayList<String> seen = new ArrayList<>();
        for (int i = 0; i < refs.length() && cached.length() < 4; i++) {
            JSONObject ref = refs.optJSONObject(i); if (ref == null) continue;
            String artifactId = ref.optString("artifactId");
            if (!ArtifactCache.isValidId(artifactId) || seen.contains(artifactId)) continue;
            try {
                if (artifacts.find(artifactId) == null && backend != null) {
                    byte[] data = backend.downloadArtifact(artifactId);
                    artifacts.store(artifactId, data, ref.optString("mime"), nullable(ref, "sha256"));
                }
                if (artifacts.find(artifactId) != null) { cached.put(safeArtifact(ref, true)); seen.add(artifactId); }
            } catch (Exception ignored) { /* Text/history remains available if an image cannot be cached. */ }
        }
        if (cached.length() > 0) item.put("artifacts", cached);
    }

    private JSONObject safeArtifact(JSONObject ref, boolean requireCached) throws Exception {
        String artifactId = required(ref, "artifactId");
        if (!ArtifactCache.isValidId(artifactId)) throw new UiFailure("artifact_id", "图片标识无效", false);
        if (requireCached && artifacts.find(artifactId) == null) throw new UiFailure("artifact_cache", "图片尚未缓存", false);
        String mime = required(ref, "mime");
        if (!mime.matches("image/(png|jpeg|gif|webp)")) throw new UiFailure("artifact_type", "图片格式无效", false);
        JSONObject out = new JSONObject().put("artifactId", artifactId).put("mime", mime)
                .put("name", safe(ref.optString("name", "image"))).put("size", Math.max(0, ref.optLong("size", 0)))
                .put("localUrl", "/assets/artifacts/" + artifactId);
        String sha = nullable(ref, "sha256"); if (sha != null) out.put("sha256", sha);
        return out;
    }

    private void emitView(){if(closed||!uiReady)return;viewEmitter.emitNow();}

    private void emitViewNow(){if(closed||!uiReady)return;try{JSONObject view=new JSONObject().put("route",route).put("now",Instant.now().toString()).put("appearance",new JSONObject().put("theme",theme).put("resolvedTheme",resolvedTheme())).put("pairDraft",pairDraftJson());if(activeNodeId!=null)view.put("activeNodeId",activeNodeId);if(activeSessionId!=null)view.put("activeLocalSessionId",activeSessionId);view.put("connection",connectionJson());JSONArray nodes=new JSONArray();for(MoyuDatabase.NodeRecord n:db.listNodes())nodes.put(nodeJson(n));view.put("nodes",nodes);JSONArray sessions=new JSONArray();for(MoyuDatabase.SessionRecord s:db.listSessions())sessions.put(sessionJson(s));view.put("sessions",sessions);MoyuDatabase.SessionRecord active=activeSessionId==null?null:db.getSession(activeSessionId);if(active!=null)view.put("activeSession",sessionDetail(active));if(serverView!=null)view.put("server",serverView);if(accountsView!=null)view.put("accounts",accountsView);if(configView!=null)view.put("config",configView);view.put("diagnostics",diagnosticsJson()).put("ui",new JSONObject().put("pendingRequestIds",new JSONArray()));JSONObject envelope=new JSONObject().put("version",1).put("type","view.full").put("revision",++revision).put("view",view);host.dispatch(envelope);}catch(Exception ignored){}}

    private JSONObject connectionJson()throws Exception{JSONObject c=new JSONObject().put("state",connectionState).put("summary",connectionSummary);if(activeNodeId!=null)c.put("nodeId",activeNodeId);if(phoneBackendRttMs>=0)c.put("phoneBackendRttMs",phoneBackendRttMs);if(connectionErrorCode!=null)c.put("error",uiError(connectionErrorCode,connectionErrorSummary,false));return c;}
    private JSONObject nodeJson(MoyuDatabase.NodeRecord n)throws Exception{boolean active=n.nodeId.equals(activeNodeId);JSONObject out=new JSONObject().put("nodeId",n.nodeId).put("displayName",n.displayName).put("relayNode",n.relayNode).put("configured",isConfigured(n)).put("active",active).put("overlayState",active?overlayState:"idle").put("backendState",active&&online?"online":active&&"failed".equals(overlayState)?"offline":"unknown").put("syncState",active?syncState:"idle").put("peerConnected",active&&overlayPeerConnected).put("linkMode",active?overlayLinkMode:"unknown").put("lastConnectedAt",n.lastConnectedAt==null?JSONObject.NULL:n.lastConnectedAt).put("secretState",new JSONObject().put("token",secrets.has(n.nodeId,"token")).put("networkSecret",secrets.has(n.nodeId,"networkSecret")));if(active&&overlayLinkObservedAt!=null)out.put("linkObservedAt",overlayLinkObservedAt);return out;}
    private JSONObject sessionJson(MoyuDatabase.SessionRecord s)throws Exception{JSONObject o=new JSONObject().put("localSessionId",s.localSessionId).put("nodeId",s.nodeId).put("kind",s.kind).put("title",s.title).put("updatedAt",s.updatedAt).put("state",s.state).put("unread",s.unread).put("lastSeq",s.lastSeq);if(s.remoteSessionId!=null)o.put("remoteSessionId",s.remoteSessionId);if(s.nativeSessionId!=null)o.put("nativeSessionId",s.nativeSessionId).put("resumable",true).put("nativeMessageCount",s.nativeMessageCount).put("nativeCachedMessages",s.nativeCachedSeq).put("nativeCacheComplete",s.nativeCachedSeq>=s.nativeMessageCount);if(s.profileId!=null)o.put("profileId",s.profileId);if(s.model!=null)o.put("model",s.model);if(s.effort!=null)o.put("effort",s.effort);if(s.permissionMode!=null)o.put("permissionMode",s.permissionMode);if(s.preview!=null&&!s.preview.isEmpty())o.put("preview",s.preview);return o;}
    private JSONObject sessionDetail(MoyuDatabase.SessionRecord s)throws Exception{JSONObject o=sessionJson(s);if(s.cwd!=null)o.put("cwd",s.cwd);int limit=historyLimits.getOrDefault(s.localSessionId,200);JSONArray timeline=db.timeline(s.localSessionId,limit);boolean sameOnlineNode=online&&activeNodeId!=null&&activeNodeId.equals(s.nodeId);o.put("messages",timeline).put("hasOlderLocalMessages",timeline.length()>=limit).put("composerDraft",s.draft==null?"":s.draft).put("composerAttachments",new JSONArray(pendingAttachments.getOrDefault(s.localSessionId,new ArrayList<>()))).put("canSend",sameOnlineNode&&!"running".equals(s.state)&&(s.remoteSessionId!=null||s.nativeSessionId!=null)).put("canInterrupt",sameOnlineNode&&"running".equals(s.state));JSONObject adapter=serverView==null?null:findAdapter(serverView.optJSONArray("adapters"),s.kind);JSONObject caps=adapter==null?null:adapter.optJSONObject("capabilities");o.put("effortLevels",caps==null?new JSONArray():caps.optJSONArray("effortLevels")).put("permissionModes",caps==null?new JSONArray():caps.optJSONArray("permissionModes"));for(int i=timeline.length()-1;i>=0;i--){JSONObject item=timeline.optJSONObject(i);if(item!=null&&"approval".equals(item.optString("kind"))){JSONObject a=item.optJSONObject("approval");if(a!=null&&("pending".equals(a.optString("state"))||"submitting".equals(a.optString("state")))){o.put("pendingApproval",a);break;}}}JSONObject transport=transportMetrics();if(transport.length()>0)o.put("transport",transport);JSONObject diff=diffViews.get(s.localSessionId);if(diff!=null)o.put("diff",diff);return o;}
    private JSONObject findAdapter(JSONArray adapters,String kind){if(adapters==null)return null;for(int i=0;i<adapters.length();i++){JSONObject a=adapters.optJSONObject(i);if(a!=null&&kind.equals(a.optString("adapter")))return a;}return null;}
    private JSONObject diagnosticsJson()throws Exception{JSONObject diagnostics=new JSONObject().put("net",netView==null?JSONObject.NULL:netView).put("transport",transportMetrics()).put("backendVersion",serverView==null?JSONObject.NULL:serverView.optString("version")).put("protocolVersion",1).put("notes",new JSONArray().put("手机↔后端 RTT 由 Android native client 测量。").put("PC↔relay TCP 只在后端提供时显示，不能代表 Provider 延迟。"));if(lastSyncAt!=null)diagnostics.put("lastSyncAt",lastSyncAt);return diagnostics;}
    private void markSynced(){lastSyncAt=Instant.now().toString();db.putSetting("lastSyncAt",lastSyncAt);}
    private JSONObject transportMetrics()throws Exception{JSONObject t=new JSONObject();if(phoneBackendRttMs>=0)t.put("phoneBackendRttMs",phoneBackendRttMs);if(transportView!=null){JSONObject session=transportView.optJSONObject("session");JSONObject src=session==null?transportView:session;for(String k:new String[]{"backendCliQueueMs","backendCliDispatchMs","cliFirstEventMs","relayLatencyMs","observedAt"})if(src.has(k)&&src.opt(k)!=JSONObject.NULL)t.put(k,src.opt(k));JSONObject relay=transportView.optJSONObject("relay");if(relay!=null&&relay.has("latencyMs")&&relay.opt("latencyMs")!=JSONObject.NULL)t.put("relayLatencyMs",relay.opt("latencyMs"));}return t;}

    private void resetOverlayLinkInfo(){overlayPeerConnected=false;overlayLinkMode="unknown";overlayLinkObservedAt=null;}

    private void result(String requestId,boolean ok,JSONObject data,UiFailure error){if(requestId==null||requestId.isEmpty())return;try{JSONObject e=new JSONObject().put("version",1).put("type","intent.result").put("requestId",requestId).put("ok",ok);if(data!=null)e.put("data",data);if(error!=null)e.put("error",uiError(error.code,error.getMessage(),error.retryable));host.dispatch(e);}catch(Exception ignored){}}
    private static JSONObject uiError(String code,String summary,boolean retryable)throws Exception{return new JSONObject().put("code",code).put("summary",summary==null?code:summary).put("retryable",retryable).put("category",category(code));}
    private void setConnectionError(String code,String summary,boolean retryable){online=false;connectionState="error";connectionSummary=summary==null?"连接失败":summary;connectionErrorCode=code;connectionErrorSummary=connectionSummary;syncState="error";}
    private void clearConnectionError(){connectionErrorCode=null;connectionErrorSummary=null;}
    private void requireOnline()throws UiFailure{if(!online||backend==null)throw new UiFailure("network_unreachable","当前节点未在线；不会自动重放此操作",true);}
    private MoyuDatabase.SessionRecord sessionRemote(String localId)throws UiFailure{MoyuDatabase.SessionRecord s=db.getSession(localId);if(s==null||s.remoteSessionId==null)throw new UiFailure("session_not_found","远端会话不存在或已结束",false);if(activeNodeId==null||!activeNodeId.equals(s.nodeId))throw new UiFailure("wrong_node","请先连接该会话所属节点",true);return s;}
    private void requireActiveNode(JSONObject p)throws UiFailure{if(activeNodeId==null||!activeNodeId.equals(required(p,"nodeId")))throw new UiFailure("wrong_node","请先连接所选节点",true);}
    private MoyuDatabase.SessionRecord findByRemote(String remote){return activeNodeId==null?null:db.findRemoteSession(activeNodeId,remote);}
    private String remoteId(String local)throws UiFailure{return sessionRemote(local).remoteSessionId;}

    private void startPing(){if(pingTask!=null)pingTask.cancel(false);pingTask=worker.scheduleAtFixedRate(()->{try{if(online&&backend!=null){lastPingAt=SystemClock.elapsedRealtime();backend.send(new JSONObject().put("type","ping").put("clientTs",lastPingAt));}}catch(Exception ignored){}},2,15,TimeUnit.SECONDS);}
    private void scheduleReconnect(){if(closed||pairing||activeNodeId==null)return;long delay=Math.min(30,1L<<Math.min(5,reconnectAttempt++));worker.schedule(()->{if(!closed&&!online&&!pairing&&"running".equals(overlayState))connectBackend();},delay,TimeUnit.SECONDS);}
    private void closeBackend(){online=false;if(pingTask!=null){pingTask.cancel(false);pingTask=null;}BackendClient old=backend;backend=null;if(old!=null)old.close();}

    private boolean isConfigured(MoyuDatabase.NodeRecord n){return n!=null&&secrets.has(n.nodeId,"token")&&secrets.has(n.nodeId,"networkSecret")&&!n.relayNode.isEmpty()&&!n.networkName.isEmpty();}
    private String resolvedTheme(){if(!"system".equals(theme))return theme;int night=host.context().getResources().getConfiguration().uiMode&android.content.res.Configuration.UI_MODE_NIGHT_MASK;return night==android.content.res.Configuration.UI_MODE_NIGHT_YES?"dark":"light";}
    private static String mapTurnState(String v){if("running".equals(v)||"completed".equals(v)||"failed".equals(v))return v;return "idle";}
    private static String category(String code){if(code==null)return"unknown";if(code.contains("auth")||"unauthorized".equals(code))return"auth";if(code.contains("network")||code.contains("overlay")||code.contains("node"))return"network";if(code.contains("not_found"))return"not-found";if(code.contains("queue")||code.contains("rate"))return"rate-limit";if(code.contains("parse")||code.contains("response"))return"parse";return"unknown";}
    private static String required(JSONObject o,String key)throws UiFailure{String v=o.optString(key,"").trim();if(v.isEmpty())throw new UiFailure("bad_intent","缺少 "+key,false);return v;}
    private static Object approvalDecision(JSONObject payload)throws UiFailure{
        Object decision=payload.opt("decision");
        if(decision instanceof String&&((String)decision).matches("allow|allow_session|deny|cancel"))return decision;
        if(!(decision instanceof JSONObject))throw new UiFailure("bad_intent","审批决定无效",false);
        JSONObject wrapper=(JSONObject)decision;JSONObject modification=wrapper.optJSONObject("allowWithModification");
        if(wrapper.length()!=1||modification==null||modification.length()!=1)throw new UiFailure("bad_intent","结构化审批决定无效",false);
        JSONObject answers=modification.optJSONObject("answers");if(answers==null||answers.length()<1||answers.length()>4)throw new UiFailure("bad_intent","回答数量无效",false);
        java.util.Iterator<String> keys=answers.keys();while(keys.hasNext()){
            String key=keys.next();Object answer=answers.opt(key);if(key==null||key.isEmpty()||key.length()>2000)throw new UiFailure("bad_intent","问题标识无效",false);
            if(answer instanceof String){if(((String)answer).isEmpty()||((String)answer).length()>2000)throw new UiFailure("bad_intent","回答无效",false);continue;}
            if(!(answer instanceof JSONArray)||((JSONArray)answer).length()<1||((JSONArray)answer).length()>20)throw new UiFailure("bad_intent","多选回答无效",false);
            JSONArray selected=(JSONArray)answer;for(int i=0;i<selected.length();i++){Object value=selected.opt(i);if(!(value instanceof String)||((String)value).isEmpty()||((String)value).length()>2000)throw new UiFailure("bad_intent","多选回答无效",false);}
        }
        return wrapper;
    }
    private static String nullable(JSONObject o,String key){if(!o.has(key)||o.isNull(key))return null;String v=o.optString(key,null);return emptyToNull(v);}
    private static String emptyToNull(String v){return v==null||v.isEmpty()?null:v;}
    private static String valueOrEmpty(String v){return v==null?"":v;}
    private static void copyNonEmpty(JSONObject from,JSONObject to,String key)throws Exception{String v=from.optString(key,"");if(!v.isEmpty())to.put(key,v);}
    private static String path(String v){return query(v).replace("+","%20");}
    private static String query(String v){try{return URLEncoder.encode(v==null?"":v,StandardCharsets.UTF_8.name());}catch(Exception e){return"";}}
    private static int count(JSONArray a){return a==null?0:a.length();}
    private static boolean validIpLike(String v){return v!=null&&v.matches("[0-9a-fA-F:.]{2,64}");}
    private static String safe(String v){if(v==null||v.isEmpty())return"操作失败";return v.length()>240?v.substring(0,240):v;}

    private static final class LiveStream{final String createdAt;final StringBuilder text=new StringBuilder();LiveStream(String createdAt){this.createdAt=createdAt;}}
    private static final class UiFailure extends Exception{final String code;final boolean retryable;UiFailure(String code,String summary,boolean retryable){super(summary);this.code=code;this.retryable=retryable;}}

    @Override public void close(){closed=true;worker.execute(()->{viewEmitter.close();closeBackend();worker.shutdown();});}
}
