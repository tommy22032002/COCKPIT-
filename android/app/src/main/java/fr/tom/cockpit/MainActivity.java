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
    private static final int REQ_VOICE = 12;
    private android.speech.tts.TextToSpeech tts;
    private boolean ttsReady = false;
    // ---------- mains libres : écoute du mot de réveil quand l'appli est ouverte ----------
    private static final int REQ_MIC = 13;
    private android.speech.SpeechRecognizer sr;
    private boolean wakeOn = false, speaking = false, resumed = false;
    private final android.os.Handler wh = new android.os.Handler(android.os.Looper.getMainLooper());

    private void wakeLoop() {
        if (!wakeOn || speaking || !resumed) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return;
        try {
            if (sr == null) {
                if (!android.speech.SpeechRecognizer.isRecognitionAvailable(this)) { js("window.__wakeErr&&window.__wakeErr('indisponible')"); wakeOn = false; return; }
                sr = android.speech.SpeechRecognizer.createSpeechRecognizer(this);
                sr.setRecognitionListener(new android.speech.RecognitionListener() {
                    @Override public void onResults(Bundle b) {
                        ArrayList<String> l = b.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION);
                        if (l != null && !l.isEmpty()) js("window.__heard&&window.__heard(" + JSONObject.quote(l.get(0)) + ")");
                        wh.postDelayed(() -> wakeLoop(), 200);
                    }
                    @Override public void onError(int e) {
                        int d = (e == android.speech.SpeechRecognizer.ERROR_RECOGNIZER_BUSY || e == android.speech.SpeechRecognizer.ERROR_CLIENT) ? 1500 : (e == android.speech.SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ? -1 : 300);
                        if (d > 0) wh.postDelayed(() -> wakeLoop(), d);
                    }
                    @Override public void onReadyForSpeech(Bundle p) { }
                    @Override public void onBeginningOfSpeech() { }
                    @Override public void onRmsChanged(float v) { }
                    @Override public void onBufferReceived(byte[] b) { }
                    @Override public void onEndOfSpeech() { }
                    @Override public void onPartialResults(Bundle b) { }
                    @Override public void onEvent(int t, Bundle b) { }
                });
            }
            Intent i = new Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            i.putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            i.putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, "fr-FR");
            i.putExtra(android.speech.RecognizerIntent.EXTRA_MAX_RESULTS, 1);
            i.putExtra(android.speech.RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1400L);
            sr.startListening(i);
        } catch (Exception e) { wh.postDelayed(() -> wakeLoop(), 2000); }
    }

    private String voiceName = "";
    private float vPitch = 0.88f, vRate = 1.0f;

    private void initTts(final String first) {
        tts = new android.speech.tts.TextToSpeech(MainActivity.this, st -> {
            ttsReady = st == android.speech.tts.TextToSpeech.SUCCESS;
            if (!ttsReady) return;
            tts.setLanguage(java.util.Locale.FRANCE);
            tts.setOnUtteranceProgressListener(new android.speech.tts.UtteranceProgressListener() {
                @Override public void onStart(String id) { wh.post(() -> { speaking = true; wakePause(); }); }
                @Override public void onDone(String id) { wh.postDelayed(() -> { speaking = false; wakeLoop(); }, 350); }
                @Override public void onError(String id) { wh.post(() -> { speaking = false; wakeLoop(); }); }
            });
            applyVoice();
            if (first != null && !first.isEmpty()) { speaking = true; wakePause(); tts.speak(first, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "cockpit"); }
        });
    }

    /** Applique la voix choisie ; sinon une voix d'homme française si le téléphone en a une. */
    private void applyVoice() {
        if (tts == null || !ttsReady) return;
        try {
            android.speech.tts.Voice pick = null, male = null;
            for (android.speech.tts.Voice v : tts.getVoices()) {
                if (v.getLocale() == null || !"fr".equals(v.getLocale().getLanguage())) continue;
                String n = v.getName().toLowerCase(java.util.Locale.ROOT);
                if (!voiceName.isEmpty() && v.getName().equals(voiceName)) pick = v;
                boolean m = n.contains("frb") || n.contains("frd") || n.contains("male") && !n.contains("female") || n.contains("smtm") || n.contains("#male");
                if (m && (male == null || (!v.isNetworkConnectionRequired() && male.isNetworkConnectionRequired()))) male = v;
            }
            if (pick == null && voiceName.isEmpty()) pick = male;
            if (pick != null) tts.setVoice(pick);
            tts.setPitch(vPitch);
            tts.setSpeechRate(vRate);
        } catch (Exception ignored) { }
    }

    private void wakePause() { try { if (sr != null) sr.cancel(); } catch (Exception ignored) { } }

    private void js(String code) { if (web != null) web.evaluateJavascript(code, null); }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_MIC) {
            boolean ok = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            if (ok) wakeLoop(); else { wakeOn = false; js("window.__wakeErr&&window.__wakeErr('refus')"); }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        if (wakeOn) wh.postDelayed(() -> wakeLoop(), 400);
    }
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
            if (pageReady) web.evaluateJavascript("(function(h){if(window.__nav)__nav(h);else location.hash=h})('" + hash + "')", null);
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
        if (requestCode == REQ_VOICE) {
            String txt = "";
            if (resultCode == RESULT_OK && data != null) {
                ArrayList<String> r = data.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS);
                if (r != null && !r.isEmpty()) txt = r.get(0);
            }
            if (web != null) web.evaluateJavascript("window.__voice&&window.__voice(" + JSONObject.quote(txt) + ")", null);
            return;
        }
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
        resumed = false;
        wakePause();
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

        /** Cours en direct (Yahoo Finance) : appelé par le cockpit, hors du fil principal. */
        @JavascriptInterface public String quotes(String symsJson) {
            try { return Quotes.fetchAll(new org.json.JSONArray(symsJson)).toString(); } catch (Exception e) { return "{}"; }
        }

        @JavascriptInterface public String fetchText(String url) { return Quotes.fetchText(url); }

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

        /** Impression / enregistrement en PDF de la page affichée (rapport mensuel). */
        @JavascriptInterface public void print(final String title) {
            runOnUiThread(() -> {
                try {
                    android.print.PrintManager pm = (android.print.PrintManager) getSystemService(android.content.Context.PRINT_SERVICE);
                    String t = (title == null || title.isEmpty()) ? "Cockpit" : title;
                    pm.print(t, web.createPrintDocumentAdapter(t), new android.print.PrintAttributes.Builder()
                        .setMediaSize(android.print.PrintAttributes.MediaSize.ISO_A4).build());
                } catch (Exception e) { }
            });
        }

        /** Assistant IA : requête POST vers Groq ou Gemini uniquement (pas de blocage CORS dans l'appli). */
        /** Version asynchrone : la réponse revient dans window.__httpCb(id, json), sans bloquer la page. */
        @JavascriptInterface public void httpPostAsync(final String id, final String url, final String headersJson, final String body) {
            new Thread(() -> {
                final String r = httpPost(url, headersJson, body);
                runOnUiThread(() -> { if (web != null) web.evaluateJavascript("window.__httpCb&&window.__httpCb(" + JSONObject.quote(id) + "," + JSONObject.quote(r) + ")", null); });
            }).start();
        }

        @JavascriptInterface public String httpPost(String url, String headersJson, String body) {
            JSONObject res = new JSONObject();
            try {
                URL u = new URL(url);
                String h = u.getHost();
                if (!"https".equals(u.getProtocol()) || !(h.equals("api.groq.com") || h.equals("generativelanguage.googleapis.com") || h.equals("api.anthropic.com") || h.equals("api.github.com"))) { res.put("status", 0); res.put("body", "hôte refusé"); return res.toString(); }
                HttpURLConnection c = (HttpURLConnection) u.openConnection();
                c.setConnectTimeout(15000); c.setReadTimeout(90000);
                boolean get = body == null || body.isEmpty();
                c.setRequestMethod(get ? "GET" : "POST");
                JSONObject hd = new JSONObject(headersJson == null || headersJson.isEmpty() ? "{}" : headersJson);
                java.util.Iterator<String> it = hd.keys();
                while (it.hasNext()) { String k = it.next(); c.setRequestProperty(k, hd.getString(k)); }
                if (!get) { c.setDoOutput(true); OutputStream o = c.getOutputStream(); o.write(body.getBytes("UTF-8")); o.close(); }
                int code = c.getResponseCode();
                InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                if (in != null) { byte[] buf = new byte[8192]; int n; while ((n = in.read(buf)) > 0 && out.size() < 4_000_000) out.write(buf, 0, n); in.close(); }
                res.put("status", code); res.put("body", out.toString("UTF-8"));
            } catch (Exception e) { try { res.put("status", 0); res.put("body", String.valueOf(e.getMessage())); } catch (Exception ignored) { } }
            return res.toString();
        }

        /** Dictée vocale : reconnaissance d'Android, en français. Le texte revient dans window.__voice. */
        @JavascriptInterface public void listen() {
            runOnUiThread(() -> {
                try {
                    Intent i = new Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
                    i.putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
                    i.putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, "fr-FR");
                    i.putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Parle à ton Cockpit");
                    startActivityForResult(i, REQ_VOICE);
                } catch (Exception e) {
                    if (web != null) web.evaluateJavascript("window.__voiceErr&&window.__voiceErr('indisponible')", null);
                }
            });
        }

        /** Voix choisie (nom de voix Android), hauteur et vitesse. */
        @JavascriptInterface public void setVoice(final String name, final float pitch, final float rate) {
            runOnUiThread(() -> { voiceName = name == null ? "" : name; vPitch = pitch > 0 ? pitch : 1f; vRate = rate > 0 ? rate : 1f; applyVoice(); });
        }

        /** Liste des voix françaises installées (JSON). Lance la synthèse si besoin : rappeler un peu plus tard. */
        @JavascriptInterface public String listVoices() {
            JSONArray out = new JSONArray();
            try {
                if (tts == null) { runOnUiThread(() -> initTts(null)); return out.toString(); }
                if (!ttsReady) return out.toString();
                for (android.speech.tts.Voice v : tts.getVoices()) {
                    if (v.getLocale() == null || !"fr".equals(v.getLocale().getLanguage())) continue;
                    JSONObject o = new JSONObject();
                    o.put("name", v.getName()); o.put("locale", v.getLocale().toString()); o.put("network", v.isNetworkConnectionRequired()); o.put("quality", v.getQuality());
                    out.put(o);
                }
            } catch (Exception ignored) { }
            return out.toString();
        }

        /** Lecture à voix haute (synthèse vocale du téléphone). */
        @JavascriptInterface public void speak(final String text) {
            runOnUiThread(() -> {
                try {
                    if (tts == null) initTts(text);
                    else if (ttsReady) { applyVoice(); speaking = true; wakePause(); tts.speak(text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "cockpit"); }
                } catch (Exception ignored) { }
            });
        }

        /** Mains libres : écoute en continu tant que l'appli est ouverte (le texte reconnu arrive dans window.__heard). */
        @JavascriptInterface public void wakeStart() {
            runOnUiThread(() -> {
                wakeOn = true;
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
                else wakeLoop();
            });
        }

        @JavascriptInterface public void wakeStop() { runOnUiThread(() -> { wakeOn = false; wakePause(); }); }

        @JavascriptInterface public void stopSpeak() { runOnUiThread(() -> { try { if (tts != null) tts.stop(); } catch (Exception ignored) { } if (speaking) { speaking = false; wh.postDelayed(() -> wakeLoop(), 300); } }); }

        @JavascriptInterface public void openExternal(String url) {
            runOnUiThread(() -> {
                try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); } catch (Exception ignored) { }
            });
        }
    }

    @Override
    protected void onDestroy() {
        try { if (tts != null) tts.shutdown(); } catch (Exception ignored) { }
        try { if (sr != null) sr.destroy(); } catch (Exception ignored) { }
        if (web != null) {
            web.setVisibility(View.GONE);
            web.destroy();
        }
        super.onDestroy();
    }
}
