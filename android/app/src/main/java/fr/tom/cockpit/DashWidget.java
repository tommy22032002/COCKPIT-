package fr.tom.cockpit;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

/** Grand widget : courbe du PEA (1 mois, 3 mois, 1 an, tout), apport, objectif, prochain achat. */
public class DashWidget extends AppWidgetProvider {
    static final String ACTION_PERIOD = "fr.tom.cockpit.PERIOD";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (ACTION_PERIOD.equals(intent.getAction())) {
            int id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1);
            if (id != -1) {
                Prefs.setPeriod(ctx, id, intent.getIntExtra("per", 1));
                Widgets.render(ctx, AppWidgetManager.getInstance(ctx), new int[]{id}, true);
            }
            return;
        }
        super.onReceive(ctx, intent);
    }

    @Override
    public void onUpdate(Context ctx, AppWidgetManager m, int[] ids) {
        Widgets.render(ctx, m, ids, true);
        final PendingResult pr = goAsync();
        final Context app = ctx.getApplicationContext();
        new Thread(() -> {
            try { Widgets.refreshPrices(app, false); } finally { pr.finish(); }
        }).start();
    }

    @Override
    public void onAppWidgetOptionsChanged(Context ctx, AppWidgetManager m, int id, Bundle opts) {
        // le widget a été agrandi ou réduit : on redessine la courbe à la bonne taille
        Widgets.render(ctx, m, new int[]{id}, true);
    }
}
