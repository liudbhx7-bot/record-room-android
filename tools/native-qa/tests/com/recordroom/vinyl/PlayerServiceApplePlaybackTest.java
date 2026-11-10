package com.recordroom.vinyl;

import android.os.Looper;
import androidx.media3.datasource.DataSource;
import androidx.media3.exoplayer.ExoPlayer;
import com.apple.android.music.playback.controller.MediaPlayerController;
import com.apple.android.music.playback.model.MediaPlayerException;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Real PlayerService routing and actual MusicKit adapter; SDK controller/credentials are fixtures, local Media3 is real. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=34)
public class PlayerServiceApplePlaybackTest {
    public static class FixtureService extends PlayerService {
        final AppleMusicPlaybackTest.Tokens tokens=new AppleMusicPlaybackTest.Tokens();
        final List<AppleMusicPlaybackTest.Controller> engines=new ArrayList<>();int media3Opens;
        @Override protected AppleMusicPlayback createApplePlayback(AppleMusicPlayback.Listener listener){
            AppleMusicPlaybackTest.Controller engine=new AppleMusicPlaybackTest.Controller();engines.add(engine);
            return new AppleMusicPlayback(this,tokens,tokens,listener,engine);
        }
        @Override protected DataSource.Factory createDataSourceFactory(PlaybackResource resource){media3Opens++;return super.createDataSourceFactory(resource);}
    }
    private ServiceController<FixtureService> owner;private FixtureService service;
    @Before public void create(){owner=Robolectric.buildService(FixtureService.class).create();service=owner.get();service.setPlayMode(PlayerService.MODE_ORDER);}
    @After public void destroy(){owner.destroy();Shadows.shadowOf(Looper.getMainLooper()).idle();}
    private Object field(String name)throws Exception{Field field=PlayerService.class.getDeclaredField(name);field.setAccessible(true);return field.get(service);}
    private static Track apple(){return AppleMusicPlaybackTest.song("111",false);}
    private static Track local(){return new Track("fixture-local","Local demo","Artist","Album","local","asset:///demo/night-1.wav","",18000,1);}
    private AppleMusicPlaybackTest.Controller engine(){return service.engines.get(service.engines.size()-1);}
    private void await(BooleanSupplier condition)throws Exception{
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(12);
        while(!condition.getAsBoolean()&&System.nanoTime()<until){Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20));Thread.sleep(5);}
        assertTrue("Timed out: "+service.getError(),condition.getAsBoolean());
    }

    @Test public void mixedQueueRoutesAppleToSdkAndSeeksBeforeFirstAudioThenRoutesLocalToMedia3()throws Exception{
        service.setQueue(Arrays.asList(apple(),local()),0,false);service.seekTo(7000);service.startPlayback();
        assertEquals(1,service.engines.size());assertNull(field("player"));assertEquals(0,service.media3Opens);assertTrue(service.isPreparing());assertFalse(service.isPlaying());assertFalse(engine().autoplay);assertFalse((Boolean)field("hasFocus"));
        AppleMusicPlaybackTest.Controller appleEngine=engine();appleEngine.loaded(apple(),101);
        assertTrue(service.isPlaying());assertEquals(7000,service.getPositionMs());assertTrue(appleEngine.calls.indexOf("seek:7000")<appleEngine.calls.indexOf("play"));assertEquals(0,service.media3Opens);
        service.next();await(service::isPlaying);assertEquals(local().id,service.getCurrent().id);assertTrue(field("player") instanceof ExoPlayer);assertNull(field("applePlayer"));assertEquals(1,service.media3Opens);assertTrue(appleEngine.calls.contains("release"));
    }

    @Test public void pauseDuringPreparationDiscardsLateSdkCallbacksAndResumeKeepsPendingSeek()throws Exception{
        service.setQueue(Collections.singletonList(apple()),0,true);service.seekTo(9000);AppleMusicPlaybackTest.Controller first=engine();MediaPlayerController.Listener late=first.listener;
        service.pausePlayback();assertFalse(service.isPlaying());assertFalse(service.isPreparing());assertNull(field("applePlayer"));assertEquals(9000,service.getPositionMs());
        first.listener=late;first.loaded(apple(),101);assertFalse(service.isPlaying());assertNull(field("applePlayer"));assertEquals(9000,service.getPositionMs());assertFalse(first.calls.contains("play"));
        service.startPlayback();assertEquals(2,service.engines.size());engine().loaded(apple(),102);assertTrue(service.isPlaying());assertEquals(9000,service.getPositionMs());assertTrue(engine().calls.indexOf("seek:9000")<engine().calls.indexOf("play"));assertEquals(0,service.media3Opens);
    }

    @Test public void realSdkEndedEventUsesAppOrderAndRepeatOnePolicies()throws Exception{
        service.setQueue(Collections.singletonList(apple()),0,true);engine().loaded(apple(),101);engine().position=180000;engine().listener.onItemEnded(engine().sdk,engine().item,180000);
        assertFalse(service.isPlaying());assertFalse((Boolean)field("desiredPlay"));assertTrue((Boolean)field("ended"));assertEquals(180000,service.getPositionMs());assertFalse((Boolean)field("foreground"));
        service.setPlayMode(PlayerService.MODE_REPEAT_ONE);service.startPlayback();engine().loaded(apple(),102);int before=service.engines.size();AppleMusicPlaybackTest.Controller old=engine();old.position=180000;old.listener.onItemEnded(old.sdk,old.item,180000);
        assertEquals(before+1,service.engines.size());assertTrue(service.isPreparing());assertFalse(service.isPlaying());engine().loaded(apple(),103);assertTrue(service.isPlaying());assertEquals(0,service.getPositionMs());assertEquals(0,service.media3Opens);
    }

    @Test public void logoutStopsCurrentAppleAndPreservesOtherProviderQueueItems()throws Exception{
        Track qq=new Track("Qq01","QQ song","Artist","Album","QQ音乐","qq://song/Qq01","",180000,1,"Artist","","","qq","","");
        service.setQueue(Arrays.asList(apple(),local(),qq),0,true);engine().loaded(apple(),101);AppleMusicPlaybackTest.Controller old=engine();MediaPlayerController.Listener late=old.listener;
        service.removeProviderTracks("applemusic");assertEquals(2,service.getQueueSize());assertEquals(local().id,service.getCurrent().id);assertEquals("qq",service.getQueue().get(1).provider);assertFalse(service.isPlaying());assertNull(field("applePlayer"));assertNull(field("player"));assertTrue(old.calls.contains("release"));
        late.onPlaybackStateUpdated(old.sdk);assertFalse(service.isPlaying());assertEquals(0,service.getPositionMs());
    }

    @Test public void logoutOfOtherAppleTracksKeepsCurrentLocalAudioEngineAndPosition()throws Exception{
        service.setQueue(Arrays.asList(local(),apple()),0,true);await(service::isPlaying);service.seekTo(5000);await(()->service.isPlaying()&&service.getPositionMs()>=5000);Object localEngine=field("player");
        service.removeProviderTracks("applemusic");assertSame(localEngine,field("player"));assertTrue("Current local audio stays playing",service.isPlaying());assertEquals(1,service.getQueueSize());assertEquals(local().id,service.getCurrent().id);assertTrue("Local position is preserved",service.getPositionMs()>=5000);assertTrue("Apple engine was never created",service.engines.isEmpty());
    }

    @Test public void subscriptionFailureStopsWithoutFallbackAndSeekCapabilityFollowsSdk()throws Exception{
        service.setQueue(Collections.singletonList(apple()),0,true);engine().seek=false;engine().loaded(apple(),101);assertFalse(service.canSeek());assertTrue(service.isPlaying());
        engine().error(MediaPlayerException.TYPE_USER_SUBSCRIPTION_INVALID);assertFalse(service.isPlaying());assertTrue(service.getError().contains("订阅"));assertEquals(0,service.media3Opens);assertNull(field("applePlayer"));assertNull(field("player"));
    }

    @Test public void sdkFocusPauseShowsPlayAndOneTapResumesWhileSdkAutoResumeRestoresIntent()throws Exception{
        service.setQueue(Collections.singletonList(apple()),0,true);engine().loaded(apple(),101);assertTrue((Boolean)field("desiredPlay"));
        engine().state=com.apple.android.music.playback.model.PlaybackState.PAUSED;
        engine().listener.onPlaybackStateChanged(engine().sdk,1,2);assertFalse(service.isPlaying());assertFalse((Boolean)field("desiredPlay"));
        java.lang.reflect.Method notification=PlayerService.class.getDeclaredMethod("notification");notification.setAccessible(true);
        assertEquals("播放",((android.app.Notification)notification.invoke(service)).actions[1].title.toString());
        int before=Collections.frequency(engine().calls,"play");service.toggle();assertTrue(service.isPlaying());assertEquals(before+1,Collections.frequency(engine().calls,"play"));assertTrue((Boolean)field("desiredPlay"));
        assertEquals("暂停",((android.app.Notification)notification.invoke(service)).actions[1].title.toString());
        engine().state=com.apple.android.music.playback.model.PlaybackState.PAUSED;engine().listener.onPlaybackStateChanged(engine().sdk,1,2);assertFalse((Boolean)field("desiredPlay"));
        engine().state=com.apple.android.music.playback.model.PlaybackState.PLAYING;engine().listener.onPlaybackStateChanged(engine().sdk,2,1);assertTrue(service.isPlaying());assertTrue((Boolean)field("desiredPlay"));assertFalse("MusicKit alone owns focus",(Boolean)field("hasFocus"));
    }
}
