package fr.tom.cockpit;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;

/** Petit widget : valeur du PEA et variation du jour. */
public class PeaWidget extends AppWidgetProvider {
    @Override
    public void onUpdate(Context ctx, AppWidgetManager m, int[] ids) {
        Widgets.render(ctx, m, ids, false);
        final PendingResult pr = goAsync();
        final Context app = ctx.getApplicationContext();
        new Thread(() -> {
            try { Widgets.refreshPrices(app, false); } finally { pr.finish(); }
        }).start();
    }
}
