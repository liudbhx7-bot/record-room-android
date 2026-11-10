package com.recordroom.vinyl;

import android.content.Context;
import android.content.SharedPreferences;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import javax.crypto.spec.SecretKeySpec;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Offline contract/entitlement fixtures. No test supplies a real user cookie or plays vendor audio. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=34)
public class QQProviderTest {
    private static final String COOKIE="uin=o000123; qm_keyst=fixture-secret";
    static final class Session implements ProviderSession {
        String cookies="";long epoch;int writes;
        Session(){} Session(String value){cookies=value;}
        public String readCookies(){return cookies;}
        public void writeCookies(String value){cookies=value;epoch++;writes++;}
        public void clear(){cookies="";epoch++;}
        public long generation(){return epoch;}
    }
    interface Handler { JSONObject request(String url,JSONObject body,String cookies)throws ProviderException; }
    static final class Http extends QQHttp {
        Handler handler;int calls;
        Http(Handler handler){this.handler=handler;}
        @Override JSONObject request(String url,JSONObject body,String cookies)throws ProviderException{calls++;return handler.request(url,body,cookies);}
    }
    static final class Audio extends QQAudioProbe {
        long duration=180000;int calls;Runnable onCall;
        @Override long durationMs(String url,Map<String,String> headers){calls++;assertFalse(headers.containsKey("Cookie"));assertFalse(headers.containsKey("Authorization"));if(onCall!=null)onCall.run();return duration;}
    }
    interface Action { void run()throws ProviderException; }
    private static void fails(ProviderException.Kind expected,Action action)throws Exception{try{action.run();fail("Expected "+expected);}catch(ProviderException failure){assertEquals(expected,failure.kind);assertFalse(failure.getMessage().contains("fixture-secret"));}}
    private static JSONObject json(Object... pairs){try{JSONObject o=new JSONObject();for(int i=0;i<pairs.length;i+=2)o.put((String)pairs[i],pairs[i+1]);return o;}catch(Exception failure){throw new AssertionError(failure);}}
    private static JSONObject block(String key,JSONObject data){return json("code",0,key,json("code",0,"data",data));}
    private static JSONObject song(String id){return json("mid",id,"name","测试歌曲 "+id,"interval",180,"singer",new JSONArray().put(json("mid","Singer01","name","测试歌手")),"album",json("mid","Album01","name","测试专辑"),"file",json("media_mid","Media01"));}
    private static JSONObject liked(int first,int count,int total){JSONArray rows=new JSONArray();for(int i=0;i<count;i++)rows.put(song("Song"+(first+i)));return json("songlist",rows,"total_song_num",total,"hasmore",first+count<total?1:0,"dirinfo",json("uin","123"));}
    private static Track track()throws ProviderException{return QQProvider.track(song("Song01"));}
    private static JSONObject vkey(){return json("midurlinfo",new JSONArray().put(json("filename","M500Media01.mp3","songmid","Song01","result",0,"purl","M500Media01.mp3?vkey=fixture-vkey")),"sip",new JSONArray().put("https://ws.stream.qqmusic.qq.com/"));}
    private static Http playbackHttp(JSONObject[] resource){return new Http((url,body,cookies)->{
        assertTrue(cookies.contains("fixture-secret"));
        if(body.has("songinfo")){assertEquals("Song01",body.optJSONObject("songinfo").optJSONObject("param").optString("song_mid"));return block("songinfo",json("track_info",song("Song01")));}
        assertEquals("fixture-secret",body.optJSONObject("comm").optString("authst"));JSONObject param=body.optJSONObject("req_0").optJSONObject("param");assertEquals("123",param.optString("uin"));assertEquals("M500Media01.mp3",param.optJSONArray("filename").optString(0));assertEquals(1,param.optJSONArray("filename").length());
        return block("req_0",resource[0]);
    });}

    @Test public void catalogFeeLabelDoesNotSkipAuthenticatedEntitlementResolution()throws Exception{
        JSONObject[] resource={vkey()};Audio audio=new Audio();Http http=playbackHttp(resource);
        QQProvider provider=new QQProvider(new Session(COOKIE),http,audio);
        for(Track.PlaybackAccess access:new Track.PlaybackAccess[]{Track.PlaybackAccess.VIP,Track.PlaybackAccess.PURCHASE}){
            Track song=track().withPlaybackAccess(access);int before=http.calls;
            assertEquals(180000,provider.resolve(song).durationMs);assertTrue(http.calls>before);assertEquals(access,song.playbackAccess);
        }
        assertEquals(2,audio.calls);
    }

    @Test public void searchSignMatchesReferenceAndCookiesPreferCurrentWechatAccount()throws Exception{
        assertEquals("zzcf8e26805gyafigxmxjehoe02mvsjjgtwzw6f1a05f9",QQProvider.searchSign("{}"));
        assertEquals("zzce52634cbcpispnllkwa6oyvlivnkbgmhts353ac54b",QQProvider.searchSign("{\"comm\":{\"ct\":24}}"));
        assertEquals("zzcec0f6cc9pr3mun7otvbjilfrl2p32tiss6ff0c234",QQProvider.searchSign("音乐"));
        QQProvider.Auth auth=QQProvider.Auth.parse("uin=123; wxuin=o000456; login_type=2; wxopenid=fixture; wxskey=fixture-secret; analytics=drop",0,false,true);
        assertEquals("456",auth.uin);assertTrue(auth.cookies.contains("uin=456"));assertFalse(auth.cookies.contains("analytics"));
        assertEquals("456",QQProvider.Auth.parse("uin=123; wxuin=456; tmeLoginType=2; qm_keyst=fixture-secret",0,false,true).uin);
        fails(ProviderException.Kind.LOGIN_CHALLENGE,()->QQProvider.Auth.parse("uin=123; p_skey=fixture-secret",0,false,true));
        fails(ProviderException.Kind.LOGIN_CHALLENGE,()->QQProvider.Auth.parse("uin=0; qm_keyst=fixture-secret",0,false,true));
        fails(ProviderException.Kind.INVALID_RESPONSE,()->QQProvider.Auth.parse(COOKIE+"\r\nInjected: yes",0,false,true));
    }

