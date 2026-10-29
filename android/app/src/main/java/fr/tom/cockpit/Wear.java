package fr.tom.cockpit;

import android.content.Context;

import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.Wearable;

import org.json.JSONArray;
import org.json.JSONObject;

/** Envoie le résumé du PEA à la montre (Wear OS) : seulement entre le téléphone et la montre de Tom. */
final class Wear {
    private Wear() {}
    private static String last = "";

    static void push(Context ctx) {
        try {
            String s = Prefs.widgetJson(ctx);
            if (s == null || s.length() < 3) return;
            JSONObject d = new JSONObject(s), o = new JSONObject();
            for (String k : new String[]{"value", "gain", "dayAbs", "dayPct", "cash", "verse"}) o.put(k, d.optDouble(k, 0));
            o.put("at", d.optString("at", ""));
            o.put("privacy", d.optBoolean("privacy", false));
            JSONArray L = d.optJSONArray("lines"), out = new JSONArray();
            if (L != null) for (int i = 0; i < L.length(); i++) {
                JSONObject l = L.getJSONObject(i);
                out.put(new JSONObject().put("n", l.optString("n", l.optString("k", ""))).put("s", l.optString("s", ""))
                        .put("q", l.optDouble("q", 0)).put("px", l.optDouble("px", 0)));
            }
            o.put("lines", out);
            String js = o.toString();
            if (js.equals(last)) return;
            last = js;
            PutDataMapRequest r = PutDataMapRequest.create("/pea");
            r.getDataMap().putString("d", js);
            r.getDataMap().putLong("t", System.currentTimeMillis());
            Wearable.getDataClient(ctx.getApplicationContext()).putDataItem(r.asPutDataRequest().setUrgent());
        } catch (Throwable ignored) { }
    }
}
