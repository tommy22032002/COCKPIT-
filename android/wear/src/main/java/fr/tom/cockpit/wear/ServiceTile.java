package fr.tom.cockpit.wear;

import android.content.SharedPreferences;

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

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Tuile « Service » : pointer l'arrivée et le départ depuis la montre, pourboires +2 / +5 €. Le téléphone note tout dans Capital. */
public class ServiceTile extends TileService {
    static LayoutElementBuilders.LayoutElement button(String id, String label, int bg, int fg, float w, float h, float sp) {
        ModifiersBuilders.Clickable c = new ModifiersBuilders.Clickable.Builder().setId(id)
                .setOnClick(new ActionBuilders.LoadAction.Builder().build()).build();
        return new LayoutElementBuilders.Box.Builder()
                .setWidth(DimensionBuilders.dp(w)).setHeight(DimensionBuilders.dp(h))
                .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER).setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
                .setModifiers(new ModifiersBuilders.Modifiers.Builder().setClickable(c)
                        .setBackground(new ModifiersBuilders.Background.Builder().setColor(ColorBuilders.argb(bg))
                                .setCorner(new ModifiersBuilders.Corner.Builder().setRadius(DimensionBuilders.dp(h / 2)).build()).build()).build())
                .addContent(PeaTile.txt(label, sp, fg, true)).build();
    }

    static String now() { return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(new Date()); }
    static void send(TileService t, String path, String txt) { new Thread(() -> Pea.askPhone(t, path, txt)).start(); }

    @NonNull @Override
    protected ListenableFuture<TileBuilders.Tile> onTileRequest(@NonNull RequestBuilders.TileRequest req) {
        SharedPreferences sp = Pea.sp(this);
        JSONObject d = Pea.get(this);
        JSONObject so = d == null ? null : d.optJSONObject("service");
        long local = sp.getLong("svcS", 0), localAt = sp.getLong("svcAt", 0);
        long phone = 0; double todayH = so == null ? 0 : so.optDouble("today", 0);
        if (so != null && so.has("cur")) { try { phone = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US) { { setTimeZone(java.util.TimeZone.getTimeZone("UTC")); } }.parse(so.optString("cur").substring(0, 19)).getTime(); } catch (Exception ignored) { } }
        boolean fresh = System.currentTimeMillis() - localAt < 6 * 3600_000L;
        long start = fresh ? local : phone;
        String clicked = "";
        try { clicked = req.getCurrentState().getLastClickableId(); } catch (Exception ignored) { }
        String msg = null;
        if ("svc".equals(clicked)) {
            if (start > 0) {
                long min = (System.currentTimeMillis() - start) / 60_000L;
                send(this, "/pea/svc", "stop|" + now());
                msg = "Fin : " + (min / 60) + " h " + String.format(Locale.FRANCE, "%02d", min % 60);
                start = 0;
            } else {
                send(this, "/pea/svc", "start|" + now());
                start = System.currentTimeMillis(); msg = "Bon service !";
            }
            sp.edit().putLong("svcS", start).putLong("svcAt", System.currentTimeMillis()).apply();
        } else if ("tip2".equals(clicked) || "tip5".equals(clicked)) {
            int v = "tip2".equals(clicked) ? 2 : 5;
            send(this, "/pea/tip", String.valueOf(v));
            int n = sp.getInt("tipToday", 0); String day = sp.getString("tipDay", "");
            String t = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
            if (!t.equals(day)) n = 0;
            n += v; sp.edit().putInt("tipToday", n).putString("tipDay", t).apply();
            msg = "+" + v + " € · " + n + " € aujourd'hui";
        }
        boolean on = start > 0;
        LayoutElementBuilders.Column.Builder col = new LayoutElementBuilders.Column.Builder()
                .setWidth(DimensionBuilders.expand()).setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                .addContent(PeaTile.txt("Service", 13, Pea.MUTED, false)).addContent(PeaTile.gap(4));
        if (on) col.addContent(PeaTile.txt("depuis " + new SimpleDateFormat("HH:mm", Locale.FRANCE).format(new Date(start)), 18, Pea.GAIN, true));
        else col.addContent(PeaTile.txt(todayH > 0 ? Pea.fh(todayH) + " aujourd'hui" : "Pas en service", 16, Pea.INK, true));
        col.addContent(PeaTile.gap(8)).addContent(button("svc", on ? "Je pars" : "J'arrive", on ? Pea.LOSS : Pea.GAIN, 0xFF000000, 130, 46, 17)).addContent(PeaTile.gap(8));
        col.addContent(new LayoutElementBuilders.Row.Builder()
                .addContent(button("tip2", "+2 €", 0xFF1E2633, Pea.INK, 60, 36, 14))
                .addContent(new LayoutElementBuilders.Spacer.Builder().setWidth(DimensionBuilders.dp(8)).build())
                .addContent(button("tip5", "+5 €", 0xFF1E2633, Pea.INK, 60, 36, 14)).build());
        if (msg != null) col.addContent(PeaTile.gap(6)).addContent(PeaTile.txt(msg, 11, Pea.ACCENT, false));
        LayoutElementBuilders.Box root = new LayoutElementBuilders.Box.Builder()
                .setWidth(DimensionBuilders.expand()).setHeight(DimensionBuilders.expand())
                .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER).setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                .addContent(col.build()).build();
        return Futures.immediateFuture(new TileBuilders.Tile.Builder().setResourcesVersion("1")
                .setFreshnessIntervalMillis(on ? 60_000L : 30 * 60_000L)
                .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(root)).build());
    }

    @NonNull @Override
    protected ListenableFuture<ResourceBuilders.Resources> onTileResourcesRequest(@NonNull RequestBuilders.ResourcesRequest req) {
        return Futures.immediateFuture(new ResourceBuilders.Resources.Builder().setVersion("1").build());
    }
}
