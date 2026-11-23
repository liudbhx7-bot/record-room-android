// Build the player first, then run: node web-player/tests/offline-demos.cjs OUTPUT_DIR
// Requires Playwright with the WebKit and Chromium browsers installed.
const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),http=require('node:http');
const {webkit,chromium}=require('playwright');
const root=path.resolve(process.argv[2]);
const mime={'.js':'application/javascript','.json':'application/json','.webmanifest':'application/manifest+json',
  '.css':'text/css','.webp':'image/webp','.png':'image/png','.html':'text/html','.wav':'audio/wav'};
const logged=[];
let up=true, installGate;
const server=http.createServer(async(req,res)=>{
  if(!up){req.socket.destroy();return;}
  const route=new URL(req.url,'http://localhost').pathname;
  if(route.endsWith('jsmediatags-license.txt') && installGate)await installGate;
  const asset=path.join(root,route==='/'?'index.html':route);
  if(!fs.existsSync(asset)){res.writeHead(404).end();return;}
  logged.push(route);
  const headers={'Content-Type':mime[path.extname(asset)]||'application/octet-stream','Cache-Control':'no-store'};
  const range=/^bytes=(\d+)-(\d*)$/.exec(req.headers.range || '');
  if(range){
    const size=fs.statSync(asset).size,start=Number(range[1]),end=range[2]?Math.min(size-1,Number(range[2])):size-1;
    res.writeHead(206,{...headers,'Content-Length':end-start+1,'Content-Range':'bytes '+start+'-'+end+'/'+size,'Accept-Ranges':'bytes'});
    fs.createReadStream(asset,{start,end}).pipe(res);
  }else{res.writeHead(200,headers);fs.createReadStream(asset).pipe(res);}
});
const audioPaths=()=>logged.filter(f=>/-(1|2|3)\.wav$/.test(f));
async function waitForAsync(page,predicate){
  const start=Date.now();
  do{if(await page.evaluate(predicate))return;await new Promise(r=>setTimeout(r,70));}while(Date.now()-start<12000);
  throw new Error('Timed out: '+predicate);
}
async function verify(engine,base){
  const b=await engine.launch();
  try{
    up=true;logged.length=0;const p=await b.newPage();p.setDefaultTimeout(12000);
    await p.goto(base);
    await p.waitForFunction(()=>!!navigator.serviceWorker.controller && !document.getElementById('play').disabled);
    const keys=()=>p.evaluate(async()=>{const out=[];for(const name of await caches.keys()){for(const req of await (await caches.open(name)).keys())out.push(new URL(req.url).pathname);}return out;});
    assert.deepEqual(audioPaths(),[]);assert(!(await keys()).some(f=>/-\d\.wav$/.test(f)));
    await p.evaluate(()=>{const s=document.getElementById('seek');s.value='3';s.dispatchEvent(new Event('change'));});
    assert.deepEqual(audioPaths(),[]);
    await p.click('#play');await p.waitForFunction(()=>document.getElementById('audio').currentTime>3);
    await waitForAsync(p,async()=>!!await caches.match('assets/night-1.wav'));
    assert.deepEqual(audioPaths(),['/assets/night-1.wav']);
    assert.deepEqual((await keys()).filter(f=>/-\d\.wav$/.test(f)),['/assets/night-1.wav']);
    up=false;
    await p.reload();await p.waitForFunction(()=>!document.getElementById('play').disabled);
    await p.click('#play');await p.waitForFunction(()=>document.getElementById('audio').currentTime>.25);
    await p.evaluate(()=>{const s=document.getElementById('seek');s.value='8';s.dispatchEvent(new Event('change'));});
    await p.waitForFunction(()=>document.getElementById('audio').currentTime>=8);
    const range=await p.evaluate(async()=>{const r=await fetch('assets/night-1.wav',{headers:{Range:'bytes=20-24'}});
      return [r.status,(await r.arrayBuffer()).byteLength];});assert.deepEqual(range,[206,5]);
    await p.click('#next');await p.waitForFunction(()=>document.getElementById('message').textContent.includes('联网'));
    assert.equal(await p.locator('#title').innerText(),'灯下留声');assert((await keys()).every(f=>!f.endsWith('night-2.wav')));
    up=true;
    await p.click('#play');await p.waitForFunction(()=>document.getElementById('audio').currentTime>.25);
    assert.deepEqual(audioPaths(),['/assets/night-1.wav','/assets/night-2.wav']);
    // Cold first play prior to worker control, with pre-upgrade media on disk.
    const fresh=await b.newPage();fresh.setDefaultTimeout(12000);
    let release;
    installGate=new Promise(resolve=>{release=resolve;});logged.length=0;
    try{
      await fresh.goto(base);await fresh.waitForFunction(()=>!document.getElementById('play').disabled);
      await fresh.evaluate(async base=>{
        const old=await caches.open('recordroom:'+base+':previous');
        await old.add(base+'assets/night-2.wav');
        await old.put(base+'assets/night-3.wav',new Response('outdated'));
      },base);
      logged.length=0;
      assert.equal(await fresh.evaluate(()=>!!navigator.serviceWorker.controller),false);
      await fresh.click('#play');await fresh.waitForFunction(()=>document.getElementById('audio').currentTime>.2);
    }finally{release();installGate=null;}
    await waitForAsync(fresh,async()=>!!navigator.serviceWorker.controller && !!await caches.match('assets/night-1.wav'));
    assert(await fresh.evaluate(async()=>!!await caches.match('assets/night-2.wav') && !await caches.match('assets/night-3.wav')));
    assert(audioPaths().every(f=>f==='/assets/night-1.wav')); // Migration made no network requests.
    console.log(engine.name()+': PASS zero startup WAVs, single on-demand request, offline restart/play/seek, uncached failure/retry, pre-control first play, old-cache migration.');
  }finally{await b.close();}
}
(async()=>{await new Promise(r=>server.listen(0,'127.0.0.1',r));
  try{for(const engine of [webkit,chromium])await verify(engine,'http://127.0.0.1:'+server.address().port+'/');}
  finally{server.closeAllConnections();server.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
