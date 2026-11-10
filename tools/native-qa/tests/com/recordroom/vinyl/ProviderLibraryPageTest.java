package com.recordroom.vinyl;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.Looper;
import android.view.View;
import android.widget.EditText;
import android.widget.ListView;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import static org.junit.Assert.*;

/** Fixture responses prove native routing/cancellation, not acceptance by a live provider account. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=34,qualifiers="w412dp-h860dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public class ProviderLibraryPageTest {
    ActivityController<Activity> controller;ProviderLibraryPage page;FakeProvider provider;
    List<Track> opened,played;String heading;int playedAt=-1,logins,accounts,closed,logouts;MusicProvider.Account account;
    final ProviderLibraryPage.Actions actions=new ProviderLibraryPage.Actions(){
        public void openTracks(List<Track> values,String name){opened=values;heading=name;}
        public void play(List<Track> values,int at){played=values;playedAt=at;}
        public void officialLogin(){logins++;}
        public void onAccount(MusicProvider.Account value){accounts++;account=value;}
        public void onLogout(){logouts++;}
    };
    @Before public void setup(){controller=Robolectric.buildActivity(Activity.class).setup().visible();provider=new FakeProvider();}
    @After public void cleanup(){provider.release.countDown();if(page!=null)page.close();controller.pause().stop().destroy();Shadows.shadowOf(Looper.getMainLooper()).idle();}
    static Object field(Object target,String name)throws Exception{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}
    void show(Bundle state)throws Exception{page=ProviderLibraryPage.restore(controller.get(),provider,"测试来源",provider::search,actions,state);page.setOnClosed(()->closed++);page.show();drain();}
    void drain()throws Exception{for(int i=0;i<3;i++){((ExecutorService)field(page,"worker")).submit(()->{}).get(10,TimeUnit.SECONDS);Shadows.shadowOf(Looper.getMainLooper()).idle();}}
    View row(String title)throws Exception{ListView list=(ListView)field(page,"list");for(int i=0;i<list.getCount();i++)if(title.equals(field(list.getAdapter().getItem(i),"title")))return list.getAdapter().getView(i,null,list);return null;}
    void tap(String title)throws Exception{View target=row(title);assertNotNull("Missing row: "+title,target);target.performClick();drain();}
    void query(String value)throws Exception{((EditText)field(page,"search")).setText(value);Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(351));drain();}

    @Test public void unauthenticatedPageOffersOfficialLoginWithoutInventingAnAccount()throws Exception{
        provider.loggedIn=false;show(null);assertNotNull(row("官方登录"));assertNull(row("喜欢的歌曲"));assertFalse(account.loggedIn);assertEquals(0,provider.playlistReads);
        tap("官方登录");assertEquals(1,logins);assertFalse(account.loggedIn);assertEquals(0,provider.qrCalls);assertFalse(provider.onMain);
    }
    @Test public void expiredAccountKeepsTheReasonAndOffersOfficialLogin()throws Exception{
        provider.accountFailure=new ProviderException(ProviderException.Kind.LOGIN_EXPIRED,"读取QQ账户失败：官方授权已失效（code 1000）");show(null);
        assertNotNull(row("登录已失效"));assertNotNull(row("官方登录"));assertNotNull(row("重试"));assertEquals(0,provider.playlistReads);assertFalse(account.loggedIn);tap("官方登录");assertEquals(1,logins);
    }
    @Test public void playlistFailureKeepsVerifiedAccountAndLikesActionWithoutClaimingEmptyPlaylists()throws Exception{
        provider.playlistFailure=new ProviderException(ProviderException.Kind.UNAVAILABLE,"读取收藏歌单失败：官方服务暂未允许请求（code -1）");show(null);
        assertTrue(account.loggedIn);assertNotNull(row("Fixture User"));assertNotNull(row("喜欢的歌曲"));assertNotNull(row("暂时无法读取"));assertNull(row("暂无本人歌单"));tap("喜欢的歌曲");assertEquals(provider.tracks,opened);
    }
    @Test public void realPlaylistAndLikesKeepEveryTrackAndHeading()throws Exception{
        show(null);assertEquals("fixture-user",account.id);assertNotNull(row("本人歌单"));assertNotNull(row("Owned fixture"));assertNotNull(row("Collected fixture"));
        tap("Owned fixture");assertEquals(provider.tracks,opened);assertEquals("Owned fixture",heading);assertEquals("owned",provider.lastPlaylist);
        tap("喜欢的歌曲");assertEquals(provider.tracks,opened);assertEquals("喜欢的歌曲",heading);assertEquals("fixture-user",provider.lastLikedUser);assertFalse(provider.onMain);
    }
    @Test public void paginatedSearchRetriesAndRecyclesWithoutPlayingWrongRows()throws Exception{
        show(null);query("Song");assertEquals(30,((List<?>)field(page,"results")).size());View old=row("Song 0");
        provider.failNextSearch=true;tap("加载更多");assertNotNull(row("重试"));assertEquals(30,((List<?>)field(page,"results")).size());
        tap("重试");assertEquals(45,((List<?>)field(page,"results")).size());assertNull(row("加载更多"));tap("Song 44");assertEquals(provider.tracks,played);assertEquals(44,playedAt);
        played=null;query("different");old.performClick();assertNull("Rows bound before a new search cannot play the new results at an old index",played);
        assertFalse(provider.onMain);
    }
    @Test public void snapshotRestoresQueryAndLoadedPageDepthWithoutStoringTracksOrCredentials()throws Exception{
        show(null);query("Song");tap("加载更多");Bundle state=page.snapshot();assertEquals("Song",state.getString("query"));assertEquals(45,state.getInt("search_offset"));
        assertFalse(state.containsKey("tracks"));assertFalse(state.containsKey("token"));assertFalse(state.containsKey("cookies"));page.close();assertEquals(1,closed);
        provider.searchOffsets.clear();show(state);assertEquals(Arrays.asList(0,30),provider.searchOffsets);assertEquals(45,((List<?>)field(page,"results")).size());assertEquals("Song",((EditText)field(page,"search")).getText().toString());tap("Song 44");assertEquals(44,playedAt);
    }
    @Test public void dismissCancelsAnInflightRequestAndRejectsItsLateResult()throws Exception{
        provider.blockAccount=true;page=new ProviderLibraryPage(controller.get(),provider,"测试来源",provider::search,actions);page.setOnClosed(()->closed++);Dialog dialog=page.show();assertTrue(provider.entered.await(5,TimeUnit.SECONDS));
        dialog.dismiss();Shadows.shadowOf(Looper.getMainLooper()).idle();provider.release.countDown();ExecutorService executor=(ExecutorService)field(page,"worker");assertTrue(executor.awaitTermination(5,TimeUnit.SECONDS));Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertTrue(executor.isShutdown());assertEquals(1,closed);assertEquals(0,accounts);assertNull(opened);assertFalse(dialog.isShowing());
    }
    @Test public void committedLogoutStillNotifiesTheLivingHostAfterPageDismissal()throws Exception{
        provider.providerId="qq";show(null);provider.blockLogout=true;View logout=row("退出测试来源账户");assertNotNull(logout);logout.performClick();assertTrue(provider.entered.await(5,TimeUnit.SECONDS));
        page.close();provider.release.countDown();Field identity=OnlineMusic.class.getDeclaredField("identityWorker");identity.setAccessible(true);((ExecutorService)identity.get(null)).submit(()->{}).get(10,TimeUnit.SECONDS);Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(1,logouts);assertFalse(provider.loggedIn);assertEquals(1,closed);
    }
    private static final class FakeProvider implements MusicProvider {
        final ArrayList<Track> tracks=new ArrayList<>();final ArrayList<Integer> searchOffsets=new ArrayList<>();
        final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        boolean loggedIn=true,accountSupported=true,onMain,failNextSearch,blockAccount,blockLogout;int accountReads,playlistReads,qrCalls;String lastPlaylist,lastLikedUser,providerId="fixture";ProviderException accountFailure,playlistFailure;
        FakeProvider(){for(int i=0;i<45;i++)tracks.add(new Track("fixture-"+i,"Song "+i,"Fixture Artist","Fixture Album","fixture","file:///fixture-"+i,"",180000,i+1,"Fixture Artist"));}
        void check(){onMain|=Looper.myLooper()==Looper.getMainLooper();}
        public String id(){return providerId;}
        public boolean supportsAccount(){return accountSupported;}
        public Account account()throws ProviderException{check();accountReads++;if(!accountSupported)throw new AssertionError("An anonymous website must never be treated as an account");if(accountFailure!=null)throw accountFailure;if(blockAccount){entered.countDown();try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException cancelled){/* Deliberately return late: the page still must reject the callback. */}}return new Account("fixture-user","Fixture User","",0,loggedIn);}
        public List<Playlist> playlists(String id)throws ProviderException{check();playlistReads++;if(playlistFailure!=null)throw playlistFailure;return Arrays.asList(new Playlist("owned","Owned fixture","","",id,true,45),new Playlist("collected","Collected fixture","","","another",false,45));}
        public List<Track> likedTracks(String id){check();lastLikedUser=id;return tracks;}
        public List<Track> playlistTracks(String id){check();lastPlaylist=id;return tracks;}
        MusicProvider.Page<Track> search(String query,int offset,int limit)throws ProviderException{check();searchOffsets.add(offset);if(failNextSearch){failNextSearch=false;throw new ProviderException(ProviderException.Kind.NETWORK,"Fixture temporary failure");}int end=Math.min(tracks.size(),offset+limit);return new MusicProvider.Page<>(tracks.subList(offset,end),tracks.size(),end,end<tracks.size());}
        public QrLogin beginQrLogin(){qrCalls++;throw new UnsupportedOperationException();}public LoginPoll pollQrLogin(String key){throw new UnsupportedOperationException();}
        public Page<Album> searchAlbums(String query,int offset,int limit){throw new UnsupportedOperationException();}public Album album(String id){throw new UnsupportedOperationException();}public Artist artist(String id){throw new UnsupportedOperationException();}public PlaybackResource resolve(Track track){throw new UnsupportedOperationException();}public void logout(){check();if(blockLogout){entered.countDown();try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException ignored){Thread.currentThread().interrupt();}}loggedIn=false;}
    }
}
