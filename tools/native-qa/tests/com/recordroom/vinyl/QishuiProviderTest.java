package com.recordroom.vinyl;

import android.net.Uri;
import java.util.ArrayList;
import java.util.Collections;
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

@RunWith(RobolectricTestRunner.class)
@Config(sdk=34)
public class QishuiProviderTest {
    static class Session implements ProviderSession {String cookies="sessionid=fixture-session";long epoch;int writes;public String readCookies(){return cookies;}public void writeCookies(String value){cookies=value;epoch++;writes++;}public void clear(){cookies="";epoch++;}public long generation(){return epoch;}}
    interface Handler{JSONObject call(String path,Map<String,String> args,JSONObject body,String cookie)throws ProviderException;}
    static class Http extends QishuiHttp {final Handler handler;int calls;Http(Handler handler){this.handler=handler;}@Override JSONObject request(String p,Map<String,String> a,JSONObject b,String c)throws ProviderException{calls++;return handler.call(p,a,b,c);}@Override JSONObject playerInfo(String url)throws ProviderException{assertTrue(safeMediaUrl(url));return handler.call("player-info",Collections.emptyMap(),null,"");}}
    interface Action{void run()throws Exception;}
    static void fails(ProviderException.Kind kind,Action action)throws Exception{try{action.run();fail("Expected "+kind);}catch(ProviderException e){assertEquals(kind,e.kind);assertFalse(e.getMessage().contains("fixture-session"));}}
    static JSONObject json(Object... values){try{JSONObject o=new JSONObject();for(int i=0;i<values.length;i+=2)o.put((String)values[i],values[i+1]);return o;}catch(Exception e){throw new AssertionError(e);}}
    static JSONObject profile(){return json("status_code",0,"data",json("my_info",json("id","u1","nickname","测试用户"),"is_vip",false,"is_svip",false));}
    static JSONObject song(String id){return json("track",json("id",id,"name","歌曲 "+id,"duration_ms",180000,"related_info",json("artist_links",new JSONArray().put(json("id","a1","name","艺人")),"album_link",json("id","al1","name","专辑"))));}
    static Track track(){return new Track("s1","歌曲","艺人","专辑","汽水音乐","qishui://song/s1","",180000,1,"艺人","","","qishui","al1","a1");}
    static JSONObject stream(String quality,long duration){return json("MainPlayUrl","https://audio.douyinvod.com/song.m4a","Duration",duration,"Bitrate",128000,"quality",quality);}
    static JSONObject playback(JSONObject stream){return json("status_code",0,"data",json("track",json("id","s1","duration_ms",180000,"audio_info",json("play_info_list",new JSONArray().put(stream)))));}

