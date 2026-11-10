package com.recordroom.vinyl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Offline protocol fixtures: never confused with the separately recorded live/account probes. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=34)
public class NeteaseProviderTest {
    static final class Session implements ProviderSession {
        String cookies="";long epoch;int writes;
        public String readCookies(){return cookies;}
        public void writeCookies(String value){cookies=value;writes++;}
        public void clear(){cookies="";epoch++;}
        public long generation(){return epoch;}
    }
    interface Handler { NeteaseHttp.Response post(String path,JSONObject args,boolean weapi,String cookies)throws ProviderException; }
    static final class Http extends NeteaseHttp {
        Handler handler;int calls;
        Http(Handler handler){this.handler=handler;}
        @Override Response post(String path,JSONObject args,boolean weapi,String cookies)throws ProviderException{calls++;return handler.post(path,args,weapi,cookies);}
        @Override Response postQr(String path,JSONObject args,String cookies)throws ProviderException{calls++;return handler.post(path,args,false,cookies);}
    }
    private static JSONObject json(Object... values){try{JSONObject object=new JSONObject();for(int i=0;i<values.length;i+=2)object.put((String)values[i],values[i+1]);return object;}catch(Exception failure){throw new AssertionError(failure);}}
    private static NeteaseHttp.Response ok(JSONObject body,String cookie){return new NeteaseHttp.Response(body,cookie);}
    private static JSONObject song(int id){return json("id",id,"name","歌曲 "+id,"dt",180000,"no",id,"ar",new JSONArray().put(json("id",8,"name","歌手")),"al",json("id",9,"name","专辑","picUrl","https://p.music.126.net/cover.png"));}
    private static Track track(){return new Track("1","歌曲","歌手","专辑","网易云音乐","netease://song/1","",180000,1,"歌手","","","netease","9","8");}
    interface Action { void run()throws ProviderException; }
    private static void fails(ProviderException.Kind kind,Action action)throws Exception{try{action.run();fail("Expected "+kind);}catch(ProviderException failure){assertEquals(kind,failure.kind);assertFalse(failure.getMessage().contains("fixture-secret"));}}

    @Test public void catalogSongSearchIsIndependentOfSavedPlaylistsAndKeepsNextPage()throws Exception{
        Session session=new Session();Http http=new Http((path,args,weapi,cookie)->{
            assertEquals("/api/cloudsearch/pc",path);assertEquals(1,args.optInt("type"));
            int offset=args.optInt("offset");return ok(json("code",200,"result",json("songCount",2,"songs",new JSONArray().put(song(offset+1)))),cookie);
        });
        MusicProvider provider=new NeteaseProvider(session,http);
        MusicProvider.Page<Track> first=provider.searchTracks("未收藏歌曲",0,1),second=provider.searchTracks("未收藏歌曲",first.nextOffset,1);
        assertTrue(first.hasMore);assertFalse(second.hasMore);assertEquals("1",first.items.get(0).id);assertEquals("2",second.items.get(0).id);
        assertEquals(first.items.get(0).albumKey(),second.items.get(0).albumKey());assertEquals(0,session.writes);
    }

    @Test public void qrTransitionsPersistOnlyConfirmedCredentialsAndRejectLateLogoutResult()throws Exception{
        Session session=new Session();int[] stage={0};Http http=new Http((path,args,weapi,cookie)->{
            if(path.endsWith("unikey"))return ok(json("code",200,"unikey","fixture-key"),"NMTID=fixture");
            if(path.endsWith("account/get"))return ok(json("code",200,"profile",json("userId",7,"nickname","测试账户")),cookie);
            int code=new int[]{801,802,803}[stage[0]++];return ok(json("code",code),code==803?"MUSIC_U=fixture-secret; __csrf=fixture":"NMTID=fixture");});
        NeteaseProvider provider=new NeteaseProvider(session,http);MusicProvider.QrLogin qr=provider.beginQrLogin();
        assertEquals("https://music.163.com/login?codekey=fixture-key",qr.url);
        assertEquals(MusicProvider.LoginStatus.WAITING_SCAN,provider.pollQrLogin(qr.key).status);assertEquals(0,session.writes);
        assertEquals(MusicProvider.LoginStatus.WAITING_CONFIRM,provider.pollQrLogin(qr.key).status);assertEquals(0,session.writes);
        assertEquals(MusicProvider.LoginStatus.SUCCESS,provider.pollQrLogin(qr.key).status);assertEquals(1,session.writes);
        assertTrue(session.cookies.contains("MUSIC_U="));provider.logout();assertEquals("",session.cookies);
        Http delayed=new Http((path,args,weapi,cookie)->{if(path.endsWith("unikey"))return ok(json("code",200,"unikey","fixture-key"),"");session.clear();return ok(json("code",803),"MUSIC_U=fixture-secret");});
        NeteaseProvider delayedProvider=new NeteaseProvider(session,delayed);delayedProvider.beginQrLogin();
        fails(ProviderException.Kind.LOGIN_EXPIRED,()->delayedProvider.pollQrLogin("fixture-key"));assertEquals("",session.cookies);assertEquals(1,session.writes);
    }

