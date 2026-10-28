"""Point du jour automatique (sans IA) pour l'onglet Journal du Cockpit.

Chaque matin de semaine : si aucun point du jour n'a été écrit pour aujourd'hui, ce script en rédige un
à partir des cours (journal/raw.json), des « notes du matin » des journaux économiques (Google Actualités)
et de l'agenda déjà connu. L'appli Android envoie une notification dès qu'il est publié.
"""
import datetime
import email.utils
import json
import os
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET

PARIS = datetime.timezone(datetime.timedelta(hours=1))  # heure d'hiver ; l'été la date reste la même
now = datetime.datetime.now(datetime.timezone.utc).astimezone(PARIS)
today = now.strftime("%Y-%m-%d")
UA = {"User-Agent": "Mozilla/5.0 (CockpitJournal)"}

brief_path = "journal/brief.json"
OUT = os.environ.get("BRIEF_OUT") or brief_path  # test : écrire ailleurs sans toucher au vrai point du jour
old = {}
if os.path.exists(brief_path):
    try:
        old = json.load(open(brief_path, encoding="utf-8"))
    except Exception:
        old = {}
if old.get("date") == today and not old.get("auto") and OUT == brief_path:
    print("Point du jour déjà rédigé aujourd'hui : rien à faire.")
    raise SystemExit(0)

raw = json.load(open("journal/raw.json", encoding="utf-8"))
Q = raw.get("q", {})


def ch(sym):
    q = Q.get(sym) or {}
    p, prev = q.get("p") or 0, q.get("prev") or 0
    return (p / prev - 1) if p and prev else None


def val(sym):
    return (Q.get(sym) or {}).get("p")


def pct(x, d=1):
    s = f"{abs(x) * 100:.{d}f}".replace(".", ",")
    return ("+" if x > 0 else "−" if x < 0 else "") + s + " %"


def adj(x, fem=False):
    e = "e" if fem else ""
    if x is None:
        return "sans cotation"
    if x >= 0.01:
        return "en nette hausse"
    if x >= 0.002:
        return "en hausse"
    if x > -0.002:
        return "quasi stable"
    if x > -0.01:
        return "en baisse"
    return "en nette baisse"


def num(v, d=0):
    return f"{v:,.{d}f}".replace(",", " ").replace(".", ",")


cac, stoxx, spx, ndx, nik, eem = (ch(s) for s in ["^FCHI", "^STOXX", "^GSPC", "^IXIC", "^N225", "EEM"])
fx, brent, gold, vix, btc = (ch(s) for s in ["EURUSD=X", "BZ=F", "GC=F", "^VIX", "BTC-EUR"])
main = [x for x in [cac, stoxx, spx, ndx, nik] if x is not None]
avg = sum(main) / len(main) if main else 0
vix_v = val("^VIX") or 0

# ---------- titre et ambiance
if avg > 0.005:
    base = "Les marchés montent"
elif avg > 0.001:
    base = "Légère hausse des marchés"
elif avg > -0.001:
    base = "Marchés hésitants"
elif avg > -0.005:
    base = "Légère baisse des marchés"
else:
    base = "Les marchés reculent"
names = {"^FCHI": "le CAC 40", "^STOXX": "l'Europe", "^GSPC": "Wall Street", "^IXIC": "le Nasdaq", "^N225": "Tokyo"}
movers = sorted([(abs(ch(s) or 0), s) for s in names], reverse=True)
extra = ""
if brent is not None and abs(brent) >= 0.03:
    extra = f", le pétrole {'bondit' if brent > 0 else 'chute'} de {pct(abs(brent), 0).lstrip('+')}"
elif movers and movers[0][0] >= 0.008:
    s = movers[0][1]
    extra = f", {names[s]} en tête" if (ch(s) or 0) * avg >= 0 else f", sauf {names[s]}"
title = base + extra
if vix_v >= 25:
    mood = "tendu"
elif avg > 0.004:
    mood = "optimiste"
elif avg < -0.004:
    mood = "prudent"
else:
    mood = "calme"

# ---------- résumé
p1 = f"À la dernière séance, la Bourse de Paris a fini {adj(cac, True)}" + (f" ({pct(cac)}, {num(val('^FCHI'))} points)" if cac is not None else "") \
     + (f" et l'ensemble de l'Europe (Stoxx 600) {adj(stoxx, True)} ({pct(stoxx)})." if stoxx is not None else ".")
p2 = f"À Wall Street, le S&P 500 termine {adj(spx)}" + (f" ({pct(spx)})" if spx is not None else "") \
     + (f" et le Nasdaq {adj(ndx)} ({pct(ndx)})." if ndx is not None else ".") \
     + (f" Ce matin, la Bourse de Tokyo (Nikkei) est {adj(nik, True)} ({pct(nik)})." if nik is not None else "")
