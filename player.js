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
let importing = false, scrub = false, lastSecond = -1;
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
  const offline = element('p',readyOffline ? '已准备好离线播放。' : '首次使用时请保持联网，直到离线资源准备完成。','hint');
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
  for(const t of members) content.append(songRow(t,members));
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
    r.onsuccess=()=>resolve(r.result);r.onerror=()=>reject(r.error);
  });
}
function saveTrack(track) {
  return new Promise((resolve,reject)=>{
    const tx=database.transaction('songs','readwrite');
    tx.objectStore('songs').put(track);tx.oncomplete=resolve;
    tx.onabort=()=>reject(tx.error || new Error('存储空间不足'));
  });
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
async function importFiles(files) {
  if(importing)return;
  const status=element('p','','hint');status.setAttribute('role','status');content.prepend(status);
  const errors=[];let count=0;importing=true;
  try{
    for(const file of files) {
      status.textContent='正在导入 '+file.name;
      try{
        if(!database)throw new Error('浏览器存储不可用');
        const duration=await probe(file);
        // Hash ordinary files to deduplicate imports. Large masters stay on disk as Blobs.
        const id=file.size<=32*1024*1024 ? Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256',await file.arrayBuffer())),b=>b.toString(16).padStart(2,'0')).join('') : crypto.randomUUID();
        if(tracks.some(t=>t.id===id))continue;
        const tags=await tagsFor(file), picture=tags.picture;
        const track={id,file,title:tags.title||file.name.replace(/\.[^.]+$/,''),artist:tags.artist||'未知音乐人',album:tags.album||'未分类专辑',albumArtist:tags.TPE2?.data||tags.aART?.data||tags.artist||'未知音乐人',duration};
        if(picture?.data?.length && picture.data.length<=10*1024*1024 && /^image\/(jpeg|jpg|png|webp|gif)$/i.test(picture.format))track.picture=new Blob([new Uint8Array(picture.data)],{type:picture.format});
        await saveTrack(track);tracks.push(track);count++;
      }catch(error){errors.push(file.name+' · '+error.message);}
    }
    if(sheet.open && ['sources','library'].includes(page)){showLibrary('','files');content.prepend(status);}
    status.textContent=[count ? '已导入 '+count+' 首' : '未添加新歌曲',...errors].join('。');
  }finally{importing=false;get('files').value='';}
}
function time(value){const n=Math.max(0,Math.floor(value||0));return Math.floor(n/60)+':'+String(n%60).padStart(2,'0');}
function updateTime(){
  const total=Number.isFinite(audio.duration)?audio.duration:current?.duration||0;
  get('elapsed').textContent=time(audio.currentTime);get('duration').textContent=time(total);
  if(!scrub){get('seek').max=String(total||1);get('seek').value=String(audio.currentTime||0);}
  const sec=Math.floor(audio.currentTime);
  if(sec%3===0&&sec!==lastSecond&&current){saved.set('position',audio.currentTime);lastSecond=sec;}
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
function select(t,order=tracks,position=0) {
  audio.pause();if(sourceURL)URL.revokeObjectURL(sourceURL);sourceURL=null;current=t;sequence=order;
  saved.set('track',t.id);saved.set('position',position);pendingSeek=position;lastSecond=-1;
  audio.src=t.file || t.demoBlob ? (sourceURL=URL.createObjectURL(t.file || t.demoBlob)) : t.url;
  get('title').textContent=t.title;get('artist').textContent=t.artist;
  get('jacket').src=get('label').src=cover(t);get('message').textContent='';
  if('mediaSession' in navigator && typeof MediaMetadata!=='undefined')navigator.mediaSession.metadata=new MediaMetadata({title:t.title,artist:t.artist,album:t.album,artwork:[{src:new URL(cover(t),document.baseURI).href}]});
  updateTime();
}
async function play(){if(!current)return;try{await audio.play();get('message').textContent='';}catch(e){if(e.name!=='AbortError')get('message').textContent='未能播放这首歌，请检查设备支持的音频格式。';}}
function skip(dir){if(!current||!sequence.length)return;const i=sequence.findIndex(t=>t.id===current.id);select(sequence[(i+dir+sequence.length)%sequence.length],sequence);play();}
function playing(){const on=!audio.paused;document.body.dataset.playing=String(on);get('play').setAttribute('aria-label',on?'暂停':'播放');get('play').lastElementChild.textContent=on?'PAUSE':'PLAY';if('mediaSession' in navigator)navigator.mediaSession.playbackState=on?'playing':'paused';}
function jump(to){if(Number.isFinite(audio.duration))audio.currentTime=Math.max(0,Math.min(audio.duration,to));}
get('tools').onclick=showTools;get('close').onclick=()=>sheet.close();sheet.onclick=e=>{if(e.target===sheet && (e.clientX<sheet.offsetLeft||e.clientY<sheet.offsetTop))sheet.close();};
get('sleeve').onclick=()=>current&&showLibrary(albumKey(current));get('artist').onclick=()=>showSearch(current?.artist||'');
get('files').onchange=e=>importFiles(Array.from(e.target.files));
get('play').onclick=()=>audio.paused?play():audio.pause();get('prev').onclick=()=>skip(-1);get('next').onclick=()=>skip(1);
get('seek').oninput=e=>{scrub=true;get('elapsed').textContent=time(Number(e.target.value));};get('seek').onchange=e=>{jump(Number(e.target.value));scrub=false;updateTime();};
get('jacket').onload=()=>palette(get('jacket'));
audio.ontimeupdate=updateTime;audio.onloadedmetadata=()=>{if(pendingSeek>0){jump(pendingSeek);pendingSeek=0;}updateTime();};audio.onplay=playing;audio.onpause=playing;
audio.onended=()=>{const i=sequence.findIndex(t=>t.id===current?.id);if(i!==-1&&i<sequence.length-1)skip(1);else{jump(0);playing();}};
audio.onerror=()=>{get('message').textContent='读取音频失败，请重新选择歌曲或联网完成首次加载。';};
window.addEventListener('pagehide',()=>{if(current)saved.set('position',audio.currentTime);});
if('mediaSession' in navigator)for(const [name,act] of Object.entries({play,pause:()=>audio.pause(),nexttrack:()=>skip(1),previoustrack:()=>skip(-1),seekto:e=>jump(e.seekTime)})){try{navigator.mediaSession.setActionHandler(name,act);}catch{}}
if('serviceWorker' in navigator){
  navigator.serviceWorker.register('./sw.js').then(()=>navigator.serviceWorker.ready).then(()=>{readyOffline=true;if(get('offline'))get('offline').textContent='已准备好离线播放。';}).catch(()=>{});}
(async()=>{
  let local=[];try{local=await readImports();}catch{get('message').textContent='本机存储不可用，导入功能暂不可用。';}
  tracks.push(...local);
  try{
    const result=await fetch('assets/catalog.json');if(!result.ok)throw new Error('catalog');
    // Each bundled demo is a short 18 s WAV. Stable local Blob URLs avoid switching
    // from network to worker byte ranges in the middle of the first play gesture.
    const demos=await Promise.all((await result.json()).map(async t=>{
      const url='assets/'+t.uri.split('/').pop();
      const response=await fetch(url);
      if(!response.ok)throw new Error('audio');
      return {...t,url,demoBlob:await response.blob(),cover:'assets/'+t.artworkUri.split('/').pop(),duration:t.durationMs/1000};
    }));
    tracks.push(...demos);
  }catch{if(!tracks.length)get('message').textContent='请联网打开一次以装入内置唱片。';}
  const last=saved.get('track');
  const track=tracks.find(t=>t.id===last)||tracks[0];
  const volume=saved.get('volume');if(volume!==null&&Number.isFinite(Number(volume)))audio.volume=Math.min(1,Math.max(0,Number(volume)));
  if(track)select(track,tracks,track.id===last?Number(saved.get('position'))||0:0);
})();
