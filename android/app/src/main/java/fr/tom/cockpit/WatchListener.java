package fr.tom.cockpit;

import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.Wearable;
import com.google.android.gms.wearable.WearableListenerService;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Demandes venant de la montre : actualiser le PEA, poser une question à Jarvis. */
public class WatchListener extends WearableListenerService {
    @Override public void onMessageReceived(MessageEvent ev) {
        String path = ev.getPath();
        if ("/pea/sync".equals(path)) {
            Widgets.refreshPrices(this, false);
            Wear.push(this);
        } else if ("/pea/resist".equals(path)) {
            // « Je résiste » depuis la montre : l'envie évitée est notée, le Cockpit la compte comme argent sauvé
            String amt = new String(ev.getData(), StandardCharsets.UTF_8).trim();
            double v = 0; try { v = Double.parseDouble(amt.replace(',', '.')); } catch (Exception ignored) { }
            String txt;
            if (v > 0) {
                try { JN.queue(this, new org.json.JSONObject().put("t", "resist").put("amt", v).put("at", new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US).format(new java.util.Date()))); } catch (Exception ignored) { }
                txt = "💪 Bravo ! " + Math.round(v) + " € pas dépensés. Ils sont notés dans ta liste d'attente comme argent sauvé : vire-les sur ton PEA quand tu veux.";
            } else txt = "Je n'ai pas compris le montant.";
            Wearable.getMessageClient(this).sendMessage(ev.getSourceNodeId(), "/jarvis/reply", txt.getBytes(StandardCharsets.UTF_8));
        } else if ("/jarvis/ask".equals(path)) {
            String q = new String(ev.getData(), StandardCharsets.UTF_8).trim();
            String txt;
            try {
                List<String[]> h = new ArrayList<>();
                h.add(new String[]{"user", "(Depuis ma montre) " + q});
                String a = Jarvis.ask(this, h);
                txt = (a.startsWith("!") ? a.substring(1) : a).replaceAll("(?s)<action>.*?</action>", "").replaceAll("\\*\\*", "").trim();
                if (a.contains("<action>")) {
                    org.json.JSONArray acts = new org.json.JSONArray();
                    java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?s)<action>(.*?)</action>").matcher(a);
                    while (m.find()) try { acts.put(new org.json.JSONObject(m.group(1).trim())); } catch (Exception ignored) { }
                    if (acts.length() > 0) {
                        JN.queue(this, new org.json.JSONObject().put("t", "watch").put("q", q).put("a", txt).put("acts", acts).put("at", new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US).format(new java.util.Date())));
                        JN.post(this, 6300 + (int) (System.currentTimeMillis() / 1000 % 50), null, "⌚ À valider : " + (q.length() > 50 ? q.substring(0, 50) + "…" : q), txt + "\n\nTouche pour valider dans Capital.", "assistant", null, false, null, true);
                        txt += "\n\nC'est prêt : valide-le dans Capital (touche la notification « À valider »).";
                    } else txt += "\n\nValide la modification dans Capital sur ton téléphone.";
                }
            } catch (Exception e) { txt = "Je n'ai pas réussi à répondre, réessaie dans un instant."; }
            Wearable.getMessageClient(this).sendMessage(ev.getSourceNodeId(), "/jarvis/reply", txt.getBytes(StandardCharsets.UTF_8));
        }
    }
}
