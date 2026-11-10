package com.recordroom.vinyl;

import android.content.SharedPreferences;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Offline SDK/API integration boundary fixtures. Synthetic JWT signatures never imply live developer authorization. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=34)
public class AppleMusicIntegrationTest {
    private static int index;
    static JSONObject json(Object... v){try{JSONObject o=new JSONObject();for(int i=0;i<v.length;i+=2)o.put((String)v[i],v[i+1]);return o;}catch(Exception e){throw new AssertionError(e);}}
    static String b64(byte[] value){return Base64.encodeToString(value,Base64.URL_SAFE|Base64.NO_PADDING|Base64.NO_WRAP);}
    static String jwt(String kid,long expires){long now=System.currentTimeMillis()/1000;return b64(json("alg","ES256","kid",kid).toString().getBytes(StandardCharsets.UTF_8))+"."+b64(json("iss","ABCDEFGHIJ","iat",now-60,"exp",expires).toString().getBytes(StandardCharsets.UTF_8))+"."+b64(new byte[64]);}
    static String jwt(){return jwt("1234567890",System.currentTimeMillis()/1000+3600);}
    static SharedPreferences prefs(){SharedPreferences p=RuntimeEnvironment.getApplication().getSharedPreferences("apple-integration-test-"+(++index),0);p.edit().clear().commit();return p;}
    static AppleMusicSessionStore store(SharedPreferences prefs){SecretKey key=new SecretKeySpec(new byte[32],"AES");return new AppleMusicSessionStore(prefs,()->key,url->{throw new AssertionError("No service request expected");});}
    interface Call{void run()throws Exception;}
    static void failure(AppleMusicApi.Error code,Call call)throws Exception{try{call.run();fail("Expected "+code);}catch(AppleMusicApi.Failure e){assertEquals(code,e.error);assertFalse(e.getMessage().contains("fixture-mut"));}}
    interface Handler{AppleMusicHttp.Response get(String path,Map<String,String> headers)throws AppleMusicApi.Failure;}
    static class Http extends AppleMusicHttp{final Handler handler;int calls;Http(Handler handler){this.handler=handler;}@Override Response get(String p,Map<String,String> h)throws AppleMusicApi.Failure{calls++;return handler.get(p,h);}}
    static AppleMusicHttp.Response storefront(){return new AppleMusicHttp.Response(200,json("data",new JSONArray().put(json("id","cn","type","storefronts"))));}
    static void authorize(AppleMusicSessionStore s)throws Exception{new AppleMusicProvider(s,new Http((p,h)->storefront())).acceptAuthorization("fixture-mut","cn","143465-19,32",s.generation(),new AppleMusicSessionStore.Attempt());}

