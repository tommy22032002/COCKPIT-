package fr.tom.cockpit;

import android.Manifest;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.SystemClock;

import org.json.JSONObject;

/** Écran verrouillé : une notification discrète et permanente avec le PEA et/ou le patrimoine net, mise à jour avec les cours. */
public class LockNotif extends BroadcastReceiver {
    static final String CHANNEL = "ecran";
    static final int NID = 4545;

    /** Réglage envoyé par l'appli dans les données du widget : lock = {on, show: pea|net|both, hide}. */
    static JSONObject cfg(JSONObject d) { JSONObject l = d.optJSONObject("lock"); return l != null ? l : new JSONObject(); }

    static void update(Context c) {
        try {
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            if (nm == null) return;
            String s = Prefs.widgetJson(c);
            JSONObject d = s != null && s.length() > 2 ? new JSONObject(s) : new JSONObject();
            JSONObject l = cfg(d);
            if (!l.optBoolean("on", false)) { nm.cancel(NID); schedule(c, false); return; }
            if (Build.VERSION.SDK_INT >= 33 && c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return;
            schedule(c, true);
            String show = l.optString("show", "both");
            double pea = d.optDouble("value", 0), dayPct = d.optDouble("dayPct", 0), dayAbs = d.optDouble("dayAbs", 0);
            // patrimoine net = partie hors PEA (calculée par l'appli) + PEA au dernier cours
            double net = d.has("netExPea") ? d.optDouble("netExPea", 0) + pea : d.optDouble("net", 0);
            String peaTxt = "PEA " + Widgets.eur(pea, false) + " · " + Widgets.pct(dayPct) + " (" + Widgets.signedEur(dayAbs) + ")";
            String netTxt = "Patrimoine net " + Widgets.eur(net, false);
            String title, text;
            if ("pea".equals(show)) { title = peaTxt; text = "Aujourd'hui"; }
            else if ("net".equals(show)) { title = netTxt; text = "PEA " + Widgets.eur(pea, false) + " · " + Widgets.pct(dayPct); }
            else { title = netTxt; text = peaTxt; }
            String at = d.optString("at", "");
            if (!at.isEmpty()) text += " · " + Widgets.ago(at);

            NotificationChannel ch = new NotificationChannel(CHANNEL, "Écran verrouillé", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Ton PEA et ton patrimoine net, visibles sur l'écran verrouillé.");
            ch.setShowBadge(false);
            ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            nm.createNotificationChannel(ch);
            Notification.Builder b = new Notification.Builder(c, CHANNEL)
                    .setSmallIcon(R.drawable.ic_stat)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .setShowWhen(false)
                    .setCategory(Notification.CATEGORY_STATUS)
                    .setContentIntent(Widgets.open(c, "net".equals(show) ? "patrimoine" : "portefeuille", 4546));
            if (l.optBoolean("hide", false)) {
                b.setVisibility(Notification.VISIBILITY_PRIVATE);
                b.setPublicVersion(new Notification.Builder(c, CHANNEL).setSmallIcon(R.drawable.ic_stat)
                        .setContentTitle("Capital").setContentText("PEA " + Widgets.pct(dayPct) + " aujourd'hui").build());
            } else b.setVisibility(Notification.VISIBILITY_PUBLIC);
            nm.notify(NID, b.build());
        } catch (Exception ignored) { }
    }

    /** Actualise les cours environ toutes les 30 min (les règles du widget espacent hors séance) tant que l'option est active. */
    static void schedule(Context c, boolean on) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        if (am == null) return;
        PendingIntent pi = PendingIntent.getBroadcast(c, 4547, new Intent(c, LockNotif.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        if (!on) { am.cancel(pi); return; }
        // reprogrammé à chaque mise à jour : la prochaine actualisation a lieu ~30 min après la dernière
        am.setInexactRepeating(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + AlarmManager.INTERVAL_HALF_HOUR, AlarmManager.INTERVAL_HALF_HOUR, pi);
    }

    @Override
    public void onReceive(Context ctx, Intent intent) {
        final PendingResult pr = goAsync();
        final Context app = ctx.getApplicationContext();
        new Thread(() -> {
            try { Widgets.refreshPrices(app, false); update(app); } catch (Exception ignored) { } finally { pr.finish(); }
        }).start();
    }
}
