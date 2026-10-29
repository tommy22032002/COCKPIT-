package fr.tom.cockpit;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognizerIntent;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Bulle Jarvis : on parle, Jarvis répond à voix haute, sans ouvrir le Cockpit. */
public class JarvisActivity extends Activity {
    private static final int REQ = 21;
    private static final Pattern END = Pattern.compile("^(merci|c'?est tout|ce sera tout|au revoir|bonne nuit|stop|non merci|rien)( jarvis)?[ .!]*$", Pattern.CASE_INSENSITIVE);
    private final List<String[]> hist = new ArrayList<>();
    private final Handler h = new Handler(Looper.getMainLooper());
    private TextView you, ans, status;
    private Button open, again;
    private TextToSpeech tts;
    private boolean ttsReady = false, closing = false;
    private String lastQ = "";
    private boolean hasAction = false;

    private int dp(float v) { return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()); }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0x66000000);
        root.setOnClickListener(v -> finishQuiet());
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(20), dp(18), dp(20), dp(16));
        GradientDrawable bg = new GradientDrawable(); bg.setColor(0xF2171E29); bg.setCornerRadius(dp(24)); bg.setStroke(dp(1), 0x26FFFFFF);
        card.setBackground(bg);
        card.setClickable(true);
        TextView title = new TextView(this); title.setText("JARVIS"); title.setTextColor(0xFF6EA8FE); title.setTypeface(Typeface.DEFAULT_BOLD); title.setLetterSpacing(.15f); title.setTextSize(12);
        status = new TextView(this); status.setTextColor(0xFF8B95A7); status.setTextSize(13); status.setText("Je t'écoute…");
        you = new TextView(this); you.setTextColor(0xFF8B95A7); you.setTextSize(14); you.setPadding(0, dp(8), 0, 0);
        ans = new TextView(this); ans.setTextColor(0xFFE8ECF3); ans.setTextSize(17); ans.setLineSpacing(0, 1.15f); ans.setPadding(0, dp(8), 0, dp(4));
        ScrollView sc = new ScrollView(this); sc.addView(ans);
        LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL); row.setPadding(0, dp(10), 0, 0); row.setGravity(Gravity.END);
        again = btn("🎤 Encore", v -> listen());
        open = btn("Ouvrir le Cockpit", v -> openCockpit());
        Button close = btn("Fermer", v -> finishQuiet());
        row.addView(again); row.addView(open); row.addView(close);
        card.addView(title); card.addView(status); card.addView(you);
        card.addView(sc, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(row);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        lp.setMargins(dp(12), dp(12), dp(12), dp(24));
        root.addView(card, lp);
        setContentView(root);
        sc.getLayoutParams().height = ViewGroup.LayoutParams.WRAP_CONTENT;
        ans.setMaxHeight(dp(320));
        initTts();
        if (Jarvis.cfg(this).optString("key", "").isEmpty()) show("", "Ouvre d'abord le Cockpit, onglet Assistant, et enregistre ta clé Claude : Jarvis pourra ensuite te répondre d'ici.", false);
        else h.postDelayed(this::listen, 250);
    }

    private Button btn(String t, View.OnClickListener l) {
        Button b = new Button(this, null, android.R.attr.borderlessButtonStyle);
        b.setText(t); b.setTextColor(0xFF6EA8FE); b.setAllCaps(false); b.setTextSize(14); b.setOnClickListener(l);
        return b;
    }

    private void listen() {
        stopTts();
        status.setText("Je t'écoute…");
        try {
            Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fr-FR");
            i.putExtra(RecognizerIntent.EXTRA_PROMPT, "Jarvis t'écoute");
            startActivityForResult(i, REQ);
        } catch (Exception e) { show("", "La dictée vocale n'est pas disponible sur ce téléphone.", false); }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ) return;
        String q = "";
        if (res == RESULT_OK && data != null) { ArrayList<String> r = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS); if (r != null && !r.isEmpty()) q = r.get(0).trim(); }
        if (q.isEmpty()) { if (hist.isEmpty()) finishQuiet(); else status.setText("Appuie sur « Encore » pour une autre question."); return; }
        String qq = q.replaceFirst("(?i)^(et |hey |ok )?jarvis[ ,]*", "").trim();
        java.util.regex.Matcher cm = Pattern.compile("^(?:ouvre|lance|va sur)\\s+(.+?)\\s+(?:et|puis)\\s+(?:mets|mets-moi|joue|lance|cherche|recherche|passe|fais(?:-moi)? (?:écouter|ecouter|voir))\\s+(.+?)[ .!?]*$", Pattern.CASE_INSENSITIVE).matcher(qq);
        final String cmd = cm.matches() ? "mets " + cm.group(2) + " sur " + cm.group(1) : qq;
        if (videoCommand(cmd)) return;
        if (playCommand(cmd)) return;
        if (openCommand(qq)) return;
        if (END.matcher(qq).matches()) { say("À ton service, Tom."); closing = true; status.setText("À plus tard."); return; }
        if (qq.isEmpty()) { say("Oui ?"); h.postDelayed(this::listen, 900); return; }
        lastQ = qq;
        hist.add(new String[]{"user", qq});
        you.setText("« " + qq + " »");
        ans.setText("");
        status.setText("Jarvis réfléchit…");
        new Thread(() -> {
            String a = Jarvis.ask(this, new ArrayList<>(hist));
            h.post(() -> {
                boolean err = a.startsWith("!");
                String txt = err ? a.substring(1) : a;
                hasAction = txt.contains("<action>");
                String shown = txt.replaceAll("(?s)<action>.*?</action>", "").replaceAll("\\*\\*", "").trim();
                if (!err) hist.add(new String[]{"assistant", shown});
                if (hasAction) shown += "\n\nPour l'enregistrer, appuie sur « Ouvrir le Cockpit » : la proposition t'y attend.";
                show(qq, shown, !err);
            });
        }).start();
    }

    // ---------- « ouvre … » : le Cockpit, un de ses onglets, ou une autre appli du téléphone ----------
    private static final Pattern OPEN = Pattern.compile("^(?:peux-tu |tu peux |est-ce que tu peux )?(?:ouvre|ouvrir|lance|lancer|affiche|afficher|montre(?:-moi)?|va (?:dans|sur)|mets?(?:-moi)? (?:sur|le|la))\\s+(.+?)[ .!?]*$", Pattern.CASE_INSENSITIVE);
    private static final String[][] PAGES = {
        {"accueil|vue d.ensemble|tableau de bord|executi", "executif"}, {"portefeuille|mes etf|lignes", "portefeuille"}, {"plan|objectif", "plan"},
        {"budget|depense|abonnement", "budget"}, {"journal|actu|point du jour|marche", "journal"}, {"assurance.vie|\\bav\\b|fonds euro", "av"},
        {"rapport", "rapport"}, {"assistant|jarvis|reglage", "assistant"}, {"envie|liste d.attente", "envies"}, {"impot", "impots"},
        {"projet|voyage|japon", "projets"}, {"patrimoine|livret", "patrimoine"}, {"dividende", "dividendes"}, {"historique|operation", "historique"},
        {"prochain achat|achat", "achat"}, {"allocation|repartition", "allocation"}, {"performance", "performance"}, {"projection", "projection"},
        {"coach", "coach"}, {"saison", "saison"}, {"mettre a jour|mise a jour", "maj"}, {"apparence|theme|couleur", "apparence"}};

    private static String norm(String s) { return java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT).replaceAll("['’]", " ").trim(); }

    // ---------- « mets Daft Punk sur Apple Music » : lecture d'une musique dans l'appli choisie ----------
    private static final Pattern PLAY = Pattern.compile("^(?:peux-tu |tu peux )?(joue|jouer|mets|mettre|mets-moi|lance|lancer|fais(?:-moi)? (?:écouter|ecouter)|écoute|ecoute|passe|je veux écouter|je veux ecouter)\\s+(?:de la |du |de l'|la musique |la chanson |le titre |l'album |la playlist )?(.+?)(?:\\s+(?:sur|dans|avec)\\s+(apple music|apple musique|spotify|deezer|youtube music|youtube musique|amazon music|soundcloud))?[ .!?]*$", Pattern.CASE_INSENSITIVE);
    private static final String[][] MUSIC = {{"apple", "com.apple.android.music", "Apple Music"}, {"spotify", "com.spotify.music", "Spotify"}, {"deezer", "deezer.android.app", "Deezer"},
        {"youtube", "com.google.android.apps.youtube.music", "YouTube Music"}, {"amazon", "com.amazon.mp3", "Amazon Music"}, {"soundcloud", "com.soundcloud.android", "SoundCloud"}};

    private static final Pattern VIDEO = Pattern.compile("^(?:mets|mets-moi|joue|lance|cherche|recherche|montre(?:-moi)?|passe|fais(?:-moi)? voir|je veux voir|regarde)\\s+(?:la |une |les |le |un )?(?:vid[ée]os? |clip |film |bande.annonce )?(?:de |du |d'|des )?(.+?)\\s+(?:sur|dans|avec)\\s+(youtube|you tube|netflix|twitch|tiktok)[ .!?]*$", Pattern.CASE_INSENSITIVE);

    private boolean videoCommand(String q) {
        java.util.regex.Matcher m = VIDEO.matcher(q.trim());
        if (!m.matches()) return false;
        String what = m.group(1).trim(), app = norm(m.group(2)).replace(" ", "");
        you.setText("« " + q + " »");
        Intent i;
        if (app.startsWith("youtube")) {
            i = new Intent(Intent.ACTION_SEARCH).setPackage("com.google.android.youtube").putExtra("query", what);
        } else if (app.equals("netflix")) {
            i = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.netflix.com/search?q=" + android.net.Uri.encode(what))).setPackage("com.netflix.mediaclient");
        } else if (app.equals("twitch")) {
            i = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.twitch.tv/search?term=" + android.net.Uri.encode(what)));
        } else {
            i = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.tiktok.com/search?q=" + android.net.Uri.encode(what)));
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { startActivity(i); }
        catch (Exception e) {
            try { startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.youtube.com/results?search_query=" + android.net.Uri.encode(what))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
            catch (Exception e2) { ans.setText("Je n'arrive pas à ouvrir " + m.group(2) + "."); return true; }
        }
        ans.setText("🔎 " + what + " · " + m.group(2));
        say("Voilà les résultats pour " + what + ". Choisis la vidéo.");
        h.postDelayed(this::finishQuiet, 2200);
        return true;
    }

    private boolean playCommand(String q) {
        java.util.regex.Matcher m = PLAY.matcher(q.trim());
        if (!m.matches()) return false;
        String verb = m.group(1).toLowerCase(Locale.ROOT), what = m.group(2).trim(), app = m.group(3) == null ? "" : norm(m.group(3));
        if (app.isEmpty() && verb.startsWith("lance")) return false; // « lance YouTube » = ouvrir une appli
        if (app.isEmpty() && what.matches("(?i).*(application|appli|cockpit|onglet|page).*")) return false; // « lance l'appli » = ouvrir
        String[] target = null;
        if (!app.isEmpty()) for (String[] x : MUSIC) if (app.contains(x[0])) { target = x; break; }
        if (target == null) { String pref = Jarvis.cfg(this).optString("music", ""); for (String[] x : MUSIC) if (x[1].equals(pref)) { target = x; break; } }
        if (target == null) target = MUSIC[0];
        Intent i = new Intent(android.provider.MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH);
        i.putExtra(android.app.SearchManager.QUERY, what);
        i.putExtra(android.provider.MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*");
        i.setPackage(target[1]);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        you.setText("« " + q + " »");
        if (target[1].equals("com.apple.android.music")) {
            // Apple Music ne laisse pas une autre appli choisir le morceau : on ouvre directement sa recherche sur le titre demandé
            Intent am = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://music.apple.com/fr/search?term=" + android.net.Uri.encode(what))).setPackage(target[1]).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try { startActivity(am); say("Voilà " + what + " sur Apple Music. Touche le morceau pour l'écouter."); ans.setText("🔎 " + what + " · Apple Music"); h.postDelayed(this::finishQuiet, 2200); return true; }
            catch (Exception ignored) { }
        }
        try {
            startActivity(i);
            say("Je lance " + what + " sur " + target[2] + ".");
            ans.setText("▶ " + what + " · " + target[2]);
            h.postDelayed(this::finishQuiet, 1800);
        } catch (Exception e) {
            Intent open = getPackageManager().getLaunchIntentForPackage(target[1]);
            if (open != null) {
                startActivity(open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                say(target[2] + " ne me laisse pas choisir la musique : je l'ouvre, cherche " + what + ".");
                h.postDelayed(this::finishQuiet, 2500);
            } else { ans.setText(target[2] + " n'est pas installée sur ce téléphone."); say(target[2] + " n'est pas installée."); }
        }
        return true;
    }

    private boolean openCommand(String q) {
        java.util.regex.Matcher m = OPEN.matcher(q.trim());
        if (!m.matches()) return false;
        String t = norm(m.group(1)).replaceAll("^(l |le |la |les |l'|mon |ma |mes |moi |l application |l appli |application |appli |la page |l onglet |onglet )+", "").trim();
        boolean cockpit = t.isEmpty() || t.matches(".*(cockpit|application|appli|pea).*");
        String page = null;
        for (String[] p : PAGES) if (java.util.regex.Pattern.compile(p[0]).matcher(t).find()) { page = p[1]; break; }
        if (cockpit && page == null) page = "executif";
        if (page != null) {
            Intent i = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP).putExtra("hash", page);
            say("J'ouvre."); startActivity(i); h.postDelayed(this::finishQuiet, 600); return true;
        }
        // autre appli installée, d'après son nom
        try {
            android.content.pm.PackageManager pm = getPackageManager();
            Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            android.content.pm.ResolveInfo best = null; int bestScore = 0;
            for (android.content.pm.ResolveInfo r : pm.queryIntentActivities(main, 0)) {
                String lab = norm(String.valueOf(r.loadLabel(pm)));
                int sc = lab.equals(t) ? 3 : (lab.startsWith(t) || t.startsWith(lab)) && lab.length() > 2 ? 2 : lab.contains(t) && t.length() > 3 ? 1 : 0;
                if (sc > bestScore) { bestScore = sc; best = r; }
            }
            if (best != null) {
                Intent li = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setClassName(best.activityInfo.packageName, best.activityInfo.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                say("J'ouvre " + best.loadLabel(pm) + "."); startActivity(li); h.postDelayed(this::finishQuiet, 600); return true;
            }
        } catch (Exception ignored) { }
        you.setText("« " + q + " »"); ans.setText("Je ne trouve pas d'appli qui s'appelle « " + m.group(1) + " »."); say("Je ne trouve pas cette appli."); return true;
    }

    private void show(String q, String a, boolean speak) {
        ans.setText(a);
        status.setText(hasAction ? "Proposition à valider dans le Cockpit" : "");
        open.setText(hasAction ? "Valider dans le Cockpit" : "Ouvrir le Cockpit");
        if (speak) say(a.replace("€", " euros").replaceAll("Pour l'enregistrer.*", ""));
    }

    private void openCockpit() {
        Intent i = new Intent(this, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        if (hasAction && !lastQ.isEmpty()) i.putExtra("jarvis_q", lastQ);
        else i.putExtra("hash", "assistant");
        startActivity(i);
        finishQuiet();
    }

    // ---------- voix (mêmes réglages que dans le Cockpit) ----------
    private void initTts() {
        tts = new TextToSpeech(this, st -> {
            ttsReady = st == TextToSpeech.SUCCESS;
            if (!ttsReady) return;
            tts.setLanguage(Locale.FRANCE);
            try {
                JSONObject v = Jarvis.cfg(this).optJSONObject("voice");
                String name = v != null ? v.optString("name", "") : "";
                Voice male = null, pick = null;
                for (Voice x : tts.getVoices()) {
                    if (x.getLocale() == null || !"fr".equals(x.getLocale().getLanguage())) continue;
                    String n = x.getName().toLowerCase(Locale.ROOT);
                    if (!name.isEmpty() && x.getName().equals(name)) pick = x;
                    if ((n.contains("frb") || n.contains("frd") || (n.contains("male") && !n.contains("female")) || n.contains("smtm")) && male == null) male = x;
                }
                if (pick == null && name.isEmpty()) pick = male;
                if (pick != null) tts.setVoice(pick);
                tts.setPitch(v != null ? (float) v.optDouble("pitch", .88) : .88f);
                tts.setSpeechRate(v != null ? (float) v.optDouble("rate", 1) : 1f);
            } catch (Exception ignored) { }
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String id) { }
                @Override public void onDone(String id) { h.postDelayed(() -> { if (closing) finishQuiet(); else if (!hasAction && "conv".equals(id)) listen(); }, 400); }
                @Override public void onError(String id) { }
            });
        });
    }

    private void say(String t) {
        if (tts == null || !ttsReady) { h.postDelayed(() -> say(t), 300); return; }
        tts.speak(t, TextToSpeech.QUEUE_FLUSH, null, t.length() < 25 ? "short" : "conv");
    }

    private void stopTts() { try { if (tts != null) tts.stop(); } catch (Exception ignored) { } }

    private void finishQuiet() { stopTts(); finish(); overridePendingTransition(0, 0); }

    @Override
    protected void onDestroy() { try { if (tts != null) tts.shutdown(); } catch (Exception ignored) { } super.onDestroy(); }
}
