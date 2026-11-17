package fr.tom.cockpit;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.os.Bundle;
import android.widget.RemoteViews;

import org.json.JSONObject;

/** Widget « Patrimoine » : même carte que l'accueil de l'appli et l'écran verrouillé (total, variation sur 1 mois, courbe). */
public class NetWidget extends AppWidgetProvider {
    @Override
    public void onUpdate(Context ctx, AppWidgetManager m, int[] ids) { render(ctx, m, ids); }

    @Override
    public void onAppWidgetOptionsChanged(Context ctx, AppWidgetManager m, int id, Bundle opts) { render(ctx, m, new int[]{id}); }

    static void updateAll(Context ctx) {
        AppWidgetManager m = AppWidgetManager.getInstance(ctx);
        int[] ids = m.getAppWidgetIds(new ComponentName(ctx, NetWidget.class));
        if (ids != null && ids.length > 0) render(ctx, m, ids);
    }

    static void render(Context ctx, AppWidgetManager m, int[] ids) {
        JSONObject d;
        try { String s = Prefs.widgetJson(ctx); d = s != null && s.length() > 2 ? new JSONObject(s) : new JSONObject(); } catch (Exception e) { d = new JSONObject(); }
        String show = "net";
        try { JSONObject l = d.optJSONObject("lock"); if (l != null && "pea".equals(l.optString("show"))) show = "net"; } catch (Exception ignored) { }
        for (int id : ids) {
            int[] wh = More.size(ctx, m, id);
            int w = wh != null ? wh[0] : 250, h = wh != null ? wh[1] : 110;
            RemoteViews v = new RemoteViews(ctx.getPackageName(), R.layout.widget_net);
            try {
                v.setImageViewBitmap(R.id.n_img, LockCard.draw(ctx, d, show, w, h));
            } catch (Exception ignored) { }
            v.setOnClickPendingIntent(R.id.n_img, Widgets.open(ctx, "comptes", 7700 + id));
            m.updateAppWidget(id, v);
        }
    }
}