    @Test public void webLoginPersistsOnlyVerifiedCurrentAccountAndDoesNotInventMembership()throws Exception{
        Session session=new Session();Http http=new Http((url,body,cookies)->body!=null?block("req_0",liked(0,0,0)):json("code",0,"data",json("creator",json("uin","123","nick","本人","headpic","http://q1.qlogo.cn/avatar"))));
        QQProvider provider=new QQProvider(session,http,new Audio());assertFalse(provider.account().loggedIn);
        MusicProvider.Account result=provider.acceptOfficialWebCookies(COOKIE+"; tracking=drop");assertTrue(result.loggedIn);assertEquals("123",result.id);assertEquals("本人",result.nickname);assertEquals(-1,result.vipType);assertEquals(1,session.writes);assertFalse(session.cookies.contains("tracking"));
        http.handler=(url,body,cookies)->body!=null?block("req_0",liked(0,0,0)):json("code",0,"data",json("creator",json("uin","999","nick","别的账号")));
        fails(ProviderException.Kind.LOGIN_REQUIRED,()->provider.acceptOfficialWebCookies(COOKIE));assertEquals(1,session.writes);
        http.handler=(url,body,cookies)->json("code",0,"req_0",json("code",104003));
        fails(ProviderException.Kind.LOGIN_EXPIRED,()->provider.acceptOfficialWebCookies(COOKIE));assertFalse(session.cookies.isEmpty());assertEquals(1,session.writes);
        http.handler=(url,body,cookies)->{session.clear();return block("req_0",liked(0,0,0));};
        fails(ProviderException.Kind.LOGIN_EXPIRED,()->provider.acceptOfficialWebCookies(COOKIE));assertEquals("",session.cookies);assertEquals(1,session.writes);
    }

    @Test public void wechatCurrentProfileDoesNotCompareMaskedLegacyQqOwnerToMusicUin()throws Exception{
        String musicUin="1152921504606846976";
        Session session=new Session();List<String> urls=new ArrayList<>();Http http=new Http((url,body,cookies)->{
            urls.add(url);
            if(body!=null)return block("req_0",json("songlist",new JSONArray(),"total_song_num",0,"dirinfo",json("uin",0,"creator",json("encrypt_uin","opaque-profile-id"))));
            return json("code",0,"data",json("creator",json("uin",0,"userid","opaque-profile-id","encrypt_uin","opaque-profile-id","nick","微信音乐用户")));
        });
        QQProvider provider=new QQProvider(session,http,new Audio());MusicProvider.Account account=provider.acceptOfficialWebCookies("uin=o000123; wxuin="+musicUin+"; login_type=2; wxopenid=fixture-openid; qm_keyst=fixture-secret");
        assertTrue(account.loggedIn);assertEquals(musicUin,account.id);assertEquals("微信音乐用户",account.nickname);assertEquals(1,session.writes);
        assertTrue("Own profile must be resolved by authenticated session, not a raw WeChat number",urls.get(1).contains("userid=0"));
    }
    @Test public void authenticatedWebReadsUseBothOfficialCsrfTokensAndMusicUin()throws Exception{
        Http http=new Http((url,body,cookies)->{
            if(body!=null)return block("req_0",liked(0,0,0));
            assertEquals("193485963",android.net.Uri.parse(url).getQueryParameter("g_tk"));
            assertEquals("193489332",android.net.Uri.parse(url).getQueryParameter("g_tk_new_20200303"));
            assertEquals("123",android.net.Uri.parse(url).getQueryParameter("uin"));
            return json("code",0,"data",json("creator",json("uin","123","nick","本人")));
        });
        assertTrue(new QQProvider(new Session(COOKIE+"; skey=abc; qqmusic_key=def; p_skey=ghi"),http,new Audio()).account().loggedIn);
    }
    @Test public void playlistPaginationUsesOfficialTotalsAndActualReceivedPageSize()throws Exception{
        List<Integer> offsets=new ArrayList<>();Http http=new Http((url,body,cookies)->{
            if(body!=null)return block("req_0",liked(0,0,0));
            if(url.contains("fcg_user_created_diss")){int offset=Integer.parseInt(android.net.Uri.parse(url).getQueryParameter("sin"));offsets.add(offset);return json("code",0,"data",json("totoal",3,"disslist",new JSONArray().put(json("tid",Integer.toString(offset+100),"diss_name","歌单"+offset,"song_cnt",1))));}
            return json("code",0,"data",json("totaldiss",0,"cdlist",new JSONArray()));
        });
        List<MusicProvider.Playlist> values=new QQProvider(new Session(COOKIE),http,new Audio()).playlists("123");assertEquals(Arrays.asList(0,1,2),offsets);assertEquals(4,values.size());
    }

