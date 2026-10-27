package fr.tom.cockpit;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Après un redémarrage du téléphone : reprogramme les rappels et redessine les widgets. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            Reminders.schedule(ctx);
            Widgets.updateAll(ctx);
        }
    }
}
