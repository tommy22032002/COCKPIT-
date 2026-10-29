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

/** Tuile « Budget du mois » : ce qu'il reste dans l'enveloppe plaisir, par jour, avec une jauge. */
public class BudgetTile extends TileService {

    @NonNull @Override
    protected ListenableFuture<TileBuilders.Tile> onTileRequest(@NonNull RequestBuilders.TileRequest req) {
        JSONObject d = Pea.get(this);
        LayoutElementBuilders.Column.Builder col = new LayoutElementBuilders.Column.Builder()
                .setWidth(DimensionBuilders.expand())
                .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                .addContent(PeaTile.txt("Budget " + (d == null ? "du mois" : d.optString("month", "du mois")), 13, Pea.MUTED, false))
                .addContent(PeaTile.gap(4));
        if (d == null || d.optDouble("envCap", 0) <= 0) {
            col.addContent(PeaTile.txt("—", 28, Pea.INK, true)).addContent(PeaTile.gap(6))
               .addContent(PeaTile.txt("Ouvre le Cockpit", 13, Pea.MUTED, false))
               .addContent(PeaTile.txt("sur ton téléphone", 13, Pea.MUTED, false));
        } else {
            boolean priv = d.optBoolean("privacy", false);
            double cap = d.optDouble("envCap", 0), spent = d.optDouble("envSpent", 0), left = cap - spent;
            int days = Math.max(1, (int) d.optDouble("daysLeft", 1));
            int c = left < 0 ? Pea.LOSS : spent / cap > 0.8 ? 0xFFF2B35B : Pea.GAIN;
            float barW = 120f, fill = (float) Math.max(0, Math.min(1, spent / cap)) * barW;
            LayoutElementBuilders.Box bar = new LayoutElementBuilders.Box.Builder()
                    .setWidth(DimensionBuilders.dp(barW)).setHeight(DimensionBuilders.dp(8))
                    .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_START)
                    .setModifiers(new ModifiersBuilders.Modifiers.Builder().setBackground(new ModifiersBuilders.Background.Builder()
                            .setColor(ColorBuilders.argb(0xFF2A3040)).setCorner(new ModifiersBuilders.Corner.Builder().setRadius(DimensionBuilders.dp(4)).build()).build()).build())
                    .addContent(new LayoutElementBuilders.Box.Builder().setWidth(DimensionBuilders.dp(Math.max(0.1f, fill))).setHeight(DimensionBuilders.dp(8))
                            .setModifiers(new ModifiersBuilders.Modifiers.Builder().setBackground(new ModifiersBuilders.Background.Builder()
                                    .setColor(ColorBuilders.argb(c)).setCorner(new ModifiersBuilders.Corner.Builder().setRadius(DimensionBuilders.dp(4)).build()).build()).build()).build())
                    .build();
            col.addContent(PeaTile.txt(left >= 0 ? "Il te reste" : "Dépassé de", 12, Pea.MUTED, false))
               .addContent(PeaTile.txt(priv ? "•• €" : Pea.eur(Math.abs(left)), 28, c, true))
               .addContent(PeaTile.gap(4))
               .addContent(bar)
               .addContent(PeaTile.gap(4))
               .addContent(PeaTile.txt((priv ? "••" : Pea.eur(spent)) + " sur " + Pea.eur(cap), 12, Pea.MUTED, false))
               .addContent(PeaTile.txt(left > 0 ? "≈ " + (priv ? "••" : Pea.eur(left / days)) + " par jour · " + days + " j" : "Pause plaisir jusqu'au 1er", 12, Pea.INK, false));
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
