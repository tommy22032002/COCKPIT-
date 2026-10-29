package fr.tom.cockpit;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.TileService;

/** Tuile « Jarvis » dans les réglages rapides : ouvre la bulle Jarvis. */
public class JarvisTile extends TileService {
    @Override
    @SuppressWarnings("deprecation")
    public void onClick() {
        Intent i = new Intent(this, JarvisActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(PendingIntent.getActivity(this, 7, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        else startActivityAndCollapse(i);
    }
}
