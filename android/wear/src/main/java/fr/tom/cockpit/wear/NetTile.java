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

import org.json.JSONArray;
import org.json.JSONObject;

/** Tuile « Patrimoine » : le total net, sa variation sur 1 mois et la répartition (comme l'accueil de Capital). */
public class NetTile extends TileService {
    @NonNull @Override
    protected ListenableFuture<TileBuilders.Tile> onTileRequest(@NonNull RequestBuilders.TileRequest req) {
        JSONObject d = Pea.get(this);
        LayoutElementBuilders.Column.Builder col = new LayoutElementBuilders.Column.Builder()
                .setWidth(DimensionBuilders.expand())
                .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                .addContent(PeaTile.txt("Patrimoine net", 13, Pea.MUTED, false))
                .addContent(PeaTile.gap(4));
        if (d == null || !d.has("net")) {
            col.addContent(PeaTile.txt("—", 30, Pea.INK, true)).addContent(PeaTile.gap(6)).addContent(PeaTile.txt("Ouvre Capital sur ton téléphone", 12, Pea.MUTED, false));
        } else {
            boolean priv = d.optBoolean("privacy", false);
            double net = d.optDouble("net", 0);
            JSONArray h = d.optJSONArray("netHist");
            double first = h != null && h.length() > 1 ? h.optDouble(0, net) : net, dv = net - first;
            int c = dv >= 0 ? Pea.GAIN : Pea.LOSS;
            col.addContent(PeaTile.txt(priv ? "•••• €" : Pea.eur(net), 28, Pea.INK, true))
               .addContent(PeaTile.gap(2))
               .addContent(PeaTile.txt(priv ? "••" : (dv >= 0 ? "▲ " : "▼ ") + Pea.eur2(dv) + " · 1 mois", 14, c, true))
               .addContent(PeaTile.gap(6));
            JSONObject p = d.optJSONObject("netParts");
            if (p != null && !priv) {
                col.addContent(PeaTile.txt("Livrets " + Pea.eur(p.optDouble("livrets", 0)), 12, Pea.MUTED, false))
                   .addContent(PeaTile.txt("PEA " + Pea.eur(p.optDouble("pea", 0)) + " · " + Pea.pct(d.optDouble("dayPct", 0)), 12, d.optDouble("dayAbs", 0) >= 0 ? Pea.GAIN : Pea.LOSS, false))
                   .addContent(PeaTile.txt("Assurance-vie " + Pea.eur(p.optDouble("av", 0)), 12, Pea.MUTED, false));
            }
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
        return Futures.immediateFuture(new TileBuilders.Tile.Builder().setResourcesVersion("1").setFreshnessIntervalMillis(60 * 60_000L)
                .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(root)).build());
    }

    @NonNull @Override
    protected ListenableFuture<ResourceBuilders.Resources> onTileResourcesRequest(@NonNull RequestBuilders.ResourcesRequest req) {
        return Futures.immediateFuture(new ResourceBuilders.Resources.Builder().setVersion("1").build());
    }
}
