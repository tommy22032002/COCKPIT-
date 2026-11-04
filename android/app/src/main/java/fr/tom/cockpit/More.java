package fr.tom.cockpit;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

/** Partie basse du widget PEA quand on l'agrandit : courbe, lignes, objectifs, plaisir du mois, prochain achat. Plus il est haut, plus il en montre. */
final class More {
    private More() {}
    static final int INK = Color.parseColor("#E8ECF3"), MUTED = Color.parseColor("#8B95A7"), LINE = Color.parseColor("#2A3240"),
            GAIN = Color.parseColor("#3FBF8F"), LOSS = Color.parseColor("#F0717A"), ACC = Color.parseColor("#6EA8FE"), WARN = Color.parseColor("#F2B35B");

    static String eur(double v) { return String.format(Locale.FRANCE, "%,.0f €", v).replace(' ', ' '); }
    static String seur(double v) { return (v > 0 ? "+" : v < 0 ? "−" : "") + String.format(Locale.FRANCE, "%,.2f €", Math.abs(v)).replace(' ', ' '); }
    static String spct(double v) { return (v > 0 ? "+" : v < 0 ? "−" : "") + String.format(Locale.FRANCE, "%.1f %%", Math.abs(v * 100)); }

    static Bitmap draw(Context ctx, int wDp, int hDp, JSONObject d, boolean priv) {
        if (wDp < 120 || hDp < 40) return null;
        float dens = ctx.getResources().getDisplayMetrics().density, sc = dens;
        while (wDp * sc * hDp * sc > 1_100_000f && sc > 1f) sc -= 0.25f;
        int W = Math.round(wDp * sc), H = Math.round(hDp * sc);
        Bitmap b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint t = new Paint(Paint.ANTI_ALIAS_FLAG), p = new Paint(Paint.ANTI_ALIAS_FLAG);
        float u = sc, y = 4 * u, pad = 2 * u;
        // 1. courbe de la valeur du PEA (30 derniers points)
        JSONArray hist = d.optJSONArray("hist");
        if (hist != null && hist.length() >= 3 && H - y > 70 * u) {
            float ch = Math.min(64 * u, (H - y) * 0.32f);
            int n = Math.min(45, hist.length()), s0 = hist.length() - n;
            double mn = Double.MAX_VALUE, mx = -Double.MAX_VALUE;
            double[] vs = new double[n], vv = new double[n];
            for (int i = 0; i < n; i++) { JSONArray q = hist.optJSONArray(s0 + i); vs[i] = q == null ? 0 : q.optDouble(1, 0); vv[i] = q == null ? 0 : q.optDouble(2, vs[i]); mn = Math.min(mn, Math.min(vs[i], vv[i])); mx = Math.max(mx, Math.max(vs[i], vv[i])); }
            if (mx - mn < 1) { mx += 1; mn -= 1; }
            t.setColor(MUTED); t.setTextSize(10 * u); c.drawText("Évolution · " + n + " derniers jours", pad, y + 10 * u, t);
            y += 14 * u;
            Path line = new Path(), fill = new Path(), vers = new Path();
            for (int i = 0; i < n; i++) {
                float x = pad + (W - 2 * pad) * i / (n - 1f);
                float yy = (float) (y + ch - (vs[i] - mn) / (mx - mn) * ch), yv = (float) (y + ch - (vv[i] - mn) / (mx - mn) * ch);
                if (i == 0) { line.moveTo(x, yy); fill.moveTo(x, y + ch); fill.lineTo(x, yy); vers.moveTo(x, yv); } else { line.lineTo(x, yy); fill.lineTo(x, yy); vers.lineTo(x, yv); }
            }
            fill.lineTo(W - pad, y + ch); fill.close();
            p.setStyle(Paint.Style.FILL); p.setColor(Color.argb(40, 110, 168, 254)); c.drawPath(fill, p);
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(1.2f * u); p.setColor(MUTED); p.setPathEffect(new android.graphics.DashPathEffect(new float[]{4 * u, 3 * u}, 0)); c.drawPath(vers, p); p.setPathEffect(null);
            p.setStrokeWidth(2 * u); p.setColor(ACC); c.drawPath(line, p);
            y += ch + 10 * u;
        }
        // 2. les lignes : valeur et plus-value
        JSONArray L = d.optJSONArray("lines");
        if (L != null && L.length() > 0 && H - y > 30 * u) {
            t.setColor(MUTED); t.setTextSize(10 * u); c.drawText("Lignes", pad, y + 10 * u, t);
            Paint r = new Paint(t); r.setTextAlign(Paint.Align.RIGHT); c.drawText("valeur · plus-value", W - pad, y + 10 * u, r);
            y += 16 * u;
            for (int i = 0; i < L.length() && H - y > 16 * u; i++) {
                JSONObject l = L.optJSONObject(i); if (l == null) continue;
                double v = l.optDouble("q", 0) * l.optDouble("px", 0), cost = l.optDouble("cost", 0), pv = cost > 0 ? v - cost : 0;
                p.setStyle(Paint.Style.FILL); p.setColor(Pie.color(l.optString("k"), i)); c.drawCircle(pad + 4 * u, y + 5 * u, 3.5f * u, p);
                t.setColor(INK); t.setTextSize(12 * u); c.drawText(l.optString("n"), pad + 12 * u, y + 9 * u, t);
                Paint rr = new Paint(t); rr.setTextAlign(Paint.Align.RIGHT);
                String pvs = priv ? "••" : (cost > 0 ? seur(pv) + "  " + spct(pv / cost) : "");
                rr.setColor(pv >= 0 ? GAIN : LOSS); rr.setTextSize(11 * u); c.drawText(pvs, W - pad, y + 9 * u, rr);
                float pvw = rr.measureText(pvs);
                rr.setColor(INK); rr.setTextSize(12 * u); c.drawText(priv ? "•••" : eur(v), W - pad - pvw - 10 * u, y + 9 * u, rr);
                y += 17 * u;
            }
            y += 6 * u;
        }
        // 3. objectifs
        y = bar(c, t, p, u, pad, W, H, y, "Objectif patrimoine", d.optDouble("msCur", 0), d.optDouble("msTgt", 0), ACC, priv);
        y = bar(c, t, p, u, pad, W, H, y, "Apport immobilier", d.optDouble("apCur", 0), d.optDouble("apTgt", 0), Color.parseColor("#B39BF0"), priv);
        // 4. plaisir du mois
        if (d.optBoolean("envOn", false)) {
            double sp = d.optDouble("envSpent", 0), cap = d.optDouble("envCap", 0);
            y = bar(c, t, p, u, pad, W, H, y, "Plaisir du mois", sp, cap, sp > cap ? LOSS : sp > cap * .8 ? WARN : GAIN, priv);
        }
        // 5. prochain achat et alerte
        String next = d.optString("next", ""), alert = d.optString("alert", "");
        if (!next.isEmpty() && H - y > 16 * u) { t.setColor(ACC); t.setTextSize(11 * u); c.drawText(fit(t, "› " + next, W - 2 * pad), pad, y + 10 * u, t); y += 16 * u; }
        if (!alert.isEmpty() && H - y > 16 * u) { t.setColor(WARN); t.setTextSize(11 * u); c.drawText(fit(t, "⚠ " + alert, W - 2 * pad), pad, y + 10 * u, t); y += 16 * u; }
        return b;
    }