bits = []
if fx is not None and abs(fx) >= 0.003:
    bits.append(f"l'euro {'monte' if fx > 0 else 'baisse'} face au dollar ({pct(fx)}, 1 € = {num(val('EURUSD=X'), 3)} $)")
if brent is not None and abs(brent) >= 0.015:
    bits.append(f"le pétrole Brent {'grimpe' if brent > 0 else 'recule'} de {pct(abs(brent)).lstrip('+')} (≈ {num(val('BZ=F'))} $)")
if gold is not None and abs(gold) >= 0.01:
    bits.append(f"l'or {'progresse' if gold > 0 else 'recule'} ({pct(gold)})")
if vix_v:
    bits.append(f"l'indice de la peur (VIX) est à {num(vix_v, 1)}" + (" : les marchés sont nerveux" if vix_v >= 25 else " : ambiance calme" if vix_v < 15 else ""))
p3 = ("Côté matières premières et devises : " + ", ".join(bits) + ".") if bits else ""
summary = [x for x in [p1, p2, p3] if x]

if avg >= 0:
    for_you = "Séance favorable pour un portefeuille d'ETF diversifié comme le tien. Rien à faire : ton plan continue, prochain achat comme prévu."
else:
    for_you = "Petite baisse : rien d'alarmant pour un investissement sur 10 ans et plus. Ton DCA achètera simplement un peu moins cher : ne change rien."
if fx is not None and abs(fx) >= 0.004:
    for_you += f" L'euro {'plus fort réduit un peu' if fx > 0 else 'plus faible gonfle un peu'} la valeur en euros de tes ETF exposés aux États-Unis."

zones = {
    "monde": f"Le MSCI World suit surtout Wall Street (environ 70 % d'actions américaines) : S&P 500 {pct(spx) if spx is not None else '—'}"
             + (f", euro/dollar {pct(fx)}." if fx is not None else "."),
    "us": f"S&P 500 {pct(spx) if spx is not None else '—'}, Nasdaq {pct(ndx) if ndx is not None else '—'} : ton Nasdaq-100 suit surtout les géants de la tech.",
    "europe": f"Stoxx 600 {pct(stoxx) if stoxx is not None else '—'}, CAC 40 {pct(cac) if cac is not None else '—'}.",
    "japon": f"Nikkei {pct(nik) if nik is not None else '—'} à la Bourse de Tokyo.",
    "emergents": f"Indice des pays émergents (EEM, en dollars) {pct(eem) if eem is not None else '—'}.",
}

MK = [("^FCHI", "CAC 40"), ("^STOXX", "Stoxx 600"), ("^GSPC", "S&P 500"), ("^IXIC", "Nasdaq"), ("^N225", "Nikkei 225"), ("EEM", "Émergents (EEM)"),
      ("EURUSD=X", "Euro / dollar"), ("^TNX", "Taux US à 10 ans"), ("^VIX", "Indice de la peur (VIX)"), ("GC=F", "Or"), ("BZ=F", "Pétrole (Brent)"), ("BTC-EUR", "Bitcoin")]
markets = [{"k": k, "n": n, "v": round(val(k), 4), "ch": round(ch(k), 5)} for k, n in MK if val(k) and ch(k) is not None]

# ---------- notes du matin des journaux (Google Actualités)
def news(q, n):
    try:
        req = urllib.request.Request("https://news.google.com/rss/search?q=" + urllib.parse.quote(q) + "&hl=fr&gl=FR&ceid=FR:fr", headers=UA)
        root = ET.fromstring(urllib.request.urlopen(req, timeout=20).read())
    except Exception as e:
        print("actu", e)
        return []
    out = []
    for it in root.iter("item"):
        t, s = it.findtext("title") or "", it.findtext("source") or ""
        if s and t.endswith(" - " + s):
            t = t[: -(len(s) + 3)]
        try:
            p = email.utils.parsedate_to_datetime(it.findtext("pubDate")).timestamp()
        except Exception:
            p = 0
        out.append({"t": t, "s": s, "u": it.findtext("link") or "", "p": p})
    out.sort(key=lambda a: -a["p"])
    return out[:n]


reads = []
seen = set()
for q in ['"avant-Bourse" OR "point marchés" OR "ce qu\'il faut savoir" OR "à suivre aujourd\'hui" bourse when:1d',
          '"Bourse de Paris" ouverture OR "CAC 40" matin when:1d', 'Wall Street clôture when:1d']:
    for a in news(q, 6):
        key = a["t"][:60].lower()
        if key in seen:
            continue
        seen.add(key)
        reads.append({"t": a["t"], "s": a["s"], "u": a["u"]})
        if len(reads) >= 5:
            break
    if len(reads) >= 5:
        break

headlines = list(reads)
for z, items in (raw.get("news") or {}).items():
    for a in items[:4]:
        key = a["t"][:60].lower()
        if key in seen:
            continue
        seen.add(key)
        headlines.append({"t": a["t"], "s": a.get("s", ""), "u": a.get("u", ""), "z": "monde" if z == "tout" else z})
