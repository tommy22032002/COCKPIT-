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

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Lignes et dessin du widget « Capital · Complet » (même contenu que le widget du PC). */
final class FullRows {
    private FullRows() {}

    static final int BG = Color.parseColor("#141A24");
    static final int LINE = Color.parseColor("#2A3444");
    static final int GOLD = Color.parseColor("#D4AF64");
    static final int BAR = Color.parseColor("#2A3244");
    static final int BLUE = Color.parseColor("#5AA0FF");
    static final int GREEN = Color.parseColor("#50C88C");
    static final int WARN = Color.parseColor("#F0AA32");

    static final int HEAD = 0, CURVE = 1, TITLE = 2, REP = 3, ACC = 4, PEAH = 5, POSH = 6, POS = 7, ALERT = 8, BUY = 9, EV = 10, FOOT = 11, MSG = 12;

    static final class Row {
        int kind;
        String hash = "";
        String a = "", b = "", c = "", e = "";
        double v1, v2, v3, f;
        int col = Color.WHITE;
        int hgt;
        boolean priv;
        double[] px, py;
    }

    private static Row row(int kind, String hash, int hgt, boolean priv) {
        Row r = new Row();
        r.kind = kind; r.hash = hash; r.hgt = hgt; r.priv = priv;
        return r;
    }

    private static String eur0(double v) { return Widgets.eur(v, false); }

    private static String sgn0(double v) {
        return (v > 0 ? "+" : v < 0 ? "−" : "") + Widgets.eur(Math.abs(v), false);
    }

    private static String pct1(double v) {
        return (v > 0 ? "+" : v < 0 ? "−" : "") + String.format(Locale.FRANCE, "%.1f", Math.abs(v * 100)) + " %";
    }

    private static int col(double v) { return v >= 0 ? Widgets.GAIN : Widgets.LOSS; }

    private static String ddmm(String iso) {
        try { return iso.substring(8, 10) + "/" + iso.substring(5, 7); } catch (Exception e) { return iso; }
    }

    private static String shortName(String isin, String name) {
        if ("FR001400U5Q4".equals(isin)) return "MSCI World";
        if ("FR0011871110".equals(isin)) return "Nasdaq-100";
        if ("FR0011550672".equals(isin)) return "Stoxx 600";
        if ("FR0013412020".equals(isin)) return "Émergents";
        if ("FR0013411980".equals(isin)) return "Japon TOPIX";
        return name.length() > 22 ? name.substring(0, 22) : name;
    }

    /** Variation du patrimoine sur N jours d'après l'historique [[date, valeur], …]. */
    private static double variation(JSONArray h, int days, double nw) {
        if (h == null || h.length() < 2) return 0;
        try {
            LocalDate last = LocalDate.parse(h.getJSONArray(h.length() - 1).getString(0));
            LocalDate tgt = last.minusDays(days);
            double base = h.getJSONArray(0).getDouble(1);
            for (int i = 0; i < h.length(); i++) {
                JSONArray p = h.getJSONArray(i);
                if (!LocalDate.parse(p.getString(0)).isAfter(tgt)) base = p.getDouble(1);
            }
            return nw - base;
        } catch (Exception e) { return 0; }
    }