    /** Largeur et hauteur du widget en dp. */
    static int[] size(Context ctx, android.appwidget.AppWidgetManager m, int id) {
        android.os.Bundle o = m.getAppWidgetOptions(id);
        boolean land = ctx.getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        int w = o.getInt(land ? android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH : android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0);
        int h = o.getInt(land ? android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT : android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0);
        return w > 0 && h > 0 ? new int[]{w, h} : null;
    }

    static float bar(Canvas c, Paint t, Paint p, float u, float pad, int W, int H, float y, String label, double cur, double tgt, int col, boolean priv) {
        if (tgt <= 0 || H - y < 26 * u) return y;
        double r = Math.max(0, Math.min(1, cur / tgt));
        t.setColor(INK); t.setTextSize(11 * u); c.drawText(label, pad, y + 10 * u, t);
        Paint rr = new Paint(t); rr.setTextAlign(Paint.Align.RIGHT); rr.setColor(MUTED);
        c.drawText((priv ? "" : eur(cur) + " / " + eur(tgt) + " · ") + String.format(Locale.FRANCE, "%.0f %%", r * 100), W - pad, y + 10 * u, rr);
        float by = y + 15 * u;
        p.setStyle(Paint.Style.FILL); p.setColor(LINE); c.drawRoundRect(new RectF(pad, by, W - pad, by + 5 * u), 3 * u, 3 * u, p);
        p.setColor(col); c.drawRoundRect(new RectF(pad, by, pad + (float) ((W - 2 * pad) * r), by + 5 * u), 3 * u, 3 * u, p);
        return y + 27 * u;
    }

    static String fit(Paint t, String s, float w) { if (t.measureText(s) <= w) return s; while (s.length() > 4 && t.measureText(s + "…") > w) s = s.substring(0, s.length() - 1); return s + "…"; }
}