    @Test public void noConfigurationDoesNotClaimConnectedOrCallNetwork()throws Exception{
        AppleMusicSessionStore s=store(prefs());Http http=new Http((p,h)->{throw new AssertionError();});MusicProvider.Account a=new AppleMusicProvider(s,http).account();assertFalse(a.loggedIn);assertEquals(-1,a.vipType);assertEquals(0,http.calls);assertNull(s.getDeveloperToken());assertNull(s.getUserToken());assertNull(s.getAppleMusicStoreFrontId());failure(AppleMusicApi.Error.CONFIGURATION,s::prepareDeveloperToken);
    }
    @Test public void developerImportChecksStructureExpiryAndRejectsKeysWithoutPersistingPlaintext()throws Exception{
        SharedPreferences p=prefs();AppleMusicSessionStore s=store(p);String token=jwt();s.importDeveloperToken(token);assertEquals(token,s.getDeveloperToken());assertFalse(p.getString("encrypted_v1","").contains(token));assertNull(s.getUserToken());
        failure(AppleMusicApi.Error.CONFIGURATION,()->s.importDeveloperToken("-----BEGIN PRIVATE KEY----- fixture"));
        failure(AppleMusicApi.Error.DEVELOPER_AUTH,()->s.importDeveloperToken(jwt("1234567890",System.currentTimeMillis()/1000-1)));
        failure(AppleMusicApi.Error.CONFIGURATION,()->s.importDeveloperToken(jwt("short",System.currentTimeMillis()/1000+3600)));
        assertEquals(token,s.developerToken());
    }
    @Test public void sdkAndRestShareVerifiedEncryptedUserAndEncodedStorefront()throws Exception{
        SharedPreferences p=prefs();AppleMusicSessionStore s=store(p);s.importDeveloperToken(jwt());Http http=new Http((path,headers)->{assertEquals("/v1/me/storefront",path);assertEquals("fixture-mut",headers.get("Music-User-Token"));assertTrue(headers.get("Authorization").startsWith("Bearer "));return storefront();});
        AppleMusicProvider provider=new AppleMusicProvider(s,http);AppleMusicSessionStore.Attempt gate=new AppleMusicSessionStore.Attempt();MusicProvider.Account account=provider.acceptAuthorization("fixture-mut","cn","143465-19,32",s.generation(),gate);
        assertTrue(account.loggedIn);assertEquals(-1,account.vipType);assertTrue(account.id.startsWith("local-"));assertTrue(gate.isCommitted());assertEquals("fixture-mut",s.getUserToken());assertEquals("cn",s.storefront());assertEquals("143465-19,32",s.getAppleMusicStoreFrontId());assertFalse(p.getString("encrypted_v1","").contains("fixture-mut"));
        gate.cancel();assertTrue(gate.isCommitted());assertEquals("fixture-mut",s.musicUserToken());
        AppleMusicSessionStore second=store(p);assertEquals("fixture-mut",second.getUserToken());long before=s.generation();second.clearUserSession();assertNull(s.getUserToken());assertNull(s.getAppleMusicStoreFrontId());assertNotNull(s.getDeveloperToken());assertTrue(s.generation()>before);
    }
    @Test public void cancellationAndLogoutDuringValidationRejectLateMut()throws Exception{
        SharedPreferences p=prefs();AppleMusicSessionStore s=store(p),other=store(p);s.importDeveloperToken(jwt());
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);AtomicReference<Throwable> error=new AtomicReference<>();AppleMusicSessionStore.Attempt gate=new AppleMusicSessionStore.Attempt();long before=s.generation();
        Http http=new Http((path,h)->{entered.countDown();try{assertTrue(release.await(3,TimeUnit.SECONDS));}catch(InterruptedException e){throw new AssertionError(e);}return storefront();});
        Thread t=new Thread(()->{try{new AppleMusicProvider(s,http).acceptAuthorization("fixture-mut","cn","143465-19,32",before,gate);}catch(Throwable e){error.set(e);}});t.start();assertTrue(entered.await(3,TimeUnit.SECONDS));gate.cancel();release.countDown();t.join(3000);assertFalse(t.isAlive());assertTrue(error.get() instanceof ProviderException);assertNull(s.getUserToken());
        long old=s.generation();Http logout=new Http((path,h)->{try{other.clearUserSession();}catch(Exception e){throw new AssertionError(e);}return storefront();});try{new AppleMusicProvider(s,logout).acceptAuthorization("fixture-mut","cn","143465-19,32",old,new AppleMusicSessionStore.Attempt());fail();}catch(ProviderException expected){assertEquals(ProviderException.Kind.LOGIN_EXPIRED,expected.kind);}assertNull(s.getUserToken());
    }
    @Test public void apiDeniedOrMismatchedStorefrontDoesNotPersistSdkSuccess()throws Exception{
        AppleMusicSessionStore s=store(prefs());s.importDeveloperToken(jwt());for(Http http:new Http[]{new Http((p,h)->new AppleMusicHttp.Response(403,null)),new Http((p,h)->storefront())}){
            try{new AppleMusicProvider(s,http).acceptAuthorization("fixture-mut","us","143441-1,32",s.generation(),new AppleMusicSessionStore.Attempt());fail();}catch(ProviderException expected){}assertNull(s.getUserToken());
        }
        try{new AppleMusicProvider(s,new Http((p,h)->storefront())).acceptAuthorization("fixture-mut","cn","",s.generation(),new AppleMusicSessionStore.Attempt());fail();}catch(ProviderException expected){assertEquals(ProviderException.Kind.INVALID_RESPONSE,expected.kind);}assertNull(s.getUserToken());
    }
    @Test public void tokenServiceIsExplicitAndSdkGettersNeverFetch()throws Exception{
        SharedPreferences p=prefs();int[] calls={0};String token=jwt();AppleMusicSessionStore s=new AppleMusicSessionStore(p,()->new SecretKeySpec(new byte[32],"AES"),url->{calls[0]++;assertEquals("https://tokens.example/apple",url);return token;});
        s.configureTokenService("https://tokens.example/apple");assertNull(s.getDeveloperToken());assertEquals(0,calls[0]);assertEquals(token,s.prepareDeveloperToken());assertEquals(1,calls[0]);assertEquals(token,s.prepareDeveloperToken());assertEquals(1,calls[0]);
        for(String value:new String[]{"http://tokens.example/apple","https://u:secret@tokens.example/apple","https://tokens.example/apple?token=secret","https://tokens.example/apple#secret"})failure(AppleMusicApi.Error.CONFIGURATION,()->s.configureTokenService(value));
    }
    @Test public void changingServiceDropsPreviousCredentialsAndFetchesNewAddress()throws Exception{
        SharedPreferences p=prefs();java.util.List<String> fetched=new java.util.ArrayList<>();AppleMusicSessionStore s=new AppleMusicSessionStore(p,()->new SecretKeySpec(new byte[32],"AES"),url->{fetched.add(url);return jwt();});
        s.configureTokenService("https://first.example/apple");s.prepareDeveloperToken();authorize(s);long previous=s.generation();s.configureTokenService("https://second.example/apple");
        assertTrue(s.generation()>previous);assertNull(s.getDeveloperToken());assertNull(s.getUserToken());assertNull(s.getAppleMusicStoreFrontId());assertEquals(1,fetched.size());s.prepareDeveloperToken();assertEquals(java.util.Arrays.asList("https://first.example/apple","https://second.example/apple"),fetched);
    }
    @Test public void signingKeyChangeInvalidatesOldUserButSameKeyRefreshPreservesUser()throws Exception{
        AppleMusicSessionStore s=store(prefs());s.importDeveloperToken(jwt());authorize(s);s.importDeveloperToken(jwt("1234567890",System.currentTimeMillis()/1000+7200));assertEquals("fixture-mut",s.getUserToken());s.importDeveloperToken(jwt("0987654321",System.currentTimeMillis()/1000+7200));assertNull(s.getUserToken());assertNull(s.getAppleMusicStoreFrontId());
    }
    @Test public void libraryIsNotMislabelledFavoritesAndPlaybackRequiresSdk()throws Exception{
        AppleMusicSessionStore s=store(prefs());s.importDeveloperToken(jwt());authorize(s);
        Http http=new Http((path,h)->new AppleMusicHttp.Response(200,json("data",new JSONArray().put(json("id","p.one","type","library-playlists","attributes",json("name","真实歌单","canEdit",true))))));
        AppleMusicProvider provider=new AppleMusicProvider(s,http);java.util.List<MusicProvider.Playlist> playlists=provider.playlists(s.accountIdentity());assertEquals(2,playlists.size());assertEquals(AppleMusicProvider.LIBRARY_SONGS,playlists.get(0).id);assertEquals("资料库歌曲",playlists.get(0).name);assertEquals(-1,playlists.get(0).trackCount);assertEquals(-1,playlists.get(1).trackCount);assertTrue(playlists.get(1).owned);
        try{provider.likedTracks(s.accountIdentity());fail();}catch(ProviderException e){assertEquals(ProviderException.Kind.UNAVAILABLE,e.kind);}try{provider.resolve(null);fail();}catch(ProviderException e){assertTrue(e.getMessage().contains("SDK_REQUIRED"));}
    }
    @Test public void trackMappingKeepsLibraryIdAndRelationshipIds()throws Exception{
        JSONObject raw=json("id","i.library","type","library-songs","attributes",json("name","歌曲","artistName","艺人","albumName","专辑","durationInMillis",180000,"playParams",json("id","i.library","isLibrary",true)),"relationships",json("albums",json("data",new JSONArray().put(json("id","l.album"))),"artists",json("data",new JSONArray().put(json("id","a.artist")))));
        Track track=new AppleMusicApi.Item(raw).asTrack();assertEquals("i.library",track.id);assertEquals("applemusic://library-songs/i.library",track.uri);assertEquals("l.album",track.albumId);assertEquals("a.artist",track.artistId);
    }
    @Test public void albumRoutesDoNotMislabelUnmappedLibrarySubsetAsCompleteCatalog()throws Exception{
        AppleMusicSessionStore s=store(prefs());s.importDeveloperToken(jwt());authorize(s);java.util.List<String> paths=new java.util.ArrayList<>();
        Http http=new Http((path,h)->{paths.add(path);boolean library=path.startsWith("/v1/me/");assertEquals(library,h.containsKey("Music-User-Token"));if(path.endsWith("/catalog"))return new AppleMusicHttp.Response(200,json("data",new JSONArray()));boolean tracks=path.contains("/tracks?");String id=tracks?"202":library?"l.album":"101";String type=tracks?"songs":library?"library-albums":"albums";return new AppleMusicHttp.Response(200,json("data",new JSONArray().put(json("id",id,"type",type,"attributes",json("name",tracks?"歌曲":"专辑","artistName","艺人")))));});
        AppleMusicProvider provider=new AppleMusicProvider(s,http);try{provider.album("l.album");fail();}catch(ProviderException e){assertEquals(ProviderException.Kind.UNAVAILABLE,e.kind);}MusicProvider.Album catalog=provider.album("101");assertEquals("applemusic://songs/202",catalog.tracks.get(0).uri);
        assertEquals(java.util.Arrays.asList("/v1/me/library/albums/l.album?include=catalog,artists","/v1/me/library/albums/l.album/catalog","/v1/catalog/cn/albums/101","/v1/catalog/cn/albums/101/tracks?limit=100"),paths);
    }
}
