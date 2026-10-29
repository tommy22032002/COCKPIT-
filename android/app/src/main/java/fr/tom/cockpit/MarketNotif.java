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

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

/** Points bourse réguliers en semaine (par défaut 10 h, 13 h 30 et 17 h 45) : ton PEA du jour et les grands indices. */
public class MarketNotif extends BroadcastReceiver {
    static final String CHANNEL = "bourse";
    static final String[][] IDX = {{"^FCHI", "CAC 40"}, {"^STOXX", "Stoxx 600"}, {"^GSPC", "S&P 500"}, {"^IXIC", "Nasdaq"}};

    static JSONObject cfg(Context c) {
        try { return new JSONObject(Prefs.sp(c).getString("mkt", "{\"on\":true,\"slots\":[600,810,1065]}")); } catch (Exception e) { return new JSONObject(); }
    }

    static void setCfg(Context c, String json) { Prefs.sp(c).edit().putString("mkt", json).apply(); schedule(c); }

    /** Programme la prochaine notification (jours de semaine uniquement). */
    static void schedule(Context c) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        if (am == null) return;
        PendingIntent pi = PendingIntent.getBroadcast(c, 4242, new Intent(c, MarketNotif.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        am.cancel(pi);
        JSONObject cf = cfg(c);
        if (!cf.optBoolean("on", true)) return;
        JSONArray slots = cf.optJSONArray("slots");
        if (slots == null || slots.length() == 0) return;
        Calendar now = Calendar.getInstance(TimeZone.getTimeZone("Europe/Paris"));
        for (int day = 0; day < 8; day++) {
            Calendar d = (Calendar) now.clone();
            d.add(Calendar.DAY_OF_YEAR, day);
            int dow = d.get(Calendar.DAY_OF_WEEK);
            if (dow == Calendar.SATURDAY || dow == Calendar.SUNDAY) continue;
            for (int i = 0; i < slots.length(); i++) {
                int m = slots.optInt(i);
                d.set(Calendar.HOUR_OF_DAY, m / 60); d.set(Calendar.MINUTE, m % 60); d.set(Calendar.SECOND, 0); d.set(Calendar.MILLISECOND, 0);
                if (d.getTimeInMillis() > now.getTimeInMillis() + 30_000L) {
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, d.getTimeInMillis(), pi);
                    return;
                }
            }
        }
    }

    @Override
    public void onReceive(Context ctx, Intent intent) {
        final PendingResult pr = goAsync();
        final Context app = ctx.getApplicationContext();
        new Thread(() -> {
            try { post(app, intent.getBooleanExtra("test", false)); } catch (Exception ignored) { }
            finally { if (!intent.getBooleanExtra("test", false)) schedule(app); pr.finish(); }
        }).start();
    }

    static String pct(double v) { return String.format(Locale.FRANCE, "%+.2f %%", v * 100).replace("+", "+").replace("-", "−"); }

    static void post(Context ctx, boolean test) {
        if (Build.VERSION.SDK_INT >= 33 && ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return;
        Calendar now = Calendar.getInstance(TimeZone.getTimeZone("Europe/Paris"));
        int hm = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
        String when = hm < 9 * 60 ? "avant l'ouverture" : hm >= 17 * 60 + 35 ? "clôture" : String.format(Locale.FRANCE, "%dh%02d", hm / 60, hm % 60);
        // ton PEA (cours rafraîchis tout de suite)
        Widgets.refreshPrices(ctx, true);
        String title;
        try {
            JSONObject d = new JSONObject(Prefs.widgetJson(ctx));
            double dayPct = d.optDouble("dayPct", 0), dayAbs = d.optDouble("dayAbs", 0), value = d.optDouble("value", 0);
            title = "Bourse · " + when + " : ton PEA " + pct(dayPct) + " (" + Widgets.signedEur(dayAbs) + ")";
            if (value > 0) title += " · " + Widgets.eur(value, false);
        } catch (Exception e) { title = "Bourse · " + when; }
        // grands indices
        StringBuilder sb = new StringBuilder();
        for (String[] x : IDX) {
            JSONObject q = Quotes.fetch(x[0]);
            if (q == null || q.optDouble("prev", 0) <= 0) continue;
            double v = q.optDouble("p") / q.optDouble("prev") - 1;
            if (sb.length() > 0) sb.append(" · ");
            sb.append(x[1]).append(" ").append(pct(v));
        }
        boolean us = hm >= 15 * 60 + 30;
        String body = sb.length() > 0 ? sb.toString() : "Cours indisponibles pour le moment.";
        if (!us && sb.length() > 0) body += "\n(Wall Street : séance d'hier, elle ouvre à 15 h 30.)";
        boolean close = hm >= 17 * 60 + 30;
        if (close && JN.on(ctx, "close")) {
            String a = JN.jarvis(ctx, "Clôture de la Bourse aujourd'hui. " + title + ". Indices : " + sb + ". En 2 phrases courtes, explique simplement ce qui a fait bouger mon PEA aujourd'hui (cherche l'actualité du jour) et dis si je dois faire quelque chose (en général : rien, le plan continue).");
            if (a != null) body += "\n\nJarvis : " + a;
        }
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Points bourse", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Ton PEA et les grands indices, quelques fois par jour en semaine.");
        nm.createNotificationChannel(ch);
        JN.post(ctx, 4343, CHANNEL, title, body, "journal", null, true, body.replace("\n", " ").replace("€", " euros"), true);
    }
}