    @Test public void loggedInAccountCanReadCollectedPlaylistWithOfficialDissnameField()throws Exception{
        // Field mapping observed in QQ's published common bundle; no private account fixture.
        Http http=new Http((url,body,cookies)->{
            if(body!=null)return block("req_0",liked(0,0,0));
            if(url.contains("homepage"))return json("code",0,"data",json("creator",json("uin","123","nick","本人")));
            if(url.contains("created_diss"))return json("code",0,"data",json("totoal",0,"disslist",new JSONArray()));
            return json("code",0,"data",json("totaldiss",1,"cdlist",new JSONArray().put(json("dissid","987","dissname","收藏歌单","imgurl","https://y.gtimg.cn/cover.jpg","song_num",3))));
        });
        QQProvider provider=new QQProvider(new Session(COOKIE),http,new Audio());assertTrue(provider.account().loggedIn);
        List<MusicProvider.Playlist> lists=provider.playlists("123");assertEquals(2,lists.size());assertEquals("收藏歌单",lists.get(1).name);assertEquals("https://y.gtimg.cn/cover.jpg",lists.get(1).coverUrl);assertFalse(lists.get(1).owned);assertEquals(3,lists.get(1).trackCount);
    }
    @Test public void officialEmptySystemFoldersDoNotInvalidateCreatedPlaylists()throws Exception{
        List<Integer> offsets=new ArrayList<>();Http http=new Http((url,body,cookies)->{
            if(body!=null)return block("req_0",liked(0,0,0));
            if(url.contains("created_diss")){int offset=Integer.parseInt(android.net.Uri.parse(url).getQueryParameter("sin"));offsets.add(offset);JSONArray rows=offset==0?new JSONArray().put(json("dirid",205,"song_cnt",0)).put(json("dirid",206,"song_cnt",0)):new JSONArray().put(json("dirid",301,"dissid",0,"tid","876","diss_name","本人歌单","song_cnt",3));return json("code",0,"data",json("totoal",3,"disslist",rows));}
            return json("code",0,"data",json("totaldiss",0,"cdlist",new JSONArray()));
        });
        List<MusicProvider.Playlist> lists=new QQProvider(new Session(COOKIE),http,new Audio()).playlists("123");assertEquals(Arrays.asList(0,2),offsets);assertEquals(2,lists.size());assertEquals("876",lists.get(1).id);assertTrue(lists.get(1).owned);
    }
    @Test public void nonemptySpecialFoldersRemainVisibleWithoutPretendingToBeOrdinaryPlaylists()throws Exception{
        Http http=new Http((url,body,cookies)->{
            if(body!=null)return block("req_0",liked(0,0,0));
            if(url.contains("created_diss"))return json("code",0,"data",json("totoal",3,"disslist",new JSONArray().put(json("dirid",205,"tid",0,"song_cnt",2)).put(json("dirid",206,"song_cnt",1)).put(json("tid","876","dissname","普通歌单","song_cnt",0))));
            return json("code",0,"data",json("totaldiss",1,"cdlist",new JSONArray().put(json("dirid",201,"dissid","987","dissname","已收藏的公开歌单","imgurl","//y.gtimg.cn/cover.jpg"))));
        });
        QQProvider provider=new QQProvider(new Session(COOKIE),http,new Audio());List<MusicProvider.Playlist> lists=provider.playlists("123");assertEquals(5,lists.size());assertEquals("qq-system-205",lists.get(1).id);assertEquals(2,lists.get(1).trackCount);assertEquals("qq-system-206",lists.get(2).id);assertEquals("876",lists.get(3).id);assertEquals("987",lists.get(4).id);assertEquals("https://y.gtimg.cn/cover.jpg",lists.get(4).coverUrl);assertEquals(-1,lists.get(4).trackCount);
        int calls=http.calls;for(String id:new String[]{lists.get(1).id,lists.get(2).id})try{provider.playlistTracks(id);fail("Special folders must not masquerade as readable standard playlists");}catch(ProviderException failure){assertEquals(ProviderException.Kind.UNAVAILABLE,failure.kind);assertTrue(failure.getMessage().contains("系统目录"));}assertEquals(calls,http.calls);
    }
    @Test public void missingPlaylistArrayIsAStageSpecificFailureNotAnEmptyLibrary()throws Exception{
        Session session=new Session(COOKIE);Http http=new Http((url,body,cookies)->body!=null?block("req_0",liked(0,0,0)):json("code",0,"data",json("totoal",0)));
        try{new QQProvider(session,http,new Audio()).playlists("123");fail("Missing array does not prove no playlists");}catch(ProviderException failure){assertEquals(ProviderException.Kind.INVALID_RESPONSE,failure.kind);assertTrue(failure.getMessage().contains("本人歌单"));assertTrue(failure.getMessage().contains("data.disslist"));assertEquals(COOKIE,session.cookies);}
    }
    @Test public void malformedCollectedPlaylistReportsItsParsingStageWithoutPrivateValues()throws Exception{
        Http http=new Http((url,body,cookies)->body!=null?block("req_0",liked(0,0,0)):url.contains("created_diss")?json("code",0,"data",json("totoal",0,"disslist",new JSONArray())):json("code",0,"data",json("totaldiss",1,"cdlist",new JSONArray().put(json("dissid","987","privateField","fixture-private-title")))));
        try{new QQProvider(new Session(COOKIE),http,new Audio()).playlists("123");fail("Incomplete playlist must fail");}catch(ProviderException failure){assertEquals(ProviderException.Kind.INVALID_RESPONSE,failure.kind);assertTrue(failure.getMessage(),failure.getMessage().contains("收藏歌单"));assertTrue(failure.getMessage(),failure.getMessage().contains("名称"));assertFalse(failure.getMessage().contains("fixture-private-title"));assertFalse(failure.getMessage().contains("fixture-secret"));}
    }

