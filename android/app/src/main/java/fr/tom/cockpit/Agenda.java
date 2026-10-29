package fr.tom.cockpit;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.provider.CalendarContract;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/** Agenda du téléphone (Google Agenda) : lecture des prochains rendez-vous et ajout d'un événement. */
final class Agenda {
    private Agenda() {}

    static boolean canRead(Context c) { return c.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED; }
    static boolean canWrite(Context c) { return c.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED; }

    static JSONArray events(Context c, int days) {
        JSONArray out = new JSONArray();
        if (!canRead(c)) return out;
        try {
            long now = System.currentTimeMillis(), end = now + days * 86_400_000L;
            Uri.Builder b = CalendarContract.Instances.CONTENT_URI.buildUpon();
            ContentUris.appendId(b, now - 3600_000L); ContentUris.appendId(b, end);
            String[] cols = {CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END, CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.EVENT_LOCATION};
            Cursor cu = c.getContentResolver().query(b.build(), cols, null, null, CalendarContract.Instances.BEGIN + " ASC");
            SimpleDateFormat f = new SimpleDateFormat("EEEE d MMMM yyyy 'à' HH'h'mm", Locale.FRANCE), fd = new SimpleDateFormat("EEEE d MMMM yyyy", Locale.FRANCE);
            f.setTimeZone(TimeZone.getTimeZone("Europe/Paris"));
            if (cu != null) {
                while (cu.moveToNext() && out.length() < 40) {
                    boolean all = cu.getInt(3) == 1;
                    if (all) fd.setTimeZone(TimeZone.getTimeZone("UTC")); else fd.setTimeZone(TimeZone.getTimeZone("Europe/Paris"));
                    JSONObject o = new JSONObject();
                    o.put("titre", cu.getString(0));
                    o.put("quand", all ? fd.format(new Date(cu.getLong(1))) + " (journée)" : f.format(new Date(cu.getLong(1))));
                    String loc = cu.getString(4);
                    if (loc != null && !loc.isEmpty()) o.put("lieu", loc);
                    out.put(o);
                }
                cu.close();
            }
        } catch (Exception ignored) { }
        return out;
    }

    /** {titre, date:"AAAA-MM-JJ", heure:"HH:MM" (vide = journée entière), duree (min), note, rappel (min)} → "ok", "perm" ou "err". */
    static String add(Context c, String json) {
        if (!canWrite(c)) return "perm";
        try {
            JSONObject o = new JSONObject(json);
            ContentResolver cr = c.getContentResolver();
            long cal = -1;
            Cursor cu = cr.query(CalendarContract.Calendars.CONTENT_URI, new String[]{CalendarContract.Calendars._ID, CalendarContract.Calendars.IS_PRIMARY, CalendarContract.Calendars.ACCOUNT_TYPE},
                    CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL + ">=" + CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR + " AND " + CalendarContract.Calendars.VISIBLE + "=1", null, null);
            if (cu != null) {
                while (cu.moveToNext()) {
                    long id = cu.getLong(0);
                    boolean prim = cu.getInt(1) == 1, google = "com.google".equals(cu.getString(2));
                    if (prim && google) { cal = id; break; }
                    if (cal < 0 && google) cal = id;
                    if (cal < 0) cal = id;
                }
                cu.close();
            }
            if (cal < 0) return "err";
            String day = o.getString("date"), h = o.optString("heure", "");
            boolean all = h.isEmpty();
            Calendar k = Calendar.getInstance(TimeZone.getTimeZone(all ? "UTC" : "Europe/Paris"));
            k.clear();
            k.set(Integer.parseInt(day.substring(0, 4)), Integer.parseInt(day.substring(5, 7)) - 1, Integer.parseInt(day.substring(8, 10)),
                    all ? 0 : Integer.parseInt(h.substring(0, 2)), all ? 0 : Integer.parseInt(h.substring(3, 5)), 0);
            long start = k.getTimeInMillis();
            long end = all ? start + 86_400_000L : start + Math.max(15, o.optInt("duree", 60)) * 60_000L;
            ContentValues v = new ContentValues();
            v.put(CalendarContract.Events.CALENDAR_ID, cal);
            v.put(CalendarContract.Events.TITLE, o.optString("titre", "Cockpit"));
            v.put(CalendarContract.Events.DESCRIPTION, o.optString("note", "Ajouté par Jarvis (Cockpit)"));
            v.put(CalendarContract.Events.DTSTART, start);
            v.put(CalendarContract.Events.DTEND, end);
            v.put(CalendarContract.Events.ALL_DAY, all ? 1 : 0);
            v.put(CalendarContract.Events.EVENT_TIMEZONE, all ? "UTC" : "Europe/Paris");
            Uri u = cr.insert(CalendarContract.Events.CONTENT_URI, v);
            if (u == null) return "err";
            int rem = o.optInt("rappel", all ? 0 : 30);
            if (rem > 0) {
                ContentValues r = new ContentValues();
                r.put(CalendarContract.Reminders.EVENT_ID, Long.parseLong(u.getLastPathSegment()));
                r.put(CalendarContract.Reminders.MINUTES, rem);
                r.put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT);
                cr.insert(CalendarContract.Reminders.CONTENT_URI, r);
            }
            return "ok";
        } catch (Exception e) { return "err"; }
    }
}
