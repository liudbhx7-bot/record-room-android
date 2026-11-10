package com.recordroom.vinyl;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import static com.recordroom.vinyl.AppleMusicIntegrationTest.*;

/** Official REST schema fixtures, not live account/DRM evidence. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=34)
public class AppleMusicCatalogTest {
    static AppleMusicSessionStore authorized()throws Exception{AppleMusicSessionStore s=store(prefs());s.importDeveloperToken(jwt());authorize(s);return s;}
    static JSONObject collection(JSONObject... rows){JSONArray a=new JSONArray();for(JSONObject row:rows)a.put(row);return json("data",a);}
    static JSONObject next(JSONObject row,String path){return json("data",new JSONArray().put(row),"next",path);}
    static JSONObject relation(String type,String id){return collection(json("type",type,"id",id));}
    static JSONObject album(String id){return json("id",id,"type","albums","attributes",json("name","目录专辑 "+id,"artistName","目录艺人","trackCount",2),"relationships",json("artists",relation("artists","9")));}
    static JSONObject song(String id){return json("id",id,"type","songs","attributes",json("name","目录歌曲 "+id,"artistName","目录艺人","durationInMillis",180000));}
    static AppleMusicHttp.Response ok(JSONObject body){return new AppleMusicHttp.Response(200,body);}

    @Test public void artistUsesOfficialEditorialTopSongsAndContinuesServerAlbumPagination()throws Exception{
        List<String> paths=new ArrayList<>();Http http=new Http((p,h)->{paths.add(p);assertFalse(h.containsKey("Music-User-Token"));
            if(p.equals("/v1/catalog/cn/artists/9"))return ok(collection(json("id","9","type","artists","attributes",json("name","目录艺人","editorialNotes",json("standard","<p>官方<b>简介</b> &amp; 乐历</p>"),"artwork",json("url","https://is1-ssl.mzstatic.com/image/{w}x{h}.jpg")))));
            if(p.contains("/view/top-songs"))return ok(collection(song("11")));
            if(p.endsWith("offset=40"))return ok(collection(album("102")));
            return ok(next(album("101"),"/v1/catalog/cn/artists/9/albums?offset=40&limit=25"));
        });
        AppleMusicProvider provider=new AppleMusicProvider(authorized(),http);MusicProvider.Artist artist=provider.artist("9");
        assertEquals("目录艺人",artist.name);assertEquals("官方简介 & 乐历",artist.description);assertTrue(artist.avatarUrl.contains("800x800"));assertEquals("11",artist.topTracks.get(0).id);assertEquals(1,artist.albums.size());
        MusicProvider.Page<MusicProvider.Album> first=provider.artistAlbums("9",0,25);assertTrue(first.hasMore);assertEquals(40,first.nextOffset);
        MusicProvider.Page<MusicProvider.Album> last=provider.artistAlbums("9",first.nextOffset,25);assertFalse(last.hasMore);assertEquals("102",last.items.get(0).id);
        assertTrue(paths.stream().anyMatch(p->p.contains("/view/top-songs")));
    }
    @Test public void libraryAlbumMappingFetchesCompleteCatalogInsteadOfOnlySavedTracks()throws Exception{
        List<String> paths=new ArrayList<>();Http http=new Http((p,h)->{paths.add(p);assertEquals(p.startsWith("/v1/me/"),h.containsKey("Music-User-Token"));
            if(p.startsWith("/v1/me/library/albums/l.partial")&&!p.contains("/catalog"))return ok(collection(json("id","l.partial","type","library-albums","attributes",json("name","仅收藏一首","trackCount",1))));
            if(p.equals("/v1/me/library/albums/l.partial/catalog"))return ok(collection(album("101")));
            if(p.equals("/v1/catalog/cn/albums/101"))return ok(collection(album("101")));
            if(p.contains("/tracks")&&p.contains("offset=1"))return ok(collection(song("12")));
            if(p.contains("/tracks"))return ok(next(song("11"),"/v1/catalog/cn/albums/101/tracks?offset=1"));
            throw new AssertionError(p);
        });
        MusicProvider.Album result=new AppleMusicProvider(authorized(),http).album("l.partial");assertEquals("101",result.id);assertEquals(2,result.tracks.size());assertEquals("applemusic://songs/11",result.tracks.get(0).uri);assertEquals("101",result.tracks.get(0).albumId);assertEquals("9",result.tracks.get(0).artistId);
        assertFalse(paths.stream().anyMatch(p->p.startsWith("/v1/me/")&&p.contains("/tracks")));
    }
    @Test public void unmappedLibraryAlbumDoesNotClaimSavedSubsetIsACompleteAlbum()throws Exception{
        Http http=new Http((p,h)->ok(p.endsWith("/catalog")?collection():collection(json("id","l.uploaded","type","library-albums","attributes",json("name","导入歌曲","trackCount",1)))));
        try{new AppleMusicProvider(authorized(),http).album("l.uploaded");fail("Unmapped library subset must not become a complete album");}catch(ProviderException e){assertEquals(ProviderException.Kind.UNAVAILABLE,e.kind);assertTrue(e.getMessage().contains("完整专辑"));}
    }
    @Test public void librarySongUsesCatalogRelationshipsWithoutChangingLibraryPlaybackIdentity()throws Exception{
        JSONObject catalog=song("11").put("relationships",json("albums",relation("albums","101"),"artists",relation("artists","9")));
        JSONObject library=json("id","i.saved","type","library-songs","attributes",json("name","我收藏的歌","playParams",json("id","i.saved","kind","song","isLibrary",true)),"relationships",json("albums",relation("library-albums","l.partial"),"artists",relation("library-artists","r.artist"),"catalog",collection(catalog)));
        Track track=new AppleMusicApi.Item(library).asTrack();assertEquals("101",track.albumId);assertEquals("9",track.artistId);assertEquals("i.saved",track.id);assertEquals("applemusic://library-songs/i.saved",track.uri);
    }
    @Test public void catalogAlbumMissingTracksDoesNotReportComplete()throws Exception{
        Http http=new Http((p,h)->ok(p.contains("/tracks")?collection(song("11")):collection(album("101"))));
        try{new AppleMusicProvider(authorized(),http).album("101");fail("One of two tracks is incomplete");}catch(ProviderException e){assertEquals(ProviderException.Kind.INVALID_RESPONSE,e.kind);}
    }
    @Test public void libraryArtistMapsThroughAuthorizedRelationshipAndMissingEditorialStaysEmpty()throws Exception{
        List<String> paths=new ArrayList<>();Http http=new Http((p,h)->{paths.add(p);assertEquals(p.startsWith("/v1/me/"),h.containsKey("Music-User-Token"));
            if(p.equals("/v1/me/library/artists/r.saved/catalog"))return ok(collection(json("id","9","type","artists")));
            if(p.equals("/v1/catalog/cn/artists/9"))return ok(collection(json("id","9","type","artists","attributes",json("name","目录艺人"))));
            return ok(collection());
        });
        MusicProvider.Artist result=new AppleMusicProvider(authorized(),http).artist("r.saved");assertEquals("r.saved",result.id);assertEquals("目录艺人",result.name);assertEquals("",result.description);assertEquals("",result.avatarUrl);assertTrue(result.topTracks.isEmpty());assertTrue(result.albums.isEmpty());assertEquals(4,paths.size());
    }
    @Test public void artistAlbumPaginationRejectsLoopsForeignPathsAndUnrepresentableOffsets()throws Exception{
        for(String next:new String[]{"/v1/catalog/cn/artists/9/albums?offset=0","/v1/catalog/cn/artists/9/albums?offset=opaque","/v1/catalog/cn/artists/other/albums?offset=25","https://evil.example/albums?offset=25"}){
            Http http=new Http((p,h)->ok(next(album("101"),next)));try{new AppleMusicProvider(authorized(),http).artistAlbums("9",0,25);fail(next);}catch(ProviderException e){assertEquals(ProviderException.Kind.INVALID_RESPONSE,e.kind);}assertEquals(1,http.calls);
        }
    }
    @Test public void libraryReadsActuallyRequestNavigationRelationshipsAndPreserveThemAcrossPages()throws Exception{
        List<String> paths=new ArrayList<>();Http http=new Http((p,h)->{paths.add(p);assertTrue(p.contains("include=albums,artists,catalog"));assertTrue(p.contains("include%5Bsongs%5D=albums,artists"));assertTrue(h.containsKey("Music-User-Token"));
            JSONObject catalog=json("id","11","type","songs","relationships",json("albums",relation("albums","101"),"artists",relation("artists","9")));
            JSONObject row=json("id",paths.size()==1?"i.one":"i.two","type","library-songs","attributes",json("name","资料库歌曲"),"relationships",json("catalog",collection(catalog)));
            return ok(paths.size()==1?next(row,"/v1/me/library/songs?offset=1"):collection(row));
        });
        List<Track> tracks=new AppleMusicProvider(authorized(),http).libraryTracks();assertEquals(2,tracks.size());assertEquals("101",tracks.get(1).albumId);assertEquals("9",tracks.get(1).artistId);assertEquals("applemusic://library-songs/i.two",tracks.get(1).uri);
    }
}
