package fr.tom.cockpit;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/** Cours en direct via Yahoo Finance : gratuit, sans clé, sans quota. */
final class Quotes {
    private Quotes() {}

    /** Symbole Yahoo à partir d'un symbole Alpha Vantage (DCAM.PAR → DCAM.PA). */
    static String yahoo(String s) {
        if (s == null) return "";
        return s.endsWith(".PAR") ? s.substring(0, s.length() - 4) + ".PA" : s;
    }

    /**
     * Renvoie {p: dernier cours, d: jour du cours (AAAA-MM-JJ, heure de Paris), prev: clôture précédente,
     * closes: [[jour, clôture], …] sur 5 jours} ou null.
     */
    static JSONObject fetch(String sym) {
        try {
            URL u = new URL("https://query1.finance.yahoo.com/v8/finance/chart/" + URLEncoder.encode(sym, "UTF-8")
                    + "?range=5d&interval=1d");
            HttpURLConnection c = (HttpURLConnection) u.openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(10000);
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) Cockpit");
            if (c.getResponseCode() != 200) return null;
            InputStream in = c.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close();
            JSONObject r = new JSONObject(out.toString("UTF-8")).getJSONObject("chart").getJSONArray("result").getJSONObject(0);
            JSONObject meta = r.getJSONObject("meta");
            double p = meta.optDouble("regularMarketPrice", 0);
            if (!(p > 0)) return null;
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            f.setTimeZone(TimeZone.getTimeZone("Europe/Paris"));
            String day = f.format(new Date(meta.optLong("regularMarketTime", System.currentTimeMillis() / 1000) * 1000L));
            JSONArray closes = new JSONArray();
            double prev = 0;
            JSONArray ts = r.optJSONArray("timestamp");
            JSONArray cl = r.getJSONObject("indicators").getJSONArray("quote").getJSONObject(0).optJSONArray("close");
            if (ts != null && cl != null) {
                for (int i = 0; i < ts.length() && i < cl.length(); i++) {
                    if (cl.isNull(i)) continue;
                    String d = f.format(new Date(ts.getLong(i) * 1000L));
                    double v = cl.getDouble(i);
                    if (d.compareTo(day) < 0) { closes.put(new JSONArray().put(d).put(v)); prev = v; }
                }
            }
            JSONObject o = new JSONObject();
            o.put("p", p);
            o.put("d", day);
            o.put("prev", prev);
            o.put("closes", closes);
            return o;
        } catch (Exception e) {
            return null;
        }
    }

    /** Plusieurs symboles d'un coup : {"DCAM.PA": {...}, …}. */
    static JSONObject fetchAll(JSONArray syms) {
        JSONObject out = new JSONObject();
        for (int i = 0; i < syms.length(); i++) {
            String s = syms.optString(i, "");
            if (s.isEmpty()) continue;
            JSONObject q = fetch(s);
            try { if (q != null) out.put(s, q); } catch (Exception ignored) { }
        }
        return out;
    }
}
