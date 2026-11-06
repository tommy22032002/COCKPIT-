package fr.tom.cockpit;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.provider.AlarmClock;
import android.provider.ContactsContract;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.Normalizer;

/** Téléphone : contacts (lecture seule, sur l'appareil), appel / SMS / WhatsApp préparés, minuteur et réveil de l'appli Horloge. */
final class Phone {
    private Phone() {}

    static boolean contactsOk(Context c) { return c.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED; }

    static String norm(String s) {
        String n = Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return n.toLowerCase().replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim();
    }

    /** Contacts dont le nom contient tous les mots cherchés (10 au plus). */
    static String find(Context c, String q) {
        JSONArray out = new JSONArray();
        if (!contactsOk(c)) return "perm";
        String[] words = norm(q).split(" ");
        try (Cursor cur = c.getContentResolver().query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                new String[]{ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.IS_SUPER_PRIMARY},
                null, null, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC")) {
            if (cur == null) return "[]";
            JSONObject last = null;
            while (cur.moveToNext() && out.length() < 10) {
                String name = cur.getString(0), num = cur.getString(1);
                if (name == null || num == null) continue;
                String nn = norm(name); boolean ok = true;
                for (String w : words) if (!w.isEmpty() && !(" " + nn + " ").contains(" " + w) ) { ok = false; break; }
                if (!ok) continue;
                String clean = num.replaceAll("[^+0-9]", "");
                if (last != null && last.optString("name").equals(name)) { JSONArray p = last.optJSONArray("phones"); boolean dup = false; for (int i = 0; i < p.length(); i++) if (p.optString(i).equals(clean)) dup = true; if (!dup) p.put(clean); continue; }
                last = new JSONObject(); last.put("name", name); JSONArray p = new JSONArray(); p.put(clean); last.put("phones", p); out.put(last);
            }
        } catch (Exception e) { return "[]"; }
        return out.toString();
    }

    static void timer(Context c, int sec, String label) {
        Intent i = new Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, Math.max(1, sec)).putExtra(AlarmClock.EXTRA_MESSAGE, label == null ? "Capital" : label).putExtra(AlarmClock.EXTRA_SKIP_UI, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        c.startActivity(i);
    }

    static void alarm(Context c, int h, int m, String label) {
        Intent i = new Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, h).putExtra(AlarmClock.EXTRA_MINUTES, m).putExtra(AlarmClock.EXTRA_MESSAGE, label == null ? "Capital" : label).putExtra(AlarmClock.EXTRA_SKIP_UI, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        c.startActivity(i);
    }

    static void dial(Context c, String num) { c.startActivity(new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(num))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
    static void sms(Context c, String num, String body) { c.startActivity(new Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(num))).putExtra("sms_body", body == null ? "" : body).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
}
