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

    /** Télécharge un texte (flux d'actu, point du jour) : seulement depuis des sites autorisés. */
    static String fetchText(String url) {
        try {
            URL u = new URL(url);
            String h = u.getHost();
            if (!"https".equals(u.getProtocol()) || !(h.equals("news.google.com") || h.equals("tommy22032002.github.io"))) return "";
            HttpURLConnection c = (HttpURLConnection) u.openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(15000);
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) Cockpit");
            if (c.getResponseCode() != 200) return "";
            InputStream in = c.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0 && out.size() < 2_000_000) out.write(buf, 0, n);
            in.close();
            return out.toString("UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    /** Nouveau point du jour publié ? Notification une fois par jour, à partir de 7 h. */
    static void checkBrief(android.content.Context ctx) {
        try {
            java.util.Calendar paris = java.util.Calendar.getInstance(TimeZone.getTimeZone("Europe/Paris"));
            if (paris.get(java.util.Calendar.HOUR_OF_DAY) < 7) return;
            String s = fetchText("https://tommy22032002.github.io/COCKPIT-/journal/brief.json?t=" + System.currentTimeMillis());
            if (s.isEmpty()) return;
            JSONObject b = new JSONObject(s);
            String date = b.optString("date", "");
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            f.setTimeZone(TimeZone.getTimeZone("Europe/Paris"));
            if (!date.equals(f.format(new Date())) || date.equals(Prefs.lastBrief(ctx))) return;
            Prefs.setLastBrief(ctx, date);
            StringBuilder say = new StringBuilder(b.optString("title", ""));
            org.json.JSONArray sm = b.optJSONArray("summary");
            if (sm != null) for (int i = 0; i < sm.length() && i < 3; i++) say.append(". ").append(sm.optString(i));
            Object fy = b.opt("forYou");
            if (fy instanceof String) say.append(". Pour toi : ").append(fy);
            JN.post(ctx, ("brief-" + date).hashCode(), null, "Le point du jour", b.optString("title", "Ton résumé des marchés est prêt."), "journal", null, true, say.toString(), false);
        } catch (Exception ignored) { }
    }
}
