package com.moyu.remote;

import android.content.Context;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
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
        void openExternal(String url);
        Context context();
    }

    private static final int MAX_INTENT_BYTES = 1024 * 1024;
    private static final String PAIR_DRAFT_ID = "__pair_draft__";
    private static final long PAIR_CONNECT_TIMEOUT_MS = 60_000L;
    private static final String LOCAL_TEST_NODE_ID = "__local_test_node__";
    private final MoyuDatabase db;
    private final SecretStore secrets;
    private final Host host;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();
    private final Map<String, Integer> historyLimits = new HashMap<>();
    private final Map<String, StringBuilder> textStreams = new HashMap<>();
    private final Map<String, StringBuilder> thinkingStreams = new HashMap<>();
    private final Map<String, JSONObject> toolItems = new HashMap<>();
    private final Map<String, JSONObject> diffViews = new HashMap<>();
    private volatile boolean closed;

    private long revision;
    private String route;
    private String theme;
    private String activeNodeId;
    private String activeSessionId;
    private String connectionState = "offline";
    private String connectionSummary = "本地历史可用；尚未连接节点";
    private String connectionErrorCode;
    private String connectionErrorSummary;
    private String overlayState = "stopped";
    private String syncState = "idle";
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
        this.db = app.database(); this.secrets = app.secrets(); this.host = host;
        route = db.setting("route", "console");
        theme = db.setting("theme", "system");
        activeNodeId = emptyToNull(db.setting("activeNodeId", ""));
        activeSessionId = emptyToNull(db.setting("activeSessionId", ""));
        pairDraftDisplayName = valueOrEmpty(secrets.get(PAIR_DRAFT_ID, "displayName"));
        pairDraftRelay = valueOrEmpty(secrets.get(PAIR_DRAFT_ID, "relayNode"));
        pairDraftString = valueOrEmpty(secrets.get(PAIR_DRAFT_ID, "pairString"));
        worker.execute(this::emitView);
    }

    public void postIntent(String raw) {
        if (raw == null || raw.getBytes(StandardCharsets.UTF_8).length > MAX_INTENT_BYTES) return;
        worker.execute(() -> handleRawIntent(raw));
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
            case "app.ready": case "view.reload": return null;
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
                if (online && activeNodeId != null && activeNodeId.equals(opened.nodeId) && opened.remoteSessionId != null) { syncOne(opened); subscribe(opened); }
                return null;
            case "session.create": return createSession(p);
            case "session.send": sendInput(required(p, "localSessionId"), required(p, "text")); return null;
            case "session.effort.set": setSessionEffort(p); return null;
            case "session.saveDraft": saveDraft(p); return null;
            case "session.interrupt": interrupt(required(p, "localSessionId")); return null;
            case "session.deleteLocal":
                String deleteId = required(p, "localSessionId"); db.deleteSession(deleteId);
                diffViews.remove(deleteId);
                if (deleteId.equals(activeSessionId)) activeSessionId = null; return null;
            case "session.loadOlder":
                String olderId = required(p, "localSessionId"); historyLimits.put(olderId, historyLimits.getOrDefault(olderId, 200) + 200); return null;
            case "approval.decide": approval(p); return null;
            case "fs.list": return listFiles(p);
            case "node.connect": connectNode(required(p, "nodeId")); return null;
            case "node.disconnect": disconnectNode(); return null;
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
                activeNodeId=node.nodeId; db.putSetting("activeNodeId", activeNodeId);
                connectionSummary = isConfigured(node) ? "节点已保存，可开始连接" : "节点已保存，但仍缺少 token 或 network secret";
            } catch (Exception error) {
                connectionState="error"; connectionErrorCode="bad_node_config"; connectionErrorSummary=safe(error.getMessage());
            }
            emitView();
        });
    }

    public void onOverlayState(String state, String error) {
        worker.execute(() -> {
            if (closed || state == null) return;
            overlayState = state;
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
        closeBackend(); pairing=false; pairGeneration++; activeNodeId=nodeId; db.putSetting("activeNodeId", nodeId);
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
        for(int i=0;i<items.length();i++) {
            JSONObject remote=items.optJSONObject(i); if(remote==null) continue;
            MoyuDatabase.SessionRecord local=upsertSessionSummary(nodeId,remote);
            syncOne(local);
        }
    }

    private MoyuDatabase.SessionRecord upsertSessionSummary(String nodeId, JSONObject remote) {
        String remoteId=remote.optString("sessionId"); MoyuDatabase.SessionRecord local=db.findRemoteSession(nodeId,remoteId);
        if(local==null){local=new MoyuDatabase.SessionRecord();local.localSessionId=UUID.randomUUID().toString();local.nodeId=nodeId;local.remoteSessionId=remoteId;local.draft="";}
        local.kind=remote.optString("kind","claude"); local.title=remote.optString("title",local.title==null?"未命名会话":local.title); local.cwd=nullable(remote,"cwd");
        local.profileId=nullable(remote,"profileId");local.model=nullable(remote,"model");local.effort=nullable(remote,"effort");local.updatedAt=remote.optString("updatedAt",Instant.now().toString());
        local.state=mapTurnState(remote.optString("turnState","idle")); db.saveSession(local); return local;
    }

    private void syncOne(MoyuDatabase.SessionRecord session) throws Exception {
        int after=session.lastSeq;
        Integer messageAfter=0;
        for(int page=0;page<8;page++) {
            String cursor=messageAfter==null?"":"&messageAfter="+messageAfter;
            JSONObject sync=backend.getObject("/sessions/"+path(session.remoteSessionId)+"/sync?after="+after+cursor+"&limit=256");
            JSONArray messages=sync.optJSONArray("messages"); if(messages!=null) for(int i=0;i<messages.length();i++) persistCanonicalMessage(session,messages.optJSONObject(i));
            JSONArray events=sync.optJSONArray("events"); if(events!=null) for(int i=0;i<events.length();i++) processEventEnvelope(events.optJSONObject(i),false);
            after=sync.optInt("nextAfterSeq",after);
            messageAfter=sync.optInt("nextMessageAfterSeq",messageAfter==null?after:messageAfter);
            session.lastSeq=Math.max(session.lastSeq,after); db.saveSession(session);
            if(!sync.optBoolean("hasMoreEvents",false)&&!sync.optBoolean("hasMoreMessages",false)) break;
        }
    }

    private void persistCanonicalMessage(MoyuDatabase.SessionRecord session, JSONObject message) throws Exception {
        if(message==null)return; int seq=message.optInt("seq"); String created=message.optString("createdAt",Instant.now().toString()); JSONObject item=new JSONObject();
        String thinking=message.optString("thinking","");
        if(!thinking.isEmpty()){item.put("kind","thinking").put("text",thinking).put("streaming",false).put("createdAt",created);}
        else if("tool".equals(message.optString("role"))){item.put("kind","tool").put("toolCallId",message.optString("toolCallId","tool-"+seq)).put("tool",message.optString("tool","tool"));
            if(message.has("toolInput"))item.put("input",message.opt("toolInput"));item.put("output",message.optString("toolOutput",message.optString("text",""))).put("state","done").put("createdAt",created);}
        else{String role=message.optString("role","system");if(!role.matches("user|assistant|system"))role="system";item.put("kind","message").put("role",role).put("text",message.optString("text","")).put("createdAt",created);}
        String key="tool".equals(message.optString("role"))
                ? "tool:"+session.remoteSessionId+":"+message.optString("toolCallId","tool-"+seq)
                : "m:"+session.remoteSessionId+":"+seq;
        db.putTimeline(session.localSessionId,key,seq,item);
    }

    private void handleWs(JSONObject message) {
        try {
            String type=message.optString("type");
            if("event".equals(type)){processEventEnvelope(message,true);}
            else if("pong".equals(type)){long sent=message.optLong("clientTs",0);if(sent>0)phoneBackendRttMs=Math.max(0,SystemClock.elapsedRealtime()-sent);}
            else if("net_change".equals(type)){JSONObject snap=message.optJSONObject("snapshot");if(snap!=null)netView=snap.optJSONObject("net");emitView();}
            else if("error".equals(type)){connectionErrorCode=message.optString("code","ws_error");connectionErrorSummary=message.optString("summary",connectionErrorCode);emitView();}
            else if("ack".equals(type)&&"subscribed".equals(message.optString("ackType"))){JSONObject replay=message.optJSONObject("replay");if(replay!=null&&replay.optBoolean("gap")){MoyuDatabase.SessionRecord s=findByRemote(message.optString("sessionId"));if(s!=null)syncOne(s);}}
        } catch(Exception error){connectionErrorCode="event_parse_error";connectionErrorSummary="收到无法投影的后端事件";emitView();}
    }

    private void processEventEnvelope(JSONObject envelope, boolean acknowledge) throws Exception {
        if(envelope==null)return; int seq=envelope.optInt("seq"); String remoteId=envelope.optString("sessionId"); MoyuDatabase.SessionRecord session=findByRemote(remoteId);if(session==null)return;
        if(seq>0&&seq<=session.lastSeq){if(acknowledge)ack(seq);return;}
        JSONObject event=envelope.optJSONObject("event");if(event==null)return; String type=event.optString("type");String now=Instant.now().toString();
        if("turn.started".equals(type)){session.state="running";textStreams.remove(session.localSessionId);thinkingStreams.remove(session.localSessionId);}
        else if("thinking.delta".equals(type)){StringBuilder b=thinkingStreams.computeIfAbsent(session.localSessionId,k->new StringBuilder());b.append(event.optString("text"));
            db.putTimeline(session.localSessionId,"live:thinking:"+session.localSessionId,seq,new JSONObject().put("kind","thinking").put("text",b.toString()).put("streaming",true).put("createdAt",now));}
        else if("thinking.done".equals(type)){StringBuilder b=thinkingStreams.remove(session.localSessionId);if(b!=null)db.putTimeline(session.localSessionId,"live:thinking:"+session.localSessionId,seq,new JSONObject().put("kind","thinking").put("text",b.toString()).put("streaming",false).put("createdAt",now));}
        else if("text.delta".equals(type)){StringBuilder b=textStreams.computeIfAbsent(session.localSessionId,k->new StringBuilder());b.append(event.optString("text"));
            db.putTimeline(session.localSessionId,"live:text:"+session.localSessionId,seq,new JSONObject().put("kind","message").put("role","assistant").put("text",b.toString()).put("createdAt",now));}
        else if("text.done".equals(type)){String text=event.optString("text",textStreams.containsKey(session.localSessionId)?textStreams.get(session.localSessionId).toString():"");textStreams.remove(session.localSessionId);
            db.deleteTimelineItem("live:text:"+session.localSessionId);db.putTimeline(session.localSessionId,"m:"+remoteId+":"+seq,seq,new JSONObject().put("kind","message").put("role","assistant").put("text",text).put("createdAt",now));}
        else if("tool.start".equals(type)){String id=event.optString("toolCallId","tool-"+seq);JSONObject item=new JSONObject().put("kind","tool").put("toolCallId",id).put("tool",event.optString("tool","tool")).put("state","running").put("createdAt",now);if(event.has("input"))item.put("input",event.opt("input"));toolItems.put(session.localSessionId+":"+id,item);db.putTimeline(session.localSessionId,"tool:"+remoteId+":"+id,seq,item);}
        else if("tool.output".equals(type)){String id=event.optString("toolCallId");String key="tool:"+remoteId+":"+id;JSONObject item=toolItems.get(session.localSessionId+":"+id);if(item==null)item=db.timelineItem(key);if(item==null)item=new JSONObject().put("kind","tool").put("toolCallId",id).put("tool","tool").put("state","running").put("createdAt",now);item.put("output",item.optString("output")+event.optString("text"));toolItems.put(session.localSessionId+":"+id,item);db.putTimeline(session.localSessionId,key,seq,item);}
        else if("tool.done".equals(type)){String id=event.optString("toolCallId");String key="tool:"+remoteId+":"+id;JSONObject item=toolItems.remove(session.localSessionId+":"+id);if(item==null)item=db.timelineItem(key);if(item==null)item=new JSONObject().put("kind","tool").put("toolCallId",id).put("tool","tool").put("createdAt",now);item.put("state",event.optBoolean("isError")?"error":"done");db.putTimeline(session.localSessionId,key,seq,item);}
        else if("approval.request".equals(type)){db.deleteTimelineItem("m:"+remoteId+":"+seq);JSONObject approval=new JSONObject().put("approvalId",event.optString("approvalId")).put("kind",event.optString("kind","permission")).put("summary",event.optString("summary","需要审批")).put("choices",event.optJSONArray("choices")==null?new JSONArray():event.optJSONArray("choices")).put("state","pending");if(event.has("tool"))approval.put("tool",event.opt("tool"));if(event.has("input"))approval.put("input",event.opt("input"));db.putTimeline(session.localSessionId,"approval:"+remoteId+":"+event.optString("approvalId"),seq,new JSONObject().put("kind","approval").put("approval",approval).put("createdAt",now));}
        else if("approval.resolved".equals(type)){String id=event.optString("approvalId");String key="approval:"+remoteId+":"+id;JSONObject existing=db.timelineItem(key);JSONObject approval=existing==null?null:existing.optJSONObject("approval");if(approval==null)approval=new JSONObject().put("approvalId",id).put("kind","permission").put("summary","审批已处理");String decision=event.optString("decision");approval.put("choices",new JSONArray()).put("state",("deny".equals(decision)||"cancel".equals(decision))?"denied":"allowed");db.putTimeline(session.localSessionId,key,seq,new JSONObject().put("kind","approval").put("approval",approval).put("createdAt",existing==null?now:existing.optString("createdAt",now)));}
        else if("turn.completed".equals(type)){session.state="completed";JSONObject meta=new JSONObject().put("kind","usage").put("usage",event.optJSONObject("usage")==null?new JSONObject():event.optJSONObject("usage")).put("createdAt",now);if(event.has("model"))meta.put("model",event.opt("model"));if(event.has("effort"))meta.put("effort",event.opt("effort"));db.putTimeline(session.localSessionId,"usage:"+remoteId+":"+seq,seq,meta);}
        else if("turn.failed".equals(type)){session.state="failed";db.deleteTimelineItem("m:"+remoteId+":"+seq);db.putTimeline(session.localSessionId,"error:"+remoteId+":"+seq,seq,new JSONObject().put("kind","error").put("error",uiError(event.optString("category","unknown"),event.optString("summary","CLI 执行失败"),false)).put("createdAt",now));}
        else if("transport.metrics".equals(type)){transportView=event.optJSONObject("metrics");}
        session.lastSeq=Math.max(session.lastSeq,seq);session.updatedAt=now;db.saveSession(session);if(acknowledge){ack(seq);emitView();}
    }

    private void ack(int seq){try{if(backend!=null)backend.send(new JSONObject().put("type","ack").put("seq",seq));}catch(Exception ignored){}}
    private void subscribe(MoyuDatabase.SessionRecord s){try{backend.send(new JSONObject().put("type","subscribe").put("sessionId",s.remoteSessionId).put("afterSeq",s.lastSeq));}catch(Exception ignored){}}

    private JSONObject createSession(JSONObject p) throws Exception {
        requireOnline(); String nodeId=required(p,"nodeId");if(!nodeId.equals(activeNodeId))throw new UiFailure("wrong_node","请先连接所选节点",true);
        JSONObject body=new JSONObject().put("kind",required(p,"kind"));copyNonEmpty(p,body,"cwd");copyNonEmpty(p,body,"title");copyNonEmpty(p,body,"profileId");copyNonEmpty(p,body,"model");copyNonEmpty(p,body,"effort");
        JSONObject created=backend.post("/sessions",body);JSONObject summary=created.optJSONObject("session");if(summary==null)summary=created;
        if(!summary.has("sessionId")&&created.has("sessionId"))summary.put("sessionId",created.opt("sessionId"));
        MoyuDatabase.SessionRecord session=upsertSessionSummary(nodeId,summary);activeSessionId=session.localSessionId;db.putSetting("activeSessionId",activeSessionId);route="conversation";db.putSetting("route",route);subscribe(session);
        return new JSONObject().put("localSessionId",session.localSessionId).put("remoteSessionId",session.remoteSessionId);
    }

    private void sendInput(String localId,String text)throws Exception{requireOnline();if(text.length()>100000)throw new UiFailure("input_too_large","输入内容过长",false);MoyuDatabase.SessionRecord s=sessionRemote(localId);
        backend.post("/sessions/"+path(s.remoteSessionId)+"/input",new JSONObject().put("text",text));db.putTimeline(localId,"local:user:"+UUID.randomUUID(),null,new JSONObject().put("kind","message").put("role","user").put("text",text).put("createdAt",Instant.now().toString()));db.setDraft(localId,"");db.setSessionState(localId,"running",s.lastSeq);}
    private void saveDraft(JSONObject p)throws Exception{String id=nullable(p,"localSessionId");if(id!=null)db.setDraft(id,p.optString("text",""));}
    private void setSessionEffort(JSONObject p)throws Exception{requireOnline();MoyuDatabase.SessionRecord s=sessionRemote(required(p,"localSessionId"));String effort=nullable(p,"effort");JSONObject body=new JSONObject().put("effort",effort==null?JSONObject.NULL:effort);JSONObject response=backend.post("/sessions/"+path(s.remoteSessionId)+"/effort",body);JSONObject summary=response.optJSONObject("session");if(summary!=null){s.effort=nullable(summary,"effort");s.updatedAt=summary.optString("updatedAt",Instant.now().toString());db.saveSession(s);}}
    private void interrupt(String localId)throws Exception{requireOnline();MoyuDatabase.SessionRecord s=sessionRemote(localId);backend.post("/sessions/"+path(s.remoteSessionId)+"/interrupt",new JSONObject());}
    private void approval(JSONObject p)throws Exception{requireOnline();MoyuDatabase.SessionRecord s=sessionRemote(required(p,"localSessionId"));JSONObject msg=new JSONObject().put("type","approval").put("sessionId",s.remoteSessionId).put("approvalId",required(p,"approvalId")).put("decision",required(p,"decision"));if(!backend.send(msg))throw new UiFailure("network_unreachable","审批未发送，请刷新会话确认状态",true);}

    private JSONObject listFiles(JSONObject p)throws Exception{requireOnline();requireActiveNode(p);JSONArray files=backend.getArray("/fs/list?path="+query(p.optString("path",".")));JSONArray nodes=new JSONArray();for(int i=0;i<files.length();i++){JSONObject f=files.optJSONObject(i);if(f==null)continue;nodes.put(new JSONObject().put("nodeId",activeNodeId).put("name",f.optString("name")).put("path",f.optString("path")).put("kind",f.optBoolean("isDir")?"directory":"file"));}return new JSONObject().put("fileNodes",nodes);}
    private void activateAccount(JSONObject p)throws Exception{requireOnline();requireActiveNode(p);backend.post("/accounts/activate",new JSONObject().put("adapter",required(p,"adapter")).put("profileId",required(p,"profileId")));MoyuDatabase.NodeRecord n=db.getNode(activeNodeId);accountsView=mapAccounts(backend.getObject("/accounts"),serverView,n.nodeId);}

    private void patchConfig(JSONObject p)throws Exception{requireOnline();requireActiveNode(p);JSONObject patch=p.optJSONObject("patch");if(patch==null)throw new UiFailure("bad_intent","缺少配置 patch",false);JSONObject remote=new JSONObject();String adapter=configView==null?"claude":configView.optString("defaultAdapter","claude");
        if(patch.has("defaultAdapter")){remote.put("defaultAdapter",patch.optString("defaultAdapter"));adapter=patch.optString("defaultAdapter");}
        JSONObject adapterPatch=new JSONObject();if(patch.has("model"))adapterPatch.put("model",patch.optString("model"));if(patch.has("sandbox"))adapterPatch.put("sandbox",patch.optString("sandbox"));if(patch.has("approvalsReviewer"))adapterPatch.put("approvalsReviewer",patch.optString("approvalsReviewer"));
        if(patch.has("approvalPolicy")){String v=patch.optString("approvalPolicy");adapterPatch.put("approvalPolicy","deny".equals(v)?"never":"on-request");}
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
        connectionState="overlayStarting"; connectionSummary="正在加入一次性配对 overlay"; overlayState="starting";
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
            connectionState="overlayStarting"; connectionSummary="配对完成，正在切换到正式网络"; overlayState="starting";
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

    private void disconnectNode(){pairing=false;pairGeneration++;clearPairRuntime();preserveConnectionOnStop=false;closeBackend();if(!localTestDirect)OverlayService.stop(host.context());online=false;connectionState="offline";connectionSummary="节点已断开，本地历史仍可用";overlayState="stopped";syncState="idle";}
    private void deleteNode(String nodeId){if(nodeId.equals(activeNodeId))disconnectNode();db.deleteNode(nodeId);secrets.deleteNode(nodeId);if(nodeId.equals(activeNodeId)){activeNodeId=null;db.putSetting("activeNodeId","");}}

    private MoyuDatabase.NodeRecord findNodeForEdit(JSONObject p){String name=p.optString("displayName");String relay=p.optString("relayNode");for(MoyuDatabase.NodeRecord n:db.listNodes())if((!name.isEmpty()&&name.equals(n.displayName))||(!relay.isEmpty()&&relay.equals(n.relayNode)))return n;return null;}
    private void openExternal(String url)throws Exception{URI uri=new URI(url);if(!("https".equalsIgnoreCase(uri.getScheme())||"http".equalsIgnoreCase(uri.getScheme()))||uri.getHost()==null)throw new UiFailure("bad_url","只允许打开明确的 HTTP(S) 外链",false);host.openExternal(url);}

    private JSONObject mapServer(JSONObject raw)throws Exception{JSONObject view=new JSONObject().put("version",raw.optString("version","0.0.2")).put("protocolVersion",1).put("maxMessageBytes",1048576).put("features",new JSONObject().put("diff",true).put("resume",true).put("eventGapSync",true).put("sessionEffort",true));JSONArray mapped=new JSONArray();JSONArray adapters=raw.optJSONArray("adapters");if(adapters==null)adapters=new JSONArray();for(int i=0;i<adapters.length();i++){JSONObject a=adapters.optJSONObject(i);if(a==null)continue;JSONObject c=a.optJSONObject("capabilities");JSONObject configuration=c==null?null:c.optJSONObject("configuration");JSONObject approval=c==null?null:c.optJSONObject("approval");JSONArray choices=new JSONArray();choices.put("allow").put("deny").put("cancel");JSONArray effortLevels=configuration==null?null:configuration.optJSONArray("effortLevels");JSONObject caps=new JSONObject().put("profiles",c!=null&&c.optBoolean("accountProfiles")).put("models",configuration!=null&&configuration.optBoolean("model")).put("effortLevels",effortLevels==null?new JSONArray():effortLevels).put("sandbox",configuration!=null&&configuration.optJSONArray("sandboxModes")!=null&&configuration.optJSONArray("sandboxModes").length()>0).put("approvalsReviewer",configuration!=null&&configuration.optJSONArray("reviewers")!=null&&configuration.optJSONArray("reviewers").length()>0).put("approvalChoices",choices).put("diff",true).put("interrupt",c==null||c.optBoolean("interrupt",true)).put("resume",c==null||c.optBoolean("resume",true)).put("description",approval==null?"原生 CLI 事件流":approval.optString("semantics","原生 CLI 事件流"));mapped.put(new JSONObject().put("adapter",a.optString("kind")).put("displayName",a.optString("displayName",a.optString("kind"))).put("available",a.optBoolean("available")).put("unavailableReason",a.optString("unavailableReason")).put("capabilities",caps));}view.put("adapters",mapped);return view;}

    private JSONObject mapAccounts(JSONObject raw,JSONObject server,String nodeId)throws Exception{JSONObject out=new JSONObject().put("nodeId",nodeId);JSONArray adapters=new JSONArray();JSONObject rawAdapters=raw.optJSONObject("adapters");JSONArray serverAdapters=server==null?new JSONArray():server.optJSONArray("adapters");if(serverAdapters==null)serverAdapters=new JSONArray();for(int i=0;i<serverAdapters.length();i++){JSONObject base=new JSONObject(serverAdapters.getJSONObject(i).toString());String kind=base.optString("adapter");JSONObject status=rawAdapters==null?null:rawAdapters.optJSONObject(kind);JSONArray profiles=new JSONArray();if(status!=null){JSONArray list=status.optJSONArray("profiles");if(list!=null)for(int j=0;j<list.length();j++){JSONObject p=list.optJSONObject(j);if(p==null)continue;JSONObject fields=p.optJSONObject("fields");profiles.put(new JSONObject().put("profileId",p.optString("id")).put("displayName",p.optString("name",p.optString("id"))).put("nativeDefault","nativeDefault".equals(p.optString("sourceKind"))).put("hasCredentials",fields!=null&&fields.optBoolean("hasCredentials")).put("active",p.optBoolean("active")));}}base.put("profiles",profiles);adapters.put(base);}out.put("adapters",adapters);return out;}

    private JSONObject mapConfig(JSONObject raw,JSONObject serverRaw)throws Exception{String adapter=raw.optString("defaultAdapter","claude");JSONObject adapterCfg=raw.optJSONObject("adapters");JSONObject current=adapterCfg==null?null:adapterCfg.optJSONObject(adapter);JSONObject view=new JSONObject().put("defaultAdapter",adapter);JSONArray models=new JSONArray();if(current!=null){if(current.has("model")){Object model=current.opt("model");view.put("model",model);if(model instanceof String&&!((String)model).isEmpty())models.put(model);}String policy=current.optString("approvalPolicy","on-request");view.put("approvalPolicy","never".equals(policy)?"deny":"ask").put("sandbox",current.optString("sandbox","workspace-write")).put("approvalsReviewer",current.optString("approvalsReviewer","user"));}view.put("availableModels",models);return view;}

    private JSONObject mapDiff(JSONObject raw)throws Exception{JSONObject out=new JSONObject().put("isGitRepo",raw.optBoolean("repo"));JSONArray files=new JSONArray();appendDiff(files,raw.optJSONArray("staged"),"staged");appendDiff(files,raw.optJSONArray("unstaged"),"unstaged");JSONArray untracked=raw.optJSONArray("untracked");if(untracked!=null)for(int i=0;i<untracked.length();i++){Object v=untracked.opt(i);if(v instanceof JSONObject){JSONObject f=(JSONObject)v;files.put(new JSONObject().put("path",f.optString("path")).put("status","untracked").put("patch",f.optString("patch")));}else files.put(new JSONObject().put("path",String.valueOf(v)).put("status","untracked"));}out.put("files",files).put("summary",new JSONObject().put("staged",count(raw.optJSONArray("staged"))).put("unstaged",count(raw.optJSONArray("unstaged"))).put("untracked",count(untracked)));return out;}
    private void appendDiff(JSONArray out,JSONArray input,String status)throws Exception{if(input==null)return;for(int i=0;i<input.length();i++){Object v=input.opt(i);if(v instanceof JSONObject){JSONObject f=(JSONObject)v;out.put(new JSONObject().put("path",f.optString("path")).put("status",status).put("patch",f.optString("patch",f.optString("diff"))));}else out.put(new JSONObject().put("path",String.valueOf(v)).put("status",status));}}

    private void emitView(){if(closed)return;try{JSONObject view=new JSONObject().put("route",route).put("now",Instant.now().toString()).put("appearance",new JSONObject().put("theme",theme).put("resolvedTheme",resolvedTheme())).put("pairDraft",pairDraftJson());if(activeNodeId!=null)view.put("activeNodeId",activeNodeId);if(activeSessionId!=null)view.put("activeLocalSessionId",activeSessionId);view.put("connection",connectionJson());JSONArray nodes=new JSONArray();for(MoyuDatabase.NodeRecord n:db.listNodes())nodes.put(nodeJson(n));view.put("nodes",nodes);JSONArray sessions=new JSONArray();for(MoyuDatabase.SessionRecord s:db.listSessions())sessions.put(sessionJson(s));view.put("sessions",sessions);MoyuDatabase.SessionRecord active=activeSessionId==null?null:db.getSession(activeSessionId);if(active!=null)view.put("activeSession",sessionDetail(active));if(serverView!=null)view.put("server",serverView);if(accountsView!=null)view.put("accounts",accountsView);if(configView!=null)view.put("config",configView);view.put("diagnostics",diagnosticsJson()).put("ui",new JSONObject().put("pendingRequestIds",new JSONArray()));JSONObject envelope=new JSONObject().put("version",1).put("type","view.full").put("revision",++revision).put("view",view);host.dispatch(envelope);}catch(Exception ignored){}}

    private JSONObject connectionJson()throws Exception{JSONObject c=new JSONObject().put("state",connectionState).put("summary",connectionSummary);if(activeNodeId!=null)c.put("nodeId",activeNodeId);if(phoneBackendRttMs>=0)c.put("phoneBackendRttMs",phoneBackendRttMs);if(connectionErrorCode!=null)c.put("error",uiError(connectionErrorCode,connectionErrorSummary,false));return c;}
    private JSONObject nodeJson(MoyuDatabase.NodeRecord n)throws Exception{boolean active=n.nodeId.equals(activeNodeId);return new JSONObject().put("nodeId",n.nodeId).put("displayName",n.displayName).put("relayNode",n.relayNode).put("configured",isConfigured(n)).put("active",active).put("overlayState",active?overlayState:"idle").put("backendState",active&&online?"online":active&&"failed".equals(overlayState)?"offline":"unknown").put("syncState",active?syncState:"idle").put("lastConnectedAt",n.lastConnectedAt==null?JSONObject.NULL:n.lastConnectedAt).put("secretState",new JSONObject().put("token",secrets.has(n.nodeId,"token")).put("networkSecret",secrets.has(n.nodeId,"networkSecret")));}
    private JSONObject sessionJson(MoyuDatabase.SessionRecord s)throws Exception{JSONObject o=new JSONObject().put("localSessionId",s.localSessionId).put("nodeId",s.nodeId).put("kind",s.kind).put("title",s.title).put("updatedAt",s.updatedAt).put("state",s.state).put("unread",s.unread).put("lastSeq",s.lastSeq);if(s.remoteSessionId!=null)o.put("remoteSessionId",s.remoteSessionId);if(s.profileId!=null)o.put("profileId",s.profileId);if(s.model!=null)o.put("model",s.model);if(s.effort!=null)o.put("effort",s.effort);return o;}
    private JSONObject sessionDetail(MoyuDatabase.SessionRecord s)throws Exception{JSONObject o=sessionJson(s);if(s.cwd!=null)o.put("cwd",s.cwd);int limit=historyLimits.getOrDefault(s.localSessionId,200);JSONArray timeline=db.timeline(s.localSessionId,limit);o.put("messages",timeline).put("hasOlderLocalMessages",timeline.length()>=limit).put("composerDraft",s.draft==null?"":s.draft).put("canSend",online&&activeNodeId!=null&&activeNodeId.equals(s.nodeId)&&s.remoteSessionId!=null).put("canInterrupt",online&&activeNodeId!=null&&activeNodeId.equals(s.nodeId)&&"running".equals(s.state));JSONObject adapter=serverView==null?null:findAdapter(serverView.optJSONArray("adapters"),s.kind);JSONObject caps=adapter==null?null:adapter.optJSONObject("capabilities");o.put("effortLevels",caps==null?new JSONArray():caps.optJSONArray("effortLevels"));for(int i=timeline.length()-1;i>=0;i--){JSONObject item=timeline.optJSONObject(i);if(item!=null&&"approval".equals(item.optString("kind"))){JSONObject a=item.optJSONObject("approval");if(a!=null&&("pending".equals(a.optString("state"))||"submitting".equals(a.optString("state")))){o.put("pendingApproval",a);break;}}}JSONObject transport=transportMetrics();if(transport.length()>0)o.put("transport",transport);JSONObject diff=diffViews.get(s.localSessionId);if(diff!=null)o.put("diff",diff);return o;}
    private JSONObject findAdapter(JSONArray adapters,String kind){if(adapters==null)return null;for(int i=0;i<adapters.length();i++){JSONObject a=adapters.optJSONObject(i);if(a!=null&&kind.equals(a.optString("adapter")))return a;}return null;}
    private JSONObject diagnosticsJson()throws Exception{return new JSONObject().put("net",netView==null?JSONObject.NULL:netView).put("transport",transportMetrics()).put("lastSyncAt",online?Instant.now().toString():JSONObject.NULL).put("backendVersion",serverView==null?JSONObject.NULL:serverView.optString("version")).put("protocolVersion",1).put("notes",new JSONArray().put("手机↔后端 RTT 由 Android native client 测量。").put("PC↔relay TCP 只在后端提供时显示，不能代表 Provider 延迟。"));}
    private JSONObject transportMetrics()throws Exception{JSONObject t=new JSONObject();if(phoneBackendRttMs>=0)t.put("phoneBackendRttMs",phoneBackendRttMs);if(transportView!=null){JSONObject session=transportView.optJSONObject("session");JSONObject src=session==null?transportView:session;for(String k:new String[]{"backendCliQueueMs","backendCliDispatchMs","cliFirstEventMs","relayLatencyMs","observedAt"})if(src.has(k)&&src.opt(k)!=JSONObject.NULL)t.put(k,src.opt(k));JSONObject relay=transportView.optJSONObject("relay");if(relay!=null&&relay.has("latencyMs")&&relay.opt("latencyMs")!=JSONObject.NULL)t.put("relayLatencyMs",relay.opt("latencyMs"));}return t;}

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
    private static String nullable(JSONObject o,String key){if(!o.has(key)||o.isNull(key))return null;String v=o.optString(key,null);return emptyToNull(v);}
    private static String emptyToNull(String v){return v==null||v.isEmpty()?null:v;}
    private static String valueOrEmpty(String v){return v==null?"":v;}
    private static void copyNonEmpty(JSONObject from,JSONObject to,String key)throws Exception{String v=from.optString(key,"");if(!v.isEmpty())to.put(key,v);}
    private static String path(String v){return query(v).replace("+","%20");}
    private static String query(String v){try{return URLEncoder.encode(v==null?"":v,StandardCharsets.UTF_8.name());}catch(Exception e){return"";}}
    private static int count(JSONArray a){return a==null?0:a.length();}
    private static boolean validIpLike(String v){return v!=null&&v.matches("[0-9a-fA-F:.]{2,64}");}
    private static String safe(String v){if(v==null||v.isEmpty())return"操作失败";return v.length()>240?v.substring(0,240):v;}

    private static final class UiFailure extends Exception{final String code;final boolean retryable;UiFailure(String code,String summary,boolean retryable){super(summary);this.code=code;this.retryable=retryable;}}

    @Override public void close(){closed=true;worker.execute(()->{closeBackend();worker.shutdown();});}
}