    @Test public void nestedRevocationClearsOnlyMatchingSessionWhileNetworkErrorsPreserveIt()throws Exception{
        Session session=new Session(COOKIE);Http http=new Http((u,b,c)->{throw new ProviderException(ProviderException.Kind.NETWORK,"连接失败");});QQProvider provider=new QQProvider(session,http,new Audio());
        fails(ProviderException.Kind.NETWORK,provider::account);assertEquals(COOKIE,session.cookies);
        http.handler=(u,b,c)->json("code",0,"req_0",json("code",0,"data",json("subcode",104003)));
        fails(ProviderException.Kind.LOGIN_EXPIRED,provider::account);assertEquals("",session.cookies);
        session.writeCookies(COOKIE);http.handler=(u,b,c)->{session.writeCookies("uin=456; qm_keyst=new-account");return block("req_0",liked(0,0,0));};
        fails(ProviderException.Kind.LOGIN_EXPIRED,provider::account);assertTrue(session.cookies.contains("new-account"));
    }

    @Test public void cancellingOfficialLoginBeforeValidatedResponseReturnsCannotPersistCredentials()throws Exception{
        Session session=new Session();java.util.concurrent.CountDownLatch validating=new java.util.concurrent.CountDownLatch(1),release=new java.util.concurrent.CountDownLatch(1);
        Http http=new Http((u,b,c)->{
            if(b!=null)return block("req_0",liked(0,0,0));
            validating.countDown();try{if(!release.await(2,java.util.concurrent.TimeUnit.SECONDS))throw new AssertionError("Release missing");}catch(InterruptedException failure){throw new AssertionError(failure);}
            return json("code",0,"data",json("creator",json("uin","123","nick","本人")));
        });
        QQProvider provider=new QQProvider(session,http,new Audio());QQProvider.LoginAttempt attempt=new QQProvider.LoginAttempt();java.util.concurrent.atomic.AtomicReference<Throwable> outcome=new java.util.concurrent.atomic.AtomicReference<>();
        Thread thread=new Thread(()->{try{provider.acceptOfficialWebCookies(COOKIE,attempt);}catch(Throwable failure){outcome.set(failure);}});
        thread.start();assertTrue(validating.await(2,java.util.concurrent.TimeUnit.SECONDS));attempt.cancel();release.countDown();thread.join(2000);assertFalse(thread.isAlive());
        assertTrue(outcome.get() instanceof ProviderException);assertEquals(ProviderException.Kind.LOGIN_EXPIRED,((ProviderException)outcome.get()).kind);assertEquals(0,session.writes);assertEquals("",session.cookies);
        int before=http.calls;fails(ProviderException.Kind.LOGIN_EXPIRED,()->provider.acceptOfficialWebCookies(COOKIE,attempt));assertEquals(before,http.calls);
    }

    @Test public void officialLoginActivityFinishInvalidatesAttemptBeforeOnDestroy()throws Exception{
        org.robolectric.android.controller.ActivityController<QQMusicLoginActivity> controller=org.robolectric.Robolectric.buildActivity(QQMusicLoginActivity.class).create().start().resume();QQMusicLoginActivity activity=controller.get();
        java.lang.reflect.Field field=QQMusicLoginActivity.class.getDeclaredField("loginAttempt");field.setAccessible(true);QQProvider.LoginAttempt attempt=(QQProvider.LoginAttempt)field.get(activity);
        activity.finish();assertTrue(activity.isFinishing());Session session=new Session();Http http=new Http((u,b,c)->{throw new AssertionError("Canceled attempt must not start verification");});QQProvider provider=new QQProvider(session,http,new Audio());
        fails(ProviderException.Kind.LOGIN_EXPIRED,()->provider.acceptOfficialWebCookies(COOKIE,attempt));assertEquals(0,session.writes);assertEquals(0,http.calls);controller.pause().stop().destroy();
    }

    @Test public void createdCollectedAndLikedListsArePagedWithoutMixingAccounts()throws Exception{
        Session session=new Session(COOKIE);List<Integer> offsets=new ArrayList<>();Http http=new Http((url,body,cookies)->{
            if(body!=null)return block("req_0",liked(0,1,101));
            boolean collected=url.contains("order_asset"),second=url.contains("sin=200");if(!collected)offsets.add(second?200:0);
            JSONArray rows=new JSONArray();int count=collected?1:second?1:200;for(int i=0;i<count;i++)rows.put(json("dissid",collected?900:second?201:i+1,"diss_name",collected?"收藏歌单":"自建歌单","song_cnt",3));
            return json("code",0,"data",json(collected?"cdlist":"disslist",rows,"total",collected?1:201));
        });
        QQProvider provider=new QQProvider(session,http,new Audio());List<MusicProvider.Playlist> lists=provider.playlists("123");assertEquals(203,lists.size());assertEquals(QQProvider.LIKED_PLAYLIST_ID,lists.get(0).id);assertEquals(101,lists.get(0).trackCount);assertTrue(lists.get(1).owned);assertFalse(lists.get(202).owned);assertEquals(Arrays.asList(0,200),offsets);
        int calls=http.calls;fails(ProviderException.Kind.LOGIN_REQUIRED,()->provider.playlists("456"));assertEquals(calls,http.calls);
        offsets.clear();http.handler=(u,b,c)->{JSONObject param=b.optJSONObject("req_0").optJSONObject("param");int offset=param.optInt("song_begin");offsets.add(offset);assertEquals(100,param.optInt("song_num"));return block("req_0",liked(offset,offset==0?100:1,101));};
        List<Track> songs=provider.likedTracks("123");assertEquals(101,songs.size());assertEquals("Song100",songs.get(100).id);assertEquals("qq",songs.get(100).provider);assertEquals(Arrays.asList(0,100),offsets);
    }

