package fr.tom.cockpit;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;

/** Grand widget : PEA, envies du mois, apport, objectif 100 000 €, prochain achat. */
public class DashWidget extends AppWidgetProvider {
    @Override
    public void onUpdate(Context ctx, AppWidgetManager m, int[] ids) {
        Widgets.render(ctx, m, ids, true);
        final PendingResult pr = goAsync();
        final Context app = ctx.getApplicationContext();
        new Thread(() -> {
            try { Widgets.refreshPrices(app, false); } finally { pr.finish(); }
        }).start();
    }
}
