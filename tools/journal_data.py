"""Collecte les cours des marchés (Yahoo Finance) et les titres d'actu (Google Actualités) pour l'onglet Journal."""
import json, time, urllib.parse, urllib.request, xml.etree.ElementTree as ET, email.utils, datetime, os

UA = {"User-Agent": "Mozilla/5.0 (CockpitJournal)"}
MKT = ["^FCHI", "^STOXX", "^GSPC", "^IXIC", "^N225", "EEM", "EURUSD=X", "^TNX", "^VIX", "GC=F", "BZ=F", "BTC-EUR",
       "DCAM.PA", "PUST.PA", "ETZD.PA", "PTPXE.PA", "PAEEM.PA"]
NEWSQ = {
    "tout": 'bourse OR "marchés financiers" OR Wall Street when:1d',
    "us": '"Wall Street" OR Nasdaq OR "Réserve fédérale" when:1d',
    "europe": '"Bourse de Paris" OR "CAC 40" OR BCE OR "Stoxx 600" when:1d',
    "japon": '"Bourse de Tokyo" OR Nikkei OR "Banque du Japon" when:3d',
    "emergents": '"marchés émergents" OR "Bourse de Shanghai" OR "Bourse de Bombay" OR "Chine économie" when:3d',
    "taux": 'inflation OR "taux directeurs" OR "euro dollar" when:2d',
}

def get(url):
    with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=20) as r:
        return r.read()

def quote(sym):
    j = json.loads(get("https://query1.finance.yahoo.com/v8/finance/chart/" + urllib.parse.quote(sym) + "?range=5d&interval=1d"))
    r = j["chart"]["result"][0]
    m = r["meta"]
    paris = datetime.timezone(datetime.timedelta(hours=1))
    day = datetime.datetime.fromtimestamp(m["regularMarketTime"], paris).strftime("%Y-%m-%d")
    prev = 0
    closes = []
    for t, c in zip(r.get("timestamp", []), r["indicators"]["quote"][0].get("close", [])):
        if c is None:
            continue
        d = datetime.datetime.fromtimestamp(t, paris).strftime("%Y-%m-%d")
        if d < day:
            prev = c
            closes.append([d, round(c, 4)])
    return {"p": m["regularMarketPrice"], "d": day, "prev": prev, "closes": closes}

def news(q):
    x = get("https://news.google.com/rss/search?q=" + urllib.parse.quote(q) + "&hl=fr&gl=FR&ceid=FR:fr")
    out = []
    for it in ET.fromstring(x).iter("item"):
        t = it.findtext("title") or ""
        s = it.findtext("source") or ""
        if s and t.endswith(" - " + s):
            t = t[: -(len(s) + 3)]
        try:
            p = int(email.utils.parsedate_to_datetime(it.findtext("pubDate")).timestamp() * 1000)
        except Exception:
            p = 0
        out.append({"t": t, "s": s, "u": it.findtext("link") or "", "p": p})
    out.sort(key=lambda a: -a["p"])
    return out[:25]

data = {"at": int(time.time() * 1000), "q": {}, "news": {}}
for s in MKT:
    try:
        data["q"][s] = quote(s)
    except Exception as e:
        print("cours", s, e)
for z, q in NEWSQ.items():
    try:
        data["news"][z] = news(q)
    except Exception as e:
        print("actu", z, e)
os.makedirs("journal", exist_ok=True)
json.dump(data, open("journal/raw.json", "w"), ensure_ascii=False, separators=(",", ":"))
print("ok", len(data["q"]), "cours,", sum(len(v) for v in data["news"].values()), "titres")
