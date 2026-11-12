package com.recordroom.vinyl;

import android.net.Uri;
import android.os.Looper;
import androidx.media3.datasource.*;
import androidx.media3.exoplayer.ExoPlayer;
import java.io.IOException;
import java.lang.reflect.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Actual ExoPlayer/extractor/audio pipeline; only remote provider and network bytes are fixtures. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=34)
public class Media3PlaybackTest {
    private static byte[] wav;
    private ServiceController<FixtureService> controller;
    private FixtureService service;
    private CountDownLatch releaseResolution;
    public static class FixtureService extends PlayerService {
        final ArrayList<PlaybackResource> opened=new ArrayList<>();
        @Override protected DataSource.Factory createDataSourceFactory(PlaybackResource resource){
            if(resource==null)return super.createDataSourceFactory(null);
            opened.add(resource);
            return ()->new DataSource(){
                final ByteArrayDataSource delegate=new ByteArrayDataSource(wav);Uri uri;
                public void addTransferListener(TransferListener listener){delegate.addTransferListener(listener);}
                public long open(DataSpec spec)throws IOException{uri=spec.uri;if(uri.getPath().contains("expired")){int code=uri.getPath().contains("401")?401:uri.getPath().contains("410")?410:403;throw new HttpDataSource.InvalidResponseCodeException(code,"fixture expiry",null,Collections.emptyMap(),spec,new byte[0]);}return delegate.open(spec);}
                public int read(byte[] data,int offset,int length)throws IOException{return delegate.read(data,offset,length);}
                public Uri getUri(){return uri;}
                public void close()throws IOException{delegate.close();}
            };
        }
    }
    interface Resolver {PlaybackResource resolve(Track track)throws Exception;}
    @Before public void setup()throws Exception{
        try(java.io.InputStream input=RuntimeEnvironment.getApplication().getAssets().open("demo/night-1.wav")){wav=input.readAllBytes();}
        controller=Robolectric.buildService(FixtureService.class).create();service=controller.get();
    }
    @After public void cleanup(){if(releaseResolution!=null)releaseResolution.countDown();controller.destroy();Shadows.shadowOf(Looper.getMainLooper()).idle();OnlineMusic.useProviderForTests(RuntimeEnvironment.getApplication(),null,null);}
    private void provider(Resolver resolver){
        MusicProvider provider=(MusicProvider)Proxy.newProxyInstance(MusicProvider.class.getClassLoader(),new Class<?>[]{MusicProvider.class},(proxy,method,args)->{
            if(method.getName().equals("resolve"))return resolver.resolve((Track)args[0]);if(method.getName().equals("id"))return "netease";return null;
        });
        OnlineMusic.useProviderForTests(service,provider,new ProviderSession(){public String readCookies(){return "";}public void writeCookies(String s){}public void clear(){}public long generation(){return 0;}});
    }
    private Track remote(long duration){return new Track("fixture-remote","Remote fixture","Artist","Album","网易云音乐","netease://song/fixture-remote","",duration,1);}
    private Track local(){return new Track("fixture-local","Local demo","Artist","Album","local","asset:///demo/night-1.wav","",18000,1);}
    private PlaybackResource resource(String name){return new PlaybackResource("https://fixture.invalid/"+name+".wav",Collections.singletonMap("X-Fixture","public-audio"),System.currentTimeMillis()+60000,18000);}
    private Object field(String name)throws Exception{Field field=PlayerService.class.getDeclaredField(name);field.setAccessible(true);return field.get(service);}
    private void await(BooleanSupplier ready)throws Exception{
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
        while(!ready.getAsBoolean()&&System.nanoTime()<deadline){Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20));Thread.sleep(5);}
        assertTrue("Timed out; error="+service.getError()+" pos="+service.getPositionMs()+" playing="+service.isPlaying()+" preparing="+service.isPreparing()+" desired="+field("desiredPlay")+" engine="+(field("player")==null?"null":((ExoPlayer)field("player")).getPlaybackState()),ready.getAsBoolean());
    }
    private void drainResolution()throws Exception{((ExecutorService)field("resourceIO")).submit(()->{}).get(10,TimeUnit.SECONDS);Shadows.shadowOf(Looper.getMainLooper()).idle();}

    @Test public void aggregateUnavailableFirstRecordingFallsBackWithoutAddingQueueRows()throws Exception{
        AtomicInteger calls=new AtomicInteger();provider(track->{calls.incrementAndGet();if(track.id.equals("fixture-remote"))throw new ProviderException(ProviderException.Kind.PREVIEW_ONLY,"Only preview");return resource("full");});
        Track first=remote(18000);Track second=new Track("second","Remote fixture","Artist","Album","网易云音乐","netease://song/second","",18000,1);
        service.setAggregateQueue(Collections.singletonList(new AggregateSearch.Result("one",Arrays.asList(first,second))),0);await(service::isPlaying);
        assertEquals(2,calls.get());assertEquals(1,service.getQueueSize());assertEquals("second",service.getCurrent().id);assertTrue(service.isFullDurationVerified());
    }
    @Test public void aggregateFailureTriesEachRecordingOnceThenStops()throws Exception{
        AtomicInteger calls=new AtomicInteger();provider(track->{calls.incrementAndGet();throw new ProviderException(ProviderException.Kind.UNAVAILABLE,"unavailable");});
        Track second=new Track("second","Remote fixture","Artist","Album","网易云音乐","netease://song/second","",18000,1);
        service.setAggregateQueue(Collections.singletonList(new AggregateSearch.Result("one",Arrays.asList(remote(18000),second))),0);await(()->!service.getError().isEmpty());assertEquals(2,calls.get());assertFalse(service.isPlaying());assertFalse(service.isPreparing());assertEquals(1,service.getQueueSize());
        service.startPlayback();await(()->!service.getError().isEmpty());assertEquals("Explicit retry may try both recordings again",4,calls.get());
    }
    @Test public void aggregateExhaustionSeparatesAttemptCountFromTheLastFailureReason()throws Exception{
        AtomicInteger calls=new AtomicInteger();provider(track->{calls.incrementAndGet();throw new ProviderException(track.id.equals("fixture-remote")?ProviderException.Kind.NETWORK:ProviderException.Kind.UNAVAILABLE,track.id.equals("fixture-remote")?"第一版本连接超时":"最后版本未返回播放地址");});
        Track second=new Track("second","Remote fixture","Artist","Album","网易云音乐","netease://song/second","",18000,1);
        service.setAggregateQueue(Collections.singletonList(new AggregateSearch.Result("one",Arrays.asList(remote(18000),second))),0);await(()->!service.getError().isEmpty());
        assertEquals("已尝试 2 个版本，当前仍无法播放。最后一次：最后版本未返回播放地址",service.getError());assertEquals(2,calls.get());assertFalse(service.isPlaying());
        service.startPlayback();await(()->!service.getError().isEmpty());assertEquals(4,calls.get());assertTrue(service.getError().startsWith("已尝试 2 个版本，"));assertFalse(service.getError().contains("4 个版本"));
    }
    @Test public void singleAggregateCandidateRetainsItsPreciseFailure()throws Exception{
        provider(track->{throw new ProviderException(ProviderException.Kind.LOGIN_EXPIRED,"该版本登录已失效");});
        service.setAggregateQueue(Collections.singletonList(new AggregateSearch.Result("one",Collections.singletonList(remote(18000)))),0);await(()->!service.getError().isEmpty());assertEquals("该版本登录已失效",service.getError());
    }
    @Test public void failureAfterFallbackHasStartedAudioIsNotCalledAStartupFailure()throws Exception{
        provider(track->{if(track.id.equals("fixture-remote"))throw new ProviderException(ProviderException.Kind.NETWORK,"Fixture network failure");return resource("full");});
        Track second=new Track("second","Remote fixture","Artist","Album","网易云音乐","netease://song/second","",18000,1);
        service.setAggregateQueue(Collections.singletonList(new AggregateSearch.Result("one",Arrays.asList(remote(18000),second))),0);await(service::isPlaying);
        androidx.media3.common.Player.Listener listener=(androidx.media3.common.Player.Listener)field("playerListener");listener.onPlayerError(new androidx.media3.common.PlaybackException("Fixture decoder failure",null,androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FAILED));
        assertEquals("播放失败：请检查网络、文件或歌曲播放权限",service.getError());assertFalse(service.isPlaying());
    }
    @Test public void realMedia3ReadsBundledWavAndSupportsSeekPause()throws Exception{
        service.setQueue(Collections.singletonList(local()),0,true);await(service::isPlaying);
        assertTrue(field("player") instanceof ExoPlayer);assertEquals(18000,service.getDurationMs());
        service.seekTo(7000);await(()->service.getPositionMs()>=7000);service.pausePlayback();long paused=service.getPositionMs();
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400));assertFalse(service.isPlaying());assertEquals(paused,service.getPositionMs());
        System.out.println("MEDIA3_REAL_ENGINE local_pcm_duration_ms="+service.getDurationMs()+" seek_pause_passed (JVM audio sink, not device sound)");
    }
    @Test public void cueTransitionsKeepNativeRendererAndRelativeTimeline() throws Exception {
        Track master=local();
        Track first=master.localSegment(1,"First","Artist","Album","Artist","","",0,4500,"");
        Track second=master.localSegment(2,"Second","Artist","Album","Artist","","",4500,10000,"");
        service.setPlayMode(PlayerService.MODE_ORDER);
        service.setQueue(Arrays.asList(first,second),0,true);
        await(service::isPlaying);
        ExoPlayer engine=(ExoPlayer)field("player");
        assertEquals(4500,service.getDurationMs());assertEquals(2,engine.getMediaItemCount());
        service.seekTo(4300);
        await(()->service.getIndex()==1);
        assertSame(engine,field("player"));assertEquals("Second",service.getCurrent().title);
        assertEquals(5500,service.getDurationMs());
        assertTrue("New song starts in its own time base",service.getPositionMs()<3000);
        assertEquals(1,engine.getMediaItemCount());
        service.pausePlayback();service.seekTo(2300);
        await(()->service.getPositionMs()==2300);
    }

    @Test public void localReadAheadReflectsEditAndPlayModeBeforeTransition() throws Exception {
        Track a=local(), b=local().localSegment(2,"B","Artist","Album","Artist","","",1000,7000,"");
        Track c=local().localSegment(3,"C","Artist","Album","Artist","","",7000,11000,"");
        service.setPlayMode(PlayerService.MODE_ORDER);service.setQueue(Arrays.asList(a,b,c),0,true);
        await(service::isPlaying);ExoPlayer engine=(ExoPlayer)field("player");
        assertEquals(LibraryStore.identity(b),engine.getMediaItemAt(1).mediaId);
        service.moveQueueItem(2,1);assertEquals(LibraryStore.identity(c),engine.getMediaItemAt(1).mediaId);
        service.setPlayMode(PlayerService.MODE_SHUFFLE);assertEquals(1,engine.getMediaItemCount());
        service.setPlayMode(PlayerService.MODE_ORDER);assertEquals(LibraryStore.identity(c),engine.getMediaItemAt(1).mediaId);
        service.removeQueueItem(1);assertEquals(LibraryStore.identity(b),engine.getMediaItemAt(1).mediaId);
        service.removeQueueItem(1);assertEquals(1,engine.getMediaItemCount());
    }

    @Test public void verifiedRemoteFixturePlaysFullDurationAndDoesNotPersistSignedUrl()throws Exception{
        provider(track->resource("full"));service.setQueue(Collections.singletonList(remote(18000)),0,true);service.seekTo(4000);await(service::isPlaying);
        assertTrue(service.isFullDurationVerified());assertEquals(18000,service.getDurationMs());assertTrue(service.getPositionMs()>=4000);
        assertEquals("public-audio",service.opened.get(0).headers.get("X-Fixture"));
        assertEquals("netease://song/fixture-remote",service.getQueue().get(0).uri);
        assertFalse(service.getQueue().get(0).toJson().toString().contains("fixture.invalid"));
    }
    @Test public void orderedQueueEndThenSeekRemainsPausedUntilExplicitPlay()throws Exception{
        service.setPlayMode(PlayerService.MODE_ORDER);
        service.setQueue(Collections.singletonList(local()),0,true);await(service::isPlaying);
        ExoPlayer engine=(ExoPlayer)field("player");
        // Reach the real extractor/renderer completion callback; do not inject an ended event.
        service.seekTo(17500);await(()->engine.getPlaybackState()==androidx.media3.common.Player.STATE_ENDED);
        assertEquals(18000,service.getPositionMs());assertFalse(service.isPlaying());
        assertFalse("An ended queue must clear ExoPlayer's pending play intent",engine.getPlayWhenReady());
        assertFalse((Boolean)field("desiredPlay"));assertFalse((Boolean)field("foreground"));assertFalse((Boolean)field("hasFocus"));
        service.seekTo(7000);await(()->engine.getPlaybackState()==androidx.media3.common.Player.STATE_READY&&!service.isPreparing());
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500));
        assertSame(engine,field("player"));assertFalse(engine.getPlayWhenReady());assertFalse(service.isPlaying());assertEquals(7000,service.getPositionMs());
        service.startPlayback();await(service::isPlaying);
        assertTrue(engine.getPlayWhenReady());assertTrue((Boolean)field("foreground"));assertTrue((Boolean)field("hasFocus"));assertTrue(service.getPositionMs()>=7000);
    }
    @Test public void previewPermissionFailsBeforeCreatingAnEngine()throws Exception{
        provider(track->{throw new ProviderException(ProviderException.Kind.PREVIEW_ONLY,"只有试听权限");});
        service.setQueue(Collections.singletonList(remote(180000)),0,true);await(()->!service.getError().isEmpty());
        assertEquals("只有试听权限",service.getError());assertNull(field("player"));assertTrue(service.opened.isEmpty());assertFalse(service.isPlaying());
    }
    @Test public void mismatchedTimelineIsRejectedBeforeAudioStarts()throws Exception{
        for(long expected:new long[]{180000,9000}){
            provider(track->new PlaybackResource("https://fixture.invalid/full.wav",Collections.emptyMap(),System.currentTimeMillis()+60000,expected));
            service.setQueue(Collections.singletonList(remote(expected)),0,true);await(()->!service.getError().isEmpty());
            assertTrue(service.getError().contains("音频时长"));assertFalse(service.isPlaying());assertFalse(service.isFullDurationVerified());assertNull(field("player"));
        }
    }
    @Test public void expiredUrlRefreshesOnceAndPreservesPendingSeek()throws Exception{
        for(int code:new int[]{401,403,410}){
            AtomicInteger resolutions=new AtomicInteger();provider(track->resource(resolutions.incrementAndGet()==1?"expired"+code:"full"));service.opened.clear();
            service.setQueue(Collections.singletonList(remote(18000)),0,false);service.seekTo(7000);service.startPlayback();await(service::isPlaying);
            assertEquals(2,resolutions.get());assertEquals(2,service.opened.size());assertTrue(service.getPositionMs()>=7000);assertTrue(service.isFullDurationVerified());
        }
    }
    @Test public void repeatExpiryDoesNotRetryIndefinitely()throws Exception{
        AtomicInteger resolutions=new AtomicInteger();provider(track->{resolutions.incrementAndGet();return resource("expired");});
        service.setQueue(Collections.singletonList(remote(18000)),0,true);await(()->!service.getError().isEmpty());
        assertEquals(2,resolutions.get());assertFalse(service.isPlaying());assertTrue(service.getError().contains("刷新后仍不可用"));
    }
    @Test public void logoutInvalidatesLateRemoteResolutionAndLeavesLocalPaused()throws Exception{
        CountDownLatch entered=new CountDownLatch(1);releaseResolution=new CountDownLatch(1);
        provider(track->{entered.countDown();while(true){try{releaseResolution.await();break;}catch(InterruptedException ignored){}}return resource("full");});
        service.setQueue(Arrays.asList(remote(18000),local()),0,true);assertTrue(entered.await(5,TimeUnit.SECONDS));
        service.removeProviderTracks("netease");releaseResolution.countDown();drainResolution();
        assertEquals(1,service.getQueueSize());assertEquals("fixture-local",service.getCurrent().id);assertEquals(0,service.getPositionMs());assertFalse(service.isPlaying());assertFalse(service.isPreparing());assertNull(field("player"));assertTrue(service.opened.isEmpty());
    }
    @Test public void logoutPreservesCurrentLocalEnginePositionAndPlayback()throws Exception{
        service.setQueue(Arrays.asList(remote(18000),local()),1,true);await(service::isPlaying);service.seekTo(5000);await(()->service.isPlaying()&&service.getPositionMs()>=5000);
        Object engine=field("player");service.removeProviderTracks("netease");
        assertSame(engine,field("player"));assertTrue("Removing another provider must keep active local audio playing",service.isPlaying());assertEquals(0,service.getIndex());assertEquals(1,service.getQueueSize());assertTrue(service.getPositionMs()>=5000);
    }
    @Test public void pausingPendingResolutionPreventsLateAutoplayAndResumeRetainsSeek()throws Exception{
        CountDownLatch entered=new CountDownLatch(1);releaseResolution=new CountDownLatch(1);AtomicInteger calls=new AtomicInteger();
        provider(track->{calls.incrementAndGet();entered.countDown();while(true){try{releaseResolution.await();break;}catch(InterruptedException ignored){}}return resource("full");});
        service.setQueue(Collections.singletonList(remote(18000)),0,true);assertTrue(entered.await(5,TimeUnit.SECONDS));service.seekTo(7000);service.pausePlayback();
        releaseResolution.countDown();drainResolution();assertFalse(service.isPlaying());assertNull(field("player"));assertEquals(7000,service.getPositionMs());
        service.startPlayback();await(service::isPlaying);assertEquals(2,calls.get());assertEquals(1,service.opened.size());assertTrue(service.getPositionMs()>=7000);
    }
}
