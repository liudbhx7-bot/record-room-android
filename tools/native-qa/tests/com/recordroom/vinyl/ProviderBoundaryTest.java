package com.recordroom.vinyl;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.util.Collections;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=34)
public class ProviderBoundaryTest {
    private SharedPreferences prefs;private SecretKey key;private Context context;
    @Before public void setup() throws Exception {context=RuntimeEnvironment.getApplication();prefs=context.getSharedPreferences("session-test",0);prefs.edit().clear().commit();KeyGenerator generator=KeyGenerator.getInstance("AES");generator.init(256);key=generator.generateKey();}
    @Test public void sessionRoundTripsAsAuthenticatedCiphertextWithoutPlaintextFallback()throws Exception {
        SecureSessionStore store=new SecureSessionStore(prefs,()->key);String value="MUSIC_U=fictional-test-token; __csrf=test-csrf";
        store.writeCookies(value);String encrypted=prefs.getString("encrypted_v1","");assertFalse(encrypted.isEmpty());assertFalse(encrypted.contains("fictional"));assertFalse(prefs.getAll().toString().contains(value));
        assertEquals(value,new SecureSessionStore(prefs,()->key).readCookies());assertEquals(0,store.generation());
        store.clear();assertEquals(1,store.generation());assertEquals("",new SecureSessionStore(prefs,()->key).readCookies());
    }
    @Test public void tamperedSessionIsInvalidatedAndCannotBecomeAnAnonymousSuccess()throws Exception {
        new SecureSessionStore(prefs,()->key).writeCookies("MUSIC_U=fixture");byte[] bytes=Base64.decode(prefs.getString("encrypted_v1",""),Base64.NO_WRAP);bytes[bytes.length-1]^=1;prefs.edit().putString("encrypted_v1",Base64.encodeToString(bytes,Base64.NO_WRAP)).commit();
        SecureSessionStore restored=new SecureSessionStore(prefs,()->key);
        try{restored.readCookies();fail("Tamper must be reported");}catch(ProviderException error){assertEquals(ProviderException.Kind.LOGIN_EXPIRED,error.kind);}
        assertFalse(prefs.contains("encrypted_v1"));assertEquals(1,restored.generation());assertEquals("",restored.readCookies());
    }
    @Test public void unavailableKeystoreDoesNotWriteAnUnencryptedSession()throws Exception {
        SecureSessionStore store=new SecureSessionStore(prefs,()->{throw new IllegalStateException("fixture unavailable key");});
        try{store.writeCookies("MUSIC_U=fixture");fail();}catch(ProviderException error){assertEquals(ProviderException.Kind.STORAGE,error.kind);}
        assertTrue(prefs.getAll().isEmpty());
    }
    @Test public void metadataKeepsRemoteIdsAndSeparatesAlbumsWithSameDisplayName()throws Exception {
        Track a=track("1","10"),b=track("2","11");Track restored=Track.fromJson(a.toJson());
        assertEquals("netease://song/1",restored.uri);assertEquals("netease",restored.provider);assertEquals("10",restored.albumId);assertEquals("99",restored.artistId);assertNotEquals(a.albumKey(),b.albumKey());
        OnlineCatalog.clear(context);OnlineCatalog.merge(context,"user-one",Collections.singletonList(a));OnlineCatalog.merge(context,"user-two",Collections.singletonList(b));
        assertEquals(1,OnlineCatalog.load(context).size());assertEquals("2",OnlineCatalog.load(context).get(0).id);
        org.json.JSONObject disk=new org.json.JSONObject(new String(java.nio.file.Files.readAllBytes(new java.io.File(context.getFilesDir(),"netease-catalog-v1.json").toPath()),java.nio.charset.StandardCharsets.UTF_8));
        assertEquals("user-two",disk.getString("account"));assertEquals("2",disk.getJSONArray("tracks").getJSONObject(0).getString("id"));
        OnlineCatalog.stage(context,"user-two",Collections.singletonList(a));assertEquals("Activity recreation sees metadata before disk write",2,OnlineCatalog.load(context).size());
        OnlineCatalog.clear(context);assertTrue(OnlineCatalog.load(context).isEmpty());
    }
    @Test public void publicArtworkUpgradesKnownCdnToTlsAndRejectsCredentialOrigins()throws Exception {
        assertEquals("https",RemoteArtworkCache.validatedUrl("http://p1.music.126.net/cover.jpg?param=300y300").getProtocol());
        for(String value:new String[]{"https://music.126.net.evil.example/cover","https://user:password@p1.music.126.net/cover","https://127.0.0.1/cover","https://p1.music.126.net:8080/cover"})try{RemoteArtworkCache.validatedUrl(value);fail("Unexpected origin accepted");}catch(java.io.IOException expected){}
    }
    private Track track(String id,String album){return new Track(id,"Title","Artist","Same album title","网易云音乐","netease://song/"+id,"https://p1.music.126.net/cover.jpg",180000,1,"Artist","","","netease",album,"99");}
}
