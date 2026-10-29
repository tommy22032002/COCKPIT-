package fr.tom.cockpit;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.Icon;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Calendar;
import java.util.TimeZone;

/** Notifications de Jarvis : boutons d'action, « Demander à Jarvis » (réponse dans la notification) et « Écouter ». */
final class JN {
    private JN() {}
    static final String CH = "jarvis";
    static final String KEY_Q = "jarvis_q";

    static JSONObject cfg(Context c) {
        try { return new JSONObject(Prefs.sp(c).getString("jn", "{}")); } catch (Exception e) { return new JSONObject(); }
    }
    static boolean on(Context c, String k) { return cfg(c).optBoolean(k, true); }
    static void setCfg(Context c, String json) { Prefs.sp(c).edit().putString("jn", json).apply(); JarvisAlarm.scheduleAll(c); }

    static JSONObject data(Context c) {
        try { return new JSONObject(Prefs.sp(c).getString("jdata", "{}")); } catch (Exception e) { return new JSONObject(); }
    }

    /** Heures calmes (22 h – 8 h) : pas d'alerte non demandée. */
    static boolean quiet(Context c) {
        if (!on(c, "quiet")) return false;
        int h = Calendar.getInstance(TimeZone.getTimeZone("Europe/Paris")).get(Calendar.HOUR_OF_DAY);
        return h >= 22 || h < 8;
    }

    static boolean allowed(Context c) {
        return Build.VERSION.SDK_INT < 33 || c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }

    static void channels(NotificationManager nm) {
        nm.createNotificationChannel(new NotificationChannel(CH, "Jarvis", NotificationManager.IMPORTANCE_DEFAULT));
    }

    /**
     * acts : [{label, act, data, open}] — open:true ouvre le Cockpit (avec jarvis_acts), sinon action en arrière-plan.
     * ask : ajoute « Demander à Jarvis » ; speak : texte lu par « Écouter » (null = pas de bouton).
     */
    static void post(Context c, int nid, String channel, String title, String body, String hash, JSONArray acts, boolean ask, String speak, boolean privateAmounts) {
        if (!allowed(c)) return;
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return;
        channels(nm);
        String ch = channel == null ? CH : channel;
        Notification.Builder b = new Notification.Builder(c, ch)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new Notification.BigTextStyle().bigText(body))
                .setAutoCancel(true)
                .setContentIntent(Widgets.open(c, hash == null || hash.isEmpty() ? "executif" : hash, 200 + Math.abs(nid % 700)));
        if (privateAmounts) {
            b.setVisibility(Notification.VISIBILITY_PRIVATE);
            b.setPublicVersion(new Notification.Builder(c, ch).setSmallIcon(R.drawable.ic_stat).setContentTitle(title.replaceAll("[0-9][0-9  ,.]*\\s?€", "…")).setContentText("Déverrouille pour voir le détail.").build());
        }
        int n = 0;
        if (acts != null) for (int i = 0; i < acts.length() && n < 2; i++) {
            JSONObject a = acts.optJSONObject(i);
            if (a == null) continue;
            PendingIntent pi;
            if (a.optBoolean("open")) {
                Intent in = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        .putExtra("jarvis_acts", a.optString("data", "")).putExtra("hash", a.optString("hash", "assistant"));
                pi = PendingIntent.getActivity(c, nid * 7 + i, in, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            } else {
                Intent in = new Intent(c, JNAction.class).putExtra("act", a.optString("act")).putExtra("data", a.optString("data", "")).putExtra("nid", nid)
                        .putExtra("title", title).putExtra("body", body).putExtra("hash", hash);
                pi = PendingIntent.getBroadcast(c, nid * 7 + i, in, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            }
            b.addAction(new Notification.Action.Builder(Icon.createWithResource(c, R.drawable.ic_stat), a.optString("label"), pi).build());
            n++;
        }
        if (ask && Build.VERSION.SDK_INT >= 24) {
            RemoteInput ri = new RemoteInput.Builder(KEY_Q).setLabel("Ta question à Jarvis…").build();
            Intent in = new Intent(c, JNReply.class).putExtra("nid", nid).putExtra("ctx", title + " — " + body);
            PendingIntent pi = PendingIntent.getBroadcast(c, nid * 7 + 5, in, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
            b.addAction(new Notification.Action.Builder(Icon.createWithResource(c, R.drawable.ic_stat), "Demander à Jarvis", pi).addRemoteInput(ri).setAllowGeneratedReplies(false).build());
            n++;
        }
        if (speak != null && !speak.isEmpty() && n < 3) {
            Intent in = new Intent(c, JarvisActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("speak", speak).putExtra("title", title);
            PendingIntent pi = PendingIntent.getActivity(c, nid * 7 + 6, in, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            b.addAction(new Notification.Action.Builder(Icon.createWithResource(c, R.drawable.ic_stat), "Écouter", pi).build());
        }
        nm.notify(nid, b.build());
    }

    /** Pose une question à Jarvis (résumé du cockpit déjà enregistré) ; renvoie null en cas d'échec. */
    static String jarvis(Context c, String question) {
        java.util.List<String[]> h = new java.util.ArrayList<>();
        h.add(new String[]{"user", question});
        String a = Jarvis.ask(c, h);
        if (a == null || a.startsWith("!")) return null;
        return a.replaceAll("(?s)<action>.*?</action>", "").replaceAll("\\*\\*", "").trim();
    }

    /** Actions en attente, appliquées par le Cockpit à sa prochaine ouverture. */
    static void queue(Context c, JSONObject act) {
        try {
            JSONArray a = new JSONArray(Prefs.sp(c).getString("pendingActs", "[]"));
            a.put(act);
            Prefs.sp(c).edit().putString("pendingActs", a.toString()).apply();
        } catch (Exception ignored) { }
    }
    static String take(Context c) {
        String s = Prefs.sp(c).getString("pendingActs", "[]");
        Prefs.sp(c).edit().putString("pendingActs", "[]").apply();
        return s;
    }
}