    @Test public void unauthorizedAndIncompleteIdentityNeverBecomeAConnectedAccount()throws Exception{
        Session session=new Session();Http expired=new Http((p,a,b,c)->json("status_code",1000016));fails(ProviderException.Kind.LOGIN_EXPIRED,()->new QishuiProvider(session,expired).playlists(""));assertEquals(1,expired.calls);
        Http empty=new Http((p,a,b,c)->json("status_code",0,"data",json("nickname","Only a label")));fails(ProviderException.Kind.INVALID_RESPONSE,()->new QishuiProvider(session,empty).account());
        session.cookies="";fails(ProviderException.Kind.LOGIN_REQUIRED,()->new QishuiProvider(session,empty).account());assertEquals(1,empty.calls);
    }
    @Test public void authorizedCookieIsValidatedBeforeCommitAndCancelledResultDoesNotReplaceSession()throws Exception{
        Session s=new Session();Http valid=new Http((p,a,b,c)->{assertEquals("/luna/pc/me",p);return profile();});QishuiProvider provider=new QishuiProvider(s,valid);
        assertEquals("u1",provider.acceptAuthorizedSession("sessionid=new-fixture",s.epoch).id);assertEquals(1,s.writes);
        Http delayed=new Http((p,a,b,c)->{s.clear();return profile();});long generation=s.epoch;fails(ProviderException.Kind.LOGIN_EXPIRED,()->new QishuiProvider(s,delayed).acceptAuthorizedSession("sessionid=late-fixture",generation));assertEquals("",s.cookies);assertEquals(1,s.writes);
        fails(ProviderException.Kind.LOGIN_REQUIRED,()->provider.acceptAuthorizedSession("sessionid=",s.epoch));
    }
    @Test public void personalPlaylistsFollowEveryCursorAndKeepOwnedVersusCollected()throws Exception{
        Session s=new Session();List<String> cursors=new ArrayList<>();Http http=new Http((p,a,b,c)->{
            if(p.endsWith("/me"))return profile();cursors.add(p+":"+a.get("cursor"));
            if(p.endsWith("user/playlist")&&a.get("cursor").isEmpty())return json("data",json("playlists",new JSONArray().put(json("playlist_id","p1","title","我喜欢的音乐","count_tracks",2)),"has_more",true,"next_cursor","next-one"));
            if(p.endsWith("user/playlist"))return json("data",json("playlists",new JSONArray().put(json("playlist_id","p2","title","我的歌单","count_tracks",1)),"has_more",false));
            return json("data",json("items",new JSONArray().put(json("playlist",json("playlist_id","p3","title","收藏歌单","count_tracks",3))),"has_more",false));
        });
        List<MusicProvider.Playlist> items=new QishuiProvider(s,http).playlists("u1");assertEquals(3,items.size());assertTrue(items.get(0).owned);assertTrue(items.get(1).owned);assertFalse(items.get(2).owned);assertEquals(3,cursors.size());assertTrue(cursors.get(1).endsWith(":next-one"));
    }
    @Test public void playlistSongsPreserveOrderDuplicatesAndMetadataNotAudioUrls()throws Exception{
        Http http=new Http((p,a,b,c)->a.get("cursor").isEmpty()?json("data",json("media_resources",new JSONArray().put(song("s1")).put(song("s1")),"has_more",true,"next_cursor","2")):json("data",json("tracks",new JSONArray().put(song("s3")),"has_more",false)));
        List<Track> tracks=new QishuiProvider(new Session(),http).playlistTracks("p1");assertEquals(3,tracks.size());assertEquals("s1",tracks.get(0).id);assertEquals("s1",tracks.get(1).id);assertEquals("s3",tracks.get(2).id);assertEquals("qishui://song/s3",tracks.get(2).uri);assertEquals("a1",tracks.get(2).artistId);assertEquals("al1",tracks.get(2).albumId);assertEquals(180000,tracks.get(2).durationMs);
    }
    @Test public void loopMissingCursorAndLogoutRejectIncompleteLibrary()throws Exception{
        Http looping=new Http((p,a,b,c)->json("data",json("tracks",new JSONArray().put(song("s1")),"has_more",true,"next_cursor","same")));
        fails(ProviderException.Kind.INVALID_RESPONSE,()->new QishuiProvider(new Session(),looping).playlistTracks("p"));assertEquals(2,looping.calls);
        Session s=new Session();Http delayed=new Http((p,a,b,c)->{s.clear();return json("data",json("tracks",new JSONArray()));});fails(ProviderException.Kind.LOGIN_EXPIRED,()->new QishuiProvider(s,delayed).playlistTracks("p"));assertEquals(1,delayed.calls);
    }
    @Test public void searchReadsPcResultGroupsAndUsesRealServerFlags()throws Exception{
        Http http=new Http((p,a,b,c)->{assertEquals("/luna/pc/search/track",p);assertEquals("搜索",a.get("q"));assertEquals("20",a.get("cursor"));return json("data",json("result_groups",new JSONArray().put(json("data",new JSONArray().put(song("s1")))) ,"has_more",true,"next_cursor","21"));});
        MusicProvider.Page<Track> page=new QishuiProvider(new Session(),http).searchTracks("搜索",20,10);assertEquals(1,page.items.size());assertTrue(page.hasMore);assertEquals(21,page.nextOffset);
    }
    @Test public void fullUnencryptedStreamUsesNoCookiesAndTrialMissingDurationOrDrmAreRejected()throws Exception{
        JSONObject[] response={playback(stream("standard",180))};Http http=new Http((p,a,b,c)->p.endsWith("/me")?profile():response[0]);QishuiProvider provider=new QishuiProvider(new Session(),http);
        PlaybackResource resource=provider.resolve(track());assertEquals(180000,resource.durationMs);assertTrue(resource.headers.isEmpty());assertTrue(resource.url.startsWith("https://audio.douyinvod.com/"));
        response[0]=playback(stream("standard",30));fails(ProviderException.Kind.PREVIEW_ONLY,()->provider.resolve(track()));
        response[0]=playback(stream("standard",0));fails(ProviderException.Kind.UNAVAILABLE,()->provider.resolve(track()));
        response[0]=playback(stream("standard",180).put("play_auth","fixture-drm"));fails(ProviderException.Kind.UNAVAILABLE,()->provider.resolve(track()));
        response[0]=playback(stream("standard",180).put("encrypted",true));fails(ProviderException.Kind.UNAVAILABLE,()->provider.resolve(track()));
        response[0]=playback(stream("standard",180).put("trial",true));fails(ProviderException.Kind.PREVIEW_ONLY,()->provider.resolve(track()));
    }
    @Test public void ordinaryVipNeverGrantsSvipOrExpiredEntitlements()throws Exception{
        long now=System.currentTimeMillis();QishuiProvider.Membership vip=QishuiProvider.membership(json("is_vip",true,"vip_end_time",(now+60000)/1000,"is_svip",true,"svip_end_time",(now-60000)/1000),now);assertEquals(1,vip.tier);
        QishuiProvider.Membership expired=QishuiProvider.membership(json("is_vip",true,"vip_end_time",(now-60000)/1000),now);assertEquals(0,expired.tier);assertTrue(expired.known);
        assertFalse(QishuiProvider.membership(json(),now).known);
        assertEquals(1,QishuiProvider.membership(json("vip_info",json("status",1,"expire_time",(now+60000)/1000)),now).tier);
        assertEquals(0,QishuiProvider.membership(json("vip_info",json("status",1,"expire_time",(now-60000)/1000)),now).tier);
        assertEquals(2,QishuiProvider.membership(json("svip_info",json("active",true,"expire_time",(now+60000)/1000)),now).tier);
        JSONObject p=profile();p.getJSONObject("data").put("is_vip",true);Http http=new Http((path,a,b,c)->path.endsWith("/me")?p:playback(stream("hi_res",180)));fails(ProviderException.Kind.UNAVAILABLE,()->new QishuiProvider(new Session(),http).resolve(track()));
    }
    @Test public void protectedChildStreamInheritsEncryptionAndUntrustedUrlsNeverEscape()throws Exception{
        JSONObject payload=json("data",json("track",json("id","s1","duration_ms",180000),"track_player",json("video_model",json("encrypt_info",json("spade_a","fixture-secret"),"video_duration",180,"audio",stream("standard",180)).toString())));
        Http http=new Http((p,a,b,c)->p.endsWith("/me")?profile():payload);fails(ProviderException.Kind.UNAVAILABLE,()->new QishuiProvider(new Session(),http).resolve(track()));
        assertFalse(QishuiHttp.safeMediaUrl("https://api.qishui.com.evil.example/audio"));assertFalse(QishuiHttp.safeMediaUrl("https://user:secret@api.qishui.com/audio"));assertFalse(QishuiHttp.safeMediaUrl("http://audio.douyinvod.com/a"));
        fails(ProviderException.Kind.INVALID_RESPONSE,()->new QishuiHttp().request("https://evil.example/api",Collections.emptyMap(),null,"sessionid=fixture-session"));
    }
    @Test public void webAuthTrustAndCookieMergeDoNotExposeAnArbitraryBridge()throws Exception{
        assertTrue(QishuiAuthActivity.navigationAllowed(Uri.parse(QishuiAuthActivity.HOST+"host.html")));assertFalse(QishuiAuthActivity.navigationAllowed(Uri.parse("https://evil.example/__recordroom_auth__/host.html")));
        assertFalse(QishuiAuthActivity.resourceAllowed(Uri.parse("http://api.qishui.com/passport")));assertFalse(QishuiAuthActivity.resourceAllowed(Uri.parse("https://api.qishui.com.evil.example/passport")));
        assertTrue(QishuiAuthActivity.scanAllowed(Uri.parse("https://bff-pc.qishui.com/light/invoke/scan_login?token=fixture")));assertFalse(QishuiAuthActivity.scanAllowed(Uri.parse("https://evil.example/login?token=fixture")));
        assertEquals("sessionid=new; sid_tt=old",QishuiAuthActivity.mergeCookies("sessionid=old; sid_tt=old","sessionid=new"));assertFalse(QishuiProvider.hasLoginCookie("sessionid=fixture\r\nInjected: true"));
    }
    @Test public void sessionStoreEncryptsAndOtherInstancesSeeAtomicReplacement()throws Exception{
        android.content.SharedPreferences prefs=RuntimeEnvironment.getApplication().getSharedPreferences("qishui-test-store",0);prefs.edit().clear().commit();javax.crypto.SecretKey key=new javax.crypto.spec.SecretKeySpec(new byte[32],"AES");QishuiSessionStore a=new QishuiSessionStore(prefs,()->key),b=new QishuiSessionStore(prefs,()->key);
        assertEquals("",a.readCookies());long before=a.generation();b.writeCookies("sessionid=fixture-session");assertEquals("sessionid=fixture-session",a.readCookies());assertTrue(a.generation()>before);assertFalse(prefs.getString("encrypted_v1","").contains("fixture-session"));a.clear();assertEquals("",b.readCookies());
    }
    @Test public void cancellationAfterHttpStartedPreventsAnyCommitWithoutInterruptingWorker()throws Exception{
        Session session=new Session();QishuiProvider.AuthorizationAttempt gate=new QishuiProvider.AuthorizationAttempt();
        java.util.concurrent.CountDownLatch entered=new java.util.concurrent.CountDownLatch(1),release=new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Throwable> error=new java.util.concurrent.atomic.AtomicReference<>();
        Http http=new Http((p,a,b,c)->{entered.countDown();try{if(!release.await(3,java.util.concurrent.TimeUnit.SECONDS))throw new AssertionError("timeout");}catch(InterruptedException e){throw new AssertionError(e);}return profile();});
        long generation=session.generation();Thread worker=new Thread(()->{try{new QishuiProvider(session,http).acceptAuthorizedSession("sessionid=late-fixture",generation,gate);}catch(Throwable failure){error.set(failure);}});worker.start();
        assertTrue(entered.await(3,java.util.concurrent.TimeUnit.SECONDS));gate.cancel();release.countDown();worker.join(3000);assertFalse(worker.isAlive());assertTrue(error.get() instanceof ProviderException);assertEquals(ProviderException.Kind.LOGIN_EXPIRED,((ProviderException)error.get()).kind);assertEquals("sessionid=fixture-session",session.cookies);assertEquals(0,session.writes);assertFalse(gate.isCommitted());
        QishuiProvider.AuthorizationAttempt success=new QishuiProvider.AuthorizationAttempt();new QishuiProvider(session,new Http((p,a,b,c)->profile())).acceptAuthorizedSession("sessionid=good-fixture",session.generation(),success);success.cancel();assertTrue(success.isCommitted());assertEquals("sessionid=good-fixture",session.cookies);
    }
    @Test public void anotherStoreLogoutCannotBeOvertakenBetweenEpochCheckAndEncryptedWrite()throws Exception{
        android.content.SharedPreferences prefs=RuntimeEnvironment.getApplication().getSharedPreferences("qishui-two-store-race",0);prefs.edit().clear().commit();javax.crypto.SecretKey key=new javax.crypto.spec.SecretKeySpec(new byte[32],"AES");
        java.util.concurrent.CountDownLatch keyEntered=new java.util.concurrent.CountDownLatch(1),releaseKey=new java.util.concurrent.CountDownLatch(1),clearStarted=new java.util.concurrent.CountDownLatch(1),clearDone=new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean blockKey=new java.util.concurrent.atomic.AtomicBoolean(false);java.util.concurrent.atomic.AtomicReference<Throwable> failure=new java.util.concurrent.atomic.AtomicReference<>();
        QishuiSessionStore first=new QishuiSessionStore(prefs,()->{if(blockKey.getAndSet(false)){keyEntered.countDown();if(!releaseKey.await(3,java.util.concurrent.TimeUnit.SECONDS))throw new AssertionError("timeout");}return key;}),second=new QishuiSessionStore(prefs,()->key);
        first.writeCookies("sessionid=old-fixture");long generation=first.generation();blockKey.set(true);
        Thread commit=new Thread(()->{try{new QishuiProvider(first,new Http((p,a,b,c)->profile())).acceptAuthorizedSession("sessionid=new-fixture",generation);}catch(Throwable e){failure.set(e);}});commit.start();assertTrue(keyEntered.await(3,java.util.concurrent.TimeUnit.SECONDS));
        Thread logout=new Thread(()->{clearStarted.countDown();try{second.clear();}catch(Throwable e){failure.set(e);}finally{clearDone.countDown();}});logout.start();assertTrue(clearStarted.await(3,java.util.concurrent.TimeUnit.SECONDS));assertFalse("clear must share the commit lock",clearDone.await(100,java.util.concurrent.TimeUnit.MILLISECONDS));
        releaseKey.countDown();commit.join(3000);logout.join(3000);assertFalse(commit.isAlive());assertFalse(logout.isAlive());assertNull(failure.get());assertEquals("",first.readCookies());assertEquals("",second.readCookies());
        long old=first.generation();second.clear();fails(ProviderException.Kind.LOGIN_EXPIRED,()->new QishuiProvider(first,new Http((p,a,b,c)->profile())).acceptAuthorizedSession("sessionid=late-fixture",old));assertEquals("",second.readCookies());
    }
    @Test public void parentPreviewAndPremiumFlagsPropagateToUnmarkedChildStreams()throws Exception{
        JSONObject[] response={playback(stream("standard",180))};Http http=new Http((p,a,b,c)->p.endsWith("/me")?profile():response[0]);QishuiProvider provider=new QishuiProvider(new Session(),http);
        response[0].getJSONObject("data").getJSONObject("track").getJSONObject("audio_info").put("is_trial",true);fails(ProviderException.Kind.PREVIEW_ONLY,()->provider.resolve(track()));
        response[0]=playback(stream("standard",180));response[0].getJSONObject("data").getJSONObject("track").getJSONObject("audio_info").put("is_preview",true);fails(ProviderException.Kind.PREVIEW_ONLY,()->provider.resolve(track()));
        response[0]=playback(stream("standard",180));response[0].getJSONObject("data").getJSONObject("track").getJSONObject("audio_info").put("only_svip_playable",true);fails(ProviderException.Kind.UNAVAILABLE,()->provider.resolve(track()));
        response[0]=json("data",json("track",json("id","s1","duration_ms",180000),"track_player",json("video_model",json("is_trial",true,"video_duration",180,"audio",stream("standard",180)).toString())));fails(ProviderException.Kind.PREVIEW_ONLY,()->provider.resolve(track()));
        response[0]=json("data",json("is_preview",true,"track",json("id","s1","duration_ms",180000,"audio_info",json("play_info_list",new JSONArray().put(stream("standard",180))))));fails(ProviderException.Kind.PREVIEW_ONLY,()->provider.resolve(track()));
        response[0]=playback(stream("standard",180));assertEquals(180000,provider.resolve(track()).durationMs);
    }

}
