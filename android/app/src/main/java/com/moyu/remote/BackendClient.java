package com.moyu.remote;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
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

    static final class ArtifactTooLargeException extends IOException {
        ArtifactTooLargeException() { super("artifact exceeds size limit"); }
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

    /** Upload image bytes without base64 inflation. The backend validates MIME, magic and size. */
    public JSONObject uploadArtifact(String name, String mime, byte[] data) throws BackendException {
        if (data == null || data.length == 0 || data.length > ArtifactCache.MAX_BYTES) {
            throw new BackendException("artifact_size", "图片大小无效", false);
        }
        MediaType mediaType = MediaType.parse(mime);
        if (mediaType == null) throw new BackendException("artifact_type", "图片格式无效", false);
        String url = apiBase + "/artifacts?name=" + urlEncode(name == null ? "image" : name);
        Request request = authenticated(new Request.Builder().url(url).header("Accept", "application/json"))
                .post(RequestBody.create(mediaType, data)).build();
        try (Response response = http.newCall(request).execute()) {
            String text = response.body() == null ? "{}" : response.body().string();
            JSONObject parsed;
            try { parsed = new JSONObject(text.isEmpty() ? "{}" : text); }
            catch (Exception invalid) { throw new BackendException("bad_response", "后端响应无法解析", false); }
            if (!response.isSuccessful()) throw backendError(response, parsed);
            return parsed;
        } catch (BackendException error) {
            throw error;
        } catch (IOException error) {
            throw new BackendException("network_unreachable", error.getMessage() == null ? "无法上传图片" : error.getMessage(), true);
        }
    }

    public byte[] downloadArtifact(String artifactId) throws BackendException {
        String url = apiBase + "/artifacts/" + urlEncode(artifactId == null ? "" : artifactId);
        Request request = authenticated(new Request.Builder().url(url).header("Accept", "image/*")).get().build();
        try (Response response = http.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String text = response.body() == null ? "{}" : response.body().string();
                JSONObject error;
                try { error = new JSONObject(text.isEmpty() ? "{}" : text); } catch (Exception ignored) { error = new JSONObject(); }
                throw backendError(response, error);
            }
            if (response.body() == null) throw new BackendException("bad_response", "图片响应为空", false);
            long declared = response.body().contentLength();
            if (declared > ArtifactCache.MAX_BYTES) throw new BackendException("artifact_size", "图片超过大小限制", false);
            byte[] data;
            try (InputStream input = response.body().byteStream()) { data = readBounded(input, ArtifactCache.MAX_BYTES); }
            if (data.length == 0 || data.length > ArtifactCache.MAX_BYTES) throw new BackendException("artifact_size", "图片大小无效", false);
            return data;
        } catch (BackendException error) {
            throw error;
        } catch (ArtifactTooLargeException error) {
            throw new BackendException("artifact_size", "图片超过大小限制", false);
        } catch (IOException error) {
            throw new BackendException("network_unreachable", error.getMessage() == null ? "无法下载图片" : error.getMessage(), true);
        }
    }

    static byte[] readBounded(InputStream input, int maxBytes) throws IOException {
        if (input == null || maxBytes < 1) throw new IOException("invalid bounded stream");
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maxBytes, 64 * 1024));
        byte[] buffer = new byte[16 * 1024];
        int total = 0;
        for (int read; (read = input.read(buffer)) >= 0;) {
            if (read == 0) continue;
            total += read;
            if (total > maxBytes) throw new ArtifactTooLargeException();
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

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
        if (authenticated) authenticated(builder);
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
                throw backendError(response, error);
            }
            return parsed;
        } catch (BackendException error) {
            throw error;
        } catch (IOException error) {
            throw new BackendException("network_unreachable", error.getMessage() == null ? "无法连接后端" : error.getMessage(), true);
        }
    }

    private Request.Builder authenticated(Request.Builder builder) {
        if (!token.isEmpty()) builder.header("Authorization", "Bearer " + token);
        return builder;
    }

    private static BackendException backendError(Response response, JSONObject error) {
        String code = error.optString("code", error.optString("error", response.code() == 401 ? "unauthorized" : "http_" + response.code()));
        String summary = error.optString("summary", code);
        return new BackendException(code, summary, response.code() >= 500 || response.code() == 429);
    }

    public synchronized void openWebSocket(Events events) {
        closeWebSocket();
        Request request = buildWebSocketRequest(apiBase, token);
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
    static Request buildWebSocketRequest(String apiBase, String token) {
        Request.Builder builder = new Request.Builder().url(apiBase.replaceFirst("^http", "ws") + "/ws");
        if (token != null && !token.isEmpty()) builder.header("Authorization", "Bearer " + token);
        return builder.build();
    }
}
