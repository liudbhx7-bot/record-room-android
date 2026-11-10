package com.recordroom.vinyl;

import android.os.Looper;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=34) @LooperMode(LooperMode.Mode.PAUSED)
public class RealArtistSearchTest {
    AggregateSearch engine;final List<CountDownLatch> gates=new ArrayList<>();
    @After public void cleanup(){for(CountDownLatch gate:gates)gate.countDown();if(engine!=null)engine.close();Shadows.shadowOf(Looper.getMainLooper()).idle();}
    interface Artists {MusicProvider.Page<MusicProvider.Artist> get(String q,int o,int l)throws Exception;}
    static MusicProvider.Artist artist(String id,String name){return new MusicProvider.Artist(id,name,"测试目录简介","asset:///demo/night-v2.png",Collections.emptyList(),Collections.emptyList());}
    static MusicProvider.Page<MusicProvider.Artist> page(MusicProvider.Artist... artists){return new MusicProvider.Page<>(Arrays.asList(artists),artists.length,artists.length,false);}
    static final class Source {
        final AtomicLong epoch=new AtomicLong();final List<Integer> artistOffsets=Collections.synchronizedList(new ArrayList<>());final MusicProvider provider;
        boolean artistSupported=true,enabled=true;int songCalls,artistCalls;
        Source(String id,AggregateSearchTest.Search songs,Artists artists){provider=(MusicProvider)Proxy.newProxyInstance(MusicProvider.class.getClassLoader(),new Class<?>[]{MusicProvider.class},(p,m,a)->{switch(m.getName()){case "id":return id;case "supportsArtistSearch":return artistSupported;case "searchTracks":songCalls++;return songs.get((String)a[0],(Integer)a[1],(Integer)a[2]);case "searchArtists":artistCalls++;artistOffsets.add((Integer)a[1]);return artists.get((String)a[0],(Integer)a[1],(Integer)a[2]);default:throw new AssertionError("Unexpected artist-search call: "+m.getName());}});}
        AggregateSearch.Source lane(){return new AggregateSearch.Source(provider,epoch::get,()->enabled,()->0);}
    }
    @Test public void artistApiCanFindSomeoneWithoutAnyMatchingSongs()throws Exception{
        Source source=new Source("qq",(q,o,l)->AggregateSearchTest.page(),(q,o,l)->page(artist("real-id","真实音乐人")));engine=new AggregateSearch(Collections.singletonList(source.lane()));engine.search("真实");AggregateSearchTest.await(()->!engine.state().loading&&!engine.state().artistLoading);assertEquals("Artists must come from their own API even when songs are empty",1,engine.state().artists.size());assertEquals("真实音乐人",engine.state().artists.get(0).name);assertEquals(1,source.artistCalls);
    }
    @Test public void songMetadataMustNeverCreateAnArtistSearchResult()throws Exception{
        Source source=new Source("qq",(q,o,l)->AggregateSearchTest.page(SearchArtistEntryTest.track("qq","q","刘德华","songArtistId")),(q,o,l)->page());engine=new AggregateSearch(Collections.singletonList(source.lane()));engine.search("刘德华");AggregateSearchTest.await(()->!engine.state().loading&&!engine.state().artistLoading);assertTrue("A song credit is not an artist-search response",engine.state().artists.isEmpty());
    }
    @Test public void sameNamesWithDifferentIdsOrProvidersRemainSeparateRealDestinations()throws Exception{
        Source qq=new Source("qq",(q,o,l)->AggregateSearchTest.page(),(q,o,l)->page(artist("id1","同名歌手"),artist("id2","同名歌手")));Source net=new Source("netease",(q,o,l)->AggregateSearchTest.page(),(q,o,l)->page(artist("123","同名歌手")));engine=new AggregateSearch(Arrays.asList(qq.lane(),net.lane()));engine.search("同名");AggregateSearchTest.await(()->!engine.state().loading&&!engine.state().artistLoading);assertEquals(3,engine.state().artists.size());
    }
    @Test public void blockedSongWorkersDoNotDelayRealArtistResponses()throws Exception{
        CountDownLatch entered=new CountDownLatch(3),release=new CountDownLatch(1);gates.add(release);List<AggregateSearch.Source> lanes=new ArrayList<>();
        for(String id:new String[]{"qq","netease","applemusic"}){Source source=new Source(id,(q,o,l)->{entered.countDown();AggregateSearchTest.uninterruptibly(release);return AggregateSearchTest.page();},(q,o,l)->page(artist(id+"-a","音乐人")));lanes.add(source.lane());}
        engine=new AggregateSearch(lanes);engine.search("音乐人");assertTrue(entered.await(5,TimeUnit.SECONDS));AggregateSearchTest.await(()->engine.state().artists.size()==3&&!engine.state().artistLoading);assertTrue(engine.state().loading);
    }
    @Test public void artistPaginationAndRetryDoNotRestartSongRequests()throws Exception{
        List<MusicProvider.Artist> first=new ArrayList<>();for(int i=0;i<25;i++)first.add(artist("a"+i,"歌手"+i));int[] attempts={0};
        Source source=new Source("qq",(q,o,l)->AggregateSearchTest.page(AggregateSearchTest.track("qq","q","歌曲","专辑",180000)),(q,o,l)->{if(o==0)return new MusicProvider.Page<>(first,27,25,true);if(attempts[0]++==0)throw new ProviderException(ProviderException.Kind.NETWORK,"safe fixture failure");return new MusicProvider.Page<>(Arrays.asList(artist("a24","歌手24"),artist("last","歌手26")),27,27,false);});
        engine=new AggregateSearch(Collections.singletonList(source.lane()));engine.search("歌手");AggregateSearchTest.await(()->!engine.state().loading&&!engine.state().artistLoading);assertEquals(25,engine.state().artists.size());engine.loadMoreArtists();AggregateSearchTest.await(()->!engine.state().artistLoading);assertEquals(1,engine.state().artistFailedCount);assertEquals(25,engine.state().artists.size());assertEquals(1,engine.state().results.size());
        engine.retryArtists();AggregateSearchTest.await(()->!engine.state().artistLoading);assertEquals(26,engine.state().artists.size());assertEquals(0,engine.state().artistFailedCount);assertFalse(engine.state().artistHasMore);assertEquals(Arrays.asList(0,25,25),source.artistOffsets);assertEquals(1,source.songCalls);
    }
    @Test public void changingQueryAndClosingRejectLateArtistResponses()throws Exception{
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);gates.add(release);
        Source source=new Source("qq",(q,o,l)->AggregateSearchTest.page(),(q,o,l)->{if(q.equals("old")){entered.countDown();AggregateSearchTest.uninterruptibly(release);}return page(artist(q,q));});
        engine=new AggregateSearch(Collections.singletonList(source.lane()));engine.search("old");assertTrue(entered.await(5,TimeUnit.SECONDS));engine.search("new");AggregateSearchTest.await(()->!engine.state().artistLoading);assertEquals("new",engine.state().artists.get(0).artist.id);release.countDown();Thread.sleep(30);Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals("new",engine.state().artists.get(0).artist.id);
        int[] changes={0};engine.setListener(state->changes[0]++);engine.close();int before=changes[0];engine.search("later");engine.loadMoreArtists();engine.retryArtists();Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals(before,changes[0]);
    }
    @Test public void accountChangeCannotRepopulateOldArtistIdentities()throws Exception{
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);gates.add(release);Source source=new Source("qq",(q,o,l)->AggregateSearchTest.page(),(q,o,l)->{entered.countDown();AggregateSearchTest.uninterruptibly(release);return page(artist("a","歌手"));});engine=new AggregateSearch(Collections.singletonList(source.lane()));engine.search("歌手");assertTrue(entered.await(5,TimeUnit.SECONDS));source.epoch.incrementAndGet();release.countDown();AggregateSearchTest.await(()->!engine.state().artistLoading);assertTrue(engine.state().artists.isEmpty());assertEquals(1,engine.state().artistFailedCount);engine.refresh();AggregateSearchTest.await(()->!engine.state().artistLoading);assertEquals(1,engine.state().artists.size());
    }
    @Test public void unsupportedOrUnconfiguredArtistsAreSkippedAndMalformedLaneDoesNotHideSuccess()throws Exception{
        Source unsupported=new Source("qq",(q,o,l)->AggregateSearchTest.page(),(q,o,l)->{throw new AssertionError("unsupported artist API called");});unsupported.artistSupported=false;Source unavailable=new Source("applemusic",(q,o,l)->{throw new AssertionError("unconfigured songs called");},(q,o,l)->{throw new AssertionError("unconfigured artists called");});unavailable.enabled=false;
        Source valid=new Source("netease",(q,o,l)->{throw new ProviderException(ProviderException.Kind.NETWORK,"song only failed");},(q,o,l)->page(artist("1","歌手")));engine=new AggregateSearch(Arrays.asList(unsupported.lane(),unavailable.lane(),valid.lane()));engine.search("歌手");AggregateSearchTest.await(()->!engine.state().loading&&!engine.state().artistLoading);assertEquals(1,engine.state().artists.size());assertEquals(2,engine.state().artistSkippedCount);assertEquals(0,engine.state().artistFailedCount);assertEquals(1,engine.state().failedCount);
        engine.close();Source malformed=new Source("qq",(q,o,l)->AggregateSearchTest.page(),(q,o,l)->page(artist("","假身份")));engine=new AggregateSearch(Arrays.asList(malformed.lane(),valid.lane()));engine.search("歌手");AggregateSearchTest.await(()->!engine.state().artistLoading);assertEquals(1,engine.state().artistFailedCount);assertEquals(1,engine.state().artists.size());
    }

    @Test public void artistTimeoutDoesNotEraseSongsOrAcceptItsLateResponse()throws Exception{
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);gates.add(release);Source source=new Source("qq",(q,o,l)->AggregateSearchTest.page(AggregateSearchTest.track("qq","q","歌曲","专辑",180000)),(q,o,l)->{entered.countDown();AggregateSearchTest.uninterruptibly(release);return page(artist("late","迟到歌手"));});engine=new AggregateSearch(Collections.singletonList(source.lane()));engine.search("歌手");assertTrue(entered.await(5,TimeUnit.SECONDS));AggregateSearchTest.await(()->!engine.state().loading);Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(15));assertFalse(engine.state().artistLoading);assertEquals(1,engine.state().artistFailedCount);assertEquals(1,engine.state().results.size());release.countDown();Thread.sleep(30);Shadows.shadowOf(Looper.getMainLooper()).idle();assertTrue(engine.state().artists.isEmpty());
    }

}
