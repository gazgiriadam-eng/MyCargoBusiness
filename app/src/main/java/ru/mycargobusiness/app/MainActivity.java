package ru.mycargobusiness.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private WebView webView;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private SecureStore secureStore;
    private final ApiClient apiClient = new ApiClient();

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        secureStore = new SecureStore(this);
        webView = new WebView(this);
        webView.setBackgroundColor(Color.rgb(24, 24, 24));
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.getSettings().setAllowFileAccess(true);
        webView.getSettings().setAllowContentAccess(false);
        webView.addJavascriptInterface(new AndroidBridge(), "AndroidBridge");
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                String scheme = uri.getScheme();
                if ("geo".equals(scheme) || "tel".equals(scheme)) {
                    openExternal(uri);
                    return true;
                }
                return false;
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                Uri uri = Uri.parse(url);
                String scheme = uri.getScheme();
                if ("geo".equals(scheme) || "tel".equals(scheme)) {
                    openExternal(uri);
                    return true;
                }
                return false;
            }
        });
        setContentView(webView);
        webView.loadUrl("file:///android_asset/app.html");
    }

    private void openExternal(Uri uri) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (ActivityNotFoundException error) {
            Toast.makeText(this, "Подходящее приложение не установлено", Toast.LENGTH_LONG).show();
        }
    }

    private void sendResult(String type, JSONObject payload) {
        String script = "window.mgbNativeResult(" + JSONObject.quote(type) + "," +
                JSONObject.quote(payload.toString()) + ")";
        runOnUiThread(() -> webView.evaluateJavascript(script, null));
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        if (webView != null) webView.destroy();
        super.onDestroy();
    }

    public final class AndroidBridge {
        @JavascriptInterface
        public void saveAndTestKeys(String dadataKey, String orsKey) {
            executor.execute(() -> {
                JSONObject result;
                try {
                    String cleanDadata = dadataKey == null ? "" : dadataKey.trim();
                    String cleanOrs = orsKey == null ? "" : orsKey.trim();
                    if (cleanDadata.isEmpty() || cleanOrs.isEmpty()) {
                        result = new JSONObject().put("ok", false).put("message", "Заполните оба ключа");
                    } else {
                        secureStore.put("dadata", cleanDadata);
                        secureStore.put("ors", cleanOrs);
                        result = apiClient.testServices(cleanDadata, cleanOrs);
                    }
                } catch (Exception error) {
                    result = new JSONObject();
                    try {
                        result.put("ok", false).put("message", "Не удалось сохранить ключи");
                    } catch (Exception ignored) { }
                }
                sendResult("services", result);
            });
        }

        @JavascriptInterface
        public void optimizeRoute(String requestJson) {
            executor.execute(() -> {
                JSONObject result;
                try {
                    String dadata = secureStore.get("dadata");
                    String ors = secureStore.get("ors");
                    if (dadata.isEmpty() || ors.isEmpty()) {
                        result = new JSONObject()
                                .put("ok", false)
                                .put("message", "Сначала подключите сервисы в настройках");
                    } else {
                        result = apiClient.optimize(requestJson, dadata, ors);
                    }
                } catch (Exception error) {
                    result = new JSONObject();
                    try {
                        result.put("ok", false).put("message", "Не удалось прочитать ключи");
                    } catch (Exception ignored) { }
                }
                sendResult("route", result);
            });
        }
    }
}