    static List<Row> build(JSONObject d) {
        List<Row> L = new ArrayList<>();
        JSONObject s = d.optJSONObject("sn");
        boolean priv = d.optBoolean("privacy", false);
        if (s == null) {
            Row m = row(MSG, "", 64, priv);
            m.a = "Ouvre Capital une fois pour remplir ce widget.";
            L.add(m);
            return L;
        }
        double nw = s.optDouble("nw", 0), cc = s.optDouble("cc", 0), ep = s.optDouble("ep", 0), inv = s.optDouble("inv", 0);
        JSONArray h = s.optJSONArray("hist");

        // 1. patrimoine et variations
        Row r = row(HEAD, "comptes", 124, priv);
        r.a = eur0(nw);
        String dt = s.optString("d", "");
        r.b = dt.length() >= 10 ? "au " + dt.substring(8, 10) + "/" + dt.substring(5, 7) + "/" + dt.substring(0, 4) : "";
        r.v1 = variation(h, 7, nw); r.v2 = variation(h, 30, nw); r.v3 = variation(h, 90, nw);
        L.add(r);

        // 2. courbe des 3 derniers mois
        try {
            if (h != null && h.length() >= 2) {
                LocalDate last = LocalDate.parse(h.getJSONArray(h.length() - 1).getString(0));
                LocalDate from = last.minusDays(92);
                List<LocalDate> ds = new ArrayList<>();
                List<Double> vs = new ArrayList<>();
                for (int i = 0; i < h.length(); i++) {
                    JSONArray p = h.getJSONArray(i);
                    LocalDate x = LocalDate.parse(p.getString(0));
                    if (!x.isBefore(from)) { ds.add(x); vs.add(p.getDouble(1)); }
                }
                if (ds.size() >= 2) {
                    double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
                    for (double v : vs) { lo = Math.min(lo, v); hi = Math.max(hi, v); }
                    double span = Math.max(1, java.time.temporal.ChronoUnit.DAYS.between(ds.get(0), ds.get(ds.size() - 1)));
                    double rg = Math.max(1, hi - lo);
                    Row c = row(CURVE, "comptes", 108, priv);
                    c.px = new double[ds.size()]; c.py = new double[ds.size()];
                    for (int i = 0; i < ds.size(); i++) {
                        c.px[i] = java.time.temporal.ChronoUnit.DAYS.between(ds.get(0), ds.get(i)) / span;
                        c.py[i] = (vs.get(i) - lo) / rg;
                    }
                    c.a = "plus bas " + eur0(lo); c.b = "plus haut " + eur0(hi);
                    L.add(c);
                }
            }
        } catch (Exception ignored) { }

        // 3. répartition
        double tot = Math.max(1, Math.max(cc, 0) + Math.max(ep, 0) + Math.max(inv, 0));
        L.add(title("RÉPARTITION", priv));
        String[] rl = {"Comptes courants", "Épargne", "Placements"};
        double[] rv = {cc, ep, inv};
        String[] rh = {"comptes:cc", "comptes:ep", "comptes:inv"};
        int[] rc = {BLUE, GREEN, GOLD};
        for (int i = 0; i < 3; i++) {
            Row x = row(REP, rh[i], 40, priv);
            x.a = rl[i] + " · " + Math.round(Math.max(rv[i], 0) / tot * 100) + " % du total";
            x.b = eur0(rv[i]); x.f = Math.max(rv[i], 0) / tot; x.col = rc[i];
            L.add(x);
        }

        // 4. comptes (du plus gros solde au plus petit)
        JSONArray ca = s.optJSONArray("comptes");
        if (ca != null && ca.length() > 0) {
            L.add(title("COMPTES", priv));
            final List<JSONObject> acc = new ArrayList<>();
            for (int i = 0; i < ca.length(); i++) { JSONObject o = ca.optJSONObject(i); if (o != null) acc.add(o); }
            Collections.sort(acc, new Comparator<JSONObject>() {
                @Override public int compare(JSONObject x, JSONObject y) { return Double.compare(y.optDouble("bal", 0), x.optDouble("bal", 0)); }
            });
            for (int i = 0; i < acc.size() && i < 9; i++) {
                Row x = row(ACC, "compte:" + (i + 1), 28, priv);
                String n = acc.get(i).optString("n", "");
                x.a = n.length() > 30 ? n.substring(0, 30) : n;
                x.b = eur0(acc.get(i).optDouble("bal", 0));
                L.add(x);
            }
        }

        // 5. PEA
        JSONObject pea = s.optJSONObject("pea");
        if (pea != null) {
            double pt = pea.optDouble("total", 0), pv = pea.optDouble("verse", 0), gain = pt - pv;
            Row x = row(PEAH, "portefeuille", 80, priv);
            x.a = eur0(pt);
            x.b = sgn0(gain) + "  " + pct1(pv > 0 ? gain / pv : 0);
            x.col = col(gain);
            x.c = eur0(pv) + " versés · gain ou perte depuis le début";
            L.add(x);
            JSONArray lines = pea.optJSONArray("lines");
            if (lines != null && lines.length() > 0) {
                L.add(row(POSH, "portefeuille", 20, priv));
                for (int i = 0; i < lines.length() && i < 6; i++) {
                    JSONObject o = lines.optJSONObject(i);
                    if (o == null) continue;
                    double cost = o.optDouble("q", 0) * o.optDouble("pru", 0), val = o.optDouble("val", 0);
                    double g = cost > 0 ? (val - cost) / cost : 0;
                    Row p = row(POS, "portefeuille", 28, priv);
                    p.a = shortName(o.optString("i", ""), o.optString("n", ""));
                    p.b = eur0(val); p.c = pct1(g); p.col = col(g);
                    L.add(p);
                }
            }
        }

        // 6. alertes et prochain achat
        JSONObject al = s.optJSONObject("al");
        if (al != null) {
            int n = 0;
            try { n = Integer.parseInt(al.optString("n", "0")); } catch (Exception ignored) { }
            Row x = row(ALERT, "executif", 62, priv);
            x.a = n > 0 ? n + (n > 1 ? " alertes" : " alerte") : "Aucune alerte";
            x.b = n > 0 ? al.optString("top", "") : "rien d'urgent";
            x.col = n > 0 ? WARN : GREEN;
            L.add(x);
        }
        String buy = s.optString("buy", "");
        if (buy.length() > 0) {
            Row x = row(BUY, "achat", 56, priv);
            x.a = buy;
            L.add(x);
        }

        // 7. prochains mouvements
        JSONArray ev = s.optJSONArray("ev");
        if (ev != null && ev.length() > 0) {
            L.add(title("PROCHAINS MOUVEMENTS", priv));
            for (int i = 0; i < ev.length() && i < 5; i++) {
                JSONArray o = ev.optJSONArray(i);
                if (o == null) continue;
                Row x = row(EV, "budget", 26, priv);
                x.a = ddmm(o.optString(0, ""));
                x.b = o.optString(1, "");
                x.v1 = o.optDouble(2, 0);
                x.c = sgn0(x.v1);
                x.col = col(x.v1);
                L.add(x);
            }
        }
        Row f = row(FOOT, "", 28, priv);
        f.a = "Touche une ligne pour l'ouvrir dans Capital";
        L.add(f);
        return L;
    }

