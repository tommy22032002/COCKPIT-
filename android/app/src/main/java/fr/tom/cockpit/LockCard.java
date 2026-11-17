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

    /** big = vue dépliée (~150 dp), sinon vue repliée (~48 dp). show = pea | net | both. */
    static Bitmap draw(Context c, JSONObject d, String show, boolean big) {
        float dn = c.getResources().getDisplayMetrics().density;
        int wDp = Math.max(260, Math.min(420, (int) (c.getResources().getDisplayMetrics().widthPixels / dn) - 56));
        int W = (int) (wDp * dn), H = (int) ((big ? 156 : 48) * dn);
        Bitmap b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(b);
        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        bg.setShader(new LinearGradient(0, 0, W, H, BG2, BG, Shader.TileMode.CLAMP));
        float r = 16 * dn;
        cv.drawRoundRect(new RectF(0, 0, W, H), r, r, bg);

        double pea = d.optDouble("value", 0), dayAbs = d.optDouble("dayAbs", 0), dayPct = d.optDouble("dayPct", 0), gain = d.optDouble("gain", 0), cost = d.optDouble("cost", 0);
        double net = d.has("netExPea") ? d.optDouble("netExPea", 0) + pea : d.optDouble("net", 0);
        boolean netMain = "net".equals(show) || "both".equals(show);
        String label = netMain ? "PATRIMOINE NET" : "PEA · FORTUNEO";
        String main = Widgets.eur(netMain ? net : pea, false);
        int dayCol = dayAbs >= 0 ? Widgets.GAIN : Widgets.LOSS;
        String day = Widgets.signedEur(dayAbs) + "  " + Widgets.pct(dayPct);
        float pad = 14 * dn;

        if (!big) {
            Paint l = txt(10.5f * dn, Widgets.MUTED, true);
            cv.drawText(netMain ? "Patrimoine net" : "PEA", pad, H / 2f - 4 * dn, l);
            Paint v = txt(20 * dn, Widgets.INK, true);
            cv.drawText(main, pad, H / 2f + 15 * dn, v);
            Paint dp = txt(14 * dn, dayCol, true);
            String s = (netMain ? "PEA " : "") + Widgets.pct(dayPct);
            float w = fit(dp, s, W * 0.42f);
            cv.drawText(s, W - pad - w, H / 2f + 5 * dn, dp);
            return b;
        }

        // colonne gauche : chiffres
        float colW = W * 0.56f;
        cv.drawText(label, pad, pad + 10 * dn, txt(11 * dn, Widgets.MUTED, true));
        Paint v = txt(32 * dn, Widgets.INK, true); fit(v, main, colW - pad);
        cv.drawText(main, pad, pad + 48 * dn, v);
        Paint dp = txt(14 * dn, dayCol, true); fit(dp, (netMain ? "PEA " : "") + day, colW - pad);
        cv.drawText((netMain ? "PEA " : "Aujourd'hui ") + day, pad, pad + 72 * dn, dp);
        String sub = netMain ? "PEA " + Widgets.eur(pea, false) + " · plus-value " + Widgets.signedEur(gain)
                : "Plus-value " + Widgets.signedEur(gain) + (cost > 0 ? "  " + Widgets.pct(gain / cost) : "");
        Paint sp = txt(11.5f * dn, Widgets.MUTED, false); fit(sp, sub, colW - pad);
        cv.drawText(sub, pad, pad + 94 * dn, sp);
        String at = d.optString("at", "");
        cv.drawText(at.isEmpty() ? "" : Widgets.ago(at), pad, H - pad + 2 * dn, txt(10 * dn, Widgets.MUTED, false));

        // colonne droite : courbe de la valeur du PEA
        RectF area = new RectF(colW + 4 * dn, pad + 6 * dn, W - pad, H - pad);
        JSONArray hist = d.optJSONArray("hist");
        int n = hist == null ? 0 : hist.length();
        int from = Math.max(0, n - 90);
        if (n - from >= 2) {
            double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
            for (int i = from; i < n; i++) { double y = hist.optJSONArray(i).optDouble(1, 0); lo = Math.min(lo, y); hi = Math.max(hi, y); }
            if (hi - lo < 1) { hi += 1; lo -= 1; }
            double first = hist.optJSONArray(from).optDouble(1, 0), last = hist.optJSONArray(n - 1).optDouble(1, 0);
            int col = last >= first ? Widgets.GAIN : Widgets.LOSS;
            Path line = new Path(), fill = new Path();
            for (int i = from; i < n; i++) {
                float x = area.left + (area.width() * (i - from) / (float) (n - 1 - from));
                float y = (float) (area.bottom - (hist.optJSONArray(i).optDouble(1, 0) - lo) / (hi - lo) * area.height());
                if (i == from) { line.moveTo(x, y); fill.moveTo(x, area.bottom); fill.lineTo(x, y); } else { line.lineTo(x, y); fill.lineTo(x, y); }
            }
            fill.lineTo(area.right, area.bottom); fill.close();
            Paint f = new Paint(Paint.ANTI_ALIAS_FLAG);
            f.setShader(new LinearGradient(0, area.top, 0, area.bottom, Color.argb(110, Color.red(col), Color.green(col), Color.blue(col)), Color.argb(0, Color.red(col), Color.green(col), Color.blue(col)), Shader.TileMode.CLAMP));
            cv.drawPath(fill, f);
            Paint ln = new Paint(Paint.ANTI_ALIAS_FLAG); ln.setStyle(Paint.Style.STROKE); ln.setStrokeWidth(2.2f * dn); ln.setColor(col); ln.setStrokeJoin(Paint.Join.ROUND);
            cv.drawPath(line, ln);
            Paint cap = txt(10 * dn, Widgets.MUTED, false);
            String per = (n - from) >= 60 ? "3 mois" : (n - from) + " j";
            cv.drawText("PEA · " + per, area.right - cap.measureText("PEA · " + per), area.top - 2 * dn, cap);
        } else {
            Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG); dot.setColor(LINE);
            cv.drawRoundRect(area, 10 * dn, 10 * dn, dot);
        }
        return b;
    }
}
