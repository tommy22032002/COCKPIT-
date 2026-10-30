package fr.tom.cockpit.wear;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;

import androidx.wear.tiles.TileService;
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester;

import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/** Données du PEA sur la montre : reçues du téléphone, puis cours actualisés directement depuis la montre. */
final class Pea {
    private Pea() {}

    static final int INK = 0xFFE8ECF3, MUTED = 0xFF8B95A7, GAIN = 0xFF3FBF8F, LOSS = 0xFFF0717A, ACCENT = 0xFF6EA8FE;
    static Runnable onChange;
    private static volatile boolean busy;

    static SharedPreferences sp(Context c) { return c.getSharedPreferences("pea", Context.MODE_PRIVATE); }

    static JSONObject get(Context c) {
        try { String s = sp(c).getString("d", ""); return s.length() > 2 ? new JSONObject(s) : null; } catch (Exception e) { return null; }
    }

    static void save(Context c, String json, boolean fromPhone) {
        SharedPreferences.Editor e = sp(c).edit().putString("d", json);
        if (fromPhone) e.putLong("phoneAt", System.currentTimeMillis());
        e.apply();
        changed(c);
    }

    static void changed(Context c) {
        Context a = c.getApplicationContext();
        try { TileService.getUpdater(a).requestUpdate(PeaTile.class); } catch (Throwable ignored) { }
        try { TileService.getUpdater(a).requestUpdate(BudgetTile.class); } catch (Throwable ignored) { }
        try { TileService.getUpdater(a).requestUpdate(SaisonTile.class); } catch (Throwable ignored) { }
        try { ComplicationDataSourceUpdateRequester.create(a, new ComponentName(a, PeaComplication.class)).requestUpdateAll(); } catch (Throwable ignored) { }
        Runnable r = onChange;
        if (r != null) r.run();
    }

    static boolean marketOpen() {
        Calendar p = Calendar.getInstance(TimeZone.getTimeZone("Europe/Paris"));
        int dow = p.get(Calendar.DAY_OF_WEEK), hm = p.get(Calendar.HOUR_OF_DAY) * 60 + p.get(Calendar.MINUTE);
        return dow != Calendar.SATURDAY && dow != Calendar.SUNDAY && hm >= 9 * 60 && hm <= 17 * 60 + 45;
    }

    /** Actualise les cours (Yahoo) depuis la montre, au plus toutes les 15 min en Bourse, toutes les 3 h sinon. */
    static void refreshAsync(Context ctx, boolean force) {
        Context c = ctx.getApplicationContext();
        long now = System.currentTimeMillis(), gap = marketOpen() ? 15 * 60_000L : 3 * 3600_000L;
        if (busy || (!force && now - sp(c).getLong("fetchAt", 0) < gap)) return;
        busy = true;
        new Thread(() -> {
            try {
                sp(c).edit().putLong("fetchAt", System.currentTimeMillis()).apply();
                askPhone(c, "/pea/sync", "");
                JSONObject d = get(c);
                if (d == null) return;
                JSONArray L = d.optJSONArray("lines");
                if (L == null || L.length() == 0) return;
                double val = 0, dayAbs = 0, base = 0;
                boolean ok = false;
                for (int i = 0; i < L.length(); i++) {
                    JSONObject l = L.getJSONObject(i);
                    double q = l.optDouble("q", 0), px = l.optDouble("px", 0);
                    double[] r = quote(l.optString("s", ""));
                    if (r != null) { px = r[0]; ok = true; l.put("px", px); l.put("prev", r[1]); if (r[1] > 0) { dayAbs += q * (px - r[1]); base += q * r[1]; } }
                    val += q * px;
                }
                if (!ok) return;
                val += d.optDouble("cash", 0);
                d.put("value", val);
                d.put("gain", val - d.optDouble("verse", val));
                d.put("dayAbs", dayAbs);
                d.put("dayPct", base > 0 ? dayAbs / base : 0);
                SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
                f.setTimeZone(TimeZone.getTimeZone("UTC"));
                d.put("at", f.format(new Date()));
                save(c, d.toString(), false);
            } catch (Exception ignored) {
            } finally { busy = false; }
        }).start();
    }

