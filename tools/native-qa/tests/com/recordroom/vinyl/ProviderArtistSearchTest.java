package com.recordroom.vinyl;
import java.util.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
/** Real artist-resource schemas, synthetic HTTP/session fixtures only. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=34)
public class ProviderArtistSearchTest {
 static JSONObject j(Object... pairs){return AppleMusicIntegrationTest.json(pairs);}
 static JSONObject qq(JSONObject data){return j("code",0,"req",j("code",0,"data",data));}
 @Test public void qqArtistSearchUsesArtistTypeRealMidPortraitAndNextPage()throws Exception{
  QQProviderTest.Http http=new QQProviderTest.Http((url,body,cookie)->{
   JSONObject param=body.optJSONObject("req").optJSONObject("param");assertEquals(1,param.optInt("search_type"));assertEquals(2,param.optInt("page_num"));assertEquals(3,param.optInt("num_per_page"));assertEquals("刘德华",param.optString("query"));
   return qq(j("meta",j("sum",4,"is_filter",0),"body",j("singer",new JSONArray().put(j("singerMID","003aQYLo2x8izP","singerName","刘德华","singerPic","http://y.gtimg.cn/music/photo_new/T001R150x150M000003aQYLo2x8izP_4.jpg")))));
  });
  MusicProvider p=new QQProvider(new QQProviderTest.Session(),http,new QQProviderTest.Audio());MusicProvider.Page<MusicProvider.Artist> page=p.searchArtists("刘德华",3,3);
  assertTrue(p.supportsArtistSearch());assertEquals(4,page.total);assertEquals(4,page.nextOffset);assertFalse(page.hasMore);assertEquals("003aQYLo2x8izP",page.items.get(0).id);assertEquals("刘德华",page.items.get(0).name);assertTrue(page.items.get(0).avatarUrl.startsWith("https://y.gtimg.cn/"));assertTrue(page.items.get(0).topTracks.isEmpty());assertTrue(page.items.get(0).albums.isEmpty());
 }
 @Test public void neteaseSearchReturnsArtistIdentityAndPortraitWithoutSongInference()throws Exception{
  NeteaseProviderTest.Http http=new NeteaseProviderTest.Http((path,args,weapi,cookie)->{
   assertEquals("/api/cloudsearch/pc",path);assertEquals(100,args.optInt("type"));assertEquals(3,args.optInt("offset"));
   return new NeteaseHttp.Response(j("code",200,"result",j("artistCount",5,"hasMore",true,"artists",new JSONArray().put(j("id",3691,"name","刘德华","img1v1Url","https://p2.music.126.net/artist.jpg","picUrl","https://p2.music.126.net/backdrop.jpg")))),cookie);
  });
  MusicProvider p=new NeteaseProvider(new NeteaseProviderTest.Session(),http);MusicProvider.Page<MusicProvider.Artist> page=p.searchArtists("刘德华",3,1);
  assertTrue(p.supportsArtistSearch());assertEquals(5,page.total);assertEquals(4,page.nextOffset);assertTrue(page.hasMore);assertEquals("3691",page.items.get(0).id);assertEquals("https://p2.music.126.net/artist.jpg",page.items.get(0).avatarUrl);assertTrue(page.items.get(0).topTracks.isEmpty());
 }
 @Test public void appleCatalogArtistSearchUsesArtistsTypeAndServerNextOffset()throws Exception{
  AppleMusicSessionStore store=AppleMusicIntegrationTest.store(AppleMusicIntegrationTest.prefs());store.importDeveloperToken(AppleMusicIntegrationTest.jwt());AppleMusicIntegrationTest.authorize(store);
  AppleMusicIntegrationTest.Http http=new AppleMusicIntegrationTest.Http((path,headers)->{
   assertTrue(path,path.startsWith("/v1/catalog/cn/search?types=artists&"));assertTrue(path,path.endsWith("&limit=2&offset=0"));assertFalse(headers.containsKey("Music-User-Token"));
   return new AppleMusicHttp.Response(200,j("results",j("artists",j("data",new JSONArray().put(j("id","137938148","type","artists","attributes",j("name","陈奕迅","artwork",j("url","https://is1-ssl.mzstatic.com/image/thumb/artist/{w}x{h}bb.jpg")))),"next","/v1/catalog/cn/search?types=artists&term=Eason&offset=2","meta",j("total",5)))));
  });
  MusicProvider p=new AppleMusicProvider(store,http);MusicProvider.Page<MusicProvider.Artist> page=p.searchArtists("Eason",0,2);
  assertTrue(p.supportsArtistSearch());assertEquals(2,page.nextOffset);assertTrue(page.hasMore);assertEquals("137938148",page.items.get(0).id);assertEquals("陈奕迅",page.items.get(0).name);assertTrue(page.items.get(0).avatarUrl.contains("mzstatic.com"));assertTrue(page.items.get(0).albums.isEmpty());
 }
 interface Action{void run()throws Exception;}
 static void fails(ProviderException.Kind kind,Action action)throws Exception{try{action.run();fail("Expected "+kind);}catch(ProviderException f){assertEquals(kind,f.kind);}}
 @Test public void qqMalformedRestrictedAndStaleArtistResultsCannotBecomeEmptySuccess()throws Exception{
  QQProviderTest.Session session=new QQProviderTest.Session();QQProviderTest.Http http=new QQProviderTest.Http((u,b,c)->qq(j("body",j("item_song",new JSONArray()),"meta",j("sum",0))));QQProvider p=new QQProvider(session,http,new QQProviderTest.Audio());
  fails(ProviderException.Kind.INVALID_RESPONSE,()->p.searchArtists("artist",0,2));
  http.handler=(u,b,c)->qq(j("body",j("singer",new JSONArray()),"meta",j("sum",0,"is_filter",-9)));fails(ProviderException.Kind.UNAVAILABLE,()->p.searchArtists("artist",0,2));
  JSONObject same=j("singerMID","Artist01","singerName","Real artist","singerPic","https://attacker.example/avatar.jpg");
  http.handler=(u,b,c)->qq(j("body",j("singer",new JSONArray().put(same).put(same)),"meta",j("sum",2)));fails(ProviderException.Kind.INVALID_RESPONSE,()->p.searchArtists("artist",0,2));
  http.handler=(u,b,c)->qq(j("body",j("singer",new JSONArray().put(same)),"meta",j("sum",1)));assertEquals("",p.searchArtists("artist",0,2).items.get(0).avatarUrl);
  session.writeCookies("uin=123; qm_keyst=fixture-only");http.handler=(u,b,c)->{session.clear();return qq(j("body",j("singer",new JSONArray().put(same)),"meta",j("sum",1)));};fails(ProviderException.Kind.LOGIN_EXPIRED,()->p.searchArtists("artist",0,2));
 }
 @Test public void neteaseEmptyMalformedAndNonadvancingArtistPagesRemainDistinct()throws Exception{
  NeteaseProviderTest.Session session=new NeteaseProviderTest.Session();NeteaseProviderTest.Http http=new NeteaseProviderTest.Http((p,a,w,c)->new NeteaseHttp.Response(j("code",200,"result",j("artistCount",0)),c));NeteaseProvider p=new NeteaseProvider(session,http);assertTrue(p.searchArtists("none",0,3).items.isEmpty());
  http.handler=(path,a,w,c)->new NeteaseHttp.Response(j("code",200,"result",j("artistCount",2)),c);fails(ProviderException.Kind.INVALID_RESPONSE,()->p.searchArtists("artist",0,3));
  http.handler=(path,a,w,c)->new NeteaseHttp.Response(j("code",200,"result",j("hasMore",true,"artists",new JSONArray())),c);fails(ProviderException.Kind.INVALID_RESPONSE,()->p.searchArtists("artist",0,3));
  JSONObject malformed=j("id",3691,"name"," ");http.handler=(path,a,w,c)->new NeteaseHttp.Response(j("code",200,"result",j("artistCount",1,"artists",new JSONArray().put(malformed))),c);fails(ProviderException.Kind.INVALID_RESPONSE,()->p.searchArtists("artist",0,3));
  http.handler=(path,a,w,c)->{session.clear();return new NeteaseHttp.Response(j("code",200,"result",j("artistCount",0)),c);};fails(ProviderException.Kind.LOGIN_EXPIRED,()->p.searchArtists("artist",0,3));
 }
 @Test public void appleMissingConfigWrongResourceAndLateSessionResultRemainExplicitFailures()throws Exception{
  AppleMusicSessionStore store=AppleMusicIntegrationTest.store(AppleMusicIntegrationTest.prefs());AppleMusicIntegrationTest.Http none=new AppleMusicIntegrationTest.Http((p,h)->{throw new AssertionError("Unconfigured source must not call API");});
  fails(ProviderException.Kind.LOGIN_REQUIRED,()->new AppleMusicProvider(store,none).searchArtists("artist",0,2));assertEquals(0,none.calls);
  store.importDeveloperToken(AppleMusicIntegrationTest.jwt());AppleMusicIntegrationTest.authorize(store);
  AppleMusicIntegrationTest.Http wrong=new AppleMusicIntegrationTest.Http((p,h)->new AppleMusicHttp.Response(200,j("results",j("artists",j("data",new JSONArray().put(j("id","123","type","songs","attributes",j("name","Not artist"))))))));fails(ProviderException.Kind.INVALID_RESPONSE,()->new AppleMusicProvider(store,wrong).searchArtists("artist",0,2));
  AppleMusicIntegrationTest.Http stale=new AppleMusicIntegrationTest.Http((p,h)->{store.clearUserSession();return new AppleMusicHttp.Response(200,j("results",j("artists",j("data",new JSONArray()))));});fails(ProviderException.Kind.LOGIN_EXPIRED,()->new AppleMusicProvider(store,stale).searchArtists("artist",0,2));
 }
}