    @Test public void playlistTruncationAndRepeatedPagesFailRatherThanSavePartialList()throws Exception{
        Session session=new Session(COOKIE);Http http=new Http((u,b,c)->json("code",0,"cdlist",new JSONArray().put(json("disstid","9","songlist",new JSONArray().put(song("Song01")),"total_song_num",2))));QQProvider provider=new QQProvider(session,http,new Audio());
        fails(ProviderException.Kind.UNAVAILABLE,()->provider.playlistTracks("9"));
        http.handler=(u,b,c)->block("req_0",liked(0,100,201));fails(ProviderException.Kind.UNAVAILABLE,()->provider.likedTracks("123"));assertEquals(3,http.calls);
        http.handler=(u,b,c)->block("req_0",json("songlist",new JSONArray(),"hasmore",1));fails(ProviderException.Kind.UNAVAILABLE,()->provider.likedTracks("123"));
        http.handler=(u,b,c)->json("code",0,"cdlist",new JSONArray().put(json("disstid","9","songlist",new JSONArray().put(song("Song01")),"song_count",2)));fails(ProviderException.Kind.UNAVAILABLE,()->provider.playlistTracks("9"));
        http.handler=(u,b,c)->block("req_0",json("songlist",new JSONArray().put(song("Song01")),"songlist_size",2,"total_song_num",2));fails(ProviderException.Kind.UNAVAILABLE,()->provider.likedTracks("123"));
        http.handler=(u,b,c)->json("code",0,"cdlist",new JSONArray().put(json("disstid","8","songlist",new JSONArray(),"total_song_num",0)));fails(ProviderException.Kind.INVALID_RESPONSE,()->provider.playlistTracks("9"));
    }

    @Test public void fullSearchUsesObservedMobileSchemaAndKeepsUnauthenticatedSearchAnonymous()throws Exception{
        Http http=new Http((url,body,cookies)->{assertEquals("",cookies);assertTrue(url.endsWith("sign="+QQProvider.searchSign(body.toString())));JSONObject req=body.optJSONObject("req"),param=req.optJSONObject("param");assertEquals("DoSearchForQQMusicMobile",req.optString("method"));assertEquals(2,param.optInt("page_num"));assertEquals(3,param.optInt("num_per_page"));assertEquals("陈奕迅",param.optString("query"));return block("req",json("code",0,"body",json("item_song",new JSONArray().put(song("Song01"))),"meta",json("sum",973)));});
        QQProvider provider=new QQProvider(new Session(),http,new Audio());MusicProvider.Page<Track> page=provider.searchTracks("陈奕迅",3,3);assertEquals(973,page.total);assertEquals(6,page.nextOffset);assertTrue(page.hasMore);assertEquals("qq://song/Song01",page.items.get(0).uri);
        fails(ProviderException.Kind.INVALID_RESPONSE,()->provider.searchTracks("词",1,3));assertEquals(1,http.calls);
    }