headlines = headlines[:18]

# ---------- agenda : rendez-vous encore à venir du dernier point du jour + emploi américain (1er vendredi)
agenda = [a for a in (old.get("agenda") or []) if a.get("d", "") >= today]
d0 = now.date()
for k in range(0, 2):
    y, m = d0.year + (d0.month - 1 + k) // 12, (d0.month - 1 + k) % 12 + 1
    d = datetime.date(y, m, 1)
    while d.weekday() != 4:
        d += datetime.timedelta(days=1)
    ds = d.isoformat()
    if ds >= today and not any("mploi" in a.get("t", "") and a.get("d") == ds for a in agenda):
        agenda.append({"d": ds, "h": "14:30", "t": "Emploi américain (chiffres du mois)", "z": "us", "level": 2,
                       "impact": "Date habituelle (1er vendredi du mois), à confirmer. Un emploi trop fort fait craindre des taux élevés plus longtemps."})
agenda.sort(key=lambda a: a["d"] + a.get("h", ""))
agenda = agenda[:8]

LESSONS = [
    "DCA : investir la même somme chaque mois, quel que soit le marché. Tu achètes plus de parts quand c'est bas, moins quand c'est haut, sans avoir à deviner le bon moment.",
    "Un ETF capitalisant réinvestit tout seul les dividendes dans son cours : rien n'arrive sur ton compte, mais ta part vaut plus. C'est le plus efficace dans un PEA.",
    "Les intérêts composés : tes gains produisent eux-mêmes des gains. Peu visibles les premières années, ils deviennent le moteur principal après 10 à 15 ans.",
    "Une baisse de 10 % arrive en moyenne presque chaque année en Bourse. Ce n'est pas une crise : c'est le prix à payer pour un rendement supérieur aux livrets.",
    "Le MSCI World contient environ 1 400 entreprises de 23 pays développés : en l'achetant, tu diversifies en une seule ligne.",
    "Les frais comptent : 0,2 % de frais par an au lieu de 1,5 %, sur 30 ans, c'est des milliers d'euros en plus pour toi.",
    "Le PEA devient vraiment intéressant après 5 ans : les gains ne paient plus d'impôt sur le revenu, seulement les prélèvements sociaux.",
    "Le VIX mesure la peur sur les marchés : sous 15, c'est calme ; au-dessus de 30, c'est la panique. Les meilleurs achats se font souvent quand il est haut.",
    "Quand la banque centrale monte ses taux, l'argent coûte plus cher : les actions baissent souvent, surtout la tech. Quand elle les baisse, c'est l'inverse.",
    "Un euro plus fort fait baisser en euros la valeur de tes actions américaines, même si elles ne bougent pas en dollars. Sur le long terme, cet effet s'équilibre.",
    "Le rendement d'un livret réglementé est garanti mais souvent proche de l'inflation : il protège ton épargne, il ne la fait pas vraiment grossir.",
    "Ne regarde pas ton portefeuille tous les jours : plus on regarde, plus on a envie d'agir. En DCA, la discipline compte plus que le timing.",
    "Diversifier, c'est ne pas dépendre d'un seul pays ou d'un seul secteur : tes 5 ETF couvrent les États-Unis, l'Europe, le Japon et les pays émergents.",
    "Le pétrole influence presque tout : quand il baisse, l'inflation ralentit et les banques centrales peuvent desserrer les taux, bonne nouvelle pour les actions.",
    "Un indice comme le CAC 40 est un panier d'entreprises : il monte quand la majorité de ces entreprises valent plus cher.",
    "Les résultats trimestriels des entreprises font bouger les cours : une entreprise peut baisser même avec de bons chiffres si le marché attendait mieux.",
    "Le Nasdaq-100 regroupe les 100 plus grandes entreprises non financières du Nasdaq, très tournées vers la tech : plus de rendement possible, mais plus de secousses.",
    "Les marchés émergents (Chine, Inde, Taïwan…) grandissent plus vite mais sont plus volatils : une petite part suffit pour en profiter.",
    "Avoir un matelas de sécurité (ton LEP) est ce qui te permet de ne jamais vendre tes ETF au mauvais moment.",
    "Le prix de revient unitaire (PRU) est le prix moyen auquel tu as acheté une ligne : tant que le cours est au-dessus, tu es en plus-value.",
]
lesson = LESSONS[now.timetuple().tm_yday % len(LESSONS)]

brief = {"v": 1, "auto": True, "date": today, "at": now.isoformat(timespec="minutes"), "title": title, "mood": mood,
         "summary": summary, "forYou": for_you, "zones": zones, "markets": markets, "agenda": agenda,
         "reads": reads, "headlines": headlines, "lesson": lesson}
json.dump(brief, open(OUT, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
print("Point du jour automatique :", title)
