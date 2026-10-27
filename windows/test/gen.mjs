import {webcrypto as crypto} from 'node:crypto';import fs from 'node:fs';
const syncNorm=c=>String(c||'').normalize('NFKC').toLowerCase().replace(/[‐-―−_ \s]/g,m=>/\s| /.test(m)?'':'-');
const b64e=u=>Buffer.from(new Uint8Array(u)).toString('base64');
async function key(code,salt){const base=await crypto.subtle.importKey('raw',new TextEncoder().encode(syncNorm(code)),'PBKDF2',false,['deriveKey']);return crypto.subtle.deriveKey({name:'PBKDF2',salt,iterations:200000,hash:'SHA-256'},base,{name:'AES-GCM',length:256},false,['encrypt','decrypt'])}
const code='Test–Code 123 ';
const lines=[{k:'W',n:'MSCI World',s:'DCAM.PAR',y:'DCAM.PA',q:100,px:6,c:'#2f6bd8'},{k:'N',n:'Nasdaq-100',s:'PUST.PAR',y:'PUST.PA',q:2,px:100,c:'#7a4fc9'},{k:'S',n:'Stoxx 600',s:'ETZD.PAR',y:'ETZD.PA',q:10,px:18,c:'#13906f'}];
const mini={v:1,privacy:false,value:1000,verse:950,gain:50,dayAbs:1,dayPct:.001,cash:30.5,lines,envSpent:10,envCap:150,envOn:true,apCur:300,apTgt:50000,msCur:12000,msTgt:100000,next:'1 100 € sur le PEA le 6 nov.',alert:'',hist:[['2026-09-01',800,800],['2026-10-01',950,940],['2026-10-26',1000,950]]};
const snap={journal:[{d:'2026-10-27',tag:'Idée',txt:'Accents éèà ✓ et emoji 🙂 pour tester l\'UTF-8'}],_mini:mini,pad:'x'.repeat(5000)};
const salt=crypto.getRandomValues(new Uint8Array(16)),iv=crypto.getRandomValues(new Uint8Array(12));
const ct=await crypto.subtle.encrypt({name:'AES-GCM',iv},await key(code,salt),new TextEncoder().encode(JSON.stringify(snap)));
const raw={at:Date.now(),q:{'DCAM.PA':{p:6.5,prev:6.4,d:'2026-10-27'},'PUST.PA':{p:110,prev:108,d:'2026-10-27'},'ETZD.PA':{p:18.5,prev:18.4,d:'2026-10-27'},
 '^FCHI':{p:7887.1,prev:7847.3},'^STOXX':{p:643.7,prev:640.7},'^GSPC':{p:7728.3,prev:7676.8},'^IXIC':{p:26508,prev:26169},'^N225':{p:63268,prev:62190},'EURUSD=X':{p:1.114,prev:1.1218},'^VIX':{p:17.7,prev:18.4},'BTC-EUR':{p:72321,prev:71603}},
 news:{tout:[{t:'Wall Street progresse, portée par le regain d’intérêt pour l’intelligence artificielle',s:'Zonebourse',u:'https://example.com/1',p:0},{t:'Boeing plonge en Bourse après des perspectives de production décevantes',s:'TradingView',u:'https://example.com/2',p:0},{t:'Le Nikkei finit en hausse de 1,73 %',s:'Les Echos',u:'https://example.com/3',p:0},{t:'La BCE devrait garder ses taux inchangés jeudi',s:'Le Figaro',u:'https://example.com/4',p:0},{t:'Le pétrole recule de près de 6 %',s:'BFM Bourse',u:'https://example.com/5',p:0}]}};
const brief={title:'Les marchés montent avant une semaine chargée en banques centrales',agenda:[{d:'2099-10-28',h:'19:00',t:'Décision de la Fed (taux américains)',level:3},{d:'2099-10-29',h:'14:15',t:'Décision de la BCE',level:3},{d:'2099-10-30',h:'matin',t:'Décision de la Banque du Japon',level:2},{d:'2099-11-06',h:'13:30',t:'Emploi américain',level:2}]};
const expect=100*6.5+2*110+10*18.5+30.5;
fs.writeFileSync('vector.json',JSON.stringify({code,salt:b64e(salt),iv:b64e(iv),ct:b64e(ct),raw,brief,expectValue:expect}));
// vérif aller-retour en JS
const pt=await crypto.subtle.decrypt({name:'AES-GCM',iv},await key('test-code123',salt),ct);console.log('js roundtrip ok',new TextDecoder().decode(pt).length,'expect',expect);