    @Test public void coherentCookieSnapshotNeverMixesNewWechatIdentityWithOldSubdomainKey(){
        String current="wxuin=1152921504606846976; wxopenid=fixture-openid; qm_keyst=current-fixture",old="uin=123; qm_keyst=stale-fixture";
        assertEquals(current,QQMusicLoginActivity.selectMusicCookies(current,old,old));
        assertEquals("wxuin=1152921504606846976; wxopenid=fixture-openid",QQMusicLoginActivity.selectMusicCookies("wxuin=1152921504606846976; wxopenid=fixture-openid",old));
    }
    @Test public void genuineWechatOwnerMismatchStillRejectsAuthorization()throws Exception{
        Session session=new Session();Http http=new Http((u,b,c)->block("req_0",json("songlist",new JSONArray(),"dirinfo",json("uin","1152921504606846977"))));
        QQProvider provider=new QQProvider(session,http,new Audio());fails(ProviderException.Kind.LOGIN_REQUIRED,()->provider.acceptOfficialWebCookies("wxuin=1152921504606846976; login_type=2; wxopenid=fixture-openid; qm_keyst=fixture-secret"));assertEquals(0,session.writes);
    }
    @Test public void hiddenNumericIdentityDoesNotHideDifferentAuthenticatedProfileAddresses()throws Exception{
        Session session=new Session();Http http=new Http((u,b,c)->b!=null?block("req_0",json("songlist",new JSONArray(),"dirinfo",json("uin",0,"creator",json("encrypt_uin","profile-one")))):json("code",0,"data",json("creator",json("uin",0,"encrypt_uin","profile-two","nick","其他账号"))));
        QQProvider provider=new QQProvider(session,http,new Audio());fails(ProviderException.Kind.LOGIN_REQUIRED,()->provider.acceptOfficialWebCookies("wxuin=1152921504606846976; login_type=2; wxopenid=fixture-openid; qm_keyst=fixture-secret"));assertEquals(0,session.writes);
    }
    @Test public void searchUsesAuthorizedAccountAndRestrictedEmptyIsNotNoResults()throws Exception{
        Session session=new Session(COOKIE);Http http=new Http((u,b,c)->{assertTrue(c.contains("fixture-secret"));assertEquals("123",b.optJSONObject("comm").optString("uin"));assertEquals("fixture-secret",b.optJSONObject("comm").optString("authst"));assertTrue(u.endsWith("sign="+QQProvider.searchSign(b.toString())));return block("req",json("body",json("item_song",new JSONArray()),"meta",json("sum",0,"is_filter",-9)));});
        fails(ProviderException.Kind.UNAVAILABLE,()->new QQProvider(session,http,new Audio()).searchTracks("词",0,20));
        http.handler=(u,b,c)->{assertEquals(2,b.optJSONObject("req").optJSONObject("param").optInt("search_type"));return block("req",json("body",json("item_album",new JSONArray().put(json("albumMid","Album01","albumName","专辑","singerName","艺人"))),"meta",json("sum",1)));};
        MusicProvider.Page<MusicProvider.Album> result=new QQProvider(session,http,new Audio()).searchAlbums("专辑",0,20);assertEquals("Album01",result.items.get(0).id);assertFalse(result.hasMore);
    }
    @Test public void albumReadsAllPagesAndCanonicalizesNumericId()throws Exception{
        List<Integer> offsets=new ArrayList<>();Http http=new Http((u,b,c)->{
            assertEquals("",c);
            if(b.has("album")){JSONObject param=b.optJSONObject("album").optJSONObject("param");assertEquals("22276",param.optString("albumID"));assertEquals("",param.optString("albumMid"));return block("album",json("basicInfo",json("albumMid","Album01","albumID",22276,"albumName","整张专辑","pmid","Album01_5"),"singer",json("singerList",new JSONArray().put(json("mid","Singer01","name","艺人")))));}
            JSONObject param=b.optJSONObject("tracks").optJSONObject("param");int offset=param.optInt("begin");assertEquals("Album01",param.optString("albumMid"));offsets.add(offset);JSONArray rows=new JSONArray();for(int i=0;i<(offset==0?200:3);i++)rows.put(json("songInfo",song("Song"+(offset+i))));return block("tracks",json("albumMid","Album01","curBegin",offset,"totalNum",203,"songList",rows));
        });
        MusicProvider.Album album=new QQProvider(new Session(),http,new Audio()).album("22276");assertEquals(Arrays.asList(0,200),offsets);assertEquals(203,album.tracks.size());assertEquals("Album01",album.id);assertEquals("Song202",album.tracks.get(202).id);for(Track song:album.tracks){assertEquals("Album01",song.albumId);assertEquals("整张专辑",song.album);assertEquals("艺人",song.albumArtist);assertEquals("Singer01",song.albumArtistId);}assertTrue(album.coverUrl.contains("Album01_5"));
    }
    @Test public void albumRejectsRepeatedPagesWrongIdentityAndChangedTotals()throws Exception{
        Http http=new Http((u,b,c)->b.has("album")?block("album",json("basicInfo",json("albumMid","Album01","albumName","专辑"))):block("tracks",json("albumMid","Album01","totalNum",2,"songList",new JSONArray().put(song("Song01")))));
        QQProvider provider=new QQProvider(new Session(),http,new Audio());fails(ProviderException.Kind.UNAVAILABLE,()->provider.album("Album01"));
        http.handler=(u,b,c)->b.has("album")?block("album",json("basicInfo",json("albumMid","Album01","albumName","专辑"))):block("tracks",json("albumMid","OtherAlbum","totalNum",0,"songList",new JSONArray()));fails(ProviderException.Kind.INVALID_RESPONSE,()->provider.album("Album01"));
        http.handler=(u,b,c)->{if(b.has("album"))return block("album",json("basicInfo",json("albumMid","Album01","albumName","专辑")));int offset=b.optJSONObject("tracks").optJSONObject("param").optInt("begin");return block("tracks",json("albumMid","Album01","curBegin",offset,"totalNum",offset==0?2:3,"songList",new JSONArray().put(song("Song"+offset))));};fails(ProviderException.Kind.UNAVAILABLE,()->provider.album("Album01"));
    }
    @Test public void oldPictureVersionIdResolvesOnlyThroughCanonicalAlbumIdentity()throws Exception{
        String albumMid="004Z85XP1c25b7";Http http=new Http((u,b,c)->{
            if(b.has("album")){assertEquals(albumMid,b.optJSONObject("album").optJSONObject("param").optString("albumMid"));return block("album",json("basicInfo",json("albumMid",albumMid,"albumName","专辑")));}
            return block("tracks",json("albumMid",albumMid,"totalNum",0,"songList",new JSONArray()));
        });
        QQProvider provider=new QQProvider(new Session(),http,new Audio());assertEquals(albumMid,provider.album(albumMid+"_5").id);int calls=http.calls;fails(ProviderException.Kind.INVALID_RESPONSE,()->provider.album("Album01_arbitrary"));assertEquals(calls,http.calls);
    }
    @Test public void artistProvidesRealBiographyRecommendedSongsAndPagedAlbumMetadata()throws Exception{
        Http http=new Http((u,b,c)->{
            if(b.has("singer"))return block("singer",json("singer_list",new JSONArray().put(json("basic_info",json("singer_mid","Singer01","name","艺人"),"wiki","<info><desc><![CDATA[真实简介<br>第二段]]></desc></info>","pic",json("pic","https://y.gtimg.cn/artist.jpg")))));
            if(b.has("songs"))return block("songs",json("singerMid","Singer01","totalNum",8,"songList",new JSONArray().put(json("songInfo",song("Song01")))));
            JSONObject param=b.optJSONObject("albums").optJSONObject("param");int begin=param.optInt("begin");JSONArray rows=new JSONArray();for(int i=begin;i<Math.min(23,begin+param.optInt("num"));i++)rows.put(json("albumMid","Album"+i,"albumName","专辑"+i,"singerName","艺人","pmid","Album"+i+"_1"));return block("albums",json("singerMid","Singer01","total",23,"albumList",rows));
        });
        QQProvider provider=new QQProvider(new Session(),http,new Audio());MusicProvider.Artist artist=provider.artist("Singer01");assertEquals("艺人",artist.name);assertTrue(artist.description.contains("真实简介"));assertFalse(artist.description.contains("<"));assertEquals(1,artist.topTracks.size());assertEquals(20,artist.albums.size());assertEquals("https://y.gtimg.cn/artist.jpg",artist.avatarUrl);
        MusicProvider.Page<MusicProvider.Album> rest=provider.artistAlbums("Singer01",20,20);assertEquals(3,rest.items.size());assertEquals(23,rest.total);assertEquals(23,rest.nextOffset);assertFalse(rest.hasMore);assertEquals("Singer01",rest.items.get(2).artistId);
    }
    @Test public void trackAlbumIdentityNeverUsesPictureVersionAndLegacyAliasesConverge()throws Exception{
        JSONObject modern=song("Song01"),legacy=song("Song02"),numeric=song("Song03");legacy.put("album",json("pmid","Album01_5","name","测试专辑"));legacy.put("albummid","Album01");numeric.put("album",json("id",22276,"pmid","Picture01_5","name","专辑"));
        Track first=QQProvider.track(modern),second=QQProvider.track(legacy);assertEquals(first.albumId,second.albumId);assertEquals(first.albumKey(),second.albumKey());assertEquals("22276",QQProvider.track(numeric).albumId);assertTrue(second.artworkUri.contains("Album01_5"));
    }

