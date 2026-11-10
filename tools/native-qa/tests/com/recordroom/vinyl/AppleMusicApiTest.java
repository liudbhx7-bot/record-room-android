package com.recordroom.vinyl;

import android.content.Context;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Deterministic official API fixtures: these are not a live Apple account or MusicKit playback proof. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=34)
public class AppleMusicApiTest {
    static final class Tokens implements AppleMusicApi.Tokens {
        String developer="fixture-developer",user="fixture-user";long epoch;int userReads;
        public String developerToken(){return developer;}
        public String musicUserToken(){userReads++;return user;}
        public long generation(){return epoch;}
    }
    interface Handler {AppleMusicHttp.Response get(String path,Map<String,String> headers)throws AppleMusicApi.Failure;}
    static final class Http extends AppleMusicHttp {
        final Handler handler;int calls;
        Http(Handler handler){this.handler=handler;}
        @Override Response get(String path,Map<String,String> headers)throws AppleMusicApi.Failure{calls++;return handler.get(path,headers);}
    }
    interface Action {void run()throws Exception;}
    private static void fails(AppleMusicApi.Error code,Action action)throws Exception{
        try{action.run();fail("Expected "+code);}catch(AppleMusicApi.Failure failure){assertEquals(code,failure.error);assertFalse(failure.getMessage().contains("fixture-user"));assertFalse(failure.getMessage().contains("fixture-developer"));}
    }
    static JSONObject json(Object... args){try{JSONObject o=new JSONObject();for(int i=0;i<args.length;i+=2)o.put((String)args[i],args[i+1]);return o;}catch(Exception e){throw new AssertionError(e);}}
    static AppleMusicHttp.Response ok(JSONObject body){return new AppleMusicHttp.Response(200,body);}
    static JSONObject song(String id,boolean library){return json("id",id,"type",library?"library-songs":"songs","attributes",json("name","真实曲目 "+id,"artistName","艺人","albumName","专辑","durationInMillis",213061,"trackNumber",3,"releaseDate","2026-10-01","artwork",json("url","https://is1-ssl.mzstatic.com/image/test/{w}x{h}bb.jpg"),"playParams",json("id",id,"kind","song","isLibrary",library,"catalogId","12345"),"previews",new JSONArray().put(json("url","https://preview.example/30sec.m4a"))));}

