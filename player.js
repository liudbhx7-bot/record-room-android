'use strict';
const get = id => document.getElementById(id);
const audio = get('audio');
const sheet = get('sheet');
const content = get('content');
const saved = {
  get(key) { try { return localStorage.getItem('recordroom.' + key); } catch { return null; } },
  set(key, value) { try { localStorage.setItem('recordroom.' + key, value); } catch {} }
};
const tracks = [];
let sequence = [], current, sourceURL, database, pendingSeek = 0, page = '', readyOffline = false;
let importing = false, scrub = false, lastSecond = -1, playbackRequested = false;
const offlineHint = '播放器已可离线打开。内置歌曲首次播放需要联网，已缓存的歌曲可离线重听。';
const fallbackArt = 'assets/icon-256.png';
function element(tag, text, cls) {
  const node = document.createElement(tag);
  if (text) node.textContent = text;
  if (cls) node.className = cls;
  return node;
}
function row(title, detail, action, art) {
  const button = element('button', '', 'row');
  if (art) { const image = element('img'); image.src = art; image.alt = ''; button.append(image); }
  const text = element('span');
  text.append(element('strong', title));
  if (detail) text.append(element('em',detail));
  button.append(text,element('span','›','chev'));
  button.onclick = action;
  return button;
}
function openPage(name, title) {
  page = name; get('heading').textContent = title; content.replaceChildren();
  if (!sheet.open) sheet.showModal();
  sheet.scrollTop = 0;
}
function cover(track) {
  if (!track) return fallbackArt;
  if (track.picture && !track.cover) track.cover = URL.createObjectURL(track.picture);
  return track.cover || fallbackArt;
}
function showTools() {
  openPage('tools','播放工具');
  content.append(row('音乐来源', '', showSources), row('资料库', '', () => showLibrary()),
    row('搜索歌曲或音乐人','',showSearch));
  const title=element('h3','音量');
  const level=element('input','','volume'); level.type='range'; level.min='0'; level.max='1'; level.step='0.01';
  level.setAttribute('aria-label','播放音量');level.value=String(audio.volume);
  level.oninput=() => {audio.volume=Number(level.value);level.value=String(audio.volume);saved.set('volume',audio.volume);};
  content.append(title, level, element('p','iPhone 可使用机身音量键。','hint'));
  const offline = element('p',readyOffline ? offlineHint : '首次使用时请保持联网，直到离线资源准备完成。','hint');
  offline.id='offline'; content.append(offline);
}
function showSources() {
  openPage('sources','音乐来源');
  const hero=element('div','','hero'),text=element('div');
  text.append(element('small','当前来源'),element('strong','唱片机资料库'));
  const jacket=element('img'); jacket.src=cover(current); jacket.alt='';
  hero.append(text,jacket);
  content.append(hero,element('h3','我的资料库'),
    row('本机音乐',tracks.filter(t=>t.file).length+' 首已导入',()=>showLibrary('', 'files')),
    row('导入音频文件','从「文件」选择歌曲',()=>get('files').click()),
    row('内置唱片',tracks.filter(t=>!t.file).length+' 首演示曲',()=>showLibrary('', 'demos')));
  content.append(element('h3','官方音乐服务'));
  for (const [name,url] of [['Apple Music','https://music.apple.com/'],['网易云音乐','https://music.163.com/'],['QQ 音乐','https://y.qq.com/'],['汽水音乐','https://qishui.douyin.com/']]) {
    const link=element('a','','row');link.href=url; link.target='_blank';link.rel='noopener noreferrer';link.style.cssText='color:inherit;text-decoration:none';
    link.append(element('strong', name),element('span','在官方页面打开 ↗','chev'));content.append(link);
  }
}
function albumKey(t) { return t.album+'\x1f'+(t.albumArtist||t.artist); }
function showLibrary(key, subset) {
  const list = tracks.filter(t => !subset || (subset === 'files' ? t.file : !t.file));
  const members = key ? list.filter(t=>albumKey(t)===key) : list;
  openPage('library',key && members[0] ? members[0].album : subset === 'files' ? '本机音乐' : '资料库');
  if (!key) {
    const albums = [...new Map(list.map(t=>[albumKey(t),t])).values()];
    const rail = element('div','','scroller');
    for (const t of albums) {
      const b=element('button','','album'), img=element('img');img.src=cover(t);img.alt='';
      b.append(img,element('span',t.album));b.onclick=()=>showLibrary(albumKey(t),subset);rail.append(b);
    }
    content.append(rail);
  }
  for(const t of members) {
    const song=songRow(t,members);
    if(!t.file){content.append(song);continue;}
    const line=element('div','','library-track'), remove=element('button','移除','remove-track');
    remove.setAttribute('aria-label','从资料库移除「'+t.title+'」');
    remove.onclick=async()=>{
      if(!window.confirm('从资料库移除「'+t.title+'」？设备中的原始文件会保留。'))return;
      remove.disabled=true;
      try {
        await removeImport(t);
        if(!remove.isConnected || !sheet.open)return;
        const previousScroll=sheet.scrollTop;
        const rowIndex=[...content.querySelectorAll('.remove-track')].indexOf(remove);
        const nextKey=key && tracks.some(entry=>albumKey(entry)===key) ? key : '';
        showLibrary(nextKey,subset);
        const note=element('p','已从资料库移除「'+t.title+'」。','hint');
        note.setAttribute('role','status');content.prepend(note);
        const remaining=content.querySelectorAll('.library-track .row');
        (remaining[Math.min(rowIndex,remaining.length-1)]||get('close')).focus({preventScroll:true});
        sheet.scrollTop=previousScroll;
      }catch {
        const note=element('p','未能移除，请再试一次。','hint');
        note.setAttribute('role','alert');line.after(note);remove.disabled=false;
      }
    };
    line.append(song,remove);content.append(line);
  }
  if (!members.length) content.append(row('导入音频文件','将音乐保存在此设备',()=>get('files').click()));
}
function songRow(track, order) {
  return row(track.title,track.artist+' · '+track.album,()=>{
    select(track,order); sheet.close(); play();
  },cover(track));
}
function showSearch(initial='') {
  openPage('search','搜索资料库');
  const field=element('input','','search');field.type='search';field.placeholder='歌曲、音乐人或专辑';field.setAttribute('aria-label','搜索资料库');field.value=initial;
  const results=element('div');content.append(field,results);
  function render() {
    const term=field.value.trim().toLocaleLowerCase();
    const matches=tracks.filter(t=>[t.title,t.artist,t.album].some(s=>s.toLocaleLowerCase().includes(term)));
    results.replaceChildren(...matches.map(t=>songRow(t,matches)));
    if (!matches.length)results.append(element('p','没有找到匹配的歌曲。','hint'));
  }
  field.oninput=render;render();if(!initial)field.focus();
}
function openDB() {
  return new Promise((resolve,reject)=>{
    const req=indexedDB.open('record-room-library',1);
    req.onupgradeneeded=()=>req.result.createObjectStore('songs',{keyPath:'id'});
    req.onsuccess=()=>resolve(req.result);req.onerror=()=>reject(req.error);
  });
}
async function readImports() {
  database=await openDB();
  return new Promise((resolve,reject)=>{
    const r=database.transaction('songs').objectStore('songs').getAll();
    r.onsuccess=()=>resolve(r.result.map(track=>{
      // WebKit private and temporary document pickers may only persist structured bytes.
      if(track.audioBytes){track.file=new Blob([track.audioBytes],{type:track.audioType});delete track.audioBytes;}
      if(track.pictureBytes){track.picture=new Blob([track.pictureBytes],{type:track.pictureType});delete track.pictureBytes;}
      return track;
    }));r.onerror=()=>reject(r.error);
  });
}
function writeTrack(track) {
  return new Promise((resolve,reject)=>{
    const tx=database.transaction('songs','readwrite');
    tx.objectStore('songs').put(track);tx.oncomplete=resolve;
    tx.onabort=()=>reject(tx.error || new Error('存储空间不足'));
  });
}
async function saveTrack(track) {
  try { await writeTrack(track); }
  catch(error) {
    if(error?.name==='QuotaExceededError')throw error;
    // Safari can grant a picker File that is readable but disallows direct IDB Blob storage.
    // A structured clone of the bytes does not retain the picker's temporary file handle.
    const {file,picture,...metadata}=track;
    metadata.audioType=file.type;metadata.audioBytes=await file.arrayBuffer();
    if(picture){metadata.pictureType=picture.type;metadata.pictureBytes=await picture.arrayBuffer();}
    await writeTrack(metadata);
  }
}
function tagsFor(file) {
  return new Promise(resolve=>jsmediatags.read(file,{onSuccess:tag=>resolve(tag.tags),onError:()=>resolve({})}));
}
function probe(file) {
  return new Promise((resolve,reject)=>{
    const el=new Audio(),url=URL.createObjectURL(file);
    const finish=(value)=>{clearTimeout(timer);el.onloadedmetadata=el.onerror=null;el.removeAttribute('src');el.load();URL.revokeObjectURL(url);value>0?resolve(value):reject(new Error('此浏览器不能播放此文件'));};
    const timer=setTimeout(()=>finish(0),18000);el.preload='metadata';el.onloadedmetadata=()=>finish(Number.isFinite(el.duration)?el.duration:0);el.onerror=()=>finish(0);el.src=url;
  });
}
const legacyDigestLimit=32*1024*1024;
const chunkSize=2*1024*1024;
const hex=bytes=>Array.from(new Uint8Array(bytes),b=>b.toString(16).padStart(2,'0')).join('');
async function contentId(file) {
  // Keep small-file IDs compatible with libraries created before 0.8.3.
  if(file.size<=legacyDigestLimit)return hex(await crypto.subtle.digest('SHA-256',await file.arrayBuffer()));
  // Hash every byte, not just a sample. Only one audio chunk is needed in memory.
  const leaves=new Uint8Array(32*Math.ceil(file.size/chunkSize));
  for(let offset=0,index=0;offset<file.size;offset+=chunkSize,index+=32){
    const chunk=await file.slice(offset,offset+chunkSize).arrayBuffer();
    leaves.set(new Uint8Array(await crypto.subtle.digest('SHA-256',chunk)),index);
  }
  return 'tree-sha256-v1:'+hex(await crypto.subtle.digest('SHA-256',leaves));
}
const legacyIds=new WeakMap();
async function alreadyImported(file,id) {
  if(tracks.some(t=>t.id===id))return true;
  if(file.size<=legacyDigestLimit)return false;
  // 0.8.2 used UUIDs for large files. Keep the original record and resume ID;
  // compare only legacy candidates of the same size when an import needs it.
  for(const t of tracks){
    if(t.file?.size!==file.size || t.id.startsWith('tree-sha256-v1:'))continue;
    let old=legacyIds.get(t);
    if(!old){
      try{old=await contentId(t.file);legacyIds.set(t,old);}
      catch(error){
        // A pre-0.8.3 external reference may have expired. It cannot be
        // compared; allow importing a usable copy and keep removal explicit.
        if(['NotReadableError','NotFoundError'].includes(error?.name))continue;
        throw error;
      }
    }
    if(old===id && tracks.includes(t))return true;
  }
  return false;
}
async function removeImport(track) {
  if(!track.file || !tracks.includes(track))return;
  await new Promise((resolve,reject)=>{
    const tx=database.transaction('songs','readwrite');
    tx.objectStore('songs').delete(track.id);tx.oncomplete=resolve;
    tx.onabort=()=>reject(tx.error || new Error('未能移除'));
  });
  const i=sequence.indexOf(track), globalQueue=sequence===tracks;
  const index=tracks.indexOf(track);
  if(index<0)return;
  tracks.splice(index,1);
  if(!globalQueue)sequence=sequence.filter(t=>t!==track);
  if(current===track) {
    const continuing=!audio.paused && sequence.length>0;
    const next=sequence[i]||sequence[0]||tracks[0];
    if(next){
      select(next,sequence.length?sequence:tracks);
      if(continuing)play();
    }else{
      current=null;sequence=tracks;audio.pause();audio.removeAttribute('src');audio.load();
      if(sourceURL)URL.revokeObjectURL(sourceURL);sourceURL=null;
      pendingSeek=0;scrub=false;lastSecond=-1;saved.set('track','');saved.set('position',0);
      get('title').textContent='唱片机';get('artist').textContent='选择歌曲';
      get('jacket').src=get('label').src=fallbackArt;get('message').textContent='';
      if('mediaSession' in navigator)navigator.mediaSession.metadata=null;
      updateTime();playing();
    }
  }
  if(track.cover?.startsWith('blob:'))URL.revokeObjectURL(track.cover);
  updateControls();
}
async function importFiles(files) {
  if(importing)return;
  const status=element('p','','hint');status.setAttribute('role','status');content.prepend(status);
  const errors=[];let count=0,duplicates=0;importing=true;
  try{
    for(const file of files) {
      status.textContent='正在导入 '+file.name;
      try{
        if(!database)throw new Error('浏览器存储不可用');
        const id=await contentId(file);
        if(await alreadyImported(file,id)){duplicates++;continue;}
        const duration=await probe(file);
        const tags=await tagsFor(file), picture=tags.picture;
        const track={id,file,title:tags.title||file.name.replace(/\.[^.]+$/,''),artist:tags.artist||'未知音乐人',album:tags.album||'未分类专辑',albumArtist:tags.TPE2?.data||tags.aART?.data||tags.artist||'未知音乐人',duration};
        if(picture?.data?.length && picture.data.length<=10*1024*1024 && /^image\/(jpeg|jpg|png|webp|gif)$/i.test(picture.format))track.picture=new Blob([new Uint8Array(picture.data)],{type:picture.format});
        // A Chromium picker File stored in IndexedDB may still depend on the
        // external file. Materialize an independent browser-owned Blob so editing
        // or removing the original cannot break an already imported track.
        track.file=await new Response(file.stream(),{headers:{'Content-Type':file.type}}).blob();
        await saveTrack(track);tracks.push(track);count++;
        if(!current)select(track);else updateControls();
      }catch(error){errors.push(file.name+' · '+error.message);}
    }
    if(sheet.open && ['sources','library'].includes(page)){showLibrary('','files');content.prepend(status);}
    status.textContent=[count ? '已导入 '+count+' 首' : '未添加新歌曲',...(duplicates?['已跳过 '+duplicates+' 首重复歌曲']:[]),...errors].join('。');
  }finally{importing=false;get('files').value='';}
}
function time(value){const n=Math.max(0,Math.floor(value||0));return Math.floor(n/60)+':'+String(n%60).padStart(2,'0');}
function updateTime(){
  const total=Number.isFinite(audio.duration)?audio.duration:current?.duration||0;
  const elapsed=pendingSeek||audio.currentTime;
  get('elapsed').textContent=time(elapsed);get('duration').textContent=time(total);
  if(!scrub){get('seek').max=String(total||1);get('seek').value=String(elapsed||0);}
  const sec=Math.floor(elapsed);
  if(sec%3===0&&sec!==lastSecond&&current){saved.set('position',elapsed);lastSecond=sec;}
}
function palette(img) {
  if(!img.naturalWidth)return;
  const c=document.createElement('canvas');c.width=c.height=12;const ctx=c.getContext('2d',{willReadFrequently:true});ctx.drawImage(img,0,0,12,12);
  const pixels=ctx.getImageData(0,0,12,12).data;let rgb=[0,0,0],total=0;
  for(let i=0;i<pixels.length;i+=4){const v=[pixels[i],pixels[i+1],pixels[i+2]],weight=(Math.max(...v)-Math.min(...v)+12)*pixels[i+3]/255;rgb=rgb.map((x,j)=>x+v[j]*weight);total+=weight;}
  if(!total)return;rgb=rgb.map(x=>x/total);
  const mix=(k,bias)=>'rgb('+rgb.map(v=>Math.min(255,Math.round(v*k+bias))).join(',')+')';
  for(const [name,value] of Object.entries({body:mix(.19,19),panel:mix(.27,33),tint:mix(.67,19),lamp:mix(.82,60)}))document.documentElement.style.setProperty('--'+name,value);
  document.querySelector('meta[name="theme-color"]').content=mix(.19,19);
}
function updateControls(){
  for(const id of ['play','sleeve','artist','seek'])get(id).disabled=!current;
  for(const id of ['prev','next'])get(id).disabled=!current || sequence.length<2;
}
function select(t,order=tracks,position=0) {
  audio.pause();if(sourceURL)URL.revokeObjectURL(sourceURL);sourceURL=null;current=t;sequence=order;
  saved.set('track',t.id);saved.set('position',position);pendingSeek=position;lastSecond=-1;
  if(t.file)audio.src=(sourceURL=URL.createObjectURL(t.file));
  else if(audio.hasAttribute('src')){audio.removeAttribute('src');audio.load();}
  // The catalog supplies duration and artwork. Do not set a demo src before play:
  // some media engines request ranges even when preload is set to none.
  get('title').textContent=t.title;get('artist').textContent=t.artist;
  get('jacket').src=get('label').src=cover(t);get('message').textContent='';
  if('mediaSession' in navigator && typeof MediaMetadata!=='undefined')navigator.mediaSession.metadata=new MediaMetadata({title:t.title,artist:t.artist,album:t.album,artwork:[{src:new URL(cover(t),document.baseURI).href}]});
  updateTime();updateControls();
}
async function play(){
  if(!current)return;
  const track=current;
  playbackRequested=true;
  if(!track.file && (!audio.hasAttribute('src') || audio.error)){
    audio.src=track.url;
    // If first play precedes worker activation, the native media request may
    // bypass it. Ask the worker to retain just this recording once ready.
    if('serviceWorker' in navigator && !navigator.serviceWorker.controller){
      const url=new URL(track.url,document.baseURI).href;
      navigator.serviceWorker.ready.then(r=>r.active?.postMessage({type:'cache-demo',url})).catch(()=>{});
    }
  }
  try{await audio.play();if(track===current)get('message').textContent='';}
  catch(e){if(track===current && e.name!=='AbortError')get('message').textContent=track.file?'未能播放这首歌，请检查设备支持的音频格式。':'未能读取内置歌曲，请联网后再点播放。';}
}
function skip(dir){if(!current||!sequence.length)return;const i=sequence.findIndex(t=>t.id===current.id);select(sequence[(i+dir+sequence.length)%sequence.length],sequence);play();}
function playing(){const on=!audio.paused;document.body.dataset.playing=String(on);get('play').setAttribute('aria-label',on?'暂停':'播放');get('play').lastElementChild.textContent=on?'PAUSE':'PLAY';if('mediaSession' in navigator)navigator.mediaSession.playbackState=on?'playing':'paused';}
function jump(to){
  if(!Number.isFinite(to))return;
  if(Number.isFinite(audio.duration))audio.currentTime=Math.max(0,Math.min(audio.duration,to));
  else if(current)pendingSeek=Math.max(0,Math.min(current.duration||0,to));
}
get('tools').onclick=showTools;get('close').onclick=()=>sheet.close();sheet.onclick=e=>{if(e.target===sheet && (e.clientX<sheet.offsetLeft||e.clientY<sheet.offsetTop))sheet.close();};
get('sleeve').onclick=()=>current&&showLibrary(albumKey(current));get('artist').onclick=()=>showSearch(current?.artist||'');
get('files').onchange=e=>importFiles(Array.from(e.target.files));
get('play').onclick=()=>audio.paused || audio.error?play():audio.pause();get('prev').onclick=()=>skip(-1);get('next').onclick=()=>skip(1);
get('seek').oninput=e=>{scrub=true;get('elapsed').textContent=time(Number(e.target.value));};get('seek').onchange=e=>{jump(Number(e.target.value));scrub=false;updateTime();};
get('jacket').onload=()=>palette(get('jacket'));
audio.ontimeupdate=updateTime;audio.onloadedmetadata=()=>{if(pendingSeek>0){jump(pendingSeek);pendingSeek=0;}updateTime();};audio.onplay=playing;audio.onpause=playing;
audio.onended=()=>{const i=sequence.findIndex(t=>t.id===current?.id);if(i!==-1&&i<sequence.length-1)skip(1);else{jump(0);playing();}};
audio.onerror=()=>{if(audio.hasAttribute('src'))get('message').textContent=current?.file?'读取音频失败，请重新选择歌曲。':'未能读取内置歌曲，请联网后再点播放。';};
window.addEventListener('pagehide',()=>{if(current)saved.set('position',pendingSeek||audio.currentTime);});
if('mediaSession' in navigator)for(const [name,act] of Object.entries({play,pause:()=>audio.pause(),nexttrack:()=>skip(1),previoustrack:()=>skip(-1),seekto:e=>jump(e.seekTime)})){try{navigator.mediaSession.setActionHandler(name,act);}catch{}}
if('serviceWorker' in navigator){
  navigator.serviceWorker.register('./sw.js').then(()=>navigator.serviceWorker.ready).then(()=>{readyOffline=true;if(get('offline'))get('offline').textContent=offlineHint;}).catch(()=>{});}
