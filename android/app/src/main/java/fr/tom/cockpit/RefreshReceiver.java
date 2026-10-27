package fr.tom.cockpit;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

/** Bouton ↻ du widget : actualise les cours tout de suite. */
public class RefreshReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        final PendingResult pr = goAsync();
        final Context app = ctx.getApplicationContext();
        Toast.makeText(app, "Actualisation des cours…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try { Widgets.refreshPrices(app, true); } finally { pr.finish(); }
        }).start();
    }
}
