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

/** Tuile « Jarvis » : un appui et la montre écoute ta question, la réponse arrive du téléphone. */
public class JarvisWearTile extends TileService {
    @NonNull @Override
    protected ListenableFuture<TileBuilders.Tile> onTileRequest(@NonNull RequestBuilders.TileRequest req) {
        ModifiersBuilders.Clickable click = new ModifiersBuilders.Clickable.Builder().setId("listen")
                .setOnClick(new ActionBuilders.LaunchAction.Builder().setAndroidActivity(
                        new ActionBuilders.AndroidActivity.Builder().setPackageName(getPackageName()).setClassName(WearMain.class.getName())
                                .addKeyToExtraMapping("listen", new ActionBuilders.AndroidBooleanExtra.Builder().setValue(true).build()).build()).build())
                .build();
        String last = Pea.sp(this).getString("jarvisLast", "");
        LayoutElementBuilders.Box orb = new LayoutElementBuilders.Box.Builder()
                .setWidth(DimensionBuilders.dp(92)).setHeight(DimensionBuilders.dp(92))
                .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER).setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
                .setModifiers(new ModifiersBuilders.Modifiers.Builder().setClickable(click)
                        .setBackground(new ModifiersBuilders.Background.Builder().setColor(ColorBuilders.argb(0xFF14233A))
                                .setCorner(new ModifiersBuilders.Corner.Builder().setRadius(DimensionBuilders.dp(46)).build()).build())
                        .setBorder(new ModifiersBuilders.Border.Builder().setWidth(DimensionBuilders.dp(3)).setColor(ColorBuilders.argb(Pea.ACCENT)).build()).build())
                .addContent(PeaTile.txt("🎙", 30, Pea.INK, false)).build();
        LayoutElementBuilders.Column.Builder col = new LayoutElementBuilders.Column.Builder()
                .setWidth(DimensionBuilders.expand()).setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                .addContent(PeaTile.txt("Jarvis", 14, Pea.ACCENT, true)).addContent(PeaTile.gap(8)).addContent(orb).addContent(PeaTile.gap(8))
                .addContent(PeaTile.txt("Touche pour parler", 12, Pea.MUTED, false));
        if (!last.isEmpty()) col.addContent(PeaTile.gap(4)).addContent(PeaTile.txt(last.length() > 34 ? last.substring(0, 34) + "…" : last, 10, Pea.MUTED, false));
        LayoutElementBuilders.Box root = new LayoutElementBuilders.Box.Builder()
                .setWidth(DimensionBuilders.expand()).setHeight(DimensionBuilders.expand())
                .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER).setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
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