    @Test public void configurationContainsOnlyPublicValuesAndAbsentFileIsUnconfigured()throws Exception{
        AppleMusicConfig cfg=AppleMusicConfig.parse(json("mediaId","com.recordroom.music","storefront","cn","tokenServiceUrl","https://music.example.org/apple/token"));
        assertTrue(cfg.hasTokenService());assertEquals("cn",cfg.storefront);assertFalse(AppleMusicConfig.unconfigured().hasTokenService());
        assertFalse(AppleMusicConfig.load(RuntimeEnvironment.getApplication()).hasTokenService());
        for(String key:new String[]{"privateKey","developerToken","musicUserToken","password"})fails(AppleMusicApi.Error.CONFIGURATION,()->AppleMusicConfig.parse(json(key,"fixture-user")));
        for(String url:new String[]{"http://music.example.org/token","https://u:p@music.example.org/token","https://music.example.org/token?secret=fixture-user","https://music.example.org/token#secret"})fails(AppleMusicApi.Error.CONFIGURATION,()->AppleMusicConfig.parse(json("tokenServiceUrl",url)));
        fails(AppleMusicApi.Error.CONFIGURATION,()->AppleMusicConfig.parse(json("storefront","CN")));
    }
    @Test public void missingTokensAndHeaderInjectionNeverReachTransport()throws Exception{
        Tokens tokens=new Tokens();Http http=new Http((p,h)->{throw new AssertionError("No request expected");});AppleMusicApi api=new AppleMusicApi(tokens,http);
        tokens.developer="";fails(AppleMusicApi.Error.CONFIGURATION,api::librarySongs);assertEquals(0,http.calls);
        tokens.developer="fixture-developer";tokens.user="";fails(AppleMusicApi.Error.USER_AUTH_REQUIRED,api::librarySongs);assertEquals(0,http.calls);
        tokens.user="fixture-user\r\nInjected: value";fails(AppleMusicApi.Error.INVALID_RESPONSE,api::librarySongs);assertEquals(0,http.calls);
        tokens.user="fixture-user";tokens.developer="fixture-developer\nvalue";fails(AppleMusicApi.Error.INVALID_RESPONSE,api::userStorefront);assertEquals(0,http.calls);
    }
    @Test public void catalogSearchUsesDeveloperOnlyAndDoesNotTurnPreviewIntoFullAudio()throws Exception{
        Tokens tokens=new Tokens();tokens.user="";
        Http http=new Http((path,headers)->{
            assertEquals("/v1/catalog/cn/search?types=songs&term=%E9%99%88%E5%A5%95%E8%BF%85%20%26%20test&limit=25&offset=0",path);
            assertEquals("Bearer fixture-developer",headers.get("Authorization"));assertFalse(headers.containsKey("Music-User-Token"));
            return ok(json("results",json("songs",json("data",new JSONArray().put(song("12345",false)),"meta",json("total",41),"next","/v1/catalog/cn/search?types=songs&offset=25"))));
        });
        AppleMusicApi.Page page=new AppleMusicApi(tokens,http).searchCatalog("cn","songs","陈奕迅 & test",25,0);
        assertEquals(41,page.total);assertEquals(0,tokens.userReads);assertEquals(1,page.items.size());
        AppleMusicApi.Item item=page.items.get(0);Track track=item.asTrack();assertEquals("applemusic://songs/12345",track.uri);assertFalse(track.uri.contains("preview"));
        assertEquals("applemusic",track.provider);assertEquals("https://is1-ssl.mzstatic.com/image/test/800x800bb.jpg",track.artworkUri);
        assertFalse(item.isLibrary);assertEquals("12345",item.catalogId);assertEquals(213061,track.durationMs);
        JSONObject copy=item.resource();copy.put("id","changed");assertEquals("12345",item.resource().optString("id"));
    }
    @Test public void libraryPagesPreserveLibraryIdentityAndCompleteOrder()throws Exception{
        Tokens tokens=new Tokens();List<String> requested=new ArrayList<>();
        Http http=new Http((path,headers)->{
            requested.add(path);assertEquals("Bearer fixture-developer",headers.get("Authorization"));assertEquals("fixture-user",headers.get("Music-User-Token"));
            if(requested.size()==1)return ok(json("data",new JSONArray().put(song("i.first",true)),"next","/v1/me/library/songs?offset=1"));
            return ok(json("data",new JSONArray().put(song("i.second",true))));
        });
        List<AppleMusicApi.Item> items=new AppleMusicApi(tokens,http).librarySongs();assertEquals(2,items.size());
        assertEquals(Arrays.asList("/v1/me/library/songs?limit=100&include=albums,artists,catalog&include%5Bsongs%5D=albums,artists","/v1/me/library/songs?offset=1&include=albums,artists,catalog&include%5Bsongs%5D=albums,artists"),requested);
        assertEquals("i.first",items.get(0).id);assertEquals("i.second",items.get(1).id);assertTrue(items.get(1).isLibrary);
        assertEquals("12345",items.get(1).catalogId);assertEquals("applemusic://library-songs/i.second",items.get(1).asTrack().uri);
        assertTrue(new JSONObject(items.get(1).playParamsJson).optBoolean("isLibrary"));
    }
    @Test public void playlistDuplicatesAreIntentionalAndVideosRemainTyped()throws Exception{
        Tokens tokens=new Tokens();JSONObject video=json("id","i.video","type","library-music-videos","attributes",json("name","音乐视频"));
        Http http=new Http((path,headers)->{
            assertEquals("/v1/me/library/playlists/p.example/tracks?limit=100&include%5Blibrary-songs%5D=albums,artists,catalog&include%5Bsongs%5D=albums,artists",path);
            return ok(json("data",new JSONArray().put(song("i.one",true)).put(song("i.one",true)).put(video)));
        });
        List<AppleMusicApi.Item> items=new AppleMusicApi(tokens,http).libraryPlaylistTracks("p.example");
        assertEquals(3,items.size());assertEquals(items.get(0).id,items.get(1).id);assertEquals("library-music-videos",items.get(2).type);
        fails(AppleMusicApi.Error.UNAVAILABLE,()->items.get(2).asTrack());
        fails(AppleMusicApi.Error.CONFIGURATION,()->new AppleMusicApi(tokens,http).libraryPlaylistTracks("../songs"));assertEquals(1,http.calls);
    }
    @Test public void maliciousNextCrossResourceAndRepeatedPagesFailWithoutLeakingTokens()throws Exception{
        for(String next:new String[]{"https://evil.example/collect","//evil.example/collect","/v1/me/library/songs?offset=1#secret","/v1/me/library/../songs","/v1/me/library/playlists?offset=1"}){
            Http http=new Http((p,h)->ok(json("data",new JSONArray().put(song("i.a",true)),"next",next)));
            fails(AppleMusicApi.Error.INVALID_RESPONSE,()->new AppleMusicApi(new Tokens(),http).librarySongs());assertEquals(1,http.calls);
        }
        Http loop=new Http((p,h)->ok(json("data",new JSONArray().put(song("i.a",true)),"next","/v1/me/library/songs?limit=100")));
        fails(AppleMusicApi.Error.INVALID_RESPONSE,()->new AppleMusicApi(new Tokens(),loop).librarySongs());assertEquals(1,loop.calls);
        Http empty=new Http((p,h)->ok(json("data",new JSONArray(),"next","/v1/me/library/songs?offset=1")));
        fails(AppleMusicApi.Error.INVALID_RESPONSE,()->new AppleMusicApi(new Tokens(),empty).librarySongs());assertEquals(1,empty.calls);
        for(String value:new String[]{"https://api.music.apple.com/v1/me/library/songs","/v1/../../x","/v1/%2e%2e/x","/v1/test\r\nHost: evil.example"})fails(AppleMusicApi.Error.INVALID_RESPONSE,()->AppleMusicHttp.safePath(value));
    }
    @Test public void logoutWhileFetchingDiscardsEvenSuccessfulResultsAndStopsPagination()throws Exception{
        Tokens tokens=new Tokens();Http http=new Http((p,h)->{tokens.epoch++;tokens.user="";return ok(json("data",new JSONArray().put(song("i.a",true)),"next","/v1/me/library/songs?offset=1"));});
        fails(AppleMusicApi.Error.SESSION_CHANGED,()->new AppleMusicApi(tokens,http).librarySongs());assertEquals(1,http.calls);
    }
    @Test public void errorsDistinguishDeveloperUserRateLimitAndNeverFollowRedirects()throws Exception{
        int[] statuses={401,403,429,302,404,500};AppleMusicApi.Error[] codes={AppleMusicApi.Error.DEVELOPER_AUTH,AppleMusicApi.Error.USER_ACCESS_DENIED,AppleMusicApi.Error.RATE_LIMITED,AppleMusicApi.Error.NETWORK,AppleMusicApi.Error.UNAVAILABLE,AppleMusicApi.Error.NETWORK};
        for(int i=0;i<statuses.length;i++){final int status=statuses[i];Http http=new Http((p,h)->new AppleMusicHttp.Response(status,json("errors",new JSONArray().put(json("detail","fixture-user fixture-developer")))));fails(codes[i],()->new AppleMusicApi(new Tokens(),http).librarySongs());assertEquals(1,http.calls);}
    }
    @Test public void accountStorefrontAndEmptyLibraryAreRealResponsesNotPlaceholderData()throws Exception{
        Http http=new Http((p,h)->ok(p.equals("/v1/me/storefront")?json("data",new JSONArray().put(json("id","hk","type","storefronts"))):json("data",new JSONArray())));
        AppleMusicApi api=new AppleMusicApi(new Tokens(),http);assertEquals("hk",api.userStorefront());assertTrue(api.librarySongs().isEmpty());assertTrue(api.libraryPlaylists().isEmpty());
        AppleMusicApi.Item badCover=new AppleMusicApi.Item(json("id","1","type","songs","attributes",json("artwork",json("url","https://mzstatic.com.evil.example/cover.jpg"))));assertEquals("",badCover.artworkUrl);assertEquals("",badCover.playParamsJson);
    }
}
