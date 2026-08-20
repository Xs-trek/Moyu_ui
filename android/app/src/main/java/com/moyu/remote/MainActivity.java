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
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.view.ViewGroup;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;
import android.widget.EditText;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

public final class MainActivity extends Activity implements AppCoordinator.Host {
    private static final String UI_ORIGIN = "https://appassets.androidplatform.net";
    private static final String EXTRA_TEST_HOST = "moyu.testBackendHost";
    private static final String EXTRA_TEST_PORT = "moyu.testBackendPort";
    private static final String EXTRA_TEST_TOKEN = "moyu.testBackendToken";
    private static final int REQUEST_IMAGE = 41;
    private WebView webView;
    private AppCoordinator coordinator;
    private Object backInvokedCallback;
    private final LatestOnlySlot<String> viewDispatchSlot = new LatestOnlySlot<>();
    private boolean backEvaluationInFlight;
    private final BroadcastReceiver overlayReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (coordinator != null) coordinator.onOverlayState(
                    intent.getStringExtra(OverlayService.EXTRA_STATE),
                    intent.getStringExtra(OverlayService.EXTRA_ERROR),
                    intent.hasExtra(OverlayService.EXTRA_PEER_CONNECTED)
                            ? intent.getBooleanExtra(OverlayService.EXTRA_PEER_CONNECTED, false) : null,
                    intent.getStringExtra(OverlayService.EXTRA_LINK_MODE),
                    intent.getStringExtra(OverlayService.EXTRA_LINK_OBSERVED_AT));
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
        registerSystemBackCallback();
        if (BuildConfig.DEBUG && BuildConfig.MOYU_LOCAL_TEST) {
            Intent launch = getIntent();
            String host = launch.getStringExtra(EXTRA_TEST_HOST);
            int port = launch.getIntExtra(EXTRA_TEST_PORT, 0);
            String token = launch.getStringExtra(EXTRA_TEST_TOKEN);
            // Launching without extras is an offline WebView smoke/visual test. Supplying
            // the complete tuple explicitly opts into the loopback backend integration.
            if (host != null && !host.trim().isEmpty() && port > 0 && token != null && !token.isEmpty()) {
                coordinator.connectLocalTest(host, port, token);
            }
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
            AppCoordinator active = coordinator;
            if (active != null) active.postIntent(message);
        }
    }

    private final class LocalAssetClient extends WebViewClient {
        @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            Uri uri = request.getUrl();
            if (!"https".equals(uri.getScheme()) || !"appassets.androidplatform.net".equals(uri.getHost())) return blocked();
            if (uri.getPath().startsWith("/assets/artifacts/")) return cachedArtifact(uri.getPath());
            if (!uri.getPath().startsWith("/assets/ui/")) return blocked();
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

        private WebResourceResponse cachedArtifact(String path) {
            String artifactId = path.substring("/assets/artifacts/".length());
            if (!ArtifactCache.isValidId(artifactId)) return blocked();
            try {
                ArtifactCache cache = ((MoyuApplication) getApplication()).artifacts();
                Map<String, String> headers = new LinkedHashMap<>();
                headers.put("Cache-Control", "private, max-age=31536000, immutable");
                headers.put("X-Content-Type-Options", "nosniff");
                return new WebResourceResponse(cache.mime(artifactId), null, 200, "OK", headers, cache.open(artifactId));
            } catch (Exception missing) { return blocked(); }
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
        final boolean isFullView = "view.full".equals(envelope.optString("type"));
        runOnUiThread(() -> {
            if (webView == null) return;
            if (!isFullView) {
                webView.evaluateJavascript(script, null);
                return;
            }
            String next = viewDispatchSlot.offer(script);
            if (next != null) evaluateFullView(next);
        });
    }

    private void evaluateFullView(String script) {
        WebView active = webView;
        if (active == null) {
            viewDispatchSlot.clear();
            return;
        }
        try {
            active.evaluateJavascript(script, ignored -> {
                if (active != webView) {
                    viewDispatchSlot.clear();
                    return;
                }
                String next = viewDispatchSlot.complete();
                if (next != null) evaluateFullView(next);
            });
        } catch (RuntimeException unavailable) {
            String next = viewDispatchSlot.complete();
            if (next != null) evaluateFullView(next);
        }
    }

    @Override public void showManualSetup(MoyuDatabase.NodeRecord existing) {
        runOnUiThread(() -> manualSetupDialog(existing));
    }

    private void manualSetupDialog(MoyuDatabase.NodeRecord existing) {
        final int ink = isNightMode() ? Color.rgb(247, 244, 234) : Color.rgb(17, 17, 17);
        final int surface = isNightMode() ? Color.rgb(28, 28, 28) : Color.rgb(255, 253, 245);
        ScrollView scroll = new ScrollView(this);
        LinearLayout form = new LinearLayout(this); form.setOrientation(LinearLayout.VERTICAL); form.setPadding(dp(18), dp(12), dp(18), dp(18)); form.setBackgroundColor(surface);
        scroll.addView(form);
        TextView errorBanner = new TextView(this); errorBanner.setVisibility(View.GONE); errorBanner.setTextColor(Color.rgb(17,17,17)); errorBanner.setTypeface(Typeface.DEFAULT_BOLD); errorBanner.setPadding(dp(10),dp(8),dp(10),dp(8)); errorBanner.setBackground(neoBackground(Color.rgb(255,113,168),Color.rgb(17,17,17),3)); form.addView(errorBanner);
        EditText name = field(form, "节点名称", existing == null ? "我的电脑" : existing.displayName, false);
        EditText relay = field(form, "中继节点，例如 tcp://host:11010", existing == null ? "" : existing.relayNode, false);
        EditText network = field(form, "EasyTier 网络名", existing == null ? "" : existing.networkName, false);
        EditText mobileVip = field(form, "手机 overlay IP", existing == null ? "10.144.144.3" : existing.mobileVip, false);
        EditText backendVip = field(form, "后端 overlay 映射 IP", existing == null ? "10.1.1.10" : existing.backendVip, false);
        EditText port = field(form, "后端端口", String.valueOf(existing == null ? 18081 : existing.gatewayPort), false); port.setInputType(InputType.TYPE_CLASS_NUMBER);
        EditText socks = field(form, "本机 SOCKS5 端口", String.valueOf(existing == null ? 1080 : existing.socksPort), false); socks.setInputType(InputType.TYPE_CLASS_NUMBER);
        EditText token = field(form, existing == null ? "后端 Bearer token" : "Bearer token（留空则保留）", "", true);
        EditText secret = field(form, existing == null ? "EasyTier network secret" : "Network secret（留空则保留）", "", true);
        TextView title = new TextView(this); title.setText(existing == null ? "手工配置节点" : "编辑节点"); title.setTextColor(Color.rgb(17,17,17)); title.setTextSize(22); title.setTypeface(Typeface.DEFAULT_BOLD); title.setPadding(dp(18),dp(16),dp(18),dp(12)); title.setBackground(neoBackground(Color.rgb(255,218,77),Color.rgb(17,17,17),3));
        AlertDialog dialog = new AlertDialog.Builder(this).setCustomTitle(title)
                .setView(scroll).setNegativeButton("取消", null).setPositiveButton("保存", null).create();
        dialog.setOnShowListener(ignored -> {
            Button save = dialog.getButton(AlertDialog.BUTTON_POSITIVE); Button cancel = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
            styleDialogButton(save, Color.rgb(88,232,205)); styleDialogButton(cancel, surface); cancel.setTextColor(ink);
            save.setOnClickListener(v -> {
              try {
                int gatewayPort = Integer.parseInt(port.getText().toString().trim());
                int socksPort = Integer.parseInt(socks.getText().toString().trim());
                if (name.getText().toString().trim().isEmpty() || relay.getText().toString().trim().isEmpty() || network.getText().toString().trim().isEmpty()) throw new IllegalArgumentException("节点名、中继和网络名不能为空");
                if (gatewayPort < 1 || gatewayPort > 65535 || socksPort < 1024 || socksPort > 65535) throw new IllegalArgumentException("端口范围无效");
                coordinator.saveManualNode(existing, name.getText().toString().trim(), relay.getText().toString().trim(), network.getText().toString().trim(),
                        mobileVip.getText().toString().trim(), backendVip.getText().toString().trim(), gatewayPort, socksPort,
                        token.getText().toString(), secret.getText().toString());
                dialog.dismiss();
              } catch (Exception error) { errorBanner.setText(error.getMessage()); errorBanner.setVisibility(View.VISIBLE); scroll.smoothScrollTo(0,0); }
            });
        });
        dialog.show();
    }

    private EditText field(LinearLayout parent, String hint, String value, boolean secret) {
        int ink = isNightMode() ? Color.rgb(247,244,234) : Color.rgb(17,17,17); int elevated = isNightMode() ? Color.rgb(38,38,38) : Color.WHITE;
        TextView label = new TextView(this); label.setText(hint); label.setTextColor(ink); label.setTypeface(Typeface.DEFAULT_BOLD); label.setPadding(0, dp(12), 0, dp(5)); parent.addView(label);
        EditText input = new EditText(this); input.setSingleLine(true); input.setText(value); input.setHint(hint); input.setTextColor(ink); input.setHintTextColor(isNightMode()?Color.rgb(187,181,167):Color.rgb(96,93,85)); input.setPadding(dp(11),dp(8),dp(11),dp(8)); input.setBackground(neoBackground(elevated,ink,2));
        if (secret) input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(50)); parent.addView(input,params); return input;
    }

    private boolean isNightMode(){return (getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK)==android.content.res.Configuration.UI_MODE_NIGHT_YES;}
    private GradientDrawable neoBackground(int fill,int stroke,int width){GradientDrawable bg=new GradientDrawable();bg.setColor(fill);bg.setCornerRadius(dp(4));bg.setStroke(dp(width),stroke);return bg;}
    private void styleDialogButton(Button button,int fill){button.setAllCaps(false);button.setTextColor(Color.rgb(17,17,17));button.setTypeface(Typeface.DEFAULT_BOLD);button.setMinHeight(dp(44));button.setBackground(neoBackground(fill,Color.rgb(17,17,17),2));LinearLayout.LayoutParams p=(LinearLayout.LayoutParams)button.getLayoutParams();p.setMargins(dp(5),dp(5),dp(5),dp(8));button.setLayoutParams(p);}

    @Override public void openExternal(String url) {
        runOnUiThread(() -> {
            String allowed = ExternalLinkPolicy.allowedUrl(url);
            if (allowed == null) {
                Toast.makeText(this, "链接不在允许列表中", Toast.LENGTH_SHORT).show();
                return;
            }
            Uri uri = Uri.parse(allowed);
            String hostName = uri.getHost() == null ? "外部网站" : uri.getHost();
            AlertDialog dialog = new AlertDialog.Builder(this)
                    .setTitle("离开 Moyu？")
                    .setMessage("即将在系统浏览器中打开 " + hostName + "。\n\n" + allowed)
                    .setNegativeButton("取消", null)
                    .setPositiveButton("继续打开", (ignored, which) -> launchAllowedExternal(allowed))
                    .create();
            dialog.setOnShowListener(ignored -> {
                int surface = isNightMode() ? Color.rgb(38,38,38) : Color.rgb(255,253,245);
                styleDialogButton(dialog.getButton(AlertDialog.BUTTON_POSITIVE), Color.rgb(88,232,205));
                styleDialogButton(dialog.getButton(AlertDialog.BUTTON_NEGATIVE), surface);
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(isNightMode() ? Color.rgb(247,244,234) : Color.rgb(17,17,17));
            });
            dialog.show();
        });
    }

    private void launchAllowedExternal(String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addCategory(Intent.CATEGORY_BROWSABLE);
            startActivity(Intent.createChooser(intent, "选择浏览器"));
        } catch (Exception error) {
            Toast.makeText(this, "无法打开链接", Toast.LENGTH_SHORT).show();
        }
    }

    @Override public void showMessage(String message) {
        runOnUiThread(() -> Toast.makeText(this, message, Toast.LENGTH_LONG).show());
    }

    @Override public void finishApp() {
        runOnUiThread(() -> {
            if (!isFinishing() && !isDestroyed()) finish();
        });
    }

    @Override public void pickImage() {
        runOnUiThread(() -> {
            Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            pick.addCategory(Intent.CATEGORY_OPENABLE);
            pick.setType("image/*");
            try { startActivityForResult(pick, REQUEST_IMAGE); }
            catch (Exception error) { coordinator.onImagePickCancelled(); Toast.makeText(this, "无法打开系统图片选择器", Toast.LENGTH_SHORT).show(); }
        });
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_IMAGE) return;
        if (resultCode != RESULT_OK || data == null || data.getData() == null) { coordinator.onImagePickCancelled(); return; }
        Uri uri = data.getData();
        try {
            String mime = getContentResolver().getType(uri);
            if ("image/jpg".equals(mime)) mime = "image/jpeg";
            if (!("image/png".equals(mime) || "image/jpeg".equals(mime) || "image/gif".equals(mime) || "image/webp".equals(mime))) {
                throw new IllegalArgumentException("仅支持 PNG、JPEG、GIF 或 WebP 图片");
            }
            String name = "image";
            try (Cursor cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) name = cursor.getString(0);
            }
            byte[] bytes;
            try (InputStream input = getContentResolver().openInputStream(uri); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                if (input == null) throw new IllegalArgumentException("无法读取所选图片");
                byte[] buffer = new byte[16 * 1024];
                int total = 0;
                for (int read; (read = input.read(buffer)) >= 0;) {
                    total += read;
                    if (total > ArtifactCache.MAX_BYTES) throw new IllegalArgumentException("图片不能超过 8 MiB");
                    output.write(buffer, 0, read);
                }
                bytes = output.toByteArray();
            }
            if (bytes.length == 0) throw new IllegalArgumentException("所选图片为空");
            coordinator.onImagePicked(name, mime, bytes);
        } catch (Exception error) {
            coordinator.onImagePickCancelled();
            Toast.makeText(this, error.getMessage() == null ? "无法读取所选图片" : error.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override public Context context() { return this; }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private void requestSystemBack() {
        WebView activeWebView = webView;
        if (activeWebView != null) {
            if (backEvaluationInFlight) return;
            backEvaluationInFlight = true;
            try {
                activeWebView.evaluateJavascript(
                        "(function(){try{return !!(window.MoyuUi&&typeof window.MoyuUi.handleBack==='function'&&window.MoyuUi.handleBack()===true);}catch(e){return false;}})();",
                        result -> {
                            backEvaluationInFlight = false;
                            if (activeWebView != webView) return;
                            if (!WebViewInteractionPolicy.wasHandled(result)) requestCoordinatorBack();
                        });
                return;
            } catch (RuntimeException unavailable) {
                backEvaluationInFlight = false;
            }
        }
        requestCoordinatorBack();
    }

    private void requestCoordinatorBack() {
        AppCoordinator active = coordinator;
        if (active != null) active.onSystemBack();
        else if (!isFinishing() && !isDestroyed()) finish();
    }

    private void registerSystemBackCallback() {
        if (Build.VERSION.SDK_INT < 33 || backInvokedCallback != null) return;
        backInvokedCallback = Api33BackCallbacks.register(this, this::requestSystemBack);
    }

    private void unregisterSystemBackCallback() {
        if (Build.VERSION.SDK_INT < 33 || backInvokedCallback == null) return;
        Api33BackCallbacks.unregister(this, backInvokedCallback);
        backInvokedCallback = null;
    }

    /** Keeps API 33 classes out of MainActivity's fields so API 26-32 can load it safely. */
    @SuppressLint("NewApi")
    private static final class Api33BackCallbacks {
        static Object register(Activity activity, Runnable action) {
            OnBackInvokedCallback callback = action::run;
            activity.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback);
            return callback;
        }

        static void unregister(Activity activity, Object callback) {
            activity.getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(
                    (OnBackInvokedCallback) callback);
        }
    }

    @SuppressWarnings("deprecation")
    @Override public void onBackPressed() {
        requestSystemBack();
    }

    private void registerReceiverCompat() {
        IntentFilter filter = new IntentFilter(OverlayService.ACTION_STATE);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(overlayReceiver, filter, Context.RECEIVER_NOT_EXPORTED); else registerReceiver(overlayReceiver, filter);
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 22);
    }

    @Override protected void onDestroy() {
        unregisterSystemBackCallback();
        unregisterReceiver(overlayReceiver);
        AppCoordinator active = coordinator;
        coordinator = null;
        if (active != null) active.close();
        backEvaluationInFlight = false;
        viewDispatchSlot.clear();
        if (webView != null) { webView.removeJavascriptInterface("MoyuHost"); webView.destroy(); webView = null; }
        super.onDestroy();
    }
}