    private static Row title(String t, boolean priv) {
        Row x = row(TITLE, "", 30, priv);
        x.a = t;
        return x;
    }

    private static String clip(Paint p, String s, float max) {
        if (p.measureText(s) <= max) return s;
        while (s.length() > 1 && p.measureText(s + "…") > max) s = s.substring(0, s.length() - 1);
        return s + "…";
    }

    private static Paint right(Paint p) { p.setTextAlign(Paint.Align.RIGHT); return p; }

    static Bitmap draw(Context c, Row r, JSONObject d, int wDp) {
        float dn = c.getResources().getDisplayMetrics().density;
        int W = Math.max(100, (int) (wDp * dn)), H = Math.max(20, (int) (r.hgt * dn));
        Bitmap b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(b);
        cv.drawColor(BG);
        float pad = 14 * dn;
        String dots = "••••";
        switch (r.kind) {
            case HEAD: {
                cv.drawText("CAPITAL", pad, 22 * dn, LockCard.txt(10 * dn, GOLD, true));
                cv.drawText(r.priv ? "•••• €" : r.a, pad, 64 * dn, LockCard.txt(31 * dn, Widgets.INK, true));
                cv.drawText(r.b, pad, 82 * dn, LockCard.txt(10 * dn, Widgets.MUTED, false));
                String[] lab = {"1 semaine", "1 mois", "3 mois"};
                double[] vv = {r.v1, r.v2, r.v3};
                float cw = (W - 2 * pad) / 3f;
                for (int i = 0; i < 3; i++) {
                    cv.drawText(lab[i], pad + i * cw, 100 * dn, LockCard.txt(9 * dn, Widgets.MUTED, false));
                    cv.drawText(r.priv ? "•••" : sgn0(vv[i]), pad + i * cw, 116 * dn, LockCard.txt(13 * dn, col(vv[i]), true));
                }
                break;
            }
            case CURVE: {
                cv.drawText("COURBE · 3 MOIS", pad, 16 * dn, LockCard.txt(9 * dn, Widgets.MUTED, true));
                float top = 26 * dn, bot = 84 * dn, x0 = pad, x1 = W - pad;
                if (r.px != null && r.px.length >= 2) {
                    Path line = new Path(), area = new Path();
                    for (int i = 0; i < r.px.length; i++) {
                        float x = (float) (x0 + r.px[i] * (x1 - x0)), y = (float) (bot - r.py[i] * (bot - top));
                        if (i == 0) { line.moveTo(x, y); area.moveTo(x, bot); area.lineTo(x, y); }
                        else { line.lineTo(x, y); area.lineTo(x, y); }
                    }
                    area.lineTo(x1, bot); area.close();
                    Paint pa = new Paint(Paint.ANTI_ALIAS_FLAG); pa.setColor(Color.argb(60, 90, 160, 255)); pa.setStyle(Paint.Style.FILL);
                    cv.drawPath(area, pa);
                    Paint pl = new Paint(Paint.ANTI_ALIAS_FLAG); pl.setColor(Widgets.ACCENT); pl.setStyle(Paint.Style.STROKE); pl.setStrokeWidth(2 * dn); pl.setStrokeJoin(Paint.Join.ROUND);
                    cv.drawPath(line, pl);
                }
                if (!r.priv) {
                    cv.drawText(r.a, pad, 100 * dn, LockCard.txt(8 * dn, Widgets.MUTED, false));
                    cv.drawText(r.b, W - pad, 100 * dn, right(LockCard.txt(8 * dn, Widgets.MUTED, false)));
                }
                break;
            }
            case TITLE: {
                Paint ln = new Paint(); ln.setColor(LINE);
                cv.drawRect(pad, 4 * dn, W - pad, 4 * dn + Math.max(1, dn), ln);
                cv.drawText(r.a, pad, 22 * dn, LockCard.txt(9 * dn, Widgets.MUTED, true));
                break;
            }
            case REP: {
                Paint t = LockCard.txt(12 * dn, Widgets.INK, false);
                cv.drawText(clip(t, r.a, (W - 2 * pad) * 0.66f), pad, 17 * dn, t);
                cv.drawText(r.priv ? dots : r.b, W - pad, 17 * dn, right(LockCard.txt(12 * dn, Widgets.INK, true)));
                float by = 26 * dn, bh = 5 * dn, bw = W - 2 * pad;
                Paint pb = new Paint(Paint.ANTI_ALIAS_FLAG); pb.setColor(BAR);
                cv.drawRoundRect(new RectF(pad, by, pad + bw, by + bh), bh / 2, bh / 2, pb);
                pb.setColor(r.col);
                cv.drawRoundRect(new RectF(pad, by, pad + (float) (bw * Math.min(1, r.f)), by + bh), bh / 2, bh / 2, pb);
                break;
            }
            case ACC: {
                Paint t = LockCard.txt(12 * dn, Widgets.INK, false);
                cv.drawText(clip(t, r.a, (W - 2 * pad) * 0.64f), pad, 19 * dn, t);
                cv.drawText(r.priv ? dots : r.b, W - pad, 19 * dn, right(LockCard.txt(12 * dn, Widgets.INK, true)));
                break;
            }
            case PEAH: {
                cv.drawText("PEA", pad, 16 * dn, LockCard.txt(9 * dn, Widgets.MUTED, true));
                cv.drawText(r.priv ? "•••• €" : r.a, pad, 44 * dn, LockCard.txt(23 * dn, Widgets.INK, true));
                cv.drawText(r.priv ? "•••" : r.b, W - pad, 42 * dn, right(LockCard.txt(12 * dn, r.col, true)));
                Paint t = LockCard.txt(9 * dn, Widgets.MUTED, false);
                cv.drawText(clip(t, r.priv ? "gain ou perte depuis le début" : r.c, W - 2 * pad), pad, 62 * dn, t);
                break;
            }
            case POSH: {
                Paint m = LockCard.txt(8 * dn, Widgets.MUTED, true);
                cv.drawText("TITRE", pad, 14 * dn, m);
                cv.drawText("VALEUR", W - pad - 64 * dn, 14 * dn, right(LockCard.txt(8 * dn, Widgets.MUTED, true)));
                cv.drawText("GAIN", W - pad, 14 * dn, right(LockCard.txt(8 * dn, Widgets.MUTED, true)));
                break;
            }
            case POS: {
                Paint t = LockCard.txt(12 * dn, Widgets.INK, false);
                cv.drawText(clip(t, r.a, (W - 2 * pad) * 0.42f), pad, 19 * dn, t);
                cv.drawText(r.priv ? dots : r.b, W - pad - 64 * dn, 19 * dn, right(LockCard.txt(12 * dn, Widgets.INK, false)));
                cv.drawText(r.priv ? "•••" : r.c, W - pad, 19 * dn, right(LockCard.txt(12 * dn, r.col, true)));
                break;
            }
            case ALERT: {
                Paint ln = new Paint(); ln.setColor(LINE);
                cv.drawRect(pad, 4 * dn, W - pad, 4 * dn + Math.max(1, dn), ln);
                cv.drawText("ALERTES", pad, 22 * dn, LockCard.txt(9 * dn, Widgets.MUTED, true));
                cv.drawText(r.a, pad, 42 * dn, LockCard.txt(14 * dn, r.col, true));
                Paint t = LockCard.txt(10 * dn, Widgets.MUTED, false);
                cv.drawText(clip(t, r.b, W - 2 * pad), pad, 57 * dn, t);
                break;
            }
            case BUY: {
                Paint ln = new Paint(); ln.setColor(LINE);
                cv.drawRect(pad, 4 * dn, W - pad, 4 * dn + Math.max(1, dn), ln);
                cv.drawText("PROCHAIN ACHAT PEA", pad, 22 * dn, LockCard.txt(9 * dn, Widgets.MUTED, true));
                Paint t = LockCard.txt(13 * dn, Widgets.INK, true);
                cv.drawText(r.priv ? dots : clip(t, r.a, W - 2 * pad), pad, 43 * dn, t);
                break;
            }
            case EV: {
                cv.drawText(r.a, pad, 17 * dn, LockCard.txt(10 * dn, Widgets.MUTED, false));
                Paint t = LockCard.txt(11 * dn, Widgets.INK, false);
                cv.drawText(clip(t, r.b, (W - 2 * pad) * 0.55f), pad + 46 * dn, 17 * dn, t);
                cv.drawText(r.priv ? "•••" : r.c, W - pad, 17 * dn, right(LockCard.txt(11 * dn, r.col, true)));
                break;
            }
            case FOOT: {
                Paint t = LockCard.txt(8 * dn, Color.parseColor("#5A6478"), false);
                String when = "";
                try {
                    JSONObject s = d.optJSONObject("sn");
                    String at = d.optString("at", "");
                    if (at.length() >= 16) when = "  ·  mis à jour " + at.substring(8, 10) + "/" + at.substring(5, 7);
                } catch (Exception ignored) { }
                cv.drawText(r.a + when, pad, 18 * dn, t);
                break;
            }
            default: {
                Paint t = LockCard.txt(12 * dn, Widgets.MUTED, false);
                cv.drawText(clip(t, r.a, W - 2 * pad), pad, 36 * dn, t);
            }
        }
        return b;
    }
}
