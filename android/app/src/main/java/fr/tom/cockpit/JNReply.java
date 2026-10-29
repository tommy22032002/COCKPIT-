package fr.tom.cockpit;

import android.app.RemoteInput;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Réponse tapée ou dictée dans une notification : Jarvis répond dans la notification. */
public class JNReply extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        Bundle r = RemoteInput.getResultsFromIntent(intent);
        CharSequence q0 = r == null ? null : r.getCharSequence(JN.KEY_Q);
        if (q0 == null || q0.toString().trim().isEmpty()) return;
        final String q = q0.toString().trim();
        final int nid = intent.getIntExtra("nid", 5000);
        final String about = intent.getStringExtra("ctx");
        final Context app = ctx.getApplicationContext();
        JN.post(app, nid, null, "Jarvis réfléchit…", "« " + q + " »", "assistant", null, false, null, false);
        final PendingResult pr = goAsync();
        new Thread(() -> {
            try {
                // petite mémoire de conversation (15 min)
                List<String[]> h = new ArrayList<>();
                try {
                    JSONObject m = new JSONObject(Prefs.sp(app).getString("jn_conv", "{}"));
                    if (System.currentTimeMillis() - m.optLong("at", 0) < 15 * 60_000L) {
                        JSONArray a = m.optJSONArray("h");
                        if (a != null) for (int i = 0; i < a.length(); i++) { JSONArray p = a.getJSONArray(i); h.add(new String[]{p.getString(0), p.getString(1)}); }
                    }
                } catch (Exception ignored) { }
                String full = (h.isEmpty() && about != null ? "(À propos de la notification « " + about + " ») " : "") + q;
                h.add(new String[]{"user", full});
                String a = Jarvis.ask(app, h);
                boolean err = a.startsWith("!");
                String txt = err ? a.substring(1) : a;
                boolean act = txt.contains("<action>");
                String shown = txt.replaceAll("(?s)<action>.*?</action>", "").replaceAll("\\*\\*", "").trim();
                if (!err) {
                    h.add(new String[]{"assistant", shown});
                    JSONArray arr = new JSONArray();
                    for (int i = Math.max(0, h.size() - 8); i < h.size(); i++) arr.put(new JSONArray().put(h.get(i)[0]).put(h.get(i)[1]));
                    Prefs.sp(app).edit().putString("jn_conv", new JSONObject().put("at", System.currentTimeMillis()).put("h", arr).toString()).apply();
                }
                JSONArray acts = null;
                if (act) acts = new JSONArray().put(new JSONObject().put("label", "Valider dans le Cockpit").put("open", true).put("data", new JSONObject().put("t", "ask").put("q", q).toString()));
                JN.post(app, nid, null, "Jarvis", "« " + q + " »\n\n" + shown, "assistant", acts, true, shown, false);
            } catch (Exception ignored) { } finally { pr.finish(); }
        }).start();
    }
}