    @Test public void riskRejectionNeverBecomesSuccessAndNetworkFailureDoesNotEraseSession()throws Exception{
        Session session=new Session();NeteaseProvider rejected=new NeteaseProvider(session,new Http((p,a,w,c)->ok(p.endsWith("unikey")?json("code",200,"unikey","fixture-key"):json("code",8821),c)));rejected.beginQrLogin();
        fails(ProviderException.Kind.LOGIN_CHALLENGE,()->rejected.pollQrLogin("fixture-key"));assertEquals(0,session.writes);
        session.cookies="MUSIC_U=fixture-secret";
        NeteaseProvider offline=new NeteaseProvider(session,new Http((p,a,w,c)->{throw new ProviderException(ProviderException.Kind.NETWORK,"网络不可用");}));
        fails(ProviderException.Kind.NETWORK,offline::account);assertTrue(session.cookies.contains("MUSIC_U="));
        NeteaseProvider expired=new NeteaseProvider(session,new Http((p,a,w,c)->ok(json("code",301),c)));
        fails(ProviderException.Kind.LOGIN_EXPIRED,expired::account);assertEquals("",session.cookies);
    }

    @Test public void createdAndSubscribedPlaylistsAreFullyPagedWithProgressGuard()throws Exception{
        Session session=new Session();session.cookies="MUSIC_U=fixture-secret";List<Integer> offsets=new ArrayList<>();
        Http http=new Http((path,args,weapi,cookie)->{assertTrue(weapi);int offset=args.optInt("offset");offsets.add(offset);JSONArray rows=new JSONArray();int count=offset==0?200:2;
            for(int i=0;i<count;i++)rows.put(json("id",offset+i+1,"name","歌单","creator",json("userId",i%2==0?7:8),"trackCount",5));return ok(json("code",200,"playlist",rows,"more",offset==0),cookie);});
        List<MusicProvider.Playlist> lists=new NeteaseProvider(session,http).playlists("7");assertEquals(202,lists.size());assertTrue(lists.get(0).owned);assertFalse(lists.get(1).owned);assertEquals(Arrays.asList(0,200),offsets);
        Http loop=new Http((p,a,w,c)->ok(json("code",200,"more",true,"playlist",new JSONArray().put(json("id",1,"creator",json("userId",7)))),c));
        fails(ProviderException.Kind.INVALID_RESPONSE,()->new NeteaseProvider(session,loop).playlists("7"));assertEquals(2,loop.calls);
    }

