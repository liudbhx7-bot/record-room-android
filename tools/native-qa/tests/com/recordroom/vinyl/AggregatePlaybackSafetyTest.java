package com.recordroom.vinyl;

import android.content.Context;
import android.media.AudioManager;
import android.os.Looper;
import androidx.media3.datasource.DataSource;
import com.apple.android.music.playback.controller.MediaPlayerController;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import javax.crypto.spec.SecretKeySpec;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAudioManager;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

/** Real service fallback and MusicKit adapter. Only provider I/O and native SDK callbacks are fixtures. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=34)
public class AggregatePlaybackSafetyTest {
    public static class FixtureService extends PlayerService {
        final AppleMusicPlaybackTest.Tokens tokens=new AppleMusicPlaybackTest.Tokens();
        final List<AppleMusicPlaybackTest.Controller> engines=new ArrayList<>();
        final List<Boolean> focusAtAppleCreation=new ArrayList<>();
        int media3Opens;
        @Override protected AppleMusicPlayback createApplePlayback(AppleMusicPlayback.Listener listener){
            focusAtAppleCreation.add(ReflectionHelpers.getField(this,"hasFocus"));
            AppleMusicPlaybackTest.Controller engine=new AppleMusicPlaybackTest.Controller();engines.add(engine);
            return new AppleMusicPlayback(this,tokens,tokens,listener,engine);
        }
        @Override protected DataSource.Factory createDataSourceFactory(PlaybackResource resource){media3Opens++;return super.createDataSourceFactory(resource);}
    }
    private ServiceController<FixtureService> owner;
    private FixtureService service;
    private CountDownLatch entered,release;
    private final ProviderSession netSession=new ProviderSession(){
        private long epoch;
        public String readCookies(){return "";}public void writeCookies(String value){}
        public void clear(){epoch++;}public long generation(){return epoch;}
    };
    @Before public void setup(){owner=Robolectric.buildService(FixtureService.class).create();service=owner.get();service.setPlayMode(PlayerService.MODE_ORDER);}
    @After public void cleanup()throws Exception{
        if(release!=null)release.countDown();
        owner.destroy();Shadows.shadowOf(Looper.getMainLooper()).idle();
        OnlineMusic.useProviderForTests(service,null,null);
    }
    private static Track net(){return new Track("recording-net","Same song","Artist","Same album","网易云音乐","netease://song/recording-net","",180000,1);}
    private static Track apple(){return AppleMusicPlaybackTest.song("111",false);}
    private static Track local(){return new Track("new-local","New selection","Other artist","Other album","local","asset:///demo/night-1.wav","",18000,1);}
    private Object field(String name){return ReflectionHelpers.getField(service,name);}
    private void aggregate(Track first){service.setAggregateQueue(Collections.singletonList(new AggregateSearch.Result("explicit-same-recording-fixture",Arrays.asList(first,apple()))),0);}
    private void blockingNetFailure(){
        entered=new CountDownLatch(1);release=new CountDownLatch(1);
        MusicProvider provider=(MusicProvider)Proxy.newProxyInstance(MusicProvider.class.getClassLoader(),new Class<?>[]{MusicProvider.class},(proxy,method,args)->{
            if(method.getName().equals("id"))return "netease";
            if(method.getName().equals("resolve")){
                entered.countDown();
                // Deliberately complete after Future.cancel(true), as real remote responses can do.
                while(true){try{release.await();break;}catch(InterruptedException ignored){}}
                throw new ProviderException(ProviderException.Kind.UNAVAILABLE,"Fixture recording unavailable");
            }
            return null;
        });
        OnlineMusic.useProviderForTests(service,provider,netSession);
    }
    private void await(BooleanSupplier condition)throws Exception{
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(!condition.getAsBoolean()&&System.nanoTime()<end){Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20));Thread.sleep(5);}
        assertTrue("Timed out; error="+service.getError()+", preparing="+service.isPreparing(),condition.getAsBoolean());
    }
    private void finishBlockedResolution()throws Exception{
        release.countDown();((ExecutorService)field("resourceIO")).submit(()->{}).get(10,TimeUnit.SECONDS);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }
    private ShadowAudioManager audio(){return Shadows.shadowOf((AudioManager)service.getSystemService(Context.AUDIO_SERVICE));}
    private void assertAppleOwnsFocus(){
        assertEquals(Collections.singletonList(false),service.focusAtAppleCreation);
        assertFalse((Boolean)field("hasFocus"));
        assertSame("The platform focus request must be abandoned, not merely hidden in app state",field("focusRequest"),audio().getLastAbandonedAudioFocusRequest());
        assertNull(field("player"));assertEquals(0,service.media3Opens);
    }

    @Test public void neteaseFailureTransfersFocusToAppleAndSeeksBeforeItsFirstAudio()throws Exception{
        blockingNetFailure();aggregate(net());assertTrue(entered.await(5,TimeUnit.SECONDS));
        assertTrue((Boolean)field("hasFocus"));
        ReflectionHelpers.setField(audio(),"lastAbandonedAudioFocusRequest",null);
        service.seekTo(11000);finishBlockedResolution();await(()->service.engines.size()==1);
        assertAppleOwnsFocus();assertEquals(1,service.getQueueSize());assertEquals("applemusic",OnlineMusic.providerId(service.getCurrent()));
        AppleMusicPlaybackTest.Controller engine=service.engines.get(0);assertFalse(engine.autoplay);assertFalse(service.isPlaying());
        engine.loaded(apple(),101);assertTrue(service.isPlaying());assertEquals(11000,service.getPositionMs());
        assertTrue("Same-song fallback applies the user's seek before play",engine.calls.indexOf("seek:11000")>=0&&engine.calls.indexOf("seek:11000")<engine.calls.indexOf("play"));
    }

    @Test public void qqUnavailableAuthorizationAlsoReleasesTheAppFocusBeforeMusicKit()throws Exception{
        Track qq=new Track("001QQ000000001","Same song","Artist","Same album","QQ音乐","qq://song/001QQ000000001","",180000,1,"Artist","","","qq","","");
        // An empty app-private QQ session fails before HTTP; this test never contacts a server.
        OnlineMusic.provider(service,"qq");ProviderSession session=ReflectionHelpers.getStaticField(OnlineMusic.class,"qqSession");session.clear();
        aggregate(qq);assertTrue((Boolean)field("hasFocus"));ReflectionHelpers.setField(audio(),"lastAbandonedAudioFocusRequest",null);
        service.seekTo(7000);await(()->service.engines.size()==1);assertAppleOwnsFocus();
        service.engines.get(0).loaded(apple(),102);assertEquals(7000,service.getPositionMs());assertTrue(service.isPlaying());
    }

    @Test public void pauseCancelsLateFailureInsteadOfStartingAnAlternative()throws Exception{
        blockingNetFailure();aggregate(net());assertTrue(entered.await(5,TimeUnit.SECONDS));service.seekTo(13000);
        service.pausePlayback();finishBlockedResolution();
        assertEquals(net().uri,service.getCurrent().uri);assertEquals(13000,service.getPositionMs());
        assertFalse(service.isPlaying());assertFalse(service.isPreparing());assertFalse((Boolean)field("desiredPlay"));
        assertTrue(service.engines.isEmpty());assertNull(field("applePlayer"));assertNull(field("player"));assertFalse((Boolean)field("hasFocus"));
    }

    @Test public void aNewQueueCannotBeReplacedByThePreviousGroupsLateFailure()throws Exception{
        blockingNetFailure();aggregate(net());assertTrue(entered.await(5,TimeUnit.SECONDS));
        service.setQueue(Collections.singletonList(local()),0,false);finishBlockedResolution();
        assertEquals(Collections.singletonList(local().id),Collections.singletonList(service.getCurrent().id));
        assertEquals(1,service.getQueueSize());assertEquals(0,service.getPositionMs());assertFalse(service.isPlaying());assertFalse(service.isPreparing());
        assertTrue(service.engines.isEmpty());assertNull(field("player"));assertEquals(0,service.media3Opens);
    }

    @Test public void loggedOutAlternativeIsRejectedByItsChangedSessionEpoch()throws Exception{
        OnlineMusic.provider(service,"applemusic");
        AppleMusicSessionStore store=new AppleMusicSessionStore(service.getSharedPreferences("aggregate-apple-fixture",Context.MODE_PRIVATE),
                ()->new SecretKeySpec(new byte[32],"AES"),url->{throw new AppleMusicApi.Failure(AppleMusicApi.Error.CONFIGURATION,"Fixture has no token service");});
        AppleMusicProvider provider=new AppleMusicProvider(store);
        ReflectionHelpers.setStaticField(OnlineMusic.class,"appleSession",store);ReflectionHelpers.setStaticField(OnlineMusic.class,"apple",provider);
        blockingNetFailure();aggregate(net());assertTrue(entered.await(5,TimeUnit.SECONDS));long epoch=store.generation();
        boolean[] loggedOut={false};OnlineMusic.logout(service,provider,(committed,error)->{assertNull(error);loggedOut[0]=committed;});
        await(()->loggedOut[0]);assertTrue(store.generation()>epoch);finishBlockedResolution();
        assertEquals("netease",OnlineMusic.providerId(service.getCurrent()));assertEquals(1,service.getQueueSize());
        assertFalse(service.isPlaying());assertFalse(service.isPreparing());assertFalse(service.getError().isEmpty());
        assertTrue("A cached alternative must not create a MusicKit engine after logout",service.engines.isEmpty());assertNull(field("applePlayer"));
    }

    @Test public void pauseAfterFallbackPreparationRejectsTheLateAppleReadyCallback()throws Exception{
        blockingNetFailure();aggregate(net());assertTrue(entered.await(5,TimeUnit.SECONDS));service.seekTo(15000);finishBlockedResolution();await(()->service.engines.size()==1);
        AppleMusicPlaybackTest.Controller engine=service.engines.get(0);MediaPlayerController.Listener late=engine.listener;
        service.pausePlayback();engine.listener=late;engine.loaded(apple(),103);
        assertFalse(service.isPlaying());assertFalse(service.isPreparing());assertEquals(15000,service.getPositionMs());
        assertFalse(engine.calls.contains("play"));assertTrue(engine.calls.contains("release"));assertNull(field("applePlayer"));
    }
}
