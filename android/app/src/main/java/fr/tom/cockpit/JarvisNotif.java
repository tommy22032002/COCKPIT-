package fr.tom.cockpit;

import android.app.Notification;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.speech.tts.TextToSpeech;
import android.os.Handler;
import android.os.Looper;
import java.util.Locale;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.regex.Pattern;

/**
 * Accès aux notifications : sert à Jarvis pour contrôler la musique, et — seulement si tu l'actives dans Capital
 * (Budget → « Lire mes notifications bancaires ») — à relever les débits et crédits annoncés par tes applis de banque.
 * Rien ne quitte le téléphone : seules les notifications qui contiennent un montant en euros ET un mot bancaire sont gardées,
 * dans une file que Capital vide à l'ouverture.
 */
public class JarvisNotif extends NotificationListenerService {
    static final Pattern MONEY = Pattern.compile("\\d[\\d  . ]*[.,]\\d{2}\\s?(€|EUR)|(€|EUR)\\s?\\d[\\d  . ]*[.,]\\d{2}|\\d+\\s?€", Pattern.CASE_INSENSITIVE);
    static final Pattern BANK = Pattern.compile("d[ée]bit|cr[ée]dit|paiement|pr[ée]l[èe]vement|virement|achat|carte|\\bCB\\b|retrait|rembours|op[ée]ration|solde|transaction|re[çc]u|envoy[ée]", Pattern.CASE_INSENSITIVE);
    static final String[] SKIP = {"com.whatsapp", "org.telegram", "com.instagram", "com.facebook", "com.snapchat", "com.discord", "com.google.android.gm", "com.microsoft.office.outlook", "com.twitter", "com.zhiliaoapp"};

    static JarvisNotif inst;
    TextToSpeech tts; boolean ttsOk;
    @Override public void onListenerConnected() { inst = this; }
    @Override public void onListenerDisconnected() { if (inst == this) inst = null; }
    @Override public void onDestroy() { try { if (tts != null) tts.shutdown(); } catch (Exception ignored) { } if (inst == this) inst = null; super.onDestroy(); }
    /** Fait parler Jarvis depuis l'arrière-plan (mode voiture). Renvoie false si le service n'est pas actif. */
    static boolean say(final String text, long delayMs) {
        final JarvisNotif s = inst; if (s == null || text == null || text.isEmpty()) return false;
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                if (s.tts != null && s.ttsOk) { s.tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "car"); return; }
                s.tts = new TextToSpeech(s, st -> { if (st == TextToSpeech.SUCCESS) { s.ttsOk = true; try { s.tts.setLanguage(Locale.FRANCE); s.tts.setPitch(0.9f); } catch (Exception ignored) { } s.tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "car"); } });
            } catch (Exception ignored) { }
        }, delayMs);
        return true;
    }

    static SharedPreferences sp(Context c) { return Prefs.sp(c); }
    static boolean on(Context c) { return sp(c).getBoolean("ntOn", false); }
    static void setOn(Context c, boolean v) { sp(c).edit().putBoolean("ntOn", v).apply(); }
    static boolean granted(Context c) {
        String l = android.provider.Settings.Secure.getString(c.getContentResolver(), "enabled_notification_listeners");
        return l != null && l.contains(c.getPackageName());
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        try {
            Context c = getApplicationContext();
            if (!on(c) || sbn == null) return;
            String pkg = sbn.getPackageName();
            if (pkg == null || pkg.equals(getPackageName())) return;
            for (String s : SKIP) if (pkg.startsWith(s)) return;
            Notification n = sbn.getNotification();
            if (n == null || (n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return;
            Bundle e = n.extras;
            String title = e == null ? "" : String.valueOf(e.getCharSequence(Notification.EXTRA_TITLE, ""));
            CharSequence big = e == null ? null : e.getCharSequence(Notification.EXTRA_BIG_TEXT);
            String text = big != null ? big.toString() : (e == null ? "" : String.valueOf(e.getCharSequence(Notification.EXTRA_TEXT, "")));
            String all = title + " " + text;
            if (!MONEY.matcher(all).find() || !BANK.matcher(all).find()) return;
            String label = pkg;
            try { PackageManager pm = getPackageManager(); label = String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0))); } catch (Exception ignored) { }
            JSONObject o = new JSONObject();
            o.put("pkg", pkg); o.put("app", label); o.put("title", title.length() > 160 ? title.substring(0, 160) : title);
            o.put("text", text.length() > 400 ? text.substring(0, 400) : text); o.put("at", sbn.getPostTime()); o.put("key", sbn.getKey());
            synchronized (JarvisNotif.class) {
                JSONArray q; try { q = new JSONArray(sp(c).getString("ntQ", "[]")); } catch (Exception x) { q = new JSONArray(); }
                for (int i = 0; i < q.length(); i++) { JSONObject p = q.optJSONObject(i); if (p != null && p.optString("text").equals(o.optString("text")) && Math.abs(p.optLong("at") - o.optLong("at")) < 120000) return; }
                q.put(o);
                while (q.length() > 200) q.remove(0);
                sp(c).edit().putString("ntQ", q.toString()).apply();
            }
        } catch (Exception ignored) { }
    }

    /** Vide la file (appelé par Capital). */
    static synchronized String take(Context c) {
        String s = sp(c).getString("ntQ", "[]");
        sp(c).edit().putString("ntQ", "[]").apply();
        return s;
    }
}
