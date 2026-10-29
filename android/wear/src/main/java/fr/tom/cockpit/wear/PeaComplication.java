package fr.tom.cockpit.wear;

import android.app.PendingIntent;
import android.content.Intent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.wear.watchface.complications.data.ComplicationData;
import androidx.wear.watchface.complications.data.ComplicationType;
import androidx.wear.watchface.complications.data.LongTextComplicationData;
import androidx.wear.watchface.complications.data.PlainComplicationText;
import androidx.wear.watchface.complications.data.RangedValueComplicationData;
import androidx.wear.watchface.complications.data.ShortTextComplicationData;
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceService;
import androidx.wear.watchface.complications.datasource.ComplicationRequest;

import org.json.JSONObject;

/** Complication pour le cadran : variation du jour (court) ou valeur + variation (long). */
public class PeaComplication extends ComplicationDataSourceService {

    private static PlainComplicationText t(String s) { return new PlainComplicationText.Builder(s).build(); }

    private PendingIntent tap() {
        Intent i = new Intent(this, WearMain.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(this, 1, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    @Nullable
    private ComplicationData build(ComplicationType type, JSONObject d) {
        boolean priv = d != null && d.optBoolean("privacy", false);
        String shortTxt = d == null ? "—" : Pea.pct(d.optDouble("dayPct", 0)).replace(" %", "%");
        String value = d == null ? "PEA —" : priv ? "PEA •••• €" : "PEA " + Pea.eur(d.optDouble("value", 0));
        if (type == ComplicationType.SHORT_TEXT)
            return new ShortTextComplicationData.Builder(t(shortTxt), t("PEA aujourd'hui " + shortTxt)).setTitle(t("PEA")).setTapAction(tap()).build();
        if (type == ComplicationType.LONG_TEXT)
            return new LongTextComplicationData.Builder(t(value + "  " + shortTxt), t(value + ", aujourd'hui " + shortTxt)).setTitle(t("Mon PEA")).setTapAction(tap()).build();
        if (type == ComplicationType.RANGED_VALUE) {
            JSONObject g = Pea.mainGoal(d);
            if (g == null) return null;
            float tgt = (float) Math.max(1, g.optDouble("tgt", 1)), cur = (float) Math.min(tgt, Math.max(0, g.optDouble("cur", 0)));
            String p = Math.round(cur / tgt * 100) + "%";
            return new RangedValueComplicationData.Builder(cur, 0f, tgt, t(g.optString("n") + " " + p + (g.optString("date").isEmpty() ? "" : ", prévu " + Pea.month(g.optString("date")))))
                    .setText(t(p)).setTitle(t(g.optString("n"))).setTapAction(tap()).build();
        }
        return null;
    }

    @Override
    public void onComplicationRequest(@NonNull ComplicationRequest req, @NonNull ComplicationRequestListener listener) {
        Pea.refreshAsync(this, false);
        try { listener.onComplicationData(build(req.getComplicationType(), Pea.get(this))); } catch (Exception ignored) { }
    }

    @Nullable @Override
    public ComplicationData getPreviewData(@NonNull ComplicationType type) {
        try {
            return build(type, new JSONObject().put("value", 12345).put("dayPct", 0.0084)
                    .put("goals", new org.json.JSONArray().put(new JSONObject().put("n", "Patrimoine").put("cur", 40).put("tgt", 100))));
        } catch (Exception e) { return null; }
    }
}
