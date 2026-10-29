package fr.tom.cockpit;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

/** Rendez-vous de Jarvis : bilan du vendredi, jour du DCA, événements importants (BCE, Fed…), rappels reportés. */
public class JarvisAlarm extends BroadcastReceiver {
    static final int RC_WEEK = 6100, RC_DCA = 6101, RC_EV = 6110;

    private static PendingIntent pi(Context c, int rc, Intent in) { return PendingIntent.getBroadcast(c, rc, in, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE); }

    private static long at(String day, int min) {
        Calendar k = Calendar.getInstance(TimeZone.getTimeZone("Europe/Paris"));
        k.set(Integer.parseInt(day.substring(0, 4)), Integer.parseInt(day.substring(5, 7)) - 1, Integer.parseInt(day.substring(8, 10)), min / 60, min % 60, 0);
        k.set(Calendar.MILLISECOND, 0);
        return k.getTimeInMillis();
    }

    static void scheduleAll(Context c) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        if (am == null) return;
        long now = System.currentTimeMillis();
        // bilan du vendredi, 18 h 30
        PendingIntent w = pi(c, RC_WEEK, new Intent(c, JarvisAlarm.class).putExtra("kind", "week"));
        am.cancel(w);
        if (JN.on(c, "week")) {
            Calendar k = Calendar.getInstance(TimeZone.getTimeZone("Europe/Paris"));
            k.set(Calendar.HOUR_OF_DAY, 18); k.set(Calendar.MINUTE, 30); k.set(Calendar.SECOND, 0);
            while (k.get(Calendar.DAY_OF_WEEK) != Calendar.FRIDAY || k.getTimeInMillis() <= now) k.add(Calendar.DAY_OF_YEAR, 1);
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, k.getTimeInMillis(), w);
        }
        JSONObject d = JN.data(c);
        // jour du DCA, 9 h 15
        PendingIntent p = pi(c, RC_DCA, new Intent(c, JarvisAlarm.class).putExtra("kind", "dca"));
        am.cancel(p);
        JSONObject dca = d.optJSONObject("dca");
        if (JN.on(c, "dca") && dca != null && dca.optString("day").length() == 10) {
            long t = at(dca.optString("day"), 9 * 60 + 15);
            if (t > now) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, p);
        }
        // événements majeurs du jour (agenda du point du jour), 8 h 45
        JSONArray ev = d.optJSONArray("events");
        for (int i = 0; i < 6; i++) {
            PendingIntent e = pi(c, RC_EV + i, new Intent(c, JarvisAlarm.class).putExtra("kind", "event").putExtra("i", i));
            am.cancel(e);
            if (!JN.on(c, "events") || ev == null || i >= ev.length()) continue;
            JSONObject x = ev.optJSONObject(i);
            if (x == null || x.optString("d").length() != 10) continue;
            long t = at(x.optString("d"), 8 * 60 + 45);
            if (t > now) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, e);
        }
    }

    static void repost(Context c, int nid, String title, String body, String hash, String acts, long delay) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        if (am == null) return;
        Intent in = new Intent(c, JarvisAlarm.class).putExtra("kind", "repost").putExtra("nid", nid).putExtra("title", title).putExtra("body", body).putExtra("hash", hash).putExtra("acts", acts);
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + delay, pi(c, 6200 + Math.abs(nid % 50), in));
    }

    @Override
    public void onReceive(Context ctx, Intent intent) {
        final PendingResult pr = goAsync();
        final Context c = ctx.getApplicationContext();
        new Thread(() -> {
            try { fire(c, intent); } catch (Exception ignored) { } finally { try { scheduleAll(c); } catch (Exception ignored) { } pr.finish(); }
        }).start();
    }

    private void fire(Context c, Intent in) throws Exception {
        String kind = in.getStringExtra("kind");
        JSONObject d = JN.data(c);
        if ("week".equals(kind)) {
            String a = JN.jarvis(c, "Fais le bilan de ma semaine en 3 phrases courtes : mon PEA (valeur et évolution), mon épargne et mes dépenses du mois, puis un conseil concret pour la semaine prochaine. Termine sur une note positive.");
            if (a == null) a = "Ouvre le Cockpit pour voir ta semaine : PEA, épargne et budget.";
            JN.post(c, 6100, null, "Ton bilan de la semaine", a, "executif", null, true, a, true);
        } else if ("dca".equals(kind)) {
            JSONObject dca = d.optJSONObject("dca");
            if (dca == null) return;
            JSONArray o = dca.optJSONArray("orders");
            StringBuilder sb = new StringBuilder();
            if (o != null) for (int i = 0; i < o.length(); i++) {
                JSONObject x = o.getJSONObject(i);
                sb.append("• ").append(x.optInt("q")).append(" ").append(x.optString("n")).append(" (≈ ").append(Widgets.eur(x.optDouble("amt"), false)).append(")\n");
            }
            String body = sb.length() > 0 ? "Aujourd'hui, achète :\n" + sb.toString().trim() + "\n\nUne fois fait, appuie sur « C'est fait » : je prépare l'enregistrement." : "C'est le jour de ton investissement. Ouvre « Prochain achat » pour savoir quoi acheter.";
            JSONObject done = new JSONObject().put("label", "C'est fait").put("open", true).put("hash", "assistant").put("data", new JSONObject().put("t", "dca").put("orders", o == null ? new JSONArray() : o).toString());
            JSONArray again = new JSONArray().put(done).put(new JSONObject().put("label", "Plus tard").put("act", "snooze"));
            JSONArray acts = new JSONArray().put(done).put(new JSONObject().put("label", "Plus tard").put("act", "snooze").put("data", again.toString()));
            JN.post(c, 6101, null, "Jour du DCA" + (dca.optDouble("total", 0) > 0 ? " · " + Widgets.eur(dca.optDouble("total"), false) : ""), body, "achat", acts, true, null, true);
        } else if ("event".equals(kind)) {
            JSONArray ev = d.optJSONArray("events");
            int i = in.getIntExtra("i", 0);
            if (ev == null || i >= ev.length()) return;
            JSONObject x = ev.getJSONObject(i);
            String body = (x.optString("h").isEmpty() ? "" : "À " + x.optString("h") + ". ") + x.optString("impact", "") + "\n\nTon plan ne change pas : ton DCA continue.";
            JN.post(c, 6110 + i, null, "Aujourd'hui : " + x.optString("t"), body.trim(), "journal", null, true, x.optString("t") + ". " + x.optString("impact", ""), false);
        } else if ("repost".equals(kind)) {
            JSONArray acts = null;
            try { String a = in.getStringExtra("acts"); if (a != null && a.startsWith("[")) acts = new JSONArray(a); } catch (Exception ignored) { }
            JN.post(c, in.getIntExtra("nid", 6300), null, in.getStringExtra("title"), in.getStringExtra("body"), in.getStringExtra("hash"), acts, true, null, true);
        }
    }

    static String fmt(double v) { return String.format(Locale.FRANCE, "%+.2f %%", v * 100).replace("-", "−"); }
}
