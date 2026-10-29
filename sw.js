// Cockpit PEA : fonctionne hors connexion, se met à jour tout seul, et envoie tes rappels.
const CACHE='cockpit-j1793312244';
const CORE=['./','index.html','manifest.webmanifest','icon-192.png','icon-512.png','cockpit.enc'];
self.addEventListener('install',e=>{e.waitUntil(caches.open(CACHE).then(c=>c.addAll(CORE.map(u=>new Request(u,{cache:'reload'})))).then(()=>self.skipWaiting()))});
self.addEventListener('activate',e=>{e.waitUntil(caches.keys().then(ks=>Promise.all(ks.filter(k=>k!==CACHE).map(k=>caches.delete(k)))).then(()=>self.clients.claim()))});
self.addEventListener('fetch',e=>{
  const req=e.request;
  if(req.method==='POST'&&new URL(req.url).pathname.endsWith('/share')){e.respondWith((async()=>{try{const fd=await req.formData();const L=[];
    for(const f of fd.getAll('files'))if(f&&f.size)L.push({name:f.name,type:f.type,blob:f});const prev=(await kv('share'))||[];await kv('share',[...prev,...L].slice(-20))}catch(err){}
    return Response.redirect(new URL('./?share=1',self.registration.scope).href,303)})());return}
  if(req.method!=='GET')return;
  const url=new URL(req.url);
  const fonts=/fonts\.(googleapis|gstatic)\.com$/.test(url.hostname);
  if(url.origin===location.origin){
    e.respondWith(fetch(req,{cache:'no-cache'}).then(r=>{if(r.ok){const cp=r.clone();caches.open(CACHE).then(c=>c.put(req,cp))}return r})
      .catch(()=>caches.match(req,{ignoreSearch:true}).then(r=>r||caches.match('index.html'))));
  }else if(fonts){
    e.respondWith(caches.match(req).then(hit=>hit||fetch(req).then(r=>{const cp=r.clone();caches.open(CACHE).then(c=>c.put(req,cp));return r})));
  }
});

// ---------- rappels : la liste est préparée par le cockpit (dates et textes seulement) ----------
const idb=()=>new Promise((res,rej)=>{const r=indexedDB.open('cockpit-notif',1);r.onupgradeneeded=()=>r.result.createObjectStore('kv');r.onsuccess=()=>res(r.result);r.onerror=()=>rej(r.error)});
async function kv(k,v){const db=await idb();return new Promise((res,rej)=>{const tx=db.transaction('kv',v===undefined?'readonly':'readwrite');const s=tx.objectStore('kv');const r=v===undefined?s.get(k):s.put(v,k);r.onsuccess=()=>res(v===undefined?r.result:v);r.onerror=()=>rej(r.error)})}
const today=()=>{const n=new Date();return n.getFullYear()+'-'+String(n.getMonth()+1).padStart(2,'0')+'-'+String(n.getDate()).padStart(2,'0')};
async function runChecks(force){
  const cfg=await kv('cfg');if(!cfg||!cfg.on)return 0;
  const shown=new Set((await kv('shown'))||[]);const t=today();let n=0;
  const old=new Date(Date.now()-3*864e5).toISOString().slice(0,10);
  for(const r of cfg.items||[]){if(r.at>t||r.at<old||shown.has(r.id))continue;
    try{await self.registration.showNotification(r.title,{body:r.body,tag:r.id,icon:'icon-192.png',badge:'icon-192.png',data:{hash:r.hash||''}});shown.add(r.id);n++}catch(e){await kv('err',String(e&&e.message||e));break}}
  // alerte de marché : baisse depuis le plus haut récent
  const m=cfg.market;if(m&&m.on&&m.key&&m.sym){const last=(await kv('mkt'))||{};
    if(force||!last.at||Date.now()-last.at>20*3600e3){try{
      const j=await fetch('https://www.alphavantage.co/query?function=GLOBAL_QUOTE&symbol='+encodeURIComponent(m.sym)+'&apikey='+encodeURIComponent(m.key)).then(r=>r.json());
      const px=parseFloat(((j||{})['Global Quote']||{})['05. price']);
      if(px>0){let peak=Math.max(last.peak||m.peak||px,px);const drop=1-px/peak;
        if(drop>=(m.thr||5)/100&&(!last.notified||Date.now()-last.notified>6*864e5)){
          await self.registration.showNotification(`${m.name||'Marché'} : −${(drop*100).toFixed(1).replace('.',',')} %`,{body:`Baisse depuis le plus haut récent. Ton DCA achète moins cher : regarde ton prochain achat.`,tag:'mkt',icon:'icon-192.png',data:{hash:'achat'}});last.notified=Date.now();n++;peak=px}
        Object.assign(last,{peak,px,at:Date.now()})}}catch(e){}
      await kv('mkt',last)}}
  await kv('shown',[...shown].slice(-200));return n}
self.addEventListener('periodicsync',e=>{if(e.tag==='cockpit-rappels')e.waitUntil(runChecks(false))});
self.addEventListener('message',e=>{const d=e.data||{};
  if(d.type==='check')e.waitUntil(runChecks(!!d.force));
  if(d.type==='test')e.waitUntil(self.registration.showNotification('Cockpit PEA',{body:'Les notifications fonctionnent. Tes rappels arriveront ici.',tag:'test',icon:'icon-192.png',data:{hash:''}}))});
self.addEventListener('notificationclick',e=>{e.notification.close();const h=(e.notification.data||{}).hash;const url=new URL('./'+(h?'#'+h:''),self.registration.scope).href;
  e.waitUntil(self.clients.matchAll({type:'window',includeUncontrolled:true}).then(cs=>{for(const c of cs){if('focus' in c){c.postMessage({type:'go',hash:h});return c.focus()}}return self.clients.openWindow(url)}))});
