package com.moyu.remote;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

public final class MainActivity extends Activity implements AppCoordinator.Host {
    private static final String UI_ORIGIN = "https://appassets.androidplatform.net";
    private static final String EXTRA_TEST_HOST = "moyu.testBackendHost";
    private static final String EXTRA_TEST_PORT = "moyu.testBackendPort";
    private static final String EXTRA_TEST_TOKEN = "moyu.testBackendToken";
    private WebView webView;
    private AppCoordinator coordinator;
    private final BroadcastReceiver overlayReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (coordinator != null) coordinator.onOverlayState(intent.getStringExtra(OverlayService.EXTRA_STATE), intent.getStringExtra(OverlayService.EXTRA_ERROR));
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG);
        webView = new WebView(this);
        webView.setBackgroundColor(Color.TRANSPARENT);
        setContentView(webView, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        configureWebView();
        MoyuApplication app = (MoyuApplication) getApplication();
        coordinator = new AppCoordinator(app, this);
        if (BuildConfig.DEBUG && BuildConfig.MOYU_LOCAL_TEST) {
            Intent launch = getIntent();
            String host = launch.getStringExtra(EXTRA_TEST_HOST);
            int port = launch.getIntExtra(EXTRA_TEST_PORT, 0);
            String token = launch.getStringExtra(EXTRA_TEST_TOKEN);
            coordinator.connectLocalTest(host, port, token);
        }
        registerReceiverCompat();
        requestNotificationPermission();
        webView.loadUrl(UI_ORIGIN + "/assets/ui/index.html");
    }

    @SuppressLint("SetJavaScriptEnabled") // Required by the bundled offline UI; navigation and network access are blocked below.
    private void configureWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(false);
        settings.setDatabaseEnabled(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setSupportMultipleWindows(false);
        settings.setSafeBrowsingEnabled(true);
        webView.addJavascriptInterface(new MoyuHostBridge(), "MoyuHost");
        webView.setWebViewClient(new LocalAssetClient());
    }

    public final class MoyuHostBridge {
        @JavascriptInterface public void postMessage(String message) {
            if (message == null || message.length() > 1024 * 1024) return;
            coordinator.postIntent(message);
        }
    }

    private final class LocalAssetClient extends WebViewClient {
        @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            Uri uri = request.getUrl();
            if (!"https".equals(uri.getScheme()) || !"appassets.androidplatform.net".equals(uri.getHost()) || !uri.getPath().startsWith("/assets/ui/")) return blocked();
            String relative = uri.getPath().substring("/assets/".length());
            if (relative.contains("..") || relative.endsWith("preview.html") || relative.contains("/preview-")) return blocked();
            try {
                InputStream input = getAssets().open(relative);
                Map<String, String> headers = new LinkedHashMap<>();
                headers.put("Cache-Control", "no-store");
                headers.put("X-Content-Type-Options", "nosniff");
                return new WebResourceResponse(mime(relative), "UTF-8", 200, "OK", headers, input);
            } catch (Exception missing) { return blocked(); }
        }

        @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri uri = request.getUrl();
            return !("https".equals(uri.getScheme()) && "appassets.androidplatform.net".equals(uri.getHost()) && uri.getPath().startsWith("/assets/ui/"));
        }

        private WebResourceResponse blocked() {
            return new WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", new LinkedHashMap<>(), new ByteArrayInputStream(new byte[0]));
        }
    }

    private static String mime(String path) {
        if (path.endsWith(".html")) return "text/html";
        if (path.endsWith(".js")) return "application/javascript";
        if (path.endsWith(".css")) return "text/css";
        if (path.endsWith(".svg")) return "image/svg+xml";
        if (path.endsWith(".png")) return "image/png";
        if (path.endsWith(".woff2")) return "font/woff2";
        return "application/octet-stream";
    }

    @Override public void dispatch(JSONObject envelope) {
        final String script = "window.dispatchEvent(new CustomEvent('moyu:view',{detail:JSON.parse(" + JSONObject.quote(envelope.toString()) + ")}));";
        runOnUiThread(() -> { if (webView != null) webView.evaluateJavascript(script, null); });
    }

    @Override public void showManualSetup(MoyuDatabase.NodeRecord existing) {
        runOnUiThread(() -> manualSetupDialog(existing));
    }

    private void manualSetupDialog(MoyuDatabase.NodeRecord existing) {
        ScrollView scroll = new ScrollView(this);
        LinearLayout form = new LinearLayout(this); form.setOrientation(LinearLayout.VERTICAL); form.setPadding(dp(22), dp(12), dp(22), dp(8));
        scroll.addView(form);
        EditText name = field(form, "节点名称", existing == null ? "我的电脑" : existing.displayName, false);
        EditText relay = field(form, "中继节点，例如 tcp://host:11010", existing == null ? "" : existing.relayNode, false);
        EditText network = field(form, "EasyTier 网络名", existing == null ? "" : existing.networkName, false);
        EditText mobileVip = field(form, "手机 overlay IP", existing == null ? "10.144.144.3" : existing.mobileVip, false);
        EditText backendVip = field(form, "后端 overlay 映射 IP", existing == null ? "10.1.1.10" : existing.backendVip, false);
        EditText port = field(form, "后端端口", String.valueOf(existing == null ? 18081 : existing.gatewayPort), false); port.setInputType(InputType.TYPE_CLASS_NUMBER);
        EditText socks = field(form, "本机 SOCKS5 端口", String.valueOf(existing == null ? 1080 : existing.socksPort), false); socks.setInputType(InputType.TYPE_CLASS_NUMBER);
        EditText token = field(form, existing == null ? "后端 Bearer token" : "Bearer token（留空则保留）", "", true);
        EditText secret = field(form, existing == null ? "EasyTier network secret" : "Network secret（留空则保留）", "", true);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(existing == null ? "手工配置节点" : "编辑节点")
                .setView(scroll).setNegativeButton("取消", null).setPositiveButton("保存", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                int gatewayPort = Integer.parseInt(port.getText().toString().trim());
                int socksPort = Integer.parseInt(socks.getText().toString().trim());
                if (name.getText().toString().trim().isEmpty() || relay.getText().toString().trim().isEmpty() || network.getText().toString().trim().isEmpty()) throw new IllegalArgumentException("节点名、中继和网络名不能为空");
                if (gatewayPort < 1 || gatewayPort > 65535 || socksPort < 1024 || socksPort > 65535) throw new IllegalArgumentException("端口范围无效");
                coordinator.saveManualNode(existing, name.getText().toString().trim(), relay.getText().toString().trim(), network.getText().toString().trim(),
                        mobileVip.getText().toString().trim(), backendVip.getText().toString().trim(), gatewayPort, socksPort,
                        token.getText().toString(), secret.getText().toString());
                dialog.dismiss();
            } catch (Exception error) { Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show(); }
        }));
        dialog.show();
    }

    private EditText field(LinearLayout parent, String hint, String value, boolean secret) {
        TextView label = new TextView(this); label.setText(hint); label.setPadding(0, dp(9), 0, dp(3)); parent.addView(label);
        EditText input = new EditText(this); input.setSingleLine(true); input.setText(value); input.setHint(hint);
        if (secret) input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        parent.addView(input, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)); return input;
    }

    @Override public void openExternal(String url) {
        runOnUiThread(() -> { try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); } catch (Exception error) { Toast.makeText(this, "无法打开链接", Toast.LENGTH_SHORT).show(); } });
    }

    @Override public Context context() { return this; }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private void registerReceiverCompat() {
        IntentFilter filter = new IntentFilter(OverlayService.ACTION_STATE);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(overlayReceiver, filter, Context.RECEIVER_NOT_EXPORTED); else registerReceiver(overlayReceiver, filter);
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 22);
    }

    @Override protected void onDestroy() {
        unregisterReceiver(overlayReceiver);
        if (coordinator != null) coordinator.close();
        if (webView != null) { webView.removeJavascriptInterface("MoyuHost"); webView.destroy(); webView = null; }
        super.onDestroy();
    }
}