    @Test public void playbackAcceptsOnlyGrantedMatchingFullMp3AndRejectsTrials()throws Exception{
        Session session=new Session(COOKIE);JSONObject[] resource={vkey()};Audio audio=new Audio();QQProvider provider=new QQProvider(session,playbackHttp(resource),audio);
        PlaybackResource result=provider.resolve(track());assertEquals(180000,result.durationMs);assertTrue(result.url.startsWith("https://ws.stream.qqmusic.qq.com/M500Media01.mp3"));assertEquals(1,audio.calls);assertFalse(result.headers.containsKey("Cookie"));
        audio.duration=30000;fails(ProviderException.Kind.PREVIEW_ONLY,()->provider.resolve(track()));
        audio.duration=-1;fails(ProviderException.Kind.UNAVAILABLE,()->provider.resolve(track()));
        audio.duration=180000;resource[0].optJSONArray("midurlinfo").optJSONObject(0).put("trial",true);int before=audio.calls;fails(ProviderException.Kind.PREVIEW_ONLY,()->provider.resolve(track()));assertEquals(before,audio.calls);
        resource[0]=vkey();resource[0].optJSONArray("midurlinfo").optJSONObject(0).put("purl","");fails(ProviderException.Kind.UNAVAILABLE,()->provider.resolve(track()));assertEquals(before,audio.calls);
        resource[0]=vkey();resource[0].optJSONArray("midurlinfo").optJSONObject(0).put("songmid","OtherSong");fails(ProviderException.Kind.INVALID_RESPONSE,()->provider.resolve(track()));assertEquals(before,audio.calls);
    }

    @Test public void playbackRejectsForeignUrlsForeignIdentitiesAndLateAccountSwitch()throws Exception{
        Session session=new Session(COOKIE);JSONObject[] resource={vkey()};Audio audio=new Audio();Http http=playbackHttp(resource);QQProvider provider=new QQProvider(session,http,audio);
        resource[0].put("sip",new JSONArray().put("https://unrelated.example/"));fails(ProviderException.Kind.UNAVAILABLE,()->provider.resolve(track()));assertEquals(0,audio.calls);
        resource[0]=vkey();resource[0].optJSONArray("midurlinfo").optJSONObject(0).put("purl","https://ws.stream.qqmusic.qq.com/M500Media01.mp3?vkey=fixture-vkey");fails(ProviderException.Kind.INVALID_RESPONSE,()->provider.resolve(track()));assertEquals(0,audio.calls);
        resource[0]=vkey();audio.onCall=session::clear;fails(ProviderException.Kind.LOGIN_EXPIRED,()->provider.resolve(track()));assertEquals("",session.cookies);
        int before=http.calls;fails(ProviderException.Kind.LOGIN_REQUIRED,()->provider.resolve(track()));assertEquals(before,http.calls);
        Track foreign=new Track("Song01","测试","歌手","专辑","网易云音乐","netease://song/Song01","",180000,1,"歌手","","","netease","Album01","Singer01");fails(ProviderException.Kind.INVALID_RESPONSE,()->provider.resolve(foreign));assertEquals(before,http.calls);
    }

