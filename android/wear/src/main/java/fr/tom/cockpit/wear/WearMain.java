package fr.tom.cockpit.wear;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import android.speech.tts.TextToSpeech;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.google.android.gms.wearable.MessageClient;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.Wearable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Locale;

/** Écran de la montre : le PEA en détail, actualiser, et parler à Jarvis (via le téléphone). */
public class WearMain extends Activity implements MessageClient.OnMessageReceivedListener {
    private static final int REQ_VOICE = 7;
    private LinearLayout box;
    private TextView jarvis;
    private ScrollView scroll;
    private TextToSpeech tts;
    private boolean ttsOk;

    private int px(float dp) { return Math.round(dp * getResources().getDisplayMetrics().density); }

    private TextView tv(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER_HORIZONTAL);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        box.addView(t);
        return t;
    }

    private Button btn(String s, int color, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextColor(0xFF0B0F17);
        b.setTextSize(14);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(px(24));
        b.setBackground(bg);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(44));
        lp.topMargin = px(8);
        box.addView(b, lp);
        return b;
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFF000000);
        scroll.setFillViewport(true);
        box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int w = getResources().getDisplayMetrics().widthPixels;
        box.setPadding(w / 7, w / 9, w / 7, w / 5);
        scroll.addView(box);
        setContentView(scroll);
        tts = new TextToSpeech(this, s -> { ttsOk = s == TextToSpeech.SUCCESS; if (ttsOk) tts.setLanguage(Locale.FRANCE); });
        render();
    }

    private void render() {
        box.removeAllViews();
        JSONObject d = Pea.get(this);
        tv("PEA", 13, Pea.MUTED, false);
        if (d == null) {
            tv("—", 30, Pea.INK, true);
            tv("Ouvre le Cockpit sur ton téléphone : la montre recevra ton PEA automatiquement.", 13, Pea.MUTED, false);
        } else {
            boolean priv = d.optBoolean("privacy", false);
            double day = d.optDouble("dayAbs", 0);
            int c = day >= 0 ? Pea.GAIN : Pea.LOSS;
            tv(priv ? "•••• €" : Pea.eur(d.optDouble("value", 0)), 28, Pea.INK, true);
            tv(Pea.pct(d.optDouble("dayPct", 0)) + (priv ? "" : "  " + Pea.eur2(day)), 15, c, true);
            tv("Gain total " + (priv ? "••" : Pea.eur2(d.optDouble("gain", 0))), 13, Pea.MUTED, false);
            JSONArray L = d.optJSONArray("lines");
            if (L != null && L.length() > 0) {
                tv(" ", 6, Pea.MUTED, false);
                for (int i = 0; i < L.length(); i++) {
                    JSONObject l = L.optJSONObject(i);
                    if (l == null) continue;
                    double p = l.optDouble("px", 0), pr = l.optDouble("prev", 0);
                    String n = l.optString("n", "");
                    if (n.length() > 16) n = n.substring(0, 16) + "…";
                    tv(n + (pr > 0 ? "  " + Pea.pct(p / pr - 1) : ""), 12.5f, pr > 0 ? (p >= pr ? Pea.GAIN : Pea.LOSS) : Pea.INK, false);
                }
            }
            tv((Pea.marketOpen() ? "● Bourse ouverte · màj " : "Bourse fermée · màj ") + Pea.at(d), 11, Pea.MUTED, false);
        }
        btn("Actualiser", Pea.INK, v -> { toastLine("Actualisation…"); Pea.refreshAsync(this, true); });
        btn("🎙  Parler à Jarvis", Pea.ACCENT, v -> listen());
        jarvis = tv("", 13.5f, Pea.INK, false);
        jarvis.setGravity(Gravity.START);
    }

    private void toastLine(String s) { if (jarvis != null) { jarvis.setTextColor(Pea.MUTED); jarvis.setText(s); } }

    private void listen() {
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fr-FR");
        i.putExtra(RecognizerIntent.EXTRA_PROMPT, "Jarvis t'écoute");
        try { startActivityForResult(i, REQ_VOICE); } catch (Exception e) { toastLine("La dictée vocale n'est pas disponible sur cette montre."); }
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_VOICE || res != RESULT_OK || data == null) return;
        ArrayList<String> r = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
        if (r == null || r.isEmpty()) return;
        String q = r.get(0);
        toastLine("« " + q + " »\nJarvis réfléchit…");
        new Thread(() -> {
            boolean ok = Pea.askPhone(this, "/jarvis/ask", q);
            if (!ok) runOnUiThread(() -> toastLine("Téléphone injoignable : vérifie le Bluetooth."));
        }).start();
        scroll.postDelayed(() -> scroll.fullScroll(View.FOCUS_DOWN), 200);
    }

    @Override public void onMessageReceived(MessageEvent ev) {
        if (!"/jarvis/reply".equals(ev.getPath())) return;
        String a = new String(ev.getData(), StandardCharsets.UTF_8);
        runOnUiThread(() -> {
            if (jarvis != null) { jarvis.setTextColor(Pea.INK); jarvis.setText("Jarvis : " + a); }
            scroll.postDelayed(() -> scroll.fullScroll(View.FOCUS_DOWN), 200);
            if (ttsOk) tts.speak(a.replace("€", " euros"), TextToSpeech.QUEUE_FLUSH, null, "j");
        });
    }

    @Override protected void onResume() {
        super.onResume();
        Pea.onChange = () -> runOnUiThread(() -> { String keep = jarvis != null ? jarvis.getText().toString() : ""; render(); if (!keep.startsWith("Actualisation")) jarvis.setText(keep); });
        Wearable.getMessageClient(this).addListener(this);
        Pea.refreshAsync(this, false);
        scroll.requestFocus();
    }

    @Override protected void onPause() {
        super.onPause();
        Pea.onChange = null;
        Wearable.getMessageClient(this).removeListener(this);
    }

    @Override protected void onDestroy() {
        if (tts != null) tts.shutdown();
        super.onDestroy();
    }
}