    /** {dernier cours, clôture de la veille} ou null. */
    static double[] quote(String s) {
        if (s == null || s.isEmpty()) return null;
        if (s.endsWith(".PAR")) s = s.substring(0, s.length() - 4) + ".PA";
        try {
            HttpURLConnection c = (HttpURLConnection) new URL("https://query1.finance.yahoo.com/v8/finance/chart/" + URLEncoder.encode(s, "UTF-8") + "?range=5d&interval=1d").openConnection();
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
            double prev = 0;
            JSONArray ts = r.optJSONArray("timestamp");
            JSONArray cl = r.getJSONObject("indicators").getJSONArray("quote").getJSONObject(0).optJSONArray("close");
            if (ts != null && cl != null) for (int i = 0; i < ts.length() && i < cl.length(); i++) {
                if (cl.isNull(i)) continue;
                if (f.format(new Date(ts.getLong(i) * 1000L)).compareTo(day) < 0) prev = cl.getDouble(i);
            }
            return new double[]{p, prev};
        } catch (Exception e) { return null; }
    }

    /** Message au téléphone (à appeler hors du fil principal). Renvoie false si aucun téléphone n'est joignable. */
    static boolean askPhone(Context c, String path, String text) {
        try {
            List<Node> nodes = Tasks.await(Wearable.getNodeClient(c).getConnectedNodes());
            boolean sent = false;
            for (Node n : nodes) { Tasks.await(Wearable.getMessageClient(c).sendMessage(n.getId(), path, text.getBytes(StandardCharsets.UTF_8))); sent = true; }
            return sent;
        } catch (Exception e) { return false; }
    }

    static String eur(double v) { return String.format(Locale.FRANCE, "%,.0f", v).replace(' ', ' ').replace(' ', ' ') + " €"; }
    static String eur2(double v) { return (v > 0 ? "+" : v < 0 ? "−" : "") + String.format(Locale.FRANCE, "%,.2f", Math.abs(v)).replace(' ', ' ').replace(' ', ' ') + " €"; }
    static String pct(double v) { return (v > 0 ? "+" : v < 0 ? "−" : "") + String.format(Locale.FRANCE, "%.2f", Math.abs(v * 100)) + " %"; }

    /** Ligne « mode saison » : salaire du mois et épargne prévue, ou mois de pause. */
    static String saison(JSONObject d) {
        JSONObject s = d == null ? null : d.optJSONObject("saison");
        if (s == null) return "";
        boolean priv = d.optBoolean("privacy", false);
        if (s.optBoolean("pause") || s.optBoolean("skip")) return "Mois sans versement : plan en pause";
        double in = s.optDouble("in", 0), ep = s.optDouble("ep", 0);
        if (in >= 800) return "Saison : salaire " + (priv ? "••" : eur(in)) + " · épargne " + (priv ? "••" : eur(ep));
        return ep > 0 ? "Épargne prévue ce mois : " + (priv ? "••" : eur(ep)) : "";
    }

    /** Objectif prioritaire (patrimoine financier), sinon le premier non atteint. */
    static JSONObject mainGoal(JSONObject d) {
        JSONArray g = d == null ? null : d.optJSONArray("goals");
        if (g == null) return null;
        JSONObject first = null;
        for (int i = 0; i < g.length(); i++) {
            JSONObject o = g.optJSONObject(i);
            if (o == null || o.optDouble("cur", 0) >= o.optDouble("tgt", 1)) continue;
            if ("Patrimoine".equals(o.optString("n"))) return o;
            if (first == null) first = o;
        }
        return first;
    }

    static String month(String ym) {
        if (ym == null || ym.length() < 7) return "";
        String[] M = {"janv.", "févr.", "mars", "avr.", "mai", "juin", "juil.", "août", "sept.", "oct.", "nov.", "déc."};
        try { return M[Integer.parseInt(ym.substring(5, 7)) - 1] + " " + ym.substring(0, 4); } catch (Exception e) { return ym; }
    }

    static String fh(double h) {
        long m = Math.round(Math.abs(h) * 60);
        return (h < 0 ? "−" : "") + (m / 60) + " h" + (m % 60 != 0 ? String.format(Locale.FRANCE, " %02d", m % 60) : "");
    }

    static String at(JSONObject d) {
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
            f.setTimeZone(TimeZone.getTimeZone("UTC"));
            String iso = d.optString("at", "");
            Date t = f.parse(iso.length() >= 19 ? iso.substring(0, 19) : iso);
            if (t == null) return "";
            boolean today = new SimpleDateFormat("yyyyMMdd", Locale.US).format(t).equals(new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date()));
            return (today ? "" : new SimpleDateFormat("dd/MM ", Locale.FRANCE).format(t)) + new SimpleDateFormat("HH:mm", Locale.FRANCE).format(t);
        } catch (Exception e) { return ""; }
    }
}
