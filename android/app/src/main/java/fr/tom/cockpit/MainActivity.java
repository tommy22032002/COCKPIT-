package fr.tom.cockpit;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricPrompt;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.util.Base64;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {
    static final String HOST = "tommy22032002.github.io";
    static final String BASE = "https://" + HOST + "/COCKPIT-/";
    private static final int REQ_FILE = 11;
    private static final int REQ_NOTIF = 12;

    private WebView web;
    private ValueCallback<Uri[]> fileCallback;
    private boolean loaded = false;
    private boolean pageReady = false;
    private String pendingHash = null;
    private String pendingShare = "[]";

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#0C0F15"));
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setSupportZoom(false);
        s.setTextZoom(100);

        web.addJavascriptInterface(new Bridge(), "CockpitNative");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if (HOST.equals(u.getHost())) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) { }
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                pageReady = true;
                deliverShare();
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE);
                try {
                    startActivityForResult(Intent.createChooser(i, "Choisir un fichier"), REQ_FILE);
                } catch (Exception e) {
                    fileCallback = null;
                    return false;
                }
                return true;
            }
        });

        handleIntent(getIntent());
        askNotificationPermissionOnce();

        if (Prefs.bio(this) && canAuthenticate()) authenticate();
        else load();

        checkForUpdate();
    }

    // ---------- verrouillage par empreinte ----------
    private boolean canAuthenticate() {
        try {
            BiometricManager bm = getSystemService(BiometricManager.class);
            if (bm == null) return false;
            if (Build.VERSION.SDK_INT >= 30) {
                return bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG
                        | BiometricManager.Authenticators.DEVICE_CREDENTIAL) == BiometricManager.BIOMETRIC_SUCCESS;
            }
            return bm.canAuthenticate() == BiometricManager.BIOMETRIC_SUCCESS;
        } catch (Exception e) {
            return false;
        }
    }

    @SuppressWarnings("deprecation")
    private void authenticate() {
        BiometricPrompt.Builder b = new BiometricPrompt.Builder(this)
                .setTitle("Cockpit PEA")
                .setSubtitle("Déverrouille avec ton empreinte");
        if (Build.VERSION.SDK_INT >= 30) {
            b.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG
                    | BiometricManager.Authenticators.DEVICE_CREDENTIAL);
        } else {
            b.setDeviceCredentialAllowed(true);
        }
        BiometricPrompt prompt = b.build();
        prompt.authenticate(new CancellationSignal(), getMainExecutor(), new BiometricPrompt.AuthenticationCallback() {
            @Override
            public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                load();
            }

            @Override
            public void onAuthenticationError(int errorCode, CharSequence errString) {
                showLocked();
            }
        });
    }

    private void showLocked() {
        TextView t = new TextView(this);
        t.setText("Cockpit verrouillé\n\nTouche l'écran pour réessayer");
        t.setTextColor(Color.parseColor("#E8ECF3"));
        t.setTextSize(18);
        t.setGravity(android.view.Gravity.CENTER);
        t.setBackgroundColor(Color.parseColor("#0C0F15"));
        t.setOnClickListener(v -> {
            setContentView(web);
            authenticate();
        });
        setContentView(t);
    }

    private void load() {
        if (loaded) return;
        loaded = true;
        if (web.getParent() == null) setContentView(web);
        String url = BASE + (pendingHash != null ? "#" + pendingHash : "");
        pendingHash = null;
        web.loadUrl(url);
    }

    // ---------- ouverture depuis un widget, une notification ou « Partager » ----------
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    @SuppressWarnings("deprecation")
    private void handleIntent(Intent intent) {
        if (intent == null) return;
        String hash = intent.getStringExtra("hash");
        if (hash != null && hash.matches("[a-z]+")) {
            if (pageReady) web.evaluateJavascript("window.go&&window.R&&R['" + hash + "']&&go('" + hash + "')", null);
            else pendingHash = hash;
        }
        String action = intent.getAction();
        List<Uri> uris = new ArrayList<>();
        if (Intent.ACTION_SEND.equals(action)) {
            Uri u = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (u != null) uris.add(u);
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            ArrayList<Uri> list = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (list != null) uris.addAll(list);
        }
        if (!uris.isEmpty()) {
            pendingShare = filesToJson(uris);
            deliverShare();
        }
    }

    private void deliverShare() {
        if (pageReady && pendingShare != null && !"[]".equals(pendingShare)) {
            web.evaluateJavascript("window.__nativeShare&&window.__nativeShare()", null);
        }
    }

    private String filesToJson(List<Uri> uris) {
        JSONArray arr = new JSONArray();
        ContentResolver cr = getContentResolver();
        for (Uri u : uris) {
            try {
                String name = "document";
                try (Cursor c = cr.query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                    if (c != null && c.moveToFirst()) name = c.getString(0);
                }
                String type = cr.getType(u);
                byte[] data = readAll(cr.openInputStream(u));
                JSONObject o = new JSONObject();
                o.put("name", name);
                o.put("type", type == null ? "" : type);
                o.put("b64", Base64.encodeToString(data, Base64.NO_WRAP));
                arr.put(o);
            } catch (Exception ignored) { }
        }
        return arr.toString();
    }

    private static byte[] readAll(InputStream in) throws Exception {
        if (in == null) return new byte[0];
        try (InputStream is = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = is.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    // ---------- choix de fichiers dans la page ----------
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_FILE || fileCallback == null) return;
        Uri[] result = null;
        if (resultCode == RESULT_OK && data != null) {
            ClipData clip = data.getClipData();
            if (clip != null) {
                result = new Uri[clip.getItemCount()];
                for (int i = 0; i < clip.getItemCount(); i++) result[i] = clip.getItemAt(i).getUri();
            } else if (data.getData() != null) {
                result = new Uri[]{data.getData()};
            }
        }
        fileCallback.onReceiveValue(result);
        fileCallback = null;
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onPause() {
        super.onPause();
        Widgets.updateAll(this);
    }

    // ---------- notifications ----------
    private void askNotificationPermissionOnce() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                && !Prefs.sp(this).getBoolean("askedNotif", false)) {
            Prefs.sp(this).edit().putBoolean("askedNotif", true).apply();
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
        }
    }

    // ---------- mise à jour de l'appli ----------
    private void checkForUpdate() {
        new Thread(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(BASE + "apk-version.json?t=" + System.currentTimeMillis()).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                if (c.getResponseCode() != 200) return;
                JSONObject o = new JSONObject(new String(readAll(c.getInputStream()), "UTF-8"));
                int remote = o.optInt("versionCode", 0);
                if (remote > BuildConfig.VERSION_CODE) {
                    runOnUiThread(() -> new AlertDialog.Builder(this)
                            .setTitle("Mise à jour disponible")
                            .setMessage("Une nouvelle version de l'appli Cockpit (" + o.optString("versionName", "") + ") est prête. Tes données restent en place.")
                            .setPositiveButton("Télécharger", (d, w) -> {
                                try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(BASE + "cockpit.apk"))); } catch (Exception ignored) { }
                            })
                            .setNegativeButton("Plus tard", null)
                            .show());
                }
            } catch (Exception ignored) { }
        }).start();
    }

    // ---------- pont entre la page et Android ----------
    private class Bridge {
        @JavascriptInterface public boolean isNative() { return true; }

        @JavascriptInterface public String version() { return BuildConfig.VERSION_NAME; }

        @JavascriptInterface public void updateWidget(String json) {
            Prefs.setWidgetJson(MainActivity.this, json);
            Widgets.updateAll(MainActivity.this);
        }

        @JavascriptInterface public void setReminders(String json) {
            Prefs.setReminders(MainActivity.this, json);
            Reminders.schedule(MainActivity.this);
        }

        @JavascriptInterface public String takeShare() {
            String s = pendingShare;
            pendingShare = "[]";
            return s == null ? "[]" : s;
        }

        @JavascriptInterface public boolean getBio() { return Prefs.bio(MainActivity.this); }

        @JavascriptInterface public void setBio(boolean on) { Prefs.setBio(MainActivity.this, on); }

        @JavascriptInterface public void requestNotifications() {
            runOnUiThread(() -> {
                if (Build.VERSION.SDK_INT >= 33
                        && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
                }
            });
        }

        @JavascriptInterface public void testNotification() {
            Reminders.show(MainActivity.this, "test", "Cockpit PEA", "Les rappels fonctionnent. Ils arriveront ici à 9 h le jour prévu.", "");
        }

        @JavascriptInterface public boolean saveFile(String name, String b64, String mime) {
            try {
                byte[] data = Base64.decode(b64, Base64.DEFAULT);
                ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
                v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (uri == null) return false;
                try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                    if (os == null) return false;
                    os.write(data);
                }
                runOnUiThread(() -> Toast.makeText(MainActivity.this, name + " enregistré dans Téléchargements", Toast.LENGTH_LONG).show());
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        @JavascriptInterface public void openExternal(String url) {
            runOnUiThread(() -> {
                try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); } catch (Exception ignored) { }
            });
        }
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            web.setVisibility(View.GONE);
            web.destroy();
        }
        super.onDestroy();
    }
}
