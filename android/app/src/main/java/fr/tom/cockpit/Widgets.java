package fr.tom.cockpit;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.view.View;
import android.widget.RemoteViews;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/** Affichage des widgets et actualisation des cours en arrière-plan. */
final class Widgets {
    private Widgets() {}

    static final int GAIN = Color.parseColor("#3FBF8F");
    static final int LOSS = Color.parseColor("#F0717A");
    static final int INK = Color.parseColor("#E8ECF3");
    static final int MUTED = Color.parseColor("#8B95A7");
    static final int ACCENT = Color.parseColor("#6EA8FE");
    static final int SOFT_GAIN = Color.parseColor("#8CCFB4");
    static final int SOFT_LOSS = Color.parseColor("#C9A3A8");

    static void updateAll(Context ctx) {
        AppWidgetManager m = AppWidgetManager.getInstance(ctx);
        int[] pea = m.getAppWidgetIds(new ComponentName(ctx, PeaWidget.class));
        int[] dash = m.getAppWidgetIds(new ComponentName(ctx, DashWidget.class));
        if (pea != null && pea.length > 0) render(ctx, m, pea, false);
        if (dash != null && dash.length > 0) render(ctx, m, dash, true);
    }

    static String eur(double v, boolean cents) {
        String s = String.format(Locale.FRANCE, cents ? "%,.2f" : "%,.0f", v);
        return s.replace(' ', ' ') + " €";
    }

    static String signedEur(double v) {
        return (v > 0 ? "+" : v < 0 ? "−" : "") + eur(Math.abs(v), true);
    }

    static String pct(double v) {
        return (v > 0 ? "+" : v < 0 ? "−" : "") + String.format(Locale.FRANCE, "%.2f", Math.abs(v * 100)) + " %";
    }

