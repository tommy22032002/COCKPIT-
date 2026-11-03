package fr.tom.cockpit;

import android.Manifest;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Iterator;
import java.util.Locale;

/**
 * Podomètre : le capteur « compteur de pas » d'Android compte depuis le dernier démarrage.
 * On retient la valeur au début de chaque jour pour en déduire les pas du jour (lu à l'ouverture de Capital et environ toutes les heures).
 */
public class Steps extends BroadcastReceiver {
    static SharedPreferences sp(Context c) { return Prefs.sp(c); }
    static String today() { return new SimpleDateFormat("yyyy-MM-dd", Locale.FRANCE).format(new Date()); }

    static boolean ok(Context c) {
        SensorManager sm = (SensorManager) c.getSystemService(Context.SENSOR_SERVICE);
        boolean has = sm != null && sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) != null;
        return has && c.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED;
    }

    /** Nouvelle valeur du compteur (pas depuis le démarrage). */
    static synchronized void update(Context c, long v) {
        SharedPreferences p = sp(c);
        String day = p.getString("stDay", ""), t = today();
        long base = p.getLong("stBase", -1), last = p.getLong("stLast", -1), acc = p.getLong("stAcc", 0);
        JSONObject days;
        try { days = new JSONObject(p.getString("stDays", "{}")); } catch (Exception e) { days = new JSONObject(); }
        if (base < 0 || last < 0) { base = v; last = v; acc = 0; day = t; }
        if (!t.equals(day)) {
            try { if (!day.isEmpty()) days.put(day, Math.max(0, acc + (last - base))); } catch (Exception ignored) { }
            acc = 0; base = v >= last ? last : 0; day = t;
        }
        if (v < last) { acc += Math.max(0, last - base); base = 0; }
        last = v;
        long n = Math.max(0, acc + (v - base));
        try {
            days.put(t, n);
            while (days.length() > 60) { Iterator<String> it = days.keys(); String oldest = null; while (it.hasNext()) { String k = it.next(); if (oldest == null || k.compareTo(oldest) < 0) oldest = k; } days.remove(oldest); }
        } catch (Exception ignored) { }
        p.edit().putString("stDay", day).putLong("stBase", base).putLong("stLast", last).putLong("stAcc", acc).putString("stDays", days.toString()).apply();
    }

    static long todayCount(Context c) {
        try { return new JSONObject(sp(c).getString("stDays", "{}")).optLong(today(), 0); } catch (Exception e) { return 0; }
    }
    static String daysJson(Context c) { return sp(c).getString("stDays", "{}"); }

    /** Lit le capteur une fois puis s'arrête (5 s max). */
    static void readOnce(Context c, Runnable done) {
        final SensorManager sm = (SensorManager) c.getSystemService(Context.SENSOR_SERVICE);
        final Sensor s = sm == null ? null : sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER);
        if (s == null || !ok(c)) { if (done != null) done.run(); return; }
        final Context app = c.getApplicationContext();
        final boolean[] fin = {false};
        final SensorEventListener[] l = new SensorEventListener[1];
        final Runnable stop = () -> { synchronized (fin) { if (fin[0]) return; fin[0] = true; } try { sm.unregisterListener(l[0]); } catch (Exception ignored) { } if (done != null) done.run(); };
        l[0] = new SensorEventListener() {
            @Override public void onSensorChanged(SensorEvent e) { update(app, (long) e.values[0]); stop.run(); }
            @Override public void onAccuracyChanged(Sensor sensor, int a) { }
        };
        sm.registerListener(l[0], s, SensorManager.SENSOR_DELAY_NORMAL);
        new Handler(Looper.getMainLooper()).postDelayed(stop, 5000);
    }

    /** Écoute continue tant que Capital est ouvert. */
    static SensorEventListener live;
    static void startLive(Context c) {
        if (live != null || !ok(c)) return;
        SensorManager sm = (SensorManager) c.getSystemService(Context.SENSOR_SERVICE);
        Sensor s = sm == null ? null : sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER);
        if (s == null) return;
        final Context app = c.getApplicationContext();
        live = new SensorEventListener() {
            @Override public void onSensorChanged(SensorEvent e) { update(app, (long) e.values[0]); }
            @Override public void onAccuracyChanged(Sensor sensor, int a) { }
        };
        sm.registerListener(live, s, SensorManager.SENSOR_DELAY_NORMAL);
    }
    static void stopLive(Context c) {
        if (live == null) return;
        SensorManager sm = (SensorManager) c.getSystemService(Context.SENSOR_SERVICE);
        try { if (sm != null) sm.unregisterListener(live); } catch (Exception ignored) { }
        live = null;
    }

    static void schedule(Context c) {
        if (!ok(c)) return;
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        PendingIntent pi = PendingIntent.getBroadcast(c, 7301, new Intent(c, Steps.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        am.setInexactRepeating(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + AlarmManager.INTERVAL_HOUR, AlarmManager.INTERVAL_HOUR, pi);
    }

    @Override
    public void onReceive(Context c, Intent i) {
        final PendingResult pr = goAsync();
        readOnce(c, pr::finish);
    }
}
