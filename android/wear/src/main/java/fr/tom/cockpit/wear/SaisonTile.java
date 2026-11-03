package fr.tom.cockpit.wear;

import androidx.annotation.NonNull;
import androidx.wear.protolayout.ActionBuilders;
import androidx.wear.protolayout.DimensionBuilders;
import androidx.wear.protolayout.LayoutElementBuilders;
import androidx.wear.protolayout.ModifiersBuilders;
import androidx.wear.protolayout.ResourceBuilders;
import androidx.wear.protolayout.TimelineBuilders;
import androidx.wear.tiles.RequestBuilders;
import androidx.wear.tiles.TileBuilders;
import androidx.wear.tiles.TileService;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

import org.json.JSONObject;

/** Tuile « Ma saison » : heures du mois et total de la saison ; entre deux saisons, budget par jour et compte à rebours. */
public class SaisonTile extends TileService {

    @NonNull @Override
    protected ListenableFuture<TileBuilders.Tile> onTileRequest(@NonNull RequestBuilders.TileRequest req) {
        JSONObject d = Pea.get(this);
        JSONObject pa = d == null ? null : d.optJSONObject("paie");
        LayoutElementBuilders.Column.Builder col = new LayoutElementBuilders.Column.Builder()
                .setWidth(DimensionBuilders.expand())
                .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                .addContent(PeaTile.txt("Ma saison", 13, Pea.MUTED, false))
                .addContent(PeaTile.gap(4));
        JSONObject it = pa == null ? null : pa.optJSONObject("inter");
        if (it != null) {
            // intersaison : budget par jour jusqu'au prochain salaire et compte à rebours de la prochaine saison
            boolean priv = d.optBoolean("privacy", false);
            double day = it.optDouble("day", -1);
            int jDeb = it.optInt("jDeb", -1);
            if (day >= 0) {
                col.addContent(PeaTile.txt(priv ? "••" : Pea.eur(Math.max(0, day)), 28, day < 5 ? Pea.LOSS : day < 10 ? 0xFFF2B35B : Pea.GAIN, true))
                   .addContent(PeaTile.txt("par jour · " + it.optInt("j") + " j", 12, Pea.MUTED, false))
                   .addContent(PeaTile.txt("salaire vers le " + it.optString("pay"), 11, Pea.MUTED, false));
            } else {
                col.addContent(PeaTile.txt("Intersaison", 22, Pea.INK, true));
            }
            col.addContent(PeaTile.gap(6));
            if (jDeb > 0) col.addContent(PeaTile.txt(it.optString("lieu", "Saison") + " dans " + jDeb + " j", 14, Pea.ACCENT, true));
            else if (jDeb == 0) col.addContent(PeaTile.txt(it.optString("lieu", "Saison") + " : c'est parti !", 14, Pea.ACCENT, true));
            if (it.has("todo")) col.addContent(PeaTile.txt(it.optString("todo"), 11, Pea.MUTED, false));
        } else if (pa == null || !pa.optBoolean("hasH", false)) {
            col.addContent(PeaTile.txt("—", 28, Pea.INK, true)).addContent(PeaTile.gap(6))
               .addContent(PeaTile.txt("Ouvre Capital", 13, Pea.MUTED, false))
               .addContent(PeaTile.txt("sur ton téléphone", 13, Pea.MUTED, false));
        } else {
            boolean ended = pa.optBoolean("ended", false);
            double worked = pa.optDouble("worked", 0), paid = pa.optDouble("paid", 0), ext = pa.optDouble("ext", 0);
            if (!ended) col.addContent(PeaTile.txt(Pea.fh(pa.optDouble("hMois", 0)), 28, Pea.INK, true))
                           .addContent(PeaTile.txt(pa.optString("mois") + " · " + pa.optInt("jours") + " jours", 12, Pea.MUTED, false));
            else col.addContent(PeaTile.txt("Terminée", 24, Pea.INK, true));
            col.addContent(PeaTile.gap(6))
               .addContent(PeaTile.txt(Pea.fh(worked) + " / " + Pea.fh(paid), 15, Pea.ACCENT, true))
               .addContent(PeaTile.txt(ext > 0 ? Pea.fh(ext) + " d'heures sup" : Pea.fh(pa.optDouble("manque", 0)) + " payées en plus", 12, ext > 0 ? Pea.LOSS : Pea.GAIN, false))
               .addContent(PeaTile.txt(pa.optString("nom"), 11, Pea.MUTED, false));
        }
        JSONObject fo = d == null ? null : d.optJSONObject("forme");
        if (fo != null) {
            col.addContent(PeaTile.gap(6)).addContent(PeaTile.txt("👟 " + String.format(java.util.Locale.FRANCE, "%,d", fo.optLong("pas", 0)).replace(',', ' ') + " pas · muscu " + fo.optInt("semaine") + "/" + fo.optInt("obj", 3), 11, Pea.MUTED, false));
        }
        ModifiersBuilders.Clickable click = new ModifiersBuilders.Clickable.Builder().setId("open")
                .setOnClick(new ActionBuilders.LaunchAction.Builder().setAndroidActivity(
                        new ActionBuilders.AndroidActivity.Builder().setPackageName(getPackageName()).setClassName(WearMain.class.getName()).build()).build())
                .build();
        LayoutElementBuilders.Box root = new LayoutElementBuilders.Box.Builder()
                .setWidth(DimensionBuilders.expand()).setHeight(DimensionBuilders.expand())
                .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
                .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                .setModifiers(new ModifiersBuilders.Modifiers.Builder().setClickable(click).build())
                .addContent(col.build()).build();
        return Futures.immediateFuture(new TileBuilders.Tile.Builder().setResourcesVersion("1")
                .setFreshnessIntervalMillis(60 * 60_000L)
                .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(root)).build());
    }

    @NonNull @Override
    protected ListenableFuture<ResourceBuilders.Resources> onTileResourcesRequest(@NonNull RequestBuilders.ResourcesRequest req) {
        return Futures.immediateFuture(new ResourceBuilders.Resources.Builder().setVersion("1").build());
    }
}
