package fr.tom.cockpit.wear;

import androidx.annotation.NonNull;
import androidx.wear.protolayout.ActionBuilders;
import androidx.wear.protolayout.ColorBuilders;
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

/** Tuile « Arrondis et objectif » : ce que les arrondis ont mis de côté ce mois-ci et la progression vers l'objectif. */
public class EpargneTile extends TileService {
    static LayoutElementBuilders.LayoutElement bar(double r, int color) {
        float W = 130;
        float w = (float) Math.max(4, Math.min(1, r) * W);
        return new LayoutElementBuilders.Box.Builder().setWidth(DimensionBuilders.dp(W)).setHeight(DimensionBuilders.dp(8))
                .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_START)
                .setModifiers(new ModifiersBuilders.Modifiers.Builder().setBackground(new ModifiersBuilders.Background.Builder().setColor(ColorBuilders.argb(0xFF1E2633))
                        .setCorner(new ModifiersBuilders.Corner.Builder().setRadius(DimensionBuilders.dp(4)).build()).build()).build())
                .addContent(new LayoutElementBuilders.Box.Builder().setWidth(DimensionBuilders.dp(w)).setHeight(DimensionBuilders.dp(8))
                        .setModifiers(new ModifiersBuilders.Modifiers.Builder().setBackground(new ModifiersBuilders.Background.Builder().setColor(ColorBuilders.argb(color))
                                .setCorner(new ModifiersBuilders.Corner.Builder().setRadius(DimensionBuilders.dp(4)).build()).build()).build()).build()).build();
    }

    @NonNull @Override
    protected ListenableFuture<TileBuilders.Tile> onTileRequest(@NonNull RequestBuilders.TileRequest req) {
        JSONObject d = Pea.get(this);
        boolean priv = d != null && d.optBoolean("privacy", false);
        JSONObject ar = d == null ? null : d.optJSONObject("arrondis");
        JSONObject g = Pea.mainGoal(d);
        LayoutElementBuilders.Column.Builder col = new LayoutElementBuilders.Column.Builder()
                .setWidth(DimensionBuilders.expand()).setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                .addContent(PeaTile.txt("Arrondis", 13, Pea.MUTED, false)).addContent(PeaTile.gap(2));
        if (ar != null) {
            col.addContent(PeaTile.txt(priv ? "••" : Pea.eur2(ar.optDouble("mois", 0)).replace("+", ""), 26, Pea.GAIN, true))
               .addContent(PeaTile.txt("mis de côté ce mois-ci", 11, Pea.MUTED, false));
            double tot = ar.optDouble("total", 0);
            if (tot > 0) col.addContent(PeaTile.txt((priv ? "••" : Pea.eur(tot)) + " depuis le début", 11, Pea.MUTED, false));
        } else col.addContent(PeaTile.txt("—", 24, Pea.INK, true)).addContent(PeaTile.txt("Ouvre Arrondis dans Capital", 11, Pea.MUTED, false));
        if (g != null) {
            double cur = g.optDouble("cur", 0), tgt = g.optDouble("tgt", 1);
            col.addContent(PeaTile.gap(10)).addContent(PeaTile.txt(g.optString("n", "Objectif"), 13, Pea.INK, true)).addContent(PeaTile.gap(4))
               .addContent(bar(cur / tgt, Pea.ACCENT)).addContent(PeaTile.gap(4))
               .addContent(PeaTile.txt(Math.round(cur / tgt * 100) + " %" + (priv ? "" : " · " + Pea.eur(cur) + " / " + Pea.eur(tgt)), 11, Pea.MUTED, false));
        }
        ModifiersBuilders.Clickable click = new ModifiersBuilders.Clickable.Builder().setId("open")
                .setOnClick(new ActionBuilders.LaunchAction.Builder().setAndroidActivity(
                        new ActionBuilders.AndroidActivity.Builder().setPackageName(getPackageName()).setClassName(WearMain.class.getName()).build()).build()).build();
        LayoutElementBuilders.Box root = new LayoutElementBuilders.Box.Builder()
                .setWidth(DimensionBuilders.expand()).setHeight(DimensionBuilders.expand())
                .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER).setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
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