    @Test public void transportScopesForbidCredentialLeaksAndRequireHttps()throws Exception{
        QQHttp.validateApiUrl("https://u.y.qq.com/cgi-bin/musicu.fcg");QQAudioProbe.validateAudioUrl("https://ws.stream.qqmusic.qq.com/M500Song.mp3?vkey=x");
        for(String url:new String[]{"http://u.y.qq.com/a","https://u.y.qq.com.attacker.test/a","https://user@u.y.qq.com/a","https://u.y.qq.com:8443/a","https://u.y.qq.com/a#fragment"})fails(ProviderException.Kind.INVALID_RESPONSE,()->QQHttp.validateApiUrl(url));
        for(String url:new String[]{"http://ws.stream.qqmusic.qq.com/a","https://ws.stream.qqmusic.qq.com.attacker.test/a","https://y.qq.com/a","https://user@ws.stream.qqmusic.qq.com/a"})fails(ProviderException.Kind.UNAVAILABLE,()->QQAudioProbe.validateAudioUrl(url));
    }
    @Test public void httpAndResponseFailuresRemainActionableWithoutEchoingResponseContents()throws Exception{
        QQHttp.requireSuccess(200);fails(ProviderException.Kind.LOGIN_EXPIRED,()->QQHttp.requireSuccess(401));fails(ProviderException.Kind.LOGIN_CHALLENGE,()->QQHttp.requireSuccess(403));fails(ProviderException.Kind.LOGIN_CHALLENGE,()->QQHttp.requireSuccess(302));fails(ProviderException.Kind.RATE_LIMITED,()->QQHttp.requireSuccess(429));fails(ProviderException.Kind.NETWORK,()->QQHttp.requireSuccess(503));
        fails(ProviderException.Kind.LOGIN_CHALLENGE,()->QQHttp.parseBody("<html>fixture-secret account verification</html>"));fails(ProviderException.Kind.INVALID_RESPONSE,()->QQHttp.parseBody(""));fails(ProviderException.Kind.INVALID_RESPONSE,()->QQHttp.parseBody("fixture-secret"));assertEquals(0,QQHttp.parseBody("callback({\"code\":0})").optInt("code"));
        assertTrue(QQHttp.networkFailure(new java.net.SocketTimeoutException("fixture-secret")).getMessage().contains("超时"));assertTrue(QQHttp.networkFailure(new java.net.UnknownHostException("fixture-secret")).getMessage().contains("DNS"));assertTrue(QQHttp.networkFailure(new javax.net.ssl.SSLException("fixture-secret")).getMessage().contains("安全连接"));
    }
    @Test public void serviceDenialIncludesReadStageAndSafeCodeWithoutDeletingTheSession()throws Exception{
        Session session=new Session(COOKIE);Http http=new Http((u,b,c)->json("code",0,"req_0",json("code",4000,"message","fixture-secret")));
        try{new QQProvider(session,http,new Audio()).account();fail("Expected denied read");}catch(ProviderException failure){assertEquals(ProviderException.Kind.UNAVAILABLE,failure.kind);assertTrue(failure.getMessage().contains("读取喜欢列表"));assertTrue(failure.getMessage().contains("4000"));assertFalse(failure.getMessage().contains("fixture-secret"));}assertEquals(COOKIE,session.cookies);
    }

    @Test public void mp3ProbeReadsRealFrameTimelineAndRefusesUnknownOrMalformedBodies(){
        byte[] cbr=new byte[417*8];for(int at=0;at<cbr.length;at+=417){cbr[at]=(byte)0xff;cbr[at+1]=(byte)0xfb;cbr[at+2]=(byte)0x90;}
        assertEquals(180000,QQAudioProbe.mp3Duration(cbr,2880000));assertEquals(30000,QQAudioProbe.mp3Duration(cbr,480000));assertEquals(-1,QQAudioProbe.mp3Duration(cbr,-1));
        byte[] xing=cbr.clone();for(int i=0;i<4;i++)xing[36+i]=(byte)"Xing".charAt(i);xing[43]=1;int frames=6891;for(int i=0;i<4;i++)xing[44+i]=(byte)(frames>>>(24-i*8));assertEquals(frames*1152L*1000/44100,QQAudioProbe.mp3Duration(xing,-1));
        assertEquals(-1,QQAudioProbe.mp3Duration("<html>permission denied</html>".getBytes(),3000000));
        byte[] invalid=cbr.clone();invalid[417+2]=(byte)0xa0;assertEquals(-1,QQAudioProbe.mp3Duration(invalid,2880000));
        byte[] id3=new byte[20];id3[0]='I';id3[1]='D';id3[2]='3';id3[6]=(byte)0x80;assertEquals(-1,QQAudioProbe.mp3Duration(id3,2880000));
    }

    @Test public void sessionStoreIsEncryptedAccountScopedAndTamperFailsClosed()throws Exception{
        Context context=RuntimeEnvironment.getApplication();SharedPreferences prefs=context.getSharedPreferences("qq-session-fixture",Context.MODE_PRIVATE);prefs.edit().clear().commit();SecretKeySpec key=new SecretKeySpec(new byte[32],"AES");QQSessionStore store=new QQSessionStore(prefs,()->key);
        store.writeCookies(COOKIE);String encrypted=prefs.getString("encrypted_v1","");assertFalse(encrypted.contains("fixture-secret"));assertEquals(COOKIE,new QQSessionStore(prefs,()->key).readCookies());long epoch=store.generation();store.writeCookies(COOKIE);assertEquals(epoch,store.generation());store.clear();assertTrue(store.generation()>epoch);assertFalse(prefs.contains("encrypted_v1"));
        store.writeCookies(COOKIE);byte[] wrong=new byte[32];wrong[0]=1;QQSessionStore other=new QQSessionStore(prefs,()->new SecretKeySpec(wrong,"AES"));fails(ProviderException.Kind.LOGIN_EXPIRED,other::readCookies);assertFalse(prefs.contains("encrypted_v1"));assertEquals("",other.readCookies());
        // NetEase and QQ use different AAD even when a test intentionally supplies the same key and ciphertext.
        store=new QQSessionStore(prefs,()->key);store.writeCookies(COOKIE);SharedPreferences netease=context.getSharedPreferences("netease-cross-session-fixture",Context.MODE_PRIVATE);netease.edit().clear().putString("encrypted_v1",prefs.getString("encrypted_v1","")).commit();SecureSessionStore foreign=new SecureSessionStore(netease,()->key);fails(ProviderException.Kind.LOGIN_EXPIRED,foreign::readCookies);assertTrue(prefs.contains("encrypted_v1"));
    }
}
