package fr.tom.cockpit;

import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Camembert (anneau) de la répartition du PEA, affiché quand le petit widget est élargi. */
final class Pie {
    private Pie() {}

    /** Mêmes couleurs que le cockpit (thème sombre). */
    static int color(String k, int i) {
        switch (k) {
            case "W": return Color.parseColor("#7590FF");
            case "N": return Color.parseColor("#3CBDBD");
            case "S": return Color.parseColor("#E5A35A");
            case "J": return Color.parseColor("#F07A9C");
            case "E": return Color.parseColor("#B39BF0");
            case "B": return Color.parseColor("#8E96A6");
            default:
                int[] extra = {Color.parseColor("#7BD389"), Color.parseColor("#F2C14E"), Color.parseColor("#5FB3F0"), Color.parseColor("#E07A5F")};
                return extra[i % extra.length];
        }
    }

    /** Largeur et hauteur (dp) disponibles à droite du texte, ou null si le widget est trop étroit. */
    static int[] area(Context ctx, AppWidgetManager m, int id) {
        Bundle o = m.getAppWidgetOptions(id);
        boolean land = ctx.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        int wDp = o.getInt(land ? AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH : AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0);
        int hDp = o.getInt(land ? AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT : AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0);
        int w = wDp - 28 - 150 - 10, h = hDp - 28;
        if (w < 70 || h < 60) return null;
        return new int[]{w, h};
    }

    static Bitmap draw(Context ctx, int[] area, JSONObject d, boolean priv) {
        try {
            JSONArray lines = d.optJSONArray("lines");
            if (lines == null || lines.length() == 0) return null;
            List<String> names = new ArrayList<>();
            List<Double> vals = new ArrayList<>();
            List<Integer> cols = new ArrayList<>();
            double total = 0;
            for (int i = 0; i < lines.length(); i++) {
                JSONObject l = lines.getJSONObject(i);
                double v = l.optDouble("q", 0) * l.optDouble("px", 0);
                if (v <= 0) continue;
                names.add(l.optString("n", l.optString("k")));
                vals.add(v);
                cols.add(color(l.optString("k"), i));
                total += v;
            }
            double cash = d.optDouble("cash", 0);
            if (cash > 1) { names.add("Espèces"); vals.add(cash); cols.add(Color.parseColor("#4A5263")); total += cash; }
            if (total <= 0) return null;

            float dens = ctx.getResources().getDisplayMetrics().density;
            int W = Math.round(area[0] * dens), H = Math.round(area[1] * dens);
            Bitmap bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmp);

            // anneau à gauche, légende à droite si la place le permet, sinon anneau seul
            boolean legend = area[0] >= area[1] * 1.6f || area[0] >= 150;
            float dia = legend ? Math.min(H, W * 0.48f) : Math.min(W, H);
            dia -= 4 * dens;
            float cx = legend ? dia / 2 + 2 * dens : W / 2f, cy = H / 2f;
            float stroke = dia * 0.22f;
            RectF r = new RectF(cx - dia / 2 + stroke / 2, cy - dia / 2 + stroke / 2, cx + dia / 2 - stroke / 2, cy + dia / 2 - stroke / 2);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(stroke);
            float start = -90f;
            float gap = vals.size() > 1 ? 1.5f : 0f;
            for (int i = 0; i < vals.size(); i++) {
                float sweep = (float) (vals.get(i) / total * 360.0);
                p.setColor(cols.get(i));
                c.drawArc(r, start + gap / 2, Math.max(0.5f, sweep - gap), false, p);
                start += sweep;
            }
            // centre : nombre de lignes
            Paint ct = new Paint(Paint.ANTI_ALIAS_FLAG);
            ct.setColor(Widgets.INK);
            ct.setTextAlign(Paint.Align.CENTER);
            ct.setFakeBoldText(true);
            ct.setTextSize(Math.max(10 * dens, dia * 0.16f));
            int etf = names.contains("Espèces") ? names.size() - 1 : names.size();
            c.drawText(String.valueOf(etf), cx, cy + ct.getTextSize() * 0.15f, ct);
            ct.setFakeBoldText(false);
            ct.setColor(Widgets.MUTED);
            ct.setTextSize(Math.max(8 * dens, dia * 0.085f));
            c.drawText(etf > 1 ? "lignes" : "ligne", cx, cy + ct.getTextSize() * 1.6f, ct);

            if (legend) {
                float lx = cx + dia / 2 + 10 * dens;
                float row = Math.min(18 * dens, (H - 4 * dens) / names.size());
                float ts = Math.min(11 * dens, row * 0.62f);
                float y0 = cy - row * names.size() / 2f + row / 2f;
                Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
                Paint tn = new Paint(Paint.ANTI_ALIAS_FLAG);
                tn.setColor(Widgets.INK);
                tn.setTextSize(ts);
                Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
                tp.setColor(Widgets.MUTED);
                tp.setTextSize(ts);
                tp.setTextAlign(Paint.Align.RIGHT);
                for (int i = 0; i < names.size(); i++) {
                    float y = y0 + i * row;
                    dot.setColor(cols.get(i));
                    c.drawCircle(lx + 4 * dens, y, 4 * dens, dot);
                    String pc = String.format(Locale.FRANCE, "%.0f %%", vals.get(i) / total * 100);
                    String nm = names.get(i);
                    float maxW = W - (lx + 12 * dens) - tp.measureText(pc) - 6 * dens;
                    while (nm.length() > 3 && tn.measureText(nm) > maxW) nm = nm.substring(0, nm.length() - 2) + "…";
                    if (nm.length() > 1 && nm.endsWith("……")) nm = nm.substring(0, nm.length() - 1);
                    c.drawText(nm, lx + 12 * dens, y + ts * 0.35f, tn);
                    c.drawText(pc, W - 2 * dens, y + ts * 0.35f, tp);
                }
            }
            return bmp;
        } catch (Exception e) {
            return null;
        }
    }
}
