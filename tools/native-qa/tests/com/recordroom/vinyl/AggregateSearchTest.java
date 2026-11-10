package com.recordroom.vinyl;

import android.os.Looper;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=34) @LooperMode(LooperMode.Mode.PAUSED)
public class AggregateSearchTest {
    AggregateSearch engine;final ArrayList<CountDownLatch> releases=new ArrayList<>();
    @After public void cleanup(){for(CountDownLatch release:releases)release.countDown();if(engine!=null)engine.close();Shadows.shadowOf(Looper.getMainLooper()).idle();}
    static Track track(String source,String id,String title,String album,long duration){return new Track(id,title,"Artist",album,source,source+"://song/"+id,"asset:///demo/night-v2.png",duration,1,"Artist","","",source,"album","artist");}
    static MusicProvider.Page<Track> page(Track... tracks){return new MusicProvider.Page<>(Arrays.asList(tracks),tracks.length,tracks.length,false);}
    static AggregateSearch.Source source(Fake provider,int priority){return new AggregateSearch.Source(provider,provider.epoch::get,()->true,()->priority);}
    static void await(BooleanSupplier condition)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(!condition.getAsBoolean()&&System.nanoTime()<end){Shadows.shadowOf(Looper.getMainLooper()).idle();Thread.sleep(5);}Shadows.shadowOf(Looper.getMainLooper()).idle();assertTrue("Timed out waiting for asynchronous search",condition.getAsBoolean());}
    CountDownLatch gate(){CountDownLatch gate=new CountDownLatch(1);releases.add(gate);return gate;}

    @Test public void paidLabelsDescribeAllCandidatesWithoutChangingPlaybackPriority(){
        Track vip=track("qq","v","Song","Album",180000).withPlaybackAccess(Track.PlaybackAccess.VIP);
        Track standard=track("netease","s","Song","Album",180000).withPlaybackAccess(Track.PlaybackAccess.STANDARD);
        Track unknown=track("netease","u","Song","Album",180000);
        Track purchase=track("qq","p","Song","Album",180000).withPlaybackAccess(Track.PlaybackAccess.PURCHASE);
        assertEquals("需 VIP",new AggregateSearch.Result("vip",Arrays.asList(vip)).accessLabel());
        assertEquals("需单独购买",new AggregateSearch.Result("paid",Arrays.asList(purchase)).accessLabel());
        for(Track other:Arrays.asList(standard,unknown)){
            AggregateSearch.Result result=new AggregateSearch.Result("mixed",Arrays.asList(other,vip));
            assertEquals("部分版本需 VIP",result.accessLabel());assertSame(other,result.displayTrack);assertEquals(Arrays.asList(other,vip),result.alternatives);
            assertEquals("部分版本需购买",new AggregateSearch.Result("mixed",Arrays.asList(purchase,other)).accessLabel());
        }
        assertEquals("需 VIP 或单独购买",new AggregateSearch.Result("paid",Arrays.asList(purchase,vip)).accessLabel());
        assertEquals("部分版本需 VIP / 购买",new AggregateSearch.Result("mixed",Arrays.asList(unknown,purchase,vip)).accessLabel());
        assertEquals("",new AggregateSearch.Result("unknown",Arrays.asList(unknown,standard)).accessLabel());
    }

    @Test public void independentSuccessAppearsWhileAnotherSearchIsBlockedAndOneFails()throws Exception{
        CountDownLatch entered=new CountDownLatch(1),release=gate();
        Fake qq=new Fake("qq",(q,o,l)->{entered.countDown();uninterruptibly(release);return page(track("qq","q","Song A","Album",180000));});
        Fake net=new Fake("netease",(q,o,l)->page(track("netease","n","Song B","Album",180000)));
        Fake apple=new Fake("applemusic",(q,o,l)->{throw new ProviderException(ProviderException.Kind.NETWORK,"private account error");});
        engine=new AggregateSearch(Arrays.asList(source(qq,0),source(net,1),source(apple,2)));engine.search("Song");assertTrue(entered.await(5,TimeUnit.SECONDS));
        await(()->engine.state().results.size()==1&&engine.state().failedCount==1);assertTrue(engine.state().loading);assertEquals("Song B",engine.state().results.get(0).displayTrack.title);
        release.countDown();await(()->!engine.state().loading);assertEquals(2,engine.state().results.size());assertEquals(1,engine.state().failedCount);
        assertFalse(qq.onMain||net.onMain||apple.onMain);
    }

    @Test public void exactVersionMergesButRetainsEveryPlayableAlternativeAndAuthorizedPreference()throws Exception{
        Track first=track("qq","q","Song","Album",180000),second=track("netease","n","Song","Album",180000);
        Fake qq=new Fake("qq",(q,o,l)->page(first,track("qq","live","Song (Live)","Album",180000),track("qq","unknown","Song","",0)));
        Fake net=new Fake("netease",(q,o,l)->page(second));
        engine=new AggregateSearch(Arrays.asList(source(qq,10),source(net,0)));engine.search("Song");await(()->!engine.state().loading);
        assertEquals(3,engine.state().results.size());AggregateSearch.Result merged=engine.state().results.get(0);
        assertEquals(Arrays.asList(second,first),merged.alternatives);assertSame(second,merged.displayTrack);
        assertEquals("Song (Live)",engine.state().results.get(1).displayTrack.title);
    }

    @Test public void subsequentPagesDoNotRepeatSuccessfulLanesAndRetryKeepsExistingResults()throws Exception{
        ArrayList<Track> initial=new ArrayList<>();for(int i=0;i<25;i++)initial.add(track("qq","q"+i,"Song "+i,"Album",180000));
        int[] attempts={0};Fake qq=new Fake("qq",(q,o,l)->{if(o==0)return new MusicProvider.Page<>(initial,26,25,true);if(attempts[0]++==0)throw new ProviderException(ProviderException.Kind.NETWORK,"failed");return new MusicProvider.Page<>(Collections.singletonList(track("qq","last","Last","Album",180000)),26,26,false);});
        Fake net=new Fake("netease",(q,o,l)->page(track("netease","n","Other","Album",180000)));
        engine=new AggregateSearch(Arrays.asList(source(qq,0),source(net,1)));engine.search("Song");await(()->!engine.state().loading);assertEquals(26,engine.state().results.size());
        engine.loadMore();await(()->!engine.state().loading);assertEquals(26,engine.state().results.size());assertEquals(1,engine.state().failedCount);
        engine.retryFailed();await(()->!engine.state().loading);assertEquals(27,engine.state().results.size());assertFalse(engine.state().hasMore);
        assertEquals(Arrays.asList(0,25,25),qq.offsets);assertEquals(Collections.singletonList(0),net.offsets);
    }

    @Test public void changedQueryRejectsEvenAnUncooperativeLateResponse()throws Exception{
        CountDownLatch entered=new CountDownLatch(1),release=gate();
        Fake qq=new Fake("qq",(q,o,l)->{if(q.equals("old")){entered.countDown();uninterruptibly(release);}return page(track("qq",q,q,"Album",180000));});
        engine=new AggregateSearch(Collections.singletonList(source(qq,0)));engine.search("old");assertTrue(entered.await(5,TimeUnit.SECONDS));engine.search("new");
        await(()->!engine.state().loading&&engine.state().results.size()==1);assertEquals("new",engine.state().results.get(0).displayTrack.title);
        release.countDown();Thread.sleep(20);Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals("new",engine.state().results.get(0).displayTrack.title);
    }

    @Test public void logoutDuringRequestCannotRepopulateResultsAndRefreshCanSearchAgain()throws Exception{
        CountDownLatch entered=new CountDownLatch(1),release=gate();
        Fake qq=new Fake("qq",(q,o,l)->{entered.countDown();uninterruptibly(release);return page(track("qq","q","Song","Album",180000));});
        engine=new AggregateSearch(Collections.singletonList(source(qq,0)));engine.search("Song");assertTrue(entered.await(5,TimeUnit.SECONDS));qq.epoch.incrementAndGet();release.countDown();
        await(()->!engine.state().loading);assertTrue(engine.state().results.isEmpty());assertEquals(1,engine.state().failedCount);
        engine.refresh();await(()->!engine.state().loading);assertEquals(1,engine.state().results.size());
    }

    @Test public void unconfiguredServiceIsSkippedWithoutAccountOrSearchRequests()throws Exception{
        Fake apple=new Fake("applemusic",(q,o,l)->{throw new AssertionError("Unconfigured service was contacted");});
        engine=new AggregateSearch(Collections.singletonList(new AggregateSearch.Source(apple,apple.epoch::get,()->false,()->0)));engine.search("Song");await(()->!engine.state().loading);
        assertEquals(1,engine.state().skippedCount);assertEquals(0,engine.state().failedCount);assertTrue(apple.offsets.isEmpty());
    }

    @Test public void invalidPaginationIsAnIndependentFailureAndCloseSuppressesCallbacks()throws Exception{
        Fake qq=new Fake("qq",(q,o,l)->new MusicProvider.Page<>(Collections.emptyList(),100,0,true));
        engine=new AggregateSearch(Collections.singletonList(source(qq,0)));int[] callbacks={0};engine.setListener(state->callbacks[0]++);engine.search("Song");await(()->!engine.state().loading);assertEquals(1,engine.state().failedCount);
        engine.close();int before=callbacks[0];engine.search("later");engine.loadMore();Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals(before,callbacks[0]);
    }

    @Test public void retiredOnlineUrisCannotBeResolvedOrReadAsSupportedCaches()throws Exception{
        android.content.Context context=org.robolectric.RuntimeEnvironment.getApplication();
        Track retired=track("retired-public-site","old","Old","Album",180000);assertTrue(OnlineMusic.isRemote(retired));assertEquals("unsupported",OnlineMusic.providerId(retired));
        try{OnlineMusic.resolve(context,retired);fail();}catch(ProviderException expected){assertEquals(ProviderException.Kind.UNAVAILABLE,expected.kind);}
        try{OnlineCatalog.load(context,"retired-public-site");fail();}catch(IllegalArgumentException expected){}
        Track local=new Track("local","Saved metadata","Artist","Album","本地音乐","content://audio/local","",180000,1);
        assertFalse(OnlineMusic.isRemote(local));assertEquals("Saved metadata",local.title);
    }

    static void uninterruptibly(CountDownLatch latch){boolean done=false;while(!done)try{latch.await(5,TimeUnit.SECONDS);done=true;}catch(InterruptedException ignored){}}
    interface Search {MusicProvider.Page<Track> get(String query,int offset,int limit)throws ProviderException;}
    static final class Fake implements MusicProvider {
        final String id;final Search search;final AtomicLong epoch=new AtomicLong();final List<Integer> offsets=Collections.synchronizedList(new ArrayList<>());boolean onMain;
        Fake(String id,Search search){this.id=id;this.search=search;}
        public String id(){return id;}public Page<Track> searchTracks(String query,int offset,int limit)throws ProviderException{onMain|=Looper.myLooper()==Looper.getMainLooper();offsets.add(offset);return search.get(query,offset,limit);}
        public Account account(){throw new AssertionError("Search cannot perform account sync");}public QrLogin beginQrLogin(){throw new UnsupportedOperationException();}public LoginPoll pollQrLogin(String key){throw new UnsupportedOperationException();}
        public List<Playlist> playlists(String id){throw new UnsupportedOperationException();}public List<Track> likedTracks(String id){throw new UnsupportedOperationException();}public List<Track> playlistTracks(String id){throw new UnsupportedOperationException();}
        public Page<Album> searchAlbums(String q,int o,int l){throw new UnsupportedOperationException();}public Album album(String id){throw new UnsupportedOperationException();}public Artist artist(String id){throw new UnsupportedOperationException();}public PlaybackResource resolve(Track t){throw new UnsupportedOperationException();}public void logout(){epoch.incrementAndGet();}
    }
}
