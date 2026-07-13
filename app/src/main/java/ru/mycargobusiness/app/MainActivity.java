package ru.mycargobusiness.app;

import android.annotation.SuppressLint;
import android.Manifest;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.os.Environment;
import android.content.pm.PackageManager;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

public class MainActivity extends Activity {
    private static final String UPDATE_INFO_URL = "https://raw.githubusercontent.com/gazgiriadam-eng/MyCargoBusiness/main/update.json";
    private static final String ALLOWED_APK_PREFIX = "https://github.com/gazgiriadam-eng/MyCargoBusiness/";
    private static final int EXPORT_BACKUP_REQUEST = 2001;
    private static final int IMPORT_BACKUP_REQUEST = 2002;
    private WebView webView;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private SecureStore secureStore;
    private final ApiClient apiClient = new ApiClient();
    private long updateDownloadId = -1;
    private String pendingBackup = "";
    private final BroadcastReceiver downloadReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
            if (id != updateDownloadId) return;
            DownloadManager manager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            Uri apkUri = manager.getUriForDownloadedFile(id);
            if (apkUri == null) {
                Toast.makeText(MainActivity.this, "Не удалось скачать обновление", Toast.LENGTH_LONG).show();
                return;
            }
            Intent install = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(apkUri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            openExternal(install);
        }
    };

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
        IntentFilter downloads = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(downloadReceiver, downloads, Context.RECEIVER_EXPORTED);
        else registerReceiver(downloadReceiver, downloads);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1001);
        }
    }

    private void openExternal(Uri uri) {
        openExternal(new Intent(Intent.ACTION_VIEW, uri));
    }

    private void openExternal(Intent intent) {
        try {
            startActivity(intent);
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
        try { unregisterReceiver(downloadReceiver); } catch (Exception ignored) { }
        executor.shutdownNow();
        if (webView != null) webView.destroy();
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (requestCode == EXPORT_BACKUP_REQUEST) {
            try (OutputStream output = getContentResolver().openOutputStream(uri)) {
                if (output == null) throw new IllegalStateException("Нет доступа к файлу");
                output.write(pendingBackup.getBytes(StandardCharsets.UTF_8));
                sendResult("backup", new JSONObject().put("ok", true).put("mode", "export").put("message", "Резервная копия сохранена"));
            } catch (Exception error) {
                sendResult("backup", backupError("Не удалось сохранить резервную копию"));
            } finally {
                pendingBackup = "";
            }
        } else if (requestCode == IMPORT_BACKUP_REQUEST) {
            try (InputStream input = getContentResolver().openInputStream(uri);
                 BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
                StringBuilder json = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) json.append(line).append('\n');
                new JSONObject(json.toString());
                sendResult("backup", new JSONObject().put("ok", true).put("mode", "import").put("data", json.toString()));
            } catch (Exception error) {
                sendResult("backup", backupError("Файл не является резервной копией приложения"));
            }
        }
    }

    private JSONObject backupError(String message) {
        JSONObject result = new JSONObject();
        try { result.put("ok", false).put("mode", "backup").put("message", message); } catch (Exception ignored) { }
        return result;
    }

    public final class AndroidBridge {
        @JavascriptInterface
        public void checkForUpdates(boolean userInitiated) {
            executor.execute(() -> {
                JSONObject result = new JSONObject();
                try {
                    HttpURLConnection connection = (HttpURLConnection) new URL(UPDATE_INFO_URL).openConnection();
                    connection.setConnectTimeout(10_000);
                    connection.setReadTimeout(10_000);
                    connection.setRequestProperty("Accept", "application/json");
                    connection.setRequestProperty("User-Agent", "MyCargoBusiness-Android");
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                        StringBuilder text = new StringBuilder();
                        String line;
                        while ((line = reader.readLine()) != null) text.append(line);
                        JSONObject remote = new JSONObject(text.toString());
                        int remoteCode = remote.getInt("versionCode");
                        result.put("ok", true)
                                .put("available", remoteCode > BuildConfig.VERSION_CODE)
                                .put("currentVersion", BuildConfig.VERSION_NAME)
                                .put("versionCode", remoteCode)
                                .put("versionName", remote.optString("versionName", "Новая версия"))
                                .put("notes", remote.optString("notes", "Доступно обновление"))
                                .put("apkUrl", remote.getString("apkUrl"))
                                .put("userInitiated", userInitiated);
                    } finally {
                        connection.disconnect();
                    }
                } catch (Exception error) {
                    try { result.put("ok", false).put("available", false).put("userInitiated", userInitiated).put("message", "Не удалось проверить обновления"); } catch (Exception ignored) { }
                }
                sendResult("update", result);
            });
        }

        @JavascriptInterface
        public void downloadUpdate(String apkUrl) {
            runOnUiThread(() -> {
                if (apkUrl == null || !apkUrl.startsWith(ALLOWED_APK_PREFIX)) {
                    Toast.makeText(MainActivity.this, "Недопустимая ссылка обновления", Toast.LENGTH_LONG).show();
                    return;
                }
                if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
                    Toast.makeText(MainActivity.this, "Разрешите установку обновлений для этого приложения и нажмите ещё раз", Toast.LENGTH_LONG).show();
                    openExternal(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
                    return;
                }
                DownloadManager.Request request = new DownloadManager.Request(Uri.parse(apkUrl))
                        .setTitle("Обновление «Мой грузовой бизнес»")
                        .setDescription("Загрузка новой версии")
                        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                        .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "MyCargoBusiness-update-" + System.currentTimeMillis() + ".apk");
                DownloadManager manager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                updateDownloadId = manager.enqueue(request);
                Toast.makeText(MainActivity.this, "Обновление загружается", Toast.LENGTH_SHORT).show();
            });
        }

        @JavascriptInterface
        public void exportBackup(String json) {
            runOnUiThread(() -> {
                pendingBackup = json == null ? "" : json;
                Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("application/json")
                        .putExtra(Intent.EXTRA_TITLE, "Мой-грузовой-бизнес-резервная-копия.json");
                startActivityForResult(intent, EXPORT_BACKUP_REQUEST);
            });
        }

        @JavascriptInterface
        public void importBackup() {
            runOnUiThread(() -> {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("application/json");
                startActivityForResult(intent, IMPORT_BACKUP_REQUEST);
            });
        }

        @JavascriptInterface
        public void cancelReminder(String orderId) {
            WorkManager.getInstance(MainActivity.this).cancelUniqueWork("order-reminder-" + orderId);
        }

        @JavascriptInterface
        public void scheduleReminder(String requestJson) {
            try {
                JSONObject request = new JSONObject(requestJson);
                long orderId = request.getLong("id");
                long scheduledAt = request.getLong("scheduledAt");
                int reminderMin = request.optInt("reminderMin", 120);
                long runAt = scheduledAt - TimeUnit.MINUTES.toMillis(reminderMin);
                long delay = Math.max(0, runAt - System.currentTimeMillis());
                Data input = new Data.Builder()
                        .putLong("orderId", orderId)
                        .putString("client", request.optString("client", "Клиент"))
                        .putString("route", request.optString("route", "Предстоящий заказ"))
                        .build();
                OneTimeWorkRequest work = new OneTimeWorkRequest.Builder(ReminderWorker.class)
                        .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                        .setInputData(input)
                        .build();
                WorkManager.getInstance(MainActivity.this).enqueueUniqueWork(
                        "order-reminder-" + orderId,
                        ExistingWorkPolicy.REPLACE,
                        work);
            } catch (Exception error) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "Не удалось установить напоминание", Toast.LENGTH_LONG).show());
            }
        }

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
        public void getServiceStatus() {
            executor.execute(() -> sendResult("serviceStatus", serviceStatus()));
        }

        @JavascriptInterface
        public void testSavedKeys() {
            executor.execute(() -> {
                JSONObject result;
                try {
                    String dadata = secureStore.get("dadata");
                    String ors = secureStore.get("ors");
                    if (dadata.isEmpty() || ors.isEmpty()) {
                        result = new JSONObject().put("ok", false).put("message", "Сначала сохраните оба ключа");
                    } else {
                        result = apiClient.testServices(dadata, ors);
                    }
                } catch (Exception error) {
                    result = errorResult("Не удалось проверить сохранённые ключи");
                }
                sendResult("services", result);
            });
        }

        @JavascriptInterface
        public void deleteServiceKeys() {
            executor.execute(() -> {
                secureStore.remove("dadata");
                secureStore.remove("ors");
                JSONObject result = serviceStatus();
                try {
                    result.put("message", "Ключи удалены с телефона");
                } catch (Exception ignored) { }
                sendResult("serviceStatus", result);
            });
        }

        @JavascriptInterface
        public void optimizeRoute(String requestJson) {
            executor.execute(() -> {
                JSONObject result;
                String context = "saved";
                try {
                    context = new JSONObject(requestJson).optString("context", "saved");
                    String dadata = secureStore.get("dadata");
                    String ors = secureStore.get("ors");
                    if (dadata.isEmpty() || ors.isEmpty()) {
                        result = new JSONObject()
                                .put("context", context)
                                .put("ok", false)
                                .put("message", "Сначала подключите сервисы в настройках");
                    } else {
                        result = apiClient.optimize(requestJson, dadata, ors);
                    }
                } catch (Exception error) {
                    result = new JSONObject();
                    try {
                        result.put("context", context).put("ok", false).put("message", "Не удалось прочитать ключи");
                    } catch (Exception ignored) { }
                }
                sendResult("route", result);
            });
        }

        private JSONObject serviceStatus() {
            JSONObject result = new JSONObject();
            try {
                boolean dadata = !secureStore.get("dadata").isEmpty();
                boolean ors = !secureStore.get("ors").isEmpty();
                result.put("ok", true).put("dadata", dadata).put("ors", ors);
            } catch (Exception error) {
                return errorResult("Не удалось прочитать состояние ключей");
            }
            return result;
        }

        private JSONObject errorResult(String message) {
            JSONObject result = new JSONObject();
            try {
                result.put("ok", false).put("message", message);
            } catch (Exception ignored) { }
            return result;
        }
    }
}
