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
}
