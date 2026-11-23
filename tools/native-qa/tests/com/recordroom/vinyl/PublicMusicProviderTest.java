package com.recordroom.vinyl;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=34)
public class PublicMusicProviderTest {
    private static JSONObject entry(String id, int number) throws Exception {
        return new JSONObject().put("id",id).put("title","Song " + number)
            .put("artistId","performer").put("artist","Artist").put("albumId","record").put("album","Album")
            .put("url","https://example.org/"+id+".mp3").put("durationMs",60000)
            .put("credit","Artist — recording").put("license","CC BY 4.0").put("licenseUrl","https://creativecommons.org/licenses/by/4.0/")
            .put("sourceUrl","https://example.org/works/"+id).put("trackNumber",number).put("keywords","中文测试");
    }
    private static PublicMusicProvider provider(JSONObject... rows) {
        byte[] contents = new JSONArray(Arrays.asList(rows)).toString().getBytes(StandardCharsets.UTF_8);
        return new PublicMusicProvider(() -> new ByteArrayInputStream(contents));
    }

    @Test public void searchAlbumArtistPlaybackAndCreditWorkWithoutAccount() throws Exception {
        PublicMusicProvider catalog = provider(entry("two",2),entry("one",1));
        assertFalse(catalog.supportsAccount());
        assertEquals(2,catalog.searchTracks("中文  artist",0,20).total);
        MusicProvider.Page<Track> first = catalog.searchTracks("",0,1);
        assertTrue(first.hasMore);
        assertEquals(1,catalog.searchTracks("",first.nextOffset,1).items.size());
        assertEquals("one",catalog.album("record").tracks.get(0).id);
        assertEquals(1,catalog.searchAlbums("album",0,25).total);
        assertEquals(1,catalog.searchArtists("artist",0,25).total);
        assertEquals(2,catalog.artist("performer").topTracks.size());
        Track restored = Track.fromJson(first.items.get(0).toJson());
        assertEquals("publicmusic",OnlineMusic.providerId(restored));
        assertFalse(restored.uri.contains("https"));
        assertEquals("https://example.org/two.mp3",catalog.resolve(restored).url);
        assertTrue(catalog.resolve(restored).headers.isEmpty());
        assertEquals("CC BY 4.0",catalog.entry(restored.id).license);
        assertEquals(0, OnlineMusic.sessionGeneration(RuntimeEnvironment.getApplication(),"publicmusic"));
    }

    @Test public void cachedPublicTracksSurviveLibraryAndQueueRestores() throws Exception {
        android.content.Context context=RuntimeEnvironment.getApplication();
        Track t=provider(entry("one",1)).searchTracks("",0,1).items.get(0);
        OnlineCatalog.stage(context,PublicMusicProvider.ID,"",Arrays.asList(t));
        OnlineCatalog.persist(context,PublicMusicProvider.ID);
        assertEquals(t.id,OnlineCatalog.load(context,PublicMusicProvider.ID).get(0).id);
        PlayerStateStore state=PlayerStateStore.get(context);
        state.save(state.openSession(),state.copyQueue(Arrays.asList(t)),0,1042,false,0,1f);
        assertEquals(PublicMusicProvider.ID,state.restore().queue.tracks.get(0).provider);
    }

    @Test public void shippedSelectionsHaveAttributionAndResolvableRecordings() throws Exception {
        java.nio.file.Path path = java.nio.file.Paths.get(System.getProperty("vinyl.project.dir"),
                "app/src/main/assets/public-music/catalog.json");
        PublicMusicProvider catalog = new PublicMusicProvider(() -> java.nio.file.Files.newInputStream(path));
        MusicProvider.Page<Track> selected = catalog.searchTracks("",0,Integer.MAX_VALUE);
        java.util.Set<String> artists = new java.util.HashSet<>();
        java.util.Set<String> albums = new java.util.HashSet<>();
        for (Track t : selected.items) {
            artists.add(t.artistId);
            albums.add(t.albumId);
            assertTrue(catalog.album(t.albumId).tracks.stream().anyMatch(other -> other.id.equals(t.id)));
            assertTrue(catalog.artist(t.artistId).topTracks.stream().anyMatch(other -> other.id.equals(t.id)));
            assertTrue(catalog.entry(t.id).credit.length() > 0);
            assertTrue(catalog.resolve(Track.fromJson(t.toJson())).durationMs > 0);
            if(t.artworkUri.startsWith("asset:///"))
                assertTrue(java.nio.file.Files.isRegularFile(path.getParent().getParent().resolve(t.artworkUri.substring(9))));
        }
        assertTrue("Offer more than one artist", artists.size() >= 2);
        assertTrue("Offer more than one collection", albums.size() >= 2);
    }

    @Test public void invalidSourceLicenseOrUnknownIdCannotBecomePlayable() throws Exception {
        JSONObject standard=entry("one",1);
        for (JSONObject invalid : Arrays.asList(entry("bad",2).put("sourceUrl","file://local"),
                entry("bad",2).put("url","http://example.org/music.mp3"),entry("bad",2).put("license",""),
                entry("bad",2).put("durationMs",0))) {
            try { provider(standard,invalid).entry("one");fail(); }
            catch (ProviderException expected) { assertEquals(ProviderException.Kind.INVALID_RESPONSE,expected.kind); }
        }
        try { provider(standard).entry("missing");fail(); }
        catch (ProviderException expected) { assertEquals(ProviderException.Kind.UNAVAILABLE,expected.kind); }
    }
}