    @Test public void playlistAndLikedIdsBatchAllSongsAndRestoreOriginalOrder()throws Exception{
        Session session=new Session();session.cookies="MUSIC_U=fixture-secret";List<Integer> batches=new ArrayList<>();
        Http http=new Http((path,args,weapi,cookie)->{
            if(path.endsWith("playlist/detail")){JSONArray ids=new JSONArray();for(int i=1;i<=1201;i++)ids.put(json("id",i));return ok(json("code",200,"playlist",json("trackCount",1201,"trackIds",ids)),cookie);}
            if(path.endsWith("song/like/get"))return ok(json("code",200,"ids",new JSONArray().put(3).put(1).put(2)),cookie);
            JSONArray requested;try{requested=new JSONArray(args.optString("c"));}catch(Exception failure){throw new AssertionError(failure);}
            batches.add(requested.length());JSONArray returned=new JSONArray();for(int i=requested.length()-1;i>=0;i--)returned.put(song(requested.optJSONObject(i).optInt("id")));
            return ok(json("code",200,"songs",returned),cookie);
        });
        NeteaseProvider provider=new NeteaseProvider(session,http);List<Track> all=provider.playlistTracks("9");
        assertEquals(1201,all.size());assertEquals(Arrays.asList(500,500,201),batches);
        for(int i=0;i<all.size();i++){assertEquals(""+(i+1),all.get(i).id);assertEquals("netease://song/"+(i+1),all.get(i).uri);assertEquals("netease",all.get(i).provider);}
        List<Track> liked=provider.likedTracks("7");assertEquals("3",liked.get(0).id);assertEquals("1",liked.get(1).id);assertEquals("2",liked.get(2).id);
        Http partial=new Http((p,a,w,c)->ok(json("code",200,"playlist",json("trackCount",2,"trackIds",new JSONArray().put(json("id",1)))),c));
        fails(ProviderException.Kind.UNAVAILABLE,()->new NeteaseProvider(session,partial).playlistTracks("9"));
        Http missing=new Http((p,a,w,c)->p.endsWith("playlist/detail")?ok(json("code",200,"playlist",json("trackCount",1,"trackIds",new JSONArray().put(json("id",1)))),c):ok(json("code",200,"songs",new JSONArray()),c));
        fails(ProviderException.Kind.UNAVAILABLE,()->new NeteaseProvider(session,missing).playlistTracks("9"));
    }

    @Test public void searchAlbumArtistAndAccountUseRealSchemaWithoutInventingTracks()throws Exception{
        Session session=new Session();session.cookies="MUSIC_U=fixture-secret";
        JSONObject album=json("id",9,"name","专辑","artist",json("id",8,"name","歌手"),"picUrl","http://p.music.126.net/a.jpg","size",2);
        Http http=new Http((p,a,w,c)->{
            if(p.endsWith("account/get"))return ok(json("code",200,"profile",json("userId",7,"nickname","用户","vipType",11),"account",json("id",7)),c);
            if(p.endsWith("cloudsearch/pc")){assertEquals(10,a.optInt("type"));return ok(json("code",200,"result",json("albumCount",3,"albums",new JSONArray().put(album))),c);}
            if(p.endsWith("/album/9"))return ok(json("code",200,"album",album,"songs",new JSONArray().put(song(1)).put(song(2))),c);
            if(p.endsWith("head/info/get"))return ok(json("code",200,"data",json("artist",json("id",8,"name","歌手","briefDesc","资料"))),c);
            if(p.endsWith("artist/songs"))return ok(json("code",200,"songs",new JSONArray().put(song(2))),c);
            return ok(json("code",200,"more",false,"hotAlbums",new JSONArray().put(album)),c);
        });
        NeteaseProvider provider=new NeteaseProvider(session,http);assertTrue(provider.account().loggedIn);assertEquals(11,provider.account().vipType);
        MusicProvider.Page<MusicProvider.Album> search=provider.searchAlbums("专辑",0,1);assertEquals(3,search.total);assertEquals(1,search.nextOffset);assertTrue(search.hasMore);
        MusicProvider.Album detail=provider.album("9");assertEquals(2,detail.tracks.size());assertEquals("https://p.music.126.net/a.jpg",detail.coverUrl);
        MusicProvider.Artist artist=provider.artist("8");assertEquals("歌手",artist.name);assertEquals(1,artist.topTracks.size());assertEquals(1,artist.albums.size());
    }