async function loadDemos(preferred, priorPosition) {
  get('retry').hidden=true;
  if(!current)get('message').textContent='正在准备唱片…';
  const prior=current;
  try{
    const result=await fetch('assets/catalog.json');if(!result.ok)throw new Error('catalog');
    const catalog=await result.json();
    const wanted=catalog.find(t=>t.id===preferred)?.id || catalog[0]?.id;
    for(const [index,t] of catalog.entries()){
      if(!tracks.some(entry=>entry.id===t.id))tracks.push({...t,demoIndex:index,url:'assets/'+t.uri.split('/').pop(),cover:'assets/'+t.artworkUri.split('/').pop(),duration:t.durationMs/1000});
    }
    const selected=tracks.find(t=>t.id===wanted);
    if(selected && (!current || (selected.id===preferred && current===prior && !playbackRequested)))select(selected,tracks,selected.id===preferred?priorPosition:0);
    if(!current && tracks.length)select(tracks[0]);
    if(!catalog.length)throw new Error('empty');
  }catch{
    get('message').textContent=current?'部分内置唱片尚未载入。':'内置唱片未载入。请重新联网，或从播放工具导入音乐。';
    get('retry').hidden=false;
  }
  updateControls();
}
get('retry').onclick=()=>loadDemos(current?.id, audio.currentTime||0);
(async()=>{
  const last=saved.get('track'),position=Number(saved.get('position'))||0;
  const volume=saved.get('volume');if(volume!==null&&Number.isFinite(Number(volume)))audio.volume=Math.min(1,Math.max(0,Number(volume)));
  try {
    const local=await readImports();tracks.push(...local);
    const first=local.find(t=>t.id===last)||local[0];
    if(first)select(first,tracks,first.id===last?position:0);
  }catch{get('message').textContent='本机存储不可用，正在准备内置唱片…';}
  await loadDemos(last,position);
})();
