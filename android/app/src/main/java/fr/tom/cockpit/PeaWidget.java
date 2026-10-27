package fr.tom.cockpit;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.os.Bundle;
import android.os.Bundle;

/** Petit widget : valeur du PEA et variation du jour ; élargi, il affiche la répartition en anneau. */
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

    @Override
    public void onAppWidgetOptionsChanged(Context ctx, AppWidgetManager m, int id, Bundle opts) {
        Widgets.render(ctx, m, new int[]{id}, false);
    }

    @Override
    public void onAppWidgetOptionsChanged(Context ctx, AppWidgetManager m, int id, Bundle opts) {
        // agrandi : on affiche le camembert à côté ; réduit : on le cache
        Widgets.render(ctx, m, new int[]{id}, false);
    }
}
