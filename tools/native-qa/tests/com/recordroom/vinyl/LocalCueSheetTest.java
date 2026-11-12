package com.recordroom.vinyl;

import android.net.Uri;
import androidx.media3.common.MediaItem;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=34)
public class LocalCueSheetTest {
    private static final String PREFIX = "REM GENRE Folk\nREM DATE 2018\nPERFORMER \"群星\"\nTITLE \"整轨专辑\"\nFILE \"Disk A.flac\" WAVE\n";
    private static final String SONGS = "TRACK 01 AUDIO\nTITLE \"春天\"\nPERFORMER \"甲\"\nINDEX 01 00:00:00\n" +
            "TRACK 02 AUDIO\nTITLE \"夏天\"\nPERFORMER \"乙\"\nINDEX 00 00:03:00\nINDEX 01 00:04:15\n";
    private static Track source(String name) {
        return new Track(name,name,"标签作者","标签专辑","本地音乐","content://music/"+name,"content://cover",10000,1);
    }
    @Test public void parseDecodeClipGroupAndRoundTrip() throws Exception {
        String sheet = PREFIX+SONGS;
        for (String encoding : new String[]{"UTF-8","UTF-16","GB18030"}) {
            assertEquals(sheet,LocalCueSheet.decode(sheet.getBytes(Charset.forName(encoding))));
        }
        Track master=source("disc-a"),second=source("disc-b");
        Map<String,List<Track>> byName = new HashMap<>();
        byName.put("disk a.flac",Collections.singletonList(master));
        byName.put("disk b.wav",Collections.singletonList(second));
        String multi=sheet+"FILE \"Music\\Disk B.wav\" WAVE\nTRACK 03 AUDIO\nTITLE 秋天\nINDEX 01 00:00:05";
        ArrayList<Track> sliced=LocalCueImport.expand(LocalCueSheet.parse(multi),byName,"content://cue/disc.cue");
        assertEquals(3,sliced.size());
        Track first=sliced.get(0), middle=sliced.get(1),last=sliced.get(2);
        assertEquals(4200,first.durationMs);assertEquals(5800,middle.durationMs);
        assertEquals(4200,middle.clipStartMs);assertEquals(-1,middle.clipEndMs);
        assertEquals(66,last.clipStartMs);assertEquals(second.uri,last.uri);
        assertEquals("夏天",middle.title);assertEquals("乙",middle.artist);assertEquals("群星",middle.albumArtist);
        assertEquals("整轨专辑",middle.album);assertEquals("Folk",middle.genre);assertEquals("2018",middle.year);
        assertEquals(first.albumKey(),middle.albumKey());assertNotEquals(first.id,middle.id);
        assertEquals(master.artworkUri,middle.artworkUri);
        assertEquals(middle.toJson().toString(),Track.fromJson(middle.toJson()).toJson().toString());
        MediaItem.ClippingConfiguration clip = LocalCueImport.mediaItem(first,Uri.parse(first.uri)).clippingConfiguration;
        assertEquals(0,clip.startPositionMs);assertEquals(4200,clip.endPositionMs);
        assertEquals(MediaItem.ClippingConfiguration.UNSET,LocalCueImport.mediaItem(Track.fromJson(master.toJson()),Uri.parse(master.uri)).clippingConfiguration);
        LocalCueImport.Result batch=new LocalCueImport.Result();batch.tracks.addAll(sliced);batch.indexedFiles.add(master.uri);batch.indexedFiles.add(second.uri);
        assertEquals(sliced,batch.merge(Arrays.asList(master,second)));
        assertEquals(sliced,batch.merge(sliced));
        // Editing the same CUE can remove an entire FILE block, including its previously indexed audio.
        LocalCueImport.Result revised=new LocalCueImport.Result();
        revised.indexedFiles.add(master.uri);revised.importedSheets.add(first.cueDocumentUri);
        revised.tracks.add(first);
        assertEquals(Collections.singletonList(first),revised.merge(sliced));
    }
    @Test public void selectedSheetAndAudioImportAsPlayableSegmentsAndPersist() throws Exception {
        android.content.Context app=org.robolectric.RuntimeEnvironment.getApplication();
        java.io.File folder=new java.io.File(app.getCacheDir(),"cue-import");
        assertTrue(folder.mkdirs() || folder.isDirectory());
        java.io.File audio=new java.io.File(folder,"Disk A.wav");
        try(java.io.InputStream src=app.getAssets().open("demo/night-1.wav")){
            java.nio.file.Files.copy(src,audio.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        // Robolectric's platform metadata retriever requires an explicit source fixture.
        org.robolectric.shadows.util.DataSource metadata=org.robolectric.shadows.util.DataSource.toDataSource(app,Uri.fromFile(audio));
        org.robolectric.shadows.ShadowMediaMetadataRetriever.addMetadata(metadata,android.media.MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO,"yes");
        org.robolectric.shadows.ShadowMediaMetadataRetriever.addMetadata(metadata,android.media.MediaMetadataRetriever.METADATA_KEY_DURATION,"18000");
        java.io.File index=new java.io.File(folder,"整张专辑.cue");
        java.nio.file.Files.write(index.toPath(),(PREFIX.replace("flac","wav")+SONGS).getBytes("UTF-8"));
        LocalCueImport.Result imported=LocalCueImport.read(app,Arrays.asList(Uri.fromFile(index),Uri.fromFile(audio)));
        assertEquals(imported.summary(),0,imported.failures);
        assertEquals(2,imported.tracks.size());
        assertEquals(Uri.fromFile(audio).toString(),imported.tracks.get(1).uri);
        LocalMusicImporter.saveLibrary(app,imported.merge(Collections.emptyList()));
        List<Track> restored=LocalMusicImporter.loadLibrary(app);
        assertEquals(2,restored.size());assertEquals(4200,restored.get(1).clipStartMs);
        LibraryStore library=new LibraryStore(app);
        LibraryStore.Playlist playlist=library.create("CUE keep",restored);
        java.nio.file.Files.write(index.toPath(),(PREFIX.replace("flac","wav")+"TRACK 01 AUDIO\nTITLE 修订版\nINDEX 01 00:00:00\n").getBytes("UTF-8"));
        LocalCueImport.Result revised=LocalCueImport.read(app,Arrays.asList(Uri.fromFile(index),Uri.fromFile(audio)));
        Track unrelated=source("other");
        ArrayList<Track> before=new ArrayList<>(restored);before.add(unrelated);
        ArrayList<Track> merged=revised.merge(before);
        assertEquals(2,merged.size());assertSame(unrelated,merged.get(0));
        assertEquals("修订版",merged.get(1).title);assertEquals(restored.get(0).id,merged.get(1).id);
        LocalMusicImporter.saveLibrary(app,merged);
        LibraryStore.Playlist preserved=library.playlists().stream().filter(item->playlist.id.equals(item.id)).findFirst().get();
        assertEquals(2,preserved.tracks.size());assertEquals(restored.get(0).id,preserved.tracks.get(0).id);
        assertEquals(restored.get(1).clipStartMs,preserved.tracks.get(1).clipStartMs);
    }

    @Test public void rejectsMalformedMissingOrAmbiguousFiles() throws Exception {
        Track src=source("d");
        Map<String,List<Track>> byName=Collections.singletonMap("disk a.flac",Collections.singletonList(src));
        for (String bad : new String[]{SONGS.replace("04:15","00:00"),SONGS.replace("04:15","04:75"),
                SONGS.replace("04:15","20:10"),SONGS.replace("INDEX 01","INDEX 02"),
                SONGS.replace("02 AUDIO","01 AUDIO")}) {
            try {LocalCueImport.expand(LocalCueSheet.parse(PREFIX+bad),byName,"content://cue/disc.cue");fail(bad);}
            catch(IOException expected){ assertNotNull(expected.getMessage()); }
        }
        LocalCueSheet valid = LocalCueSheet.parse(PREFIX+SONGS);
        try { LocalCueImport.expand(valid,Collections.emptyMap(),"content://cue/disc.cue");fail(); } catch(IOException e) { assertTrue(e.getMessage().contains("同时选择")); }
        try { LocalCueImport.expand(valid,Collections.singletonMap("disk a.flac",Arrays.asList(src,src)),"content://cue/disc.cue");fail(); } catch(IOException e) { assertTrue(e.getMessage().contains("同名")); }
    }
}
