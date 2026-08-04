package com.moyu.remote;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/** Native-only REST/WS transport. Production sockets traverse the loopback EasyTier SOCKS5 portal. */
public final class BackendClient implements AutoCloseable {
    public interface Events {
        void onOpen();
        void onMessage(JSONObject message);
        void onClosed(String summary);
    }

    public static final class BackendException extends Exception {
        public final String code;
        public final boolean retryable;
        BackendException(String code, String message, boolean retryable) { super(message); this.code = code; this.retryable = retryable; }
    }

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private final OkHttpClient http;
    private final String origin;
    private final String apiBase;
    private final String token;
    private volatile WebSocket webSocket;

    public BackendClient(String backendVip, int gatewayPort, int socksPort, String token) {
        this(backendVip, gatewayPort, token, new Proxy(Proxy.Type.SOCKS, new InetSocketAddress("127.0.0.1", socksPort)));
    }

    /** Debug integration transport. AppCoordinator gates this behind BuildConfig.MOYU_LOCAL_TEST;
     * release code never selects Proxy.NO_PROXY. */
    static BackendClient directForLocalTest(String backendHost, int gatewayPort, String token) {
        return new BackendClient(backendHost, gatewayPort, token, Proxy.NO_PROXY);
    }

    private BackendClient(String backendHost, int gatewayPort, String token, Proxy proxy) {
        this.origin = buildOrigin(backendHost, gatewayPort);
        this.apiBase = buildApiBase(origin);
        this.token = token == null ? "" : token;
        this.http = new OkHttpClient.Builder()
                .proxy(proxy)
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .pingInterval(20, TimeUnit.SECONDS)
                .retryOnConnectionFailure(false)
                .build();
    }

    public JSONObject getObject(String path) throws BackendException { return object("GET", path, null, true); }
    public JSONArray getArray(String path) throws BackendException { return array("GET", path, null, true); }
    public JSONObject post(String path, JSONObject body) throws BackendException { return object("POST", path, body, true); }
    public JSONObject postUnauthenticated(String path, JSONObject body) throws BackendException { return object("POST", path, body, false); }
    public JSONObject postPair(JSONObject body) throws BackendException { return objectAt("POST", buildPairUrl(origin), body, false); }
    public JSONObject patch(String path, JSONObject body) throws BackendException { return object("PATCH", path, body, true); }

    private JSONObject object(String method, String path, JSONObject body, boolean authenticated) throws BackendException {
        Object value = executeAt(method, apiBase + path, body, authenticated);
        if (value instanceof JSONObject) return (JSONObject) value;
        throw new BackendException("bad_response", "后端返回的对象格式无效", false);
    }

    private JSONObject objectAt(String method, String url, JSONObject body, boolean authenticated) throws BackendException {
        Object value = executeAt(method, url, body, authenticated);
        if (value instanceof JSONObject) return (JSONObject) value;
        throw new BackendException("bad_response", "后端返回的对象格式无效", false);
    }

    private JSONArray array(String method, String path, JSONObject body, boolean authenticated) throws BackendException {
        Object value = executeAt(method, apiBase + path, body, authenticated);
        if (value instanceof JSONArray) return (JSONArray) value;
        throw new BackendException("bad_response", "后端返回的数组格式无效", false);
    }

    private Object executeAt(String method, String url, JSONObject body, boolean authenticated) throws BackendException {
        Request.Builder builder = new Request.Builder().url(url).header("Accept", "application/json");
        if (authenticated && !token.isEmpty()) builder.header("Authorization", "Bearer " + token);
        RequestBody requestBody = body == null ? RequestBody.create(JSON, new byte[0]) : RequestBody.create(JSON, body.toString());
        if ("GET".equals(method)) builder.get();
        else if ("POST".equals(method)) builder.post(requestBody);
        else if ("PATCH".equals(method)) builder.patch(requestBody);
        else if ("DELETE".equals(method)) builder.delete(requestBody);
        try (Response response = http.newCall(builder.build()).execute()) {
            String text = response.body() == null ? "{}" : response.body().string();
            Object parsed;
            try { parsed = text.trim().startsWith("[") ? new JSONArray(text) : new JSONObject(text.isEmpty() ? "{}" : text); }
            catch (Exception invalid) { throw new BackendException("bad_response", "后端响应无法解析", false); }
            if (!response.isSuccessful()) {
                JSONObject error = parsed instanceof JSONObject ? (JSONObject) parsed : new JSONObject();
                String code = error.optString("code", error.optString("error", response.code() == 401 ? "unauthorized" : "http_" + response.code()));
                String summary = error.optString("summary", code);
                throw new BackendException(code, summary, response.code() >= 500 || response.code() == 429);
            }
            return parsed;
        } catch (BackendException error) {
            throw error;
        } catch (IOException error) {
            throw new BackendException("network_unreachable", error.getMessage() == null ? "无法连接后端" : error.getMessage(), true);
        }
    }

    public synchronized void openWebSocket(Events events) {
        closeWebSocket();
        String wsBase = apiBase.replaceFirst("^http", "ws") + "/ws?token=" + urlEncode(token);
        Request request = new Request.Builder().url(wsBase).build();
        webSocket = http.newWebSocket(request, new WebSocketListener() {
            @Override public void onOpen(WebSocket socket, Response response) { events.onOpen(); }
            @Override public void onMessage(WebSocket socket, String text) {
                try { events.onMessage(new JSONObject(text)); } catch (Exception ignored) { }
            }
            @Override public void onClosing(WebSocket socket, int code, String reason) { socket.close(code, reason); }
            @Override public void onClosed(WebSocket socket, int code, String reason) { events.onClosed("WS " + code + (reason.isEmpty() ? "" : ": " + reason)); }
            @Override public void onFailure(WebSocket socket, Throwable error, Response response) { events.onClosed(error.getMessage() == null ? "WS 连接失败" : error.getMessage()); }
        });
    }

    public boolean send(JSONObject message) { WebSocket socket = webSocket; return socket != null && socket.send(message.toString()); }

    public synchronized void closeWebSocket() {
        WebSocket socket = webSocket; webSocket = null;
        if (socket != null) socket.close(1000, "client disconnect");
    }

    @Override public void close() { closeWebSocket(); http.dispatcher().executorService().shutdown(); http.connectionPool().evictAll(); }

    private static String urlEncode(String value) {
        try { return URLEncoder.encode(value, StandardCharsets.UTF_8.name()); } catch (Exception ignored) { return ""; }
    }

    static String buildOrigin(String backendVip, int gatewayPort) {
        return "http://" + backendVip + ":" + gatewayPort;
    }

    static String buildApiBase(String origin) { return origin + "/api/v1"; }
    static String buildPairUrl(String origin) { return origin + "/pair"; }
}
