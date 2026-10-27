package fr.tom.cockpit;

import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.os.Bundle;

import org.json.JSONArray;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Dessine la courbe du PEA (valeur et versements) pour le grand widget. */
final class Chart {
    private Chart() {}

    static final String[] PERIODS = {"1 mois", "3 mois", "1 an", "depuis le début"};
    static final int[] DAYS = {31, 92, 366, 100000};

    static final class Result {
        Bitmap bmp;
        String label;
        double delta;
    }

    /** Taille disponible pour la courbe, d'après la taille réelle du widget sur l'écran. */
    static int[] size(Context ctx, AppWidgetManager m, int id) {
        Bundle o = m.getAppWidgetOptions(id);
        boolean land = ctx.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        int wDp = o.getInt(land ? AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH : AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 300);
        int hDp = o.getInt(land ? AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT : AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 300);
        if (wDp <= 0) wDp = 300;
        if (hDp <= 0) hDp = 300;
        // en-tête, périodes, apport, objectif, prochain achat ≈ 250 dp
        int chartH = Math.max(70, hDp - 250);
        int chartW = Math.max(120, wDp - 28);
        return new int[]{chartW, chartH};
    }

    static Result draw(Context ctx, AppWidgetManager m, int id, JSONArray hist, int per, boolean priv) {
        if (hist == null || hist.length() < 2) return null;
        try {
            SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            Calendar from = Calendar.getInstance();
            from.add(Calendar.DAY_OF_YEAR, -DAYS[per]);
            String lim = iso.format(from.getTime());

            List<String> ds = new ArrayList<>();
            List<double[]> pts = new ArrayList<>();
            double[] before = null;
            String beforeD = null;
            for (int i = 0; i < hist.length(); i++) {
                JSONArray p = hist.getJSONArray(i);
                String d = p.getString(0);
                double[] vv = {p.getDouble(1), p.getDouble(2)};
                if (d.compareTo(lim) < 0) { before = vv; beforeD = d; continue; }
                ds.add(d);
                pts.add(vv);
            }
            if (before != null && per < 3) { ds.add(0, lim); pts.add(0, before); }
            if (pts.size() < 2) return null;

            int[] sz = size(ctx, m, id);
            float dens = ctx.getResources().getDisplayMetrics().density;
            float scale = dens;
            // RemoteViews limite la taille des images : on reste sous ~1,2 Mpx
            while (sz[0] * scale * sz[1] * scale > 1_200_000f && scale > 1f) scale -= 0.25f;
            int W = Math.round(sz[0] * scale), H = Math.round(sz[1] * scale);
            Bitmap bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmp);

            long t0 = iso.parse(ds.get(0)).getTime(), t1 = iso.parse(ds.get(ds.size() - 1)).getTime();
            if (t1 <= t0) t1 = t0 + 86_400_000L;
            double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
            for (double[] p : pts) { lo = Math.min(lo, Math.min(p[0], p[1])); hi = Math.max(hi, Math.max(p[0], p[1])); }
            double pad = Math.max((hi - lo) * 0.08, hi * 0.002 + 1);
            lo -= pad; hi += pad;

            float labelW = priv ? 0 : 44 * scale;
            float top = 4 * scale, bottom = H - 16 * scale, left = 2 * scale, right = W - labelW;
            float[] xs = new float[pts.size()], yv = new float[pts.size()], yb = new float[pts.size()];
            for (int i = 0; i < pts.size(); i++) {
                long t = iso.parse(ds.get(i)).getTime();
                xs[i] = left + (float) ((t - t0) / (double) (t1 - t0)) * (right - left);
                yv[i] = (float) (bottom - (pts.get(i)[0] - lo) / (hi - lo) * (bottom - top));
                yb[i] = (float) (bottom - (pts.get(i)[1] - lo) / (hi - lo) * (bottom - top));
            }
            double first = pts.get(0)[0], last = pts.get(pts.size() - 1)[0];
            double flows = pts.get(pts.size() - 1)[1] - pts.get(0)[1];
            double delta = last - first - flows; // gain de la période, hors versements
            int col = delta >= 0 ? Widgets.GAIN : Widgets.LOSS;

            // grille
            Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
            grid.setColor(Color.argb(28, 255, 255, 255));
            grid.setStrokeWidth(1 * scale);
            Paint txt = new Paint(Paint.ANTI_ALIAS_FLAG);
            txt.setColor(Widgets.MUTED);
            txt.setTextSize(10 * scale);
            for (int g = 0; g <= 3; g++) {
                float y = top + (bottom - top) * g / 3f;
                c.drawLine(left, y, right, y, grid);
                if (!priv) {
                    double val = hi - (hi - lo) * g / 3.0;
                    c.drawText(String.format(Locale.FRANCE, "%,.0f", val).replace(' ', ' '), right + 4 * scale, y + 4 * scale, txt);
                }
            }

            // aire sous la courbe
            Path area = new Path();
            area.moveTo(xs[0], bottom);
            for (int i = 0; i < xs.length; i++) area.lineTo(xs[i], yv[i]);
            area.lineTo(xs[xs.length - 1], bottom);
            area.close();
            Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
            fill.setShader(new LinearGradient(0, top, 0, bottom, Color.argb(90, Color.red(col), Color.green(col), Color.blue(col)),
                    Color.argb(0, Color.red(col), Color.green(col), Color.blue(col)), Shader.TileMode.CLAMP));
            c.drawPath(area, fill);

            // versements (en escalier, pointillés)
            Path vb = new Path();
            vb.moveTo(xs[0], yb[0]);
            for (int i = 1; i < xs.length; i++) { vb.lineTo(xs[i], yb[i - 1]); vb.lineTo(xs[i], yb[i]); }
            Paint pv = new Paint(Paint.ANTI_ALIAS_FLAG);
            pv.setStyle(Paint.Style.STROKE);
            pv.setColor(Color.argb(170, 139, 149, 167));
            pv.setStrokeWidth(1.4f * scale);
            pv.setPathEffect(new DashPathEffect(new float[]{5 * scale, 4 * scale}, 0));
            c.drawPath(vb, pv);

            // valeur
            Path line = new Path();
            line.moveTo(xs[0], yv[0]);
            for (int i = 1; i < xs.length; i++) line.lineTo(xs[i], yv[i]);
            Paint pl = new Paint(Paint.ANTI_ALIAS_FLAG);
            pl.setStyle(Paint.Style.STROKE);
            pl.setColor(col);
            pl.setStrokeWidth(2.2f * scale);
            pl.setStrokeJoin(Paint.Join.ROUND);
            pl.setStrokeCap(Paint.Cap.ROUND);
            c.drawPath(line, pl);
            Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
            dot.setColor(col);
            c.drawCircle(xs[xs.length - 1], yv[yv.length - 1], 3.5f * scale, dot);

            // dates
            SimpleDateFormat out = new SimpleDateFormat(per >= 2 ? "MMM yy" : "d MMM", Locale.FRANCE);
            Date dA = iso.parse(ds.get(0)), dB = iso.parse(ds.get(ds.size() - 1));
            c.drawText(out.format(dA), left, H - 3 * scale, txt);
            String eb = out.format(dB);
            c.drawText(eb, right - txt.measureText(eb), H - 3 * scale, txt);

            Result r = new Result();
            r.bmp = bmp;
            r.delta = delta;
            double base = first + Math.max(0, flows);
            String pct = base > 0 ? "  " + Widgets.pct(delta / base) : "";
            r.label = (priv ? "••" : Widgets.signedEur(delta)) + pct + " sur " + PERIODS[per] + "   ─ valeur  ┄ versé";
            return r;
        } catch (Exception e) {
            return null;
        }
    }

    static final int[] PALETTE = {0xFF4C8DF6, 0xFF9B7CF0, 0xFF3FBFAE, 0xFFE07AA8, 0xFFE9B04F, 0xFF6FCF6A, 0xFFF08A5D};

    /** Camembert (anneau) de la répartition du PEA, avec la légende en dessous ou à côté. */
    static Bitmap pie(Context ctx, AppWidgetManager m, int id, JSONArray lines, double cash) {
        if (lines == null || lines.length() == 0) return null;
        try {
            Bundle o = m.getAppWidgetOptions(id);
            boolean land = ctx.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
            int wDp = o.getInt(land ? AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH : AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0);
            int hDp = o.getInt(land ? AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT : AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0);
            if (wDp < 230) return null; // widget trop étroit : pas de camembert
            float scale = ctx.getResources().getDisplayMetrics().density;
            int W = Math.round((wDp - 28) * 0.53f * scale), H = Math.round(Math.max(60, hDp - 28) * scale);
            while (W * H > 900_000 && scale > 1f) { scale -= 0.25f; W = Math.round((wDp - 28) * 0.53f * scale); H = Math.round(Math.max(60, hDp - 28) * scale); }

            List<String> names = new ArrayList<>();
            List<Double> vals = new ArrayList<>();
            double tot = 0;
            for (int i = 0; i < lines.length(); i++) {
                org.json.JSONObject l = lines.getJSONObject(i);
                double v = l.optDouble("q", 0) * l.optDouble("px", 0);
                if (v <= 0) continue;
                names.add(l.optString("n", l.optString("k")));
                vals.add(v);
                tot += v;
            }
            if (cash > 0.5) { names.add("Espèces"); vals.add(cash); tot += cash; }
            if (tot <= 0) return null;

            Bitmap bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmp);
            Paint txt = new Paint(Paint.ANTI_ALIAS_FLAG);
            txt.setTextSize(10.5f * scale);
            float rowH = 14 * scale;
            float legendH = names.size() * rowH;
            // anneau à gauche et légende à droite si la place le permet, sinon légende dessous
            boolean side = W > H * 1.5f;
            float d = side ? Math.min(H - 4 * scale, W * 0.45f) : Math.min(W, H - legendH - 6 * scale);
            if (d < 40 * scale) { side = true; d = Math.min(H - 4 * scale, W * 0.42f); }
            float cx = side ? d / 2 + 2 * scale : W / 2f, cy = side ? H / 2f : d / 2 + 2 * scale;
            float ring = d * 0.2f;
            android.graphics.RectF box = new android.graphics.RectF(cx - d / 2 + ring / 2, cy - d / 2 + ring / 2, cx + d / 2 - ring / 2, cy + d / 2 - ring / 2);
            Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
            arc.setStyle(Paint.Style.STROKE);
            arc.setStrokeWidth(ring);
            float a = -90;
            for (int i = 0; i < vals.size(); i++) {
                float sweep = (float) (vals.get(i) / tot * 360);
                boolean isCash = names.get(i).equals("Espèces");
                arc.setColor(isCash ? 0xFF5A6474 : PALETTE[i % PALETTE.length]);
                c.drawArc(box, a + 0.8f, Math.max(0.5f, sweep - 1.6f), false, arc);
                a += sweep;
            }
            // nombre de lignes au centre
            Paint ctr = new Paint(Paint.ANTI_ALIAS_FLAG);
            ctr.setColor(Widgets.INK);
            ctr.setTextSize(Math.max(11 * scale, d * 0.16f));
            ctr.setTextAlign(Paint.Align.CENTER);
            ctr.setFakeBoldText(true);
            c.drawText(String.valueOf(lines.length()), cx, cy + ctr.getTextSize() * 0.15f, ctr);
            ctr.setTextSize(Math.max(8 * scale, d * 0.09f));
            ctr.setFakeBoldText(false);
            ctr.setColor(Widgets.MUTED);
            c.drawText("ETF", cx, cy + ctr.getTextSize() * 1.35f, ctr);

            // légende
            float lx = side ? d + 10 * scale : 4 * scale;
            float ly = side ? Math.max(rowH, cy - legendH / 2 + rowH * 0.75f) : d + 6 * scale + rowH * 0.75f;
            Paint sw = new Paint(Paint.ANTI_ALIAS_FLAG);
            for (int i = 0; i < vals.size(); i++) {
                if (ly > H) break;
                boolean isCash = names.get(i).equals("Espèces");
                sw.setColor(isCash ? 0xFF5A6474 : PALETTE[i % PALETTE.length]);
                c.drawCircle(lx + 4 * scale, ly - 3.5f * scale, 3.5f * scale, sw);
                String pctS = String.format(Locale.FRANCE, "%.0f\u00a0%%", vals.get(i) / tot * 100);
                txt.setColor(Widgets.INK);
                float pw = txt.measureText(pctS);
                float maxName = W - lx - 12 * scale - pw - 6 * scale;
                String n = names.get(i);
                txt.setColor(Widgets.MUTED);
                while (n.length() > 3 && txt.measureText(n) > maxName) n = n.substring(0, n.length() - 2) + "…";
                if (n.length() > 3 && n.endsWith("……")) n = n.substring(0, n.length() - 1);
                c.drawText(n, lx + 12 * scale, ly, txt);
                txt.setColor(Widgets.INK);
                c.drawText(pctS, W - pw - 2 * scale, ly, txt);
                ly += rowH;
            }
            return bmp;
        } catch (Exception e) {
            return null;
        }
    }
}
