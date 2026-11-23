// Run against a local build served over HTTP. Install Playwright and its WebKit
// browser in the test environment, then: node web-player/tests/library.cjs URL
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const {webkit, chromium} = require('playwright');

async function main() {
  const base = process.argv[2];
  if (!base) throw new Error('Pass the local player URL');
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'record-room-import-'));
  const browser = await (process.env.TEST_BROWSER === 'chromium' ? chromium : webkit).launch();
  try {
    const file = path.join(dir, 'Hi Res.wav');
    if (process.argv[3]) fs.copyFileSync(process.argv[3], file);
    else {
      const dataSize = 96000 * 6 * 60;
      const header = Buffer.alloc(44);
      header.write('RIFF');header.writeUInt32LE(dataSize + 36, 4);
      header.write('WAVEfmt ', 8);header.writeUInt32LE(16, 16);
      header.writeUInt16LE(1, 20);header.writeUInt16LE(2, 22);header.writeUInt32LE(96000, 24);
      header.writeUInt32LE(96000 * 6, 28);header.writeUInt16LE(6, 32);header.writeUInt16LE(24, 34);
      header.write('data', 36);header.writeUInt32LE(dataSize, 40);
      fs.writeFileSync(file, header);fs.truncateSync(file, dataSize + 44);
    }
    assert(fs.statSync(file).size > 32 * 1024 * 1024);
    const renamed = path.join(dir, 'Renamed.wav');
    fs.copyFileSync(file, renamed);
    const page = await browser.newPage();
    const errors=[];
    page.on('pageerror', e => errors.push(e.message));
    await page.goto(base);
    const openFiles = async () => {
      await page.locator('#tools').click();
      await page.getByRole('button', {name:'音乐来源'}).click();
      await page.getByRole('button', {name:/本机音乐/}).click();
    };
    const upload = async (name, status) => {
      await page.setInputFiles('#files', name);
      await page.locator('#content [role=status]').filter({hasText:status}).waitFor();
    };
    await openFiles();
    await upload(file, '已导入 1 首');
    await upload(renamed, '已跳过 1 首重复歌曲');
    assert.equal(await page.locator('.library-track').count(), 1);
    // Simulate a pre-0.8.3 indexedDB record without reselecting imported files.
    const legacy = await page.evaluate(async () => {
      const db = await new Promise((resolve,reject)=>{
        const req=indexedDB.open('record-room-library',1);req.onsuccess=()=>resolve(req.result);req.onerror=()=>reject(req.error);
      });
      const oldId=crypto.randomUUID();
      await new Promise((resolve,reject)=>{
        const tx=db.transaction('songs','readwrite'), store=tx.objectStore('songs');
        const q=store.openCursor();
        q.onsuccess=()=>{
          const c=q.result, data=c.value;
          store.delete(data.id);data.id=oldId;store.add(data);
        };
        tx.oncomplete=resolve;tx.onabort=()=>reject(tx.error);
      });
      db.close();return oldId;
    });
    await page.reload();await openFiles();
    await upload(renamed, '已跳过 1 首重复歌曲');
    assert.equal(await page.locator('.library-track').count(), 1);
    // Same file name, length and edge bytes, but different music in the middle.
    const handle=fs.openSync(file,'r+');
    try {const pos=12*1024*1024, byte=Buffer.alloc(1);fs.readSync(handle,byte,0,1,pos);byte[0]^=127;fs.writeSync(handle,byte,0,1,pos);}finally{fs.closeSync(handle);}
    await upload(file, '已导入 1 首');
    assert.equal(await page.locator('.library-track').count(), 2);
    const first=page.locator('.library-track').first();
    await first.locator('.row').click();
    await page.waitForFunction(()=>!document.getElementById('audio').paused);
    assert.equal(await page.evaluate(()=>localStorage.getItem('recordroom.track')),legacy);
    await openFiles();
    page.once('dialog',dialog=>dialog.dismiss());
    await first.locator('.remove-track').click();
    assert.equal(await page.locator('.library-track').count(), 2);
    page.once('dialog',dialog=>dialog.accept());
    await first.locator('.remove-track').click();
    await page.waitForFunction(()=>document.querySelectorAll('.library-track').length===1);
    await page.waitForFunction(old=>localStorage.getItem('recordroom.track')!==old && !document.getElementById('audio').paused,legacy);
    await page.reload();await openFiles();
    assert.equal(await page.locator('.library-track').count(),1);
    page.once('dialog',dialog=>dialog.accept());
    await page.locator('.remove-track').click();
    await page.getByText(/已从资料库移除/).waitFor();
    await page.reload();await openFiles();
    assert.equal(await page.locator('.library-track').count(),0);
    assert.deepEqual(errors,[]);
    assert(fs.statSync(file).size>32*1024*1024);
    // No demos available: removing the only local song must release the audio
    // element, transport state and saved resume ID, not try to play another source.
    const isolated=await browser.newContext({serviceWorkers:'block'});
    try {
      const empty=await isolated.newPage();
      await empty.route('**/assets/catalog.json',route=>route.abort());
      await empty.goto(base);
      await empty.locator('#tools').click();
      await empty.getByRole('button',{name:'音乐来源'}).click();
      await empty.setInputFiles('#files',file);
      await empty.getByText('已导入 1 首',{exact:true}).waitFor();
      empty.once('dialog',dialog=>dialog.accept());
      await empty.locator('.remove-track').click();
      await empty.getByText(/已从资料库移除/).waitFor();
      assert.equal(await empty.locator('#play').isDisabled(),true);
      assert.equal(await empty.locator('#audio').getAttribute('src'),null);
      assert.equal(await empty.evaluate(()=>localStorage.getItem('recordroom.track')),'');
    }finally{await isolated.close();}
    console.log('PASS: large WAV, renamed duplicate, legacy library, same-name different data, cancel, delete during playback, next song, persisted removal.');
  } finally {
    await browser.close();fs.rmSync(dir,{recursive:true,force:true});
  }
}
main().catch(error=>{console.error(error);process.exitCode=1;});
