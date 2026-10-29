package fr.tom.cockpit;

import android.Manifest;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Calendar;
import java.util.TimeZone;

/** Rappels programmés par Android (jour du DCA, mise à jour du 15, relevés, échéances). */
final class Reminders {
    private Reminders() {}

    static final String CHANNEL = "rappels";

    static void schedule(Context ctx) {
        AlarmManager am = ctx.getSystemService(AlarmManager.class);
        if (am == null) return;
        try {
            JSONArray arr = new JSONArray(Prefs.reminders(ctx));
            long now = System.currentTimeMillis();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject r = arr.getJSONObject(i);
                String at = r.optString("at", "");
                if (at.length() < 10) continue;
                Calendar c = Calendar.getInstance(TimeZone.getTimeZone("Europe/Paris"));
                c.set(Integer.parseInt(at.substring(0, 4)), Integer.parseInt(at.substring(5, 7)) - 1,
                        Integer.parseInt(at.substring(8, 10)), 9, 0, 0);
                long t = c.getTimeInMillis();
                if (t < now - 6L * 3600_000L) continue;
                if (t < now) t = now + 60_000L;
                Intent in = new Intent(ctx, ReminderReceiver.class);
                in.putExtra("id", r.optString("id"));
                in.putExtra("title", r.optString("title"));
                in.putExtra("body", r.optString("body"));
                in.putExtra("hash", r.optString("hash"));
                if (r.has("acts")) in.putExtra("acts", r.optJSONArray("acts").toString());
                PendingIntent pi = PendingIntent.getBroadcast(ctx, r.optString("id").hashCode(), in,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi);
            }
        } catch (Exception ignored) { }
    }

    static void show(Context ctx, String id, String title, String body, String hash, String acts) {
        JSONArray a = null;
        try { if (acts != null && acts.startsWith("[")) a = new JSONArray(acts); } catch (Exception ignored) { }
        JN.post(ctx, id.hashCode(), CHANNEL_ID(ctx), title, body, hash, a, true, null, false);
    }

    static String CHANNEL_ID(Context ctx) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm != null) nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Rappels du cockpit", NotificationManager.IMPORTANCE_DEFAULT));
        return CHANNEL;
    }

    static void show(Context ctx, String id, String title, String body, String hash) { show(ctx, id, title, body, hash, null); }

    static void showOld(Context ctx, String id, String title, String body, String hash) {
        if (Build.VERSION.SDK_INT >= 33
                && ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return;
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm == null) return;
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Rappels du cockpit", NotificationManager.IMPORTANCE_DEFAULT));
        Notification n = new Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new Notification.BigTextStyle().bigText(body))
                .setAutoCancel(true)
                .setContentIntent(Widgets.open(ctx, hash == null || hash.isEmpty() ? "executif" : hash, 100 + Math.abs(id.hashCode() % 1000)))
                .build();
        nm.notify(id.hashCode(), n);
    }
}
