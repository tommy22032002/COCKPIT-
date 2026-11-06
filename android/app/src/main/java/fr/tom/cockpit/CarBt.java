package fr.tom.cockpit;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.CancellationSignal;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Executors;

/**
 * Trajets en voiture : quand le téléphone se connecte au Bluetooth de la voiture choisie, le trajet commence ;
 * quand il se déconnecte, il s'arrête. La position n'est lue qu'à ces deux moments (distance à vol d'oiseau × 1,3).
 * Les trajets attendent dans une file que Capital vient lire (takeTrips).
 */
public class CarBt extends BroadcastReceiver {
    static final double ROAD = 1.3;

    static SharedPreferences sp(Context c) { return Prefs.sp(c); }
    static String car(Context c) { return sp(c).getString("carBt", ""); }
    static String carName(Context c) { return sp(c).getString("carBtName", ""); }
    static void setCar(Context c, String addr, String name) { sp(c).edit().putString("carBt", addr == null ? "" : addr).putString("carBtName", name == null ? "" : name).remove("trStart").apply(); }

    static boolean btOk(Context c) {
        return Build.VERSION.SDK_INT < 31 || c.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }
    static boolean locOk(Context c) {
        return c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            || c.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }
    static boolean bgOk(Context c) {
        return c.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    static String status(Context c) {
        try {
            JSONObject o = new JSONObject();
            o.put("bt", btOk(c)); o.put("loc", locOk(c)); o.put("bg", bgOk(c));
            o.put("car", car(c)); o.put("carName", carName(c));
            o.put("on", sp(c).getLong("trStart", 0) > 0);
            return o.toString();
        } catch (Exception e) { return "{}"; }
    }

    @SuppressWarnings("MissingPermission")
    static String devices(Context c) {
        JSONArray a = new JSONArray();
        try {
            if (!btOk(c)) return "[]";
            BluetoothManager bm = (BluetoothManager) c.getSystemService(Context.BLUETOOTH_SERVICE);
            BluetoothAdapter ad = bm == null ? null : bm.getAdapter();
            if (ad == null) return "[]";
            Set<BluetoothDevice> s = ad.getBondedDevices();
            if (s != null) for (BluetoothDevice d : s) {
                JSONObject o = new JSONObject();
                String n = null; try { n = d.getName(); } catch (Exception ignored) { }
                o.put("n", n == null ? d.getAddress() : n); o.put("a", d.getAddress());
                a.put(o);
            }
        } catch (Exception ignored) { }
        return a.toString();
    }

    static synchronized String take(Context c) {
        String q = sp(c).getString("trips", "[]");
        sp(c).edit().putString("trips", "[]").apply();
        return q;
    }

    static synchronized void push(Context c, JSONObject t) {
        try {
            JSONArray a = new JSONArray(sp(c).getString("trips", "[]"));
            a.put(t);
            while (a.length() > 200) a.remove(0);
            sp(c).edit().putString("trips", a.toString()).apply();
        } catch (Exception ignored) { }
    }

    @Override
    public void onReceive(Context c, Intent i) {
        String act = i.getAction();
        if (act == null) return;
        BluetoothDevice d = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
        String car = car(c);
        if (d == null || car.isEmpty() || !car.equalsIgnoreCase(d.getAddress())) return;
        final PendingResult pr = goAsync();
        final Context app = c.getApplicationContext();
        if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(act)) {
            sp(app).edit().putLong("trStart", System.currentTimeMillis()).remove("trLat").remove("trLon").apply();
            try {
                String brief = sp(app).getString("carBrief", "");
                long last = sp(app).getLong("carBriefAt", 0);
                if (sp(app).getBoolean("carVoice", true) && !brief.isEmpty() && System.currentTimeMillis() - last > 30 * 60_000L) {
                    sp(app).edit().putLong("carBriefAt", System.currentTimeMillis()).apply();
                    if (!JarvisNotif.say(brief, 7000)) JN.post(app, 7701, null, "🚗 En route, monsieur", brief, "executif", null, false, brief, true);
                }
            } catch (Exception ignored) { }
            locate(app, loc -> {
                if (loc != null) sp(app).edit().putString("trLat", String.valueOf(loc.getLatitude())).putString("trLon", String.valueOf(loc.getLongitude())).apply();
                pr.finish();
            });
        } else if (BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(act)) {
            final long s = sp(app).getLong("trStart", 0);
            final long e = System.currentTimeMillis();
            final String la = sp(app).getString("trLat", null), lo = sp(app).getString("trLon", null);
            sp(app).edit().remove("trStart").apply();
            if (s <= 0 || e - s < 3 * 60_000L || e - s > 16 * 3600_000L) { pr.finish(); return; }
            locate(app, loc -> {
                try {
                    JSONObject t = new JSONObject();
                    t.put("s", s); t.put("e", e);
                    Double km = null;
                    if (loc != null && la != null && lo != null) {
                        float[] r = new float[1];
                        Location.distanceBetween(Double.parseDouble(la), Double.parseDouble(lo), loc.getLatitude(), loc.getLongitude(), r);
                        km = Math.round(r[0] / 1000.0 * ROAD * 10) / 10.0;
                        t.put("km", km);
                    }
                    push(app, t);
                    long min = Math.round((e - s) / 60_000.0);
                    String body = (km != null ? String.format(Locale.FRANCE, "≈ %.1f km en %d min", km, min) : min + " min (kilomètres à compléter)")
                        + " · ouvre Auto pour dire si c'était pour le travail.";
                    JN.post(app, (int) (s / 1000), null, "🚗 Trajet terminé", body, "auto", null, false, null, false);
                } catch (Exception ignored) { }
                pr.finish();
            });
        } else pr.finish();
    }

    interface Got { void on(Location l); }

    /** Position récente : d'abord une position fraîche (Android 11+, 8 s max), sinon la dernière connue. */
    @SuppressWarnings("MissingPermission")
    static void locate(Context c, Got cb) {
        if (!locOk(c)) { cb.on(null); return; }
        LocationManager lm = (LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) { cb.on(null); return; }
        Location best = null;
        for (String p : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, "fused"}) {
            try { Location l = lm.getLastKnownLocation(p); if (l != null && (best == null || l.getTime() > best.getTime())) best = l; } catch (Exception ignored) { }
        }
        final Location last = best;
        if (Build.VERSION.SDK_INT >= 30) {
            String prov = lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) ? LocationManager.NETWORK_PROVIDER : LocationManager.GPS_PROVIDER;
            try {
                final CancellationSignal cs = new CancellationSignal();
                final boolean[] done = {false};
                android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
                h.postDelayed(() -> { synchronized (done) { if (done[0]) return; done[0] = true; } cs.cancel(); cb.on(last); }, 8000);
                lm.getCurrentLocation(prov, cs, Executors.newSingleThreadExecutor(), l -> {
                    synchronized (done) { if (done[0]) return; done[0] = true; }
                    cb.on(l != null ? l : last);
                });
                return;
            } catch (Exception ignored) { }
        }
        cb.on(last);
    }
}
