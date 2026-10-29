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

/** Tuile « PEA du jour » : valeur, variation du jour, gain total. */
public class PeaTile extends TileService {

    static LayoutElementBuilders.LayoutElement txt(String s, float sp, int color, boolean bold) {
        LayoutElementBuilders.FontStyle.Builder f = new LayoutElementBuilders.FontStyle.Builder()
                .setSize(DimensionBuilders.sp(sp)).setColor(ColorBuilders.argb(color));
        if (bold) f.setWeight(LayoutElementBuilders.FONT_WEIGHT_BOLD);
        return new LayoutElementBuilders.Text.Builder().setText(s).setFontStyle(f.build()).setMaxLines(1).build();
    }

    static LayoutElementBuilders.LayoutElement gap(float dp) {
        return new LayoutElementBuilders.Spacer.Builder().setHeight(DimensionBuilders.dp(dp)).build();
    }

    @NonNull @Override
    protected ListenableFuture<TileBuilders.Tile> onTileRequest(@NonNull RequestBuilders.TileRequest req) {
        Pea.refreshAsync(this, false);
        JSONObject d = Pea.get(this);
        LayoutElementBuilders.Column.Builder col = new LayoutElementBuilders.Column.Builder()
                .setWidth(DimensionBuilders.expand())
                .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                .addContent(txt("PEA du jour", 13, Pea.MUTED, false))
                .addContent(gap(4));
        if (d == null) {
            col.addContent(txt("—", 30, Pea.INK, true)).addContent(gap(6))
               .addContent(txt("Ouvre le Cockpit", 13, Pea.MUTED, false))
               .addContent(txt("sur ton téléphone", 13, Pea.MUTED, false));
        } else {
            boolean priv = d.optBoolean("privacy", false);
            double day = d.optDouble("dayAbs", 0), dp = d.optDouble("dayPct", 0), g = d.optDouble("gain", 0);
            int c = day >= 0 ? Pea.GAIN : Pea.LOSS;
            col.addContent(txt(priv ? "•••• €" : Pea.eur(d.optDouble("value", 0)), 30, Pea.INK, true))
               .addContent(gap(2))
               .addContent(txt(Pea.pct(dp), 18, c, true))
               .addContent(txt(priv ? "••" : Pea.eur2(day) + " aujourd'hui", 13, c, false))
               .addContent(gap(6))
               .addContent(txt("Gain total " + (priv ? "••" : Pea.eur2(g)), 12, Pea.MUTED, false))
               .addContent(txt(Pea.saison(d), 11, Pea.ACCENT, false))
               .addContent(txt((Pea.marketOpen() ? "● Bourse ouverte · " : "Bourse fermée · ") + Pea.at(d), 11, Pea.MUTED, false));
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
        TileBuilders.Tile tile = new TileBuilders.Tile.Builder()
                .setResourcesVersion("1")
                .setFreshnessIntervalMillis(Pea.marketOpen() ? 15 * 60_000L : 60 * 60_000L)
                .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(root))
                .build();
        return Futures.immediateFuture(tile);
    }

    @NonNull @Override
    protected ListenableFuture<ResourceBuilders.Resources> onTileResourcesRequest(@NonNull RequestBuilders.ResourcesRequest req) {
        return Futures.immediateFuture(new ResourceBuilders.Resources.Builder().setVersion("1").build());
    }
}