    @Test public void playbackRequiresExplicitFullDurationAndRejectsTrialsAndDeniedResources()throws Exception{
        Session session=new Session();JSONObject[] entry={json("id",1,"code",200,"url","https://m.music.126.net/fixture.mp3","time",180000,"expi",120,"type","mp3","freeTrialInfo",JSONObject.NULL,"freeTrialPrivilege",json("resConsumable",false,"userConsumable",false,"listenable",false))};
        Http http=new Http((p,a,w,c)->{assertEquals("standard",a.optString("level"));return ok(json("code",200,"data",new JSONArray().put(entry[0])),c);});
        NeteaseProvider provider=new NeteaseProvider(session,http);PlaybackResource full=provider.resolve(track());assertEquals(180000,full.durationMs);assertTrue(full.headers.isEmpty());assertTrue(full.expiresAtMs>System.currentTimeMillis());
        entry[0].put("freeTrialInfo",json("start",0,"end",30));fails(ProviderException.Kind.PREVIEW_ONLY,()->provider.resolve(track()));
        entry[0].put("freeTrialInfo",JSONObject.NULL).put("freeTrialPrivilege",json("listenable",true));fails(ProviderException.Kind.PREVIEW_ONLY,()->provider.resolve(track()));
        entry[0].put("freeTrialPrivilege",JSONObject.NULL).put("time",30000);fails(ProviderException.Kind.PREVIEW_ONLY,()->provider.resolve(track()));
        entry[0].remove("time");fails(ProviderException.Kind.UNAVAILABLE,()->provider.resolve(track()));
        entry[0].put("time",180000).put("url",JSONObject.NULL);fails(ProviderException.Kind.UNAVAILABLE,()->provider.resolve(track()));
        entry[0].put("url","http://unrelated.example/audio");fails(ProviderException.Kind.UNAVAILABLE,()->provider.resolve(track()));
        entry[0].put("url","https://m.music.126.net/fixture.ncm").put("type","ncm");fails(ProviderException.Kind.UNAVAILABLE,()->provider.resolve(track()));
    }

    @Test public void albumMetadataKeepsAlbumArtistSeparateFromGuestSingerAndSurvivesPersistence()throws Exception{
        JSONObject album=json("id",900,"name","合辑名称","artist",json("id",88,"name","专辑音乐人"),"picUrl","https://p.music.126.net/album.jpg","size",2);
        JSONObject first=song(1),second=song(2);second.put("ar",new JSONArray().put(json("id",11,"name","客串音乐人")));
        Http http=new Http((p,a,w,c)->ok(json("code",200,"album",album,"songs",new JSONArray().put(first).put(second)),c));
        MusicProvider.Album result=new NeteaseProvider(new Session(),http).album("900");
        for(Track song:result.tracks){
            assertEquals("合辑名称",song.album);assertEquals("900",song.albumId);
            assertEquals("专辑音乐人",song.albumArtist);assertEquals("88",song.albumArtistId);
            assertEquals("https://p.music.126.net/album.jpg",song.artworkUri);
        }
        assertEquals("歌手",result.tracks.get(0).artist);assertEquals("8",result.tracks.get(0).artistId);
        Track guest=result.tracks.get(1);assertEquals("客串音乐人",guest.artist);assertEquals("11",guest.artistId);
        Track restored=Track.fromJson(guest.toJson());assertEquals("88",restored.albumArtistId);assertEquals("11",restored.artistId);
        assertEquals("专辑音乐人",restored.albumArtist);assertEquals("客串音乐人",restored.artist);
        JSONObject legacy=guest.toJson();legacy.remove("albumArtistId");assertEquals("11",Track.fromJson(legacy).albumArtistId);
        legacy.put("albumArtistId",JSONObject.NULL);assertEquals("11",Track.fromJson(legacy).albumArtistId);
        assertEquals("8",track().albumArtistId);
    }

    @Test public void songDetailsUseEmbeddedAlbumArtistOrFallBackToSongArtist()throws Exception{
        JSONObject first=song(1);first.getJSONObject("al").put("artist",json("id",44,"name","合集音乐人"));
        Http http=new Http((p,a,w,c)->p.endsWith("playlist/detail")
                ?ok(json("code",200,"playlist",json("trackCount",2,"trackIds",new JSONArray().put(json("id",1)).put(json("id",2)))),c)
                :ok(json("code",200,"songs",new JSONArray().put(first).put(song(2))),c));
        List<Track> songs=new NeteaseProvider(new Session(),http).playlistTracks("9");
        assertEquals("44",songs.get(0).albumArtistId);assertEquals("合集音乐人",songs.get(0).albumArtist);
        assertEquals("8",songs.get(0).artistId);assertEquals("歌手",songs.get(0).artist);
        assertEquals("8",songs.get(1).albumArtistId);assertEquals("歌手",songs.get(1).albumArtist);
    }

