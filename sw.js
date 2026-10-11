// Cockpit PEA : fonctionne hors connexion, se met à jour tout seul quand le téléphone est en ligne
const CACHE='cockpit-v1';
const CORE=['./','index.html','manifest.webmanifest','icon-192.png','icon-512.png','cockpit.enc'];
self.addEventListener('install',e=>{e.waitUntil(caches.open(CACHE).then(c=>c.addAll(CORE)).then(()=>self.skipWaiting()))});
self.addEventListener('activate',e=>{e.waitUntil(caches.keys().then(ks=>Promise.all(ks.filter(k=>k!==CACHE).map(k=>caches.delete(k)))).then(()=>self.clients.claim()))});
self.addEventListener('fetch',e=>{
  const req=e.request;if(req.method!=='GET')return;
  const url=new URL(req.url);
  const fonts=/fonts\.(googleapis|gstatic)\.com$/.test(url.hostname);
  if(url.origin===location.origin){
    // réseau d'abord (pour recevoir les nouvelles versions), cache si hors ligne
    e.respondWith(fetch(req).then(r=>{if(r.ok){const cp=r.clone();caches.open(CACHE).then(c=>c.put(req,cp))}return r})
      .catch(()=>caches.match(req,{ignoreSearch:true}).then(r=>r||caches.match('index.html'))));
  }else if(fonts){
    e.respondWith(caches.match(req).then(hit=>hit||fetch(req).then(r=>{const cp=r.clone();caches.open(CACHE).then(c=>c.put(req,cp));return r})));
  }
});
