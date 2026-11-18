package fr.tom.cockpit;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.RemoteViews;

/** Widget « Capital · Complet » : liste défilante (patrimoine, courbe, répartition, comptes, PEA, alertes, mouvements). Chaque ligne ouvre la bonne page de Capital. */
public class FullWidget extends AppWidgetProvider {
    @Override
    public void onUpdate(Context ctx, AppWidgetManager m, int[] ids) {
        render(ctx, m, ids);
        m.notifyAppWidgetViewDataChanged(ids, R.id.f_list);
    }

    @Override
    public void onAppWidgetOptionsChanged(Context ctx, AppWidgetManager m, int id, Bundle opts) {
        m.notifyAppWidgetViewDataChanged(new int[]{id}, R.id.f_list);
    }

    static void updateAll(Context ctx) {
        AppWidgetManager m = AppWidgetManager.getInstance(ctx);
        int[] ids = m.getAppWidgetIds(new ComponentName(ctx, FullWidget.class));
        if (ids != null && ids.length > 0) {
            render(ctx, m, ids);
            m.notifyAppWidgetViewDataChanged(ids, R.id.f_list);
        }
    }

    static void render(Context ctx, AppWidgetManager m, int[] ids) {
        for (int id : ids) {
            RemoteViews v = new RemoteViews(ctx.getPackageName(), R.layout.widget_full);
            Intent svc = new Intent(ctx, FullService.class);
            svc.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id);
            svc.setData(Uri.parse(svc.toUri(Intent.URI_INTENT_SCHEME)));
            v.setRemoteAdapter(R.id.f_list, svc);
            Intent t = new Intent(ctx, MainActivity.class);
            t.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent pi = PendingIntent.getActivity(ctx, 7900 + id, t, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
            v.setPendingIntentTemplate(R.id.f_list, pi);
            m.updateAppWidget(id, v);
        }
    }
}
