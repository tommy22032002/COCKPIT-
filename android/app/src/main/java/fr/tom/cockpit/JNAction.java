package fr.tom.cockpit;

import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

import org.json.JSONObject;

/** Boutons des notifications : décisions enregistrées tout de suite, appliquées par le Cockpit à sa prochaine ouverture. */
public class JNAction extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        String act = intent.getStringExtra("act"), data = intent.getStringExtra("data");
        int nid = intent.getIntExtra("nid", 0);
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        try {
            switch (act == null ? "" : act) {
                case "wish_buy":
                case "wish_drop":
                    JN.queue(ctx, new JSONObject().put("t", act).put("id", data));
                    if (nm != null) nm.cancel(nid);
                    Toast.makeText(ctx, act.equals("wish_buy") ? "Noté : achat ajouté à tes envies du mois." : "Bravo, envie abandonnée : argent sauvé !", Toast.LENGTH_LONG).show();
                    break;
                case "snooze":
                    if (nm != null) nm.cancel(nid);
                    JarvisAlarm.repost(ctx, nid, intent.getStringExtra("title"), intent.getStringExtra("body"), intent.getStringExtra("hash"), data, 2 * 3600_000L);
                    Toast.makeText(ctx, "Je te le rappelle dans 2 heures.", Toast.LENGTH_SHORT).show();
                    break;
                case "done":
                    JN.queue(ctx, new JSONObject().put("t", "done").put("id", data));
                    if (nm != null) nm.cancel(nid);
                    Toast.makeText(ctx, "C'est noté.", Toast.LENGTH_SHORT).show();
                    break;
                default:
                    break;
            }
        } catch (Exception ignored) { }
    }
}
