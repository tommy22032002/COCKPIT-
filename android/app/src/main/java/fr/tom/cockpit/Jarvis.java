package fr.tom.cockpit;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.regex.Pattern;

/** Jarvis hors de l'appli : interroge Claude avec le dernier résumé du cockpit enregistré par l'appli. */
final class Jarvis {
    private Jarvis() {}

    static final Pattern WEB = Pattern.compile("(m[ée]t[ée]o|temps qu.il|pleu|pluie|temp[ée]rature|actu|news|aujourd|ce matin|ce soir|demain|hier|en ce moment|bourse|march[ée]|cac|nasdaq|wall street|bitcoin|prix|r[ée]sultat|match|score|qui est|recherche|horaire|ouvert)", Pattern.CASE_INSENSITIVE);

    static JSONObject cfg(Context c) {
        try { return new JSONObject(Prefs.sp(c).getString("jarvis", "{}")); } catch (Exception e) { return new JSONObject(); }
    }

    static void setCfg(Context c, String json) { Prefs.sp(c).edit().putString("jarvis", json).apply(); }

    static JSONObject post(String url, JSONObject headers, String body) {
        JSONObject res = new JSONObject();
        try {
            HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
            h.setConnectTimeout(15000); h.setReadTimeout(90000); h.setRequestMethod("POST"); h.setDoOutput(true);
            java.util.Iterator<String> it = headers.keys();
            while (it.hasNext()) { String k = it.next(); h.setRequestProperty(k, headers.getString(k)); }
            OutputStream o = h.getOutputStream(); o.write(body.getBytes("UTF-8")); o.close();
            int code = h.getResponseCode();
            InputStream in = code >= 400 ? h.getErrorStream() : h.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (in != null) { byte[] buf = new byte[8192]; int n; while ((n = in.read(buf)) > 0) out.write(buf, 0, n); in.close(); }
            res.put("status", code); res.put("body", out.toString("UTF-8"));
        } catch (Exception e) { try { res.put("status", 0); res.put("body", String.valueOf(e.getMessage())); } catch (Exception ignored) { } }
        return res;
    }

    /** history : paires {rôle, texte} de la conversation en cours (la question actuelle comprise). */
    static String ask(Context c, List<String[]> history) {
        JSONObject cfg = cfg(c);
        String key = cfg.optString("key", "");
        if (key.isEmpty()) return "!Ouvre d'abord le Cockpit, onglet Assistant, et enregistre ta clé Claude : Jarvis pourra ensuite te répondre d'ici.";
        try {
            String last = history.get(history.size() - 1)[1];
            JSONArray msgs = new JSONArray();
            for (String[] m : history) {
                JSONObject o = new JSONObject(); o.put("role", m[0]); o.put("content", m[0].equals("user") ? "[voix] " + m[1] : m[1]); msgs.put(o);
            }
            String sys = cfg.optString("sys", "") + "\nTu réponds en dehors de l'appli, par une petite bulle et à voix haute : 1 à 3 phrases courtes, sans liste. Les chiffres du cockpit datent du " + cfg.optString("at", "dernier passage dans l'appli") + ". Pour une modification, propose quand même le bloc <action> : Tom la validera dans le Cockpit.";
            JSONObject body = new JSONObject();
            body.put("model", cfg.optString("model", "claude-sonnet-5-5"));
            body.put("max_tokens", 700);
            body.put("system", sys);
            body.put("messages", msgs);
            if (WEB.matcher(last).find()) {
                JSONObject t = new JSONObject(); t.put("type", "web_search_20250305"); t.put("name", "web_search"); t.put("max_uses", 2);
                JSONObject loc = new JSONObject(); loc.put("type", "approximate"); loc.put("country", "FR"); loc.put("timezone", "Europe/Paris"); t.put("user_location", loc);
                body.put("tools", new JSONArray().put(t));
            }
            JSONObject h = new JSONObject();
            h.put("Content-Type", "application/json"); h.put("x-api-key", key); h.put("anthropic-version", "2023-06-01");
            JSONObject r = post("https://api.anthropic.com/v1/messages", h, body.toString());
            if (r.optInt("status") == 400 && body.has("tools")) { body.remove("tools"); r = post("https://api.anthropic.com/v1/messages", h, body.toString()); }
            if (r.optInt("status") != 200) {
                String m = ""; try { m = new JSONObject(r.optString("body")).getJSONObject("error").optString("message"); } catch (Exception ignored) { }
                if (m.toLowerCase().contains("credit")) return "!Ton crédit Claude est épuisé : ajoute quelques euros sur console.anthropic.com.";
                if (r.optInt("status") == 0) return "!Pas de connexion internet pour joindre Jarvis.";
                return "!Jarvis n'a pas pu répondre (erreur " + r.optInt("status") + ").";
            }
            JSONArray content = new JSONObject(r.getString("body")).getJSONArray("content");
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < content.length(); i++) { JSONObject b = content.getJSONObject(i); if ("text".equals(b.optString("type"))) sb.append(b.optString("text")); }
            return sb.toString().trim();
        } catch (Exception e) { return "!Jarvis a rencontré un problème."; }
    }
}