    static PendingIntent open(Context ctx, String hash, int code) {
        Intent i = new Intent(ctx, MainActivity.class);
        i.putExtra("hash", hash);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(ctx, code, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static PendingIntent refresh(Context ctx) {
        Intent i = new Intent(ctx, RefreshReceiver.class);
        i.setAction("fr.tom.cockpit.REFRESH");
        return PendingIntent.getBroadcast(ctx, 900, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static PendingIntent period(Context ctx, int widgetId, int per) {
        Intent i = new Intent(ctx, DashWidget.class);
        i.setAction(DashWidget.ACTION_PERIOD);
        i.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId);
        i.putExtra("per", per);
        return PendingIntent.getBroadcast(ctx, 1000 + widgetId * 4 + per, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static void render(Context ctx, AppWidgetManager m, int[] ids, boolean dash) {
        JSONObject d = null;
        try {
            String s = Prefs.widgetJson(ctx);
            if (s != null && s.length() > 2) d = new JSONObject(s);
        } catch (Exception ignored) { }

        for (int id : ids) {
            RemoteViews v = new RemoteViews(ctx.getPackageName(), dash ? R.layout.widget_dash : R.layout.widget_pea);
            v.setOnClickPendingIntent(R.id.w_root, open(ctx, dash ? "performance" : "portefeuille", dash ? 5 : 1));
            v.setOnClickPendingIntent(R.id.w_refresh, refresh(ctx));
            if (d == null) {
                v.setTextViewText(R.id.w_value, "—");
                v.setTextViewText(R.id.w_day, "Ouvre l'appli une fois");
                v.setTextColor(R.id.w_day, MUTED);
                v.setTextViewText(R.id.w_at, "");
            } else {
                boolean priv = d.optBoolean("privacy", false);
                double value = d.optDouble("value", 0), gain = d.optDouble("gain", 0);
                double dayAbs = d.optDouble("dayAbs", 0), dayPct = d.optDouble("dayPct", 0);
                v.setTextViewText(R.id.w_value, priv ? "•••• €" : eur(value, false));
                v.setTextViewText(R.id.w_day, (priv ? "••" : signedEur(dayAbs)) + "  " + pct(dayPct));
                v.setTextColor(R.id.w_day, dayAbs >= 0 ? GAIN : LOSS);
                v.setTextViewText(R.id.w_gain, "Gain total " + (priv ? "••" : signedEur(gain)));
                v.setTextViewText(R.id.w_at, ago(d.optString("at", "")));
                if (!dash) {
                    int[] area = Pie.area(ctx, m, id);
                    android.graphics.Bitmap pie = area == null ? null : Pie.draw(ctx, area, d, priv);
                    if (pie != null) {
                        v.setImageViewBitmap(R.id.w_pie, pie);
                        v.setViewVisibility(R.id.w_pie, View.VISIBLE);
                        v.setOnClickPendingIntent(R.id.w_pie, open(ctx, "allocation", 5));
                    } else {
                        v.setViewVisibility(R.id.w_pie, View.GONE);
                    }
                }
                if (dash) {
                    double ap = d.optDouble("apCur", 0), apT = Math.max(1, d.optDouble("apTgt", 50000));
                    v.setTextViewText(R.id.w_ap, (priv ? "••" : eur(ap, false)) + " / " + eur(apT, false));
                    v.setProgressBar(R.id.w_ap_bar, 100, (int) Math.min(100, ap / apT * 100), false);
                    double ms = d.optDouble("msCur", 0), msT = Math.max(1, d.optDouble("msTgt", 100000));
                    v.setTextViewText(R.id.w_ms, String.format(Locale.FRANCE, "%.0f\u00a0%%", Math.min(100, ms / msT * 100)));
                    v.setProgressBar(R.id.w_ms_bar, 100, (int) Math.min(100, ms / msT * 100), false);
                    v.setTextViewText(R.id.w_next, d.optString("next", ""));
                    String alert = d.optString("alert", "");
                    v.setTextViewText(R.id.w_alert, alert);
                    v.setViewVisibility(R.id.w_alert, alert.isEmpty() ? View.GONE : View.VISIBLE);
                    v.setOnClickPendingIntent(R.id.w_next, open(ctx, "achat", 3));
                    v.setOnClickPendingIntent(R.id.w_ap_row, open(ctx, "plan", 7));
                    v.setOnClickPendingIntent(R.id.w_ms_row, open(ctx, "plan", 8));
                    v.setOnClickPendingIntent(R.id.w_chart, open(ctx, "performance", 4));

                    int per = Prefs.period(ctx, id);
                    int[] chips = {R.id.w_p0, R.id.w_p1, R.id.w_p2, R.id.w_p3};
                    for (int i = 0; i < 4; i++) {
                        v.setInt(chips[i], "setBackgroundResource", i == per ? R.drawable.chip_on : R.drawable.chip_off);
                        v.setTextColor(chips[i], i == per ? INK : MUTED);
                        v.setOnClickPendingIntent(chips[i], period(ctx, id, i));
                    }
                    Chart.Result c = Chart.draw(ctx, m, id, d.optJSONArray("hist"), per, priv);
                    if (c != null) {
                        v.setImageViewBitmap(R.id.w_chart, c.bmp);
                        v.setTextViewText(R.id.w_per, c.label);
                        v.setTextColor(R.id.w_per, MUTED);
                    } else {
                        v.setTextViewText(R.id.w_per, "Pas encore assez d'historique");
                        v.setTextColor(R.id.w_per, MUTED);
                    }
                }
            }
            m.updateAppWidget(id, v);
        }
    }

    static String ago(String iso) {
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
            f.setTimeZone(TimeZone.getTimeZone("UTC"));
            Date t = f.parse(iso.length() >= 19 ? iso.substring(0, 19) : iso);
            if (t == null) return "";
            SimpleDateFormat out = new SimpleDateFormat("dd/MM HH:mm", Locale.FRANCE);
            return "màj " + out.format(t);
        } catch (Exception e) {
            return "";
        }
    }

    // ---------- actualisation des cours (Alpha Vantage, 25 appels gratuits par jour) ----------
    static void refreshPrices(Context ctx, boolean force) {
        try {
            String s = Prefs.widgetJson(ctx);
            if (s == null || s.length() < 3) return;
            JSONObject d = new JSONObject(s);
            String key = d.optString("key", "");
            JSONArray lines = d.optJSONArray("lines");
            if (lines == null || lines.length() == 0) return;

            // Yahoo Finance : sans quota, donc toutes les 20 min pendant la Bourse (9 h – 17 h 45), sinon toutes les 3 h
            long now = System.currentTimeMillis();
            Calendar paris = Calendar.getInstance(TimeZone.getTimeZone("Europe/Paris"));
            int dow = paris.get(Calendar.DAY_OF_WEEK);
            int hm = paris.get(Calendar.HOUR_OF_DAY) * 60 + paris.get(Calendar.MINUTE);
            boolean open = dow != Calendar.SATURDAY && dow != Calendar.SUNDAY && hm >= 9 * 60 && hm <= 17 * 60 + 45;
            long gap = open ? 20L * 60_000L : 3L * 3600_000L;
            if (!force && now - Prefs.lastFetch(ctx) < gap) return;
            String day = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());

            double posValue = 0, dayAbs = 0, base = 0;
            int calls = 0;
            boolean anyOk = false;
            for (int i = 0; i < lines.length(); i++) {
                JSONObject l = lines.getJSONObject(i);
                double q = l.optDouble("q", 0), px = l.optDouble("px", 0), prev = 0;
                String sym = l.optString("s", "");
                if (!sym.isEmpty()) {
                    JSONObject quote = Quotes.fetch(Quotes.yahoo(sym));
                    if (quote == null && !key.isEmpty() && Prefs.quota(ctx, day) + calls < 24) { calls++; quote = fetchQuote(sym, key); }
                    if (quote != null) {
                        double p = quote.optDouble("p", 0);
                        if (p > 0) { px = p; anyOk = true; l.put("px", p); }
                        prev = quote.optDouble("prev", 0);
                    }
                }
                posValue += q * px;
                if (prev > 0) { dayAbs += q * (px - prev); base += q * prev; }
            }
            Prefs.addQuota(ctx, day, calls);
            Prefs.setLastFetch(ctx, now);
            if (!anyOk) return;

            double value = posValue + d.optDouble("cash", 0);
            double dayPct = base > 0 ? dayAbs / base : 0;
            d.put("value", value);
            d.put("gain", value - d.optDouble("verse", value));
            d.put("dayAbs", dayAbs);
            d.put("dayPct", dayPct);
            JSONArray hist = d.optJSONArray("hist");
            if (hist != null) {
                JSONArray pt = new JSONArray().put(day).put(Math.round(value * 100) / 100.0).put(d.optDouble("verse", 0));
                JSONArray last = hist.length() > 0 ? hist.optJSONArray(hist.length() - 1) : null;
                if (last != null && day.equals(last.optString(0))) hist.put(hist.length() - 1, pt); else hist.put(pt);
            }
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
            f.setTimeZone(TimeZone.getTimeZone("UTC"));
            d.put("at", f.format(new Date()));
            Prefs.setWidgetJson(ctx, d.toString());
            updateAll(ctx);

            if (dayPct <= -0.03 && !day.equals(Prefs.lastDropAlert(ctx))) {
                Prefs.setLastDropAlert(ctx, day);
                Reminders.show(ctx, "drop-" + day, "Marché en baisse : " + pct(dayPct),
                        "Ton PEA baisse aujourd'hui. Pas de panique : ton DCA achètera moins cher.", "achat");
            }
        } catch (Exception ignored) { }
    }

    private static JSONObject fetchQuote(String sym, String key) {
        try {
            URL u = new URL("https://www.alphavantage.co/query?function=GLOBAL_QUOTE&symbol="
                    + URLEncoder.encode(sym, "UTF-8") + "&apikey=" + URLEncoder.encode(key, "UTF-8"));
            HttpURLConnection c = (HttpURLConnection) u.openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(10000);
            if (c.getResponseCode() != 200) return null;
            InputStream in = c.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close();
            JSONObject g = new JSONObject(out.toString("UTF-8")).optJSONObject("Global Quote");
            if (g == null) return null;
            JSONObject r = new JSONObject();
            r.put("p", Double.parseDouble(g.optString("05. price", "0")));
            r.put("prev", Double.parseDouble(g.optString("08. previous close", "0")));
            return r;
        } catch (Exception e) {
            return null;
        }
    }
}
