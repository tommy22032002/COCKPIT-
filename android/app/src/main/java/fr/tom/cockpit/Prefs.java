package fr.tom.cockpit;

import android.content.Context;
import android.content.SharedPreferences;

/** Petites données gardées par l'appli : dernier état envoyé par le cockpit, réglages natifs. */
final class Prefs {
    private Prefs() {}

    static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("cockpit", Context.MODE_PRIVATE);
    }

    static String widgetJson(Context c) { return sp(c).getString("widget", ""); }
    static void setWidgetJson(Context c, String json) { sp(c).edit().putString("widget", json).apply(); }

    static String reminders(Context c) { return sp(c).getString("reminders", "[]"); }
    static void setReminders(Context c, String json) { sp(c).edit().putString("reminders", json).apply(); }

    static boolean bio(Context c) { return sp(c).getBoolean("bio", true); }
    static void setBio(Context c, boolean on) { sp(c).edit().putBoolean("bio", on).apply(); }

    static long lastFetch(Context c) { return sp(c).getLong("lastFetch", 0L); }
    static void setLastFetch(Context c, long t) { sp(c).edit().putLong("lastFetch", t).apply(); }

    /** Nombre d'appels Alpha Vantage du jour (limite gratuite : 25 par jour). */
    static int quota(Context c, String day) {
        return day.equals(sp(c).getString("quotaDay", "")) ? sp(c).getInt("quotaN", 0) : 0;
    }
    static void addQuota(Context c, String day, int n) {
        int cur = quota(c, day);
        sp(c).edit().putString("quotaDay", day).putInt("quotaN", cur + n).apply();
    }

    static String lastDropAlert(Context c) { return sp(c).getString("dropDay", ""); }
    static void setLastDropAlert(Context c, String day) { sp(c).edit().putString("dropDay", day).apply(); }
}
