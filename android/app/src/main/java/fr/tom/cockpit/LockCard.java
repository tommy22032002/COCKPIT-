package fr.tom.cockpit;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;

import org.json.JSONArray;
import org.json.JSONObject;

/** Dessine la carte de l'écran verrouillé, dans le style du widget : grande (dépliée) ou compacte (repliée). */
public class LockCard {
    static final int BG = Color.parseColor("#141A24"), BG2 = Color.parseColor("#1C2431"), LINE = Color.parseColor("#2A3444");

    static Paint txt(float px, int col, boolean bold) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG); p.setTextSize(px); p.setColor(col);
        p.setTypeface(Typeface.create("sans-serif" + (bold ? "-medium" : ""), bold ? Typeface.BOLD : Typeface.NORMAL)); return p;
    }

    static float fit(Paint p, String s, float max) { while (p.measureText(s) > max && p.getTextSize() > 10) p.setTextSize(p.getTextSize() - 1); return p.measureText(s); }

    static void center(Canvas cv, String t, float cx, float y, Paint p) { cv.drawText(t, cx - p.measureText(t) / 2f, y, p); }

    /** big = vue dépliée, sinon repliée (hauteur imposée par Android : 48 dp). show = pea | net | both. */
    static Bitmap draw(Context c, JSONObject d, String show, boolean big) {
        float dn = c.getResources().getDisplayMetrics().density;
        int wDp = Math.max(260, Math.min(420, (int) (c.getResources().getDisplayMetrics().widthPixels / dn) - 56));
        int W = (int) (wDp * dn), H = (int) ((big ? 176 : 48) * dn);
        Bitmap b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(b);
        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        bg.setShader(new LinearGradient(0, 0, W, H, BG2, BG, Shader.TileMode.CLAMP));
        float r = (big ? 18 : 14) * dn;
        cv.drawRoundRect(new RectF(0, 0, W, H), r, r, bg);

        double pea = d.optDouble("value", 0), dayAbs = d.optDouble("dayAbs", 0), dayPct = d.optDouble("dayPct", 0), gain = d.optDouble("gain", 0), cost = d.optDouble("cost", 0);
        double net = d.has("netExPea") ? d.optDouble("netExPea", 0) + pea : d.optDouble("net", 0);
        boolean onlyNet = "net".equals(show), onlyPea = "pea".equals(show);
        int dayCol = dayAbs >= 0 ? Widgets.GAIN : Widgets.LOSS;
        float cx = W / 2f, pad = 14 * dn;
        String netS = Widgets.eur(net, false), peaS = Widgets.eur(pea, false), pctS = Widgets.pct(dayPct);

        if (!big) {
            if (onlyNet || onlyPea) {
                // une seule valeur, centrée et en grand
                Paint cap = txt(9.5f * dn, Widgets.MUTED, true);
                center(cv, onlyNet ? "PATRIMOINE NET" : "PEA", cx, 14 * dn, cap);
                Paint v = txt(25 * dn, Widgets.INK, true);
                String main = onlyNet ? netS : peaS;
                if (onlyPea) {
                    Paint p2 = txt(15 * dn, dayCol, true);
                    float w1 = v.measureText(main), gap = 10 * dn, w2 = p2.measureText(pctS), x0 = cx - (w1 + gap + w2) / 2f;
                    cv.drawText(main, x0, 40 * dn, v); cv.drawText(pctS, x0 + w1 + gap, 40 * dn, p2);
                } else center(cv, main, cx, 40 * dn, v);
            } else {
                // patrimoine net à gauche, PEA à droite, chacun centré dans sa moitié
                Paint cap = txt(9.5f * dn, Widgets.MUTED, true), v = txt(21 * dn, Widgets.INK, true), v2 = txt(17 * dn, Widgets.INK, true);
                center(cv, "PATRIMOINE NET", W * .3f, 14 * dn, cap); center(cv, netS, W * .3f, 39 * dn, v);
                Paint sep = new Paint(); sep.setColor(LINE); cv.drawRect(W * .58f, 10 * dn, W * .58f + dn, H - 10 * dn, sep);
                center(cv, "PEA  ·  " + pctS, W * .79f, 14 * dn, txt(9.5f * dn, dayCol, true)); center(cv, peaS, W * .79f, 38 * dn, v2);
            }
            return b;
        }

        // vue dépliée : grand montant centré, puis courbe sur toute la largeur
        Paint cap = txt(11 * dn, Widgets.MUTED, true);
        center(cv, onlyPea ? "PEA · FORTUNEO" : "PATRIMOINE NET", cx, pad + 8 * dn, cap);
        Paint v = txt(36 * dn, Widgets.INK, true); String main = onlyPea ? peaS : netS; fit(v, main, W - 2 * pad);
        center(cv, main, cx, pad + 50 * dn, v);
        if (!onlyNet) {
            String line = onlyPea ? "Aujourd'hui " + Widgets.signedEur(dayAbs) + "  " + pctS + "   ·   plus-value " + Widgets.signedEur(gain)
                    : "PEA " + peaS + "   " + pctS + " aujourd'hui";
            Paint dp = txt(13 * dn, dayCol, true); fit(dp, line, W - 2 * pad);
            center(cv, line, cx, pad + 72 * dn, dp);
        }
        String at = d.optString("at", "");
        Paint ap = txt(10 * dn, Widgets.MUTED, false);
        RectF area = new RectF(pad, pad + (onlyNet ? 64 : 84) * dn, W - pad, H - pad - 12 * dn);
        JSONArray hist = onlyPea ? d.optJSONArray("hist") : d.optJSONArray("netHist");
        int n = hist == null ? 0 : hist.length(), from = Math.max(0, n - 90);
        if (n - from >= 2) {
            double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
            for (int i = from; i < n; i++) { double y = hist.optJSONArray(i).optDouble(1, 0); lo = Math.min(lo, y); hi = Math.max(hi, y); }
            if (hi - lo < 1) { hi += 1; lo -= 1; }
            double first = hist.optJSONArray(from).optDouble(1, 0), last = hist.optJSONArray(n - 1).optDouble(1, 0);
            int col = last >= first ? Widgets.GAIN : Widgets.LOSS;
            Path ln = new Path(), fill = new Path();
            for (int i = from; i < n; i++) {
                float x = area.left + area.width() * (i - from) / (float) (n - 1 - from);
                float y = (float) (area.bottom - (hist.optJSONArray(i).optDouble(1, 0) - lo) / (hi - lo) * area.height());
                if (i == from) { ln.moveTo(x, y); fill.moveTo(x, area.bottom); fill.lineTo(x, y); } else { ln.lineTo(x, y); fill.lineTo(x, y); }
            }
            fill.lineTo(area.right, area.bottom); fill.close();
            Paint f = new Paint(Paint.ANTI_ALIAS_FLAG);
            f.setShader(new LinearGradient(0, area.top, 0, area.bottom, Color.argb(100, Color.red(col), Color.green(col), Color.blue(col)), Color.argb(0, Color.red(col), Color.green(col), Color.blue(col)), Shader.TileMode.CLAMP));
            cv.drawPath(fill, f);
            Paint st = new Paint(Paint.ANTI_ALIAS_FLAG); st.setStyle(Paint.Style.STROKE); st.setStrokeWidth(2.2f * dn); st.setColor(col); st.setStrokeJoin(Paint.Join.ROUND);
            cv.drawPath(ln, st);
            String d0 = hist.optJSONArray(from).optString(0, "");
            String evo = Widgets.signedEur(last - first) + (d0.length() >= 10 ? " depuis le " + d0.substring(8, 10) + "/" + d0.substring(5, 7) : "");
            Paint ep = txt(10 * dn, col, true);
            cv.drawText(evo, W - pad - ep.measureText(evo), H - pad + 2 * dn, ep);
        }
        cv.drawText(at.isEmpty() ? "" : Widgets.ago(at), pad, H - pad + 2 * dn, ap);
        return b;
    }
}