    @Test public void cookieMergeHandlesFoldedHeadersDeletionAndDomainScope()throws Exception{
        Map<String,List<String>> headers=new HashMap<>();headers.put("Set-Cookie",Arrays.asList("NMTID=first; Domain=music.163.com, MUSIC_U=fixture-secret; HttpOnly; Domain=music.163.com; Secure", "__csrf=removed; Max-Age=0; Domain=.music.163.com", "foreign=bad; Domain=unrelated.example"));
        Map<String,String> values=NeteaseHttp.parseCookies(NeteaseHttp.mergeCookies("__csrf=old",headers));
        assertEquals("fixture-secret",values.get("MUSIC_U"));assertEquals("first",values.get("NMTID"));assertFalse(values.containsKey("foreign"));assertFalse(values.containsKey("__csrf"));
        assertTrue(NeteaseHttp.parseCookies("MUSIC_U=value\r\nInjected: yes").isEmpty());
        NeteaseHttp transport=new NeteaseHttp();fails(ProviderException.Kind.INVALID_RESPONSE,()->transport.post("https://unrelated.example/api",json(),true,"MUSIC_U=fixture-secret"));
        Map<String,String> encrypted=NeteaseCrypto.weapi("{\"x\":1}","abcdefghijklmnop");assertEquals(256,encrypted.get("encSecKey").length());assertTrue(encrypted.get("encSecKey").matches("[0-9a-f]{256}"));
        assertEquals(encrypted,NeteaseCrypto.weapi("{\"x\":1}","abcdefghijklmnop"));assertNotEquals(encrypted,NeteaseCrypto.weapi("{\"x\":2}","abcdefghijklmnop"));
        assertTrue(NeteaseCrypto.eapi("/api/test","{\"x\":1}").get("params").matches("[0-9A-F]+"));
    }

    @Test public void successfulResponseDeletingAuthenticationClearsEncryptedSessionBeforeNextRequest()throws Exception{
        android.content.SharedPreferences preferences=org.robolectric.RuntimeEnvironment.getApplication().getSharedPreferences("netease_deleted_cookie_fixture",android.content.Context.MODE_PRIVATE);
        preferences.edit().clear().commit();
        javax.crypto.SecretKey key=new javax.crypto.spec.SecretKeySpec(new byte[32],"AES");
        SecureSessionStore session=new SecureSessionStore(preferences,()->key);
        session.writeCookies("MUSIC_U=fixture-secret; __csrf=fixture; NMTID=fixture");
        assertTrue(preferences.contains("encrypted_v1"));long before=session.generation();
        int[] calls={0};Http http=new Http((path,args,weapi,cookie)->{
            JSONObject body=json("code",200,"result",json("albumCount",0,"albums",new JSONArray()));
            if(calls[0]++==0){
                assertTrue(NeteaseHttp.parseCookies(cookie).containsKey("MUSIC_U"));
                Map<String,List<String>> headers=new HashMap<>();
                headers.put("Set-Cookie",Collections.singletonList("MUSIC_U=; Max-Age=0; Domain=.music.163.com"));
                return ok(body,NeteaseHttp.mergeCookies(cookie,headers));
            }
            assertFalse("Revoked credentials must not be sent again",NeteaseHttp.parseCookies(cookie).containsKey("MUSIC_U"));
            return ok(body,cookie);
        });
        NeteaseProvider provider=new NeteaseProvider(session,http);
        fails(ProviderException.Kind.LOGIN_EXPIRED,()->provider.searchAlbums("fixture",0,1));
        assertEquals("",session.readCookies());assertTrue(session.generation()>before);
        assertFalse("No stale encrypted credentials survive process restart",preferences.contains("encrypted_v1"));
        assertEquals("",new SecureSessionStore(preferences,()->key).readCookies());
        assertFalse(provider.account().loggedIn);
        assertTrue(provider.searchAlbums("fixture",0,1).items.isEmpty());assertEquals(2,calls[0]);
    }
}
