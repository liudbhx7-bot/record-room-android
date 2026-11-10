package com.recordroom.vinyl;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Bundle;
import android.os.Looper;
import android.os.Parcel;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ListView;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
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

/** Deterministic UI/lifecycle tests use explicit fixtures; no claim of live account acceptance. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=34,qualifiers="w412dp-h860dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public class NeteasePageTest {
    private ActivityController<Activity> controller;
    private NeteasePage page;
    private FakeProvider provider;
    private final ArrayList<String> callbacks=new ArrayList<>();
    private List<Track> opened,played;
    private int playAt;
    private final NeteasePage.Actions actions=new NeteasePage.Actions(){
        public void openTracks(List<Track> tracks,String title){opened=tracks;callbacks.add("open:"+title);}
        public void play(List<Track> tracks,int at){played=tracks;playAt=at;callbacks.add("play");}
        public void onLogout(){callbacks.add("logout");}
        public void onAccount(MusicProvider.Account account){callbacks.add("account:"+account.loggedIn);}
        public void officialLogin(){callbacks.add("official-login");}
    };
    @Before public void setup(){controller=Robolectric.buildActivity(Activity.class).setup().visible();provider=new FakeProvider();}
    @After public void cleanup(){provider.release.countDown();if(page!=null)page.close();controller.pause().stop().destroy();}
    private static Object field(Object target,String name)throws Exception{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}
    private void show()throws Exception{page=new NeteasePage(controller.get(),provider,actions);page.show();drain();}
    private void drain()throws Exception{for(int i=0;i<4;i++){Field identity=OnlineMusic.class.getDeclaredField("identityWorker");identity.setAccessible(true);((ExecutorService)identity.get(null)).submit(()->{}).get(10,TimeUnit.SECONDS);((ExecutorService)field(page,"worker")).submit(()->{}).get(10,TimeUnit.SECONDS);Shadows.shadowOf(Looper.getMainLooper()).idle();}layout();}
    private void layout()throws Exception{Dialog dialog=(Dialog)field(page,"dialog");View decor=dialog.getWindow().getDecorView();for(int i=0;i<2;i++){decor.measure(View.MeasureSpec.makeMeasureSpec(412,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(860,View.MeasureSpec.EXACTLY));decor.layout(0,0,412,860);Shadows.shadowOf(Looper.getMainLooper()).idle();}}
    private ListView list()throws Exception{return (ListView)field(page,"list");}
    private void tap(String label)throws Exception{
        ListView list=list();int found=-1;for(int i=0;i<list.getCount();i++)if(label.equals(field(list.getAdapter().getItem(i),"title"))){found=i;break;}
        assertTrue("Missing actionable row: "+label,found>=0);list.setSelection(found);layout();View view=find(list,label);assertNotNull("Row must be in viewport",view);assertTrue(view.performClick());
    }
    private boolean has(String label)throws Exception{for(int i=0;i<list().getCount();i++)if(label.equals(field(list().getAdapter().getItem(i),"title")))return true;return false;}
    private static View find(View view,String label){if(label.contentEquals(view.getContentDescription()==null?"":view.getContentDescription()))return view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){View found=find(((ViewGroup)view).getChildAt(i),label);if(found!=null)return found;}return null;}
    private void query(String query)throws Exception{((EditText)field(page,"search")).setText(query);Shadows.shadowOf(Looper.getMainLooper()).idleFor(400,TimeUnit.MILLISECONDS);drain();}
    private static Bundle parcel(Bundle state){Parcel p=Parcel.obtain();try{p.writeBundle(state);p.setDataPosition(0);return p.readBundle(NeteasePageTest.class.getClassLoader());}finally{p.recycle();}}
    private void render(String name)throws Exception{layout();View decor=((Dialog)field(page,"dialog")).getWindow().getDecorView();Bitmap image=Bitmap.createBitmap(412,860,Bitmap.Config.ARGB_8888);try{decor.draw(new Canvas(image));File out=new File(System.getProperty("vinyl.output.dir"),name);try(FileOutputStream stream=new FileOutputStream(out)){assertTrue(image.compress(Bitmap.CompressFormat.PNG,100,stream));}}finally{image.recycle();}}

    @Test public void actualRowsUseCreatedSubscribedLikedAndFullPlaylistResultsOffMainThread()throws Exception{
        show();assertTrue(has("Created List"));assertFalse(has("Saved List"));
        tap("Created List");drain();assertEquals(provider.tracks,opened);assertTrue(callbacks.contains("open:Created List"));
        tap("收藏的歌单");assertTrue(has("Saved List"));assertFalse(has("Created List"));
        tap("Saved List");drain();assertEquals(provider.tracks,opened);assertEquals("22",provider.lastPlaylist);
        tap("喜欢的歌曲");drain();assertEquals(provider.tracks,opened);assertTrue(callbacks.contains("open:喜欢的歌曲"));
        render("netease-account-fixture-api34.png");tap("退出网易云账户");drain();assertTrue(callbacks.contains("logout"));assertTrue(has("扫码登录"));assertFalse(provider.loggedIn);assertFalse(provider.uiThreadCall);
    }
    @Test public void searchPaginationSurvivesParcelAndArtistBackKeepsCompleteResults()throws Exception{
        show();query("Music");assertEquals(Arrays.asList(0),provider.offsets);assertTrue(has("Album 1"));tap("加载更多专辑");drain();assertEquals(Arrays.asList(0,2),provider.offsets);assertTrue(has("Album 3"));assertFalse(has("加载更多专辑"));
        Bundle state=parcel(page.snapshot());assertEquals(3,state.getInt("search_offset"));page.close();page=NeteasePage.restore(controller.get(),provider,actions,state);page.show();drain();assertTrue(has("Album 3"));assertEquals(Arrays.asList(0,2,0,2),provider.offsets);
        tap("查看音乐人 Test Artist");drain();assertTrue(has("音乐人简介"));tap("Song 39");assertEquals(provider.tracks,played);assertEquals(39,playAt);
        ((Dialog)field(page,"dialog")).onBackPressed();layout();assertTrue(has("Album 3"));assertEquals("Cached back must not refetch or lose pagination",4,provider.offsets.size());
        tap("Album 3");drain();assertEquals("3",provider.lastAlbum);assertEquals(provider.tracks,opened);assertTrue("Album preserves canonical album heading in host",callbacks.contains("open:null"));render("netease-search-fixture-api34.png");assertFalse(provider.uiThreadCall);
    }
    @Test public void qrEncodesActualProviderUrlAndRecreationNeverPersistsKey()throws Exception{
        provider.loggedIn=false;show();tap("扫码登录");drain();Bitmap qr=(Bitmap)field(page,"qrBitmap");assertNotNull(qr);
        int[] pixels=new int[qr.getWidth()*qr.getHeight()];qr.getPixels(pixels,0,qr.getWidth(),0,0,qr.getWidth(),qr.getHeight());
        assertEquals(provider.qrUrl,new MultiFormatReader().decode(new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(qr.getWidth(),qr.getHeight(),pixels)))).getText());
        render("netease-qr-fixture-api34.png");Bundle state=parcel(page.snapshot());assertFalse(state.toString().contains("secret-login-key"));assertFalse(state.containsKey("key"));
        page.close();page=NeteasePage.restore(controller.get(),provider,actions,state);page.show();drain();assertEquals(2,provider.qrStarts);
        provider.pollStatus=MusicProvider.LoginStatus.WAITING_CONFIRM;Shadows.shadowOf(Looper.getMainLooper()).idleFor(1800,TimeUnit.MILLISECONDS);drain();assertEquals("请在网易云音乐中确认登录",field(page,"qrStatus"));
        provider.pollStatus=MusicProvider.LoginStatus.SUCCESS;Shadows.shadowOf(Looper.getMainLooper()).idleFor(1800,TimeUnit.MILLISECONDS);drain();assertTrue(has("Created List"));assertTrue(callbacks.contains("account:true"));assertFalse(provider.uiThreadCall);
    }
    @Test public void expiredQrHasRealRefreshAndClosedLateRequestCannotPublish()throws Exception{
        provider.loggedIn=false;show();tap("扫码登录");drain();provider.pollStatus=MusicProvider.LoginStatus.EXPIRED;
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(1800,TimeUnit.MILLISECONDS);drain();assertNull(field(page,"qrBitmap"));assertEquals("二维码已过期，请刷新",field(page,"qrStatus"));tap("刷新二维码");drain();assertEquals(2,provider.qrStarts);
        ((Dialog)field(page,"dialog")).onBackPressed();drain();provider.blockSearch=true;((EditText)field(page,"search")).setText("blocked");Shadows.shadowOf(Looper.getMainLooper()).idleFor(400,TimeUnit.MILLISECONDS);assertTrue(provider.entered.await(5,TimeUnit.SECONDS));
        int before=callbacks.size();final int[] closes={0};page.setOnClosed(()->closes[0]++);page.close();page.close();provider.release.countDown();assertTrue(((ExecutorService)field(page,"worker")).awaitTermination(5,TimeUnit.SECONDS));Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals(1,closes[0]);assertEquals(before,callbacks.size());assertFalse(((Dialog)field(page,"dialog")).isShowing());
    }
    @Test public void replacedQueryIgnoresLateResultAndLoginFailureOffersActualRecovery()throws Exception{
        show();provider.blockSearch=true;((EditText)field(page,"search")).setText("blocked");Shadows.shadowOf(Looper.getMainLooper()).idleFor(400,TimeUnit.MILLISECONDS);assertTrue(provider.entered.await(5,TimeUnit.SECONDS));
        ((EditText)field(page,"search")).setText("Current");Shadows.shadowOf(Looper.getMainLooper()).idleFor(400,TimeUnit.MILLISECONDS);provider.release.countDown();drain();assertTrue(has("“Current”的专辑"));assertFalse(has("Stale Album"));
        provider.loginFailure=true;((Dialog)field(page,"dialog")).onBackPressed();drain();assertTrue(has("重新扫码登录"));tap("重新扫码登录");drain();assertNotNull(field(page,"qrBitmap"));
    }
    @Test public void officialLoginStopsQrPollingAndRefreshesActualAccountOnReturn()throws Exception{
        provider.loggedIn=false;show();tap("扫码登录");drain();assertTrue(has("官方网页登录"));
        tap("官方网页登录");assertTrue(callbacks.contains("official-login"));int before=provider.pollCalls;
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(6,TimeUnit.SECONDS);drain();assertEquals("Polling stops while official verification owns login",before,provider.pollCalls);
        provider.loggedIn=true;page.refreshAccount();drain();assertTrue(has("Created List"));assertTrue(callbacks.contains("account:true"));
        tap("退出网易云账户");drain();tap("扫码登录");drain();provider.challenge=true;
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(1800,TimeUnit.MILLISECONDS);drain();assertTrue(has("官方网页登录"));tap("官方网页登录");assertEquals(2,Collections.frequency(callbacks,"official-login"));
    }

    @Test public void officialLoginRemainsActionableWhileQrPollIsBlocked()throws Exception{
        provider.loggedIn=false;show();tap("扫码登录");drain();provider.blockPoll=true;
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(1800,TimeUnit.MILLISECONDS);assertTrue(provider.entered.await(5,TimeUnit.SECONDS));
        tap("官方网页登录");assertTrue("Official verification must not wait for a slow QR endpoint",callbacks.contains("official-login"));
        int before=callbacks.size();provider.release.countDown();drain();assertEquals("Canceled poll must not replace official-login UI",before,callbacks.size());
    }

    @Test public void albumFetchRestoresSearchViewportAfterLoadingRowWasLaidOut()throws Exception{
        provider.largeSearch=true;show();query("Large");ListView results=list();
        results.setSelectionFromTop(31,-11);layout();int position=results.getFirstVisiblePosition();int top=results.getChildAt(0).getTop();
        assertEquals(31,position);View album=find(results,"Album 16");assertNotNull(album);
        provider.blockAlbum=true;album.performClick();assertTrue(provider.entered.await(5,TimeUnit.SECONDS));layout();
        assertEquals("The loading row really replaced the scrolled collection",1,results.getCount());assertEquals(0,results.getFirstVisiblePosition());
        provider.release.countDown();drain();assertEquals(provider.tracks,opened);assertEquals("16",provider.lastAlbum);
        assertEquals("Opening the album must preserve the underlying search row",position,results.getFirstVisiblePosition());
        assertEquals("Opening the album must preserve the row's pixel offset",top,results.getChildAt(0).getTop());
        assertNotNull(find(results,"Album 16"));assertFalse(provider.uiThreadCall);
    }

    @Test public void pendingAlbumRecreationRebuildsSearchBeforeOpeningAlbum()throws Exception{
        provider.largeSearch=true;show();query("Large");list().setSelectionFromTop(31,-11);layout();
        provider.blockAlbum=true;find(list(),"Album 16").performClick();assertTrue(provider.entered.await(5,TimeUnit.SECONDS));layout();Bundle state=parcel(page.snapshot());assertEquals("16",state.getString("pending_album"));
        page.close();provider.release.countDown();page=NeteasePage.restore(controller.get(),provider,actions,state);page.show();drain();
        assertTrue("Search context must be re-fetched before replaying pending album navigation",has("Album 16"));assertTrue(has("Album 30"));assertNotNull(opened);assertEquals("16",provider.lastAlbum);assertEquals(31,list().getFirstVisiblePosition());
    }
    @Test public void pendingAlbumRecreationRebuildsArtistInsteadOfShowingLoggedOutHome()throws Exception{
        show();page.showArtist("9");drain();provider.blockAlbum=true;tap("Album 1");assertTrue(provider.entered.await(5,TimeUnit.SECONDS));Bundle state=parcel(page.snapshot());
        page.close();provider.release.countDown();page=NeteasePage.restore(controller.get(),provider,actions,state);page.show();drain();
        assertTrue(has("音乐人简介"));assertTrue(has("Song 39"));assertFalse(has("扫码登录"));assertNotNull(field(page,"artist"));assertEquals(provider.tracks,opened);
    }

    private static final class FakeProvider implements MusicProvider {
        volatile boolean loggedIn=true,uiThreadCall,blockSearch,blockPoll,blockAlbum,largeSearch,loginFailure,challenge;volatile LoginStatus pollStatus=LoginStatus.WAITING_SCAN;
        int qrStarts,pollCalls;String lastPlaylist,lastAlbum;final String qrUrl="https://music.163.com/login?codekey=secret-login-key";
        final List<Integer> offsets=new ArrayList<>();final ArrayList<Track> tracks=new ArrayList<>();
        final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        FakeProvider(){for(int i=0;i<40;i++)tracks.add(new Track(""+(100+i),"Song "+i,"Test Artist","Test Album","网易云音乐","netease://song/"+(100+i),"",180000,i+1,"Test Artist","","","netease","1","9"));}
        private void call(){if(Looper.myLooper()==Looper.getMainLooper())uiThreadCall=true;}
        public String id(){return "netease";}
        public QrLogin beginQrLogin(){call();qrStarts++;return new QrLogin("secret-login-key",qrUrl,System.currentTimeMillis()+120000);}
        public LoginPoll pollQrLogin(String key)throws ProviderException{call();pollCalls++;if(blockPoll){entered.countDown();boolean waiting=true;while(waiting)try{waiting=!release.await(5,TimeUnit.SECONDS);}catch(InterruptedException ignored){}}if(challenge)throw new ProviderException(ProviderException.Kind.LOGIN_CHALLENGE,"网易云要求额外验证，请使用官方网页登录");if(pollStatus==LoginStatus.SUCCESS)loggedIn=true;return new LoginPoll(pollStatus,"");}
        public Account account()throws ProviderException{call();if(loginFailure)throw new ProviderException(ProviderException.Kind.LOGIN_EXPIRED,"登录已失效，请重新扫码");return new Account("7","Fixture User","",0,loggedIn);}
        public List<Playlist> playlists(String uid){call();return Arrays.asList(new Playlist("11","Created List","","","7",true,40),new Playlist("22","Saved List","","","8",false,40));}
        public List<Track> likedTracks(String uid){call();return tracks;}
        public List<Track> playlistTracks(String id){call();lastPlaylist=id;return tracks;}
        public Page<Album> searchAlbums(String query,int offset,int limit){call();if(blockSearch&&query.equals("blocked")){entered.countDown();boolean waiting=true;while(waiting)try{waiting=!release.await(5,TimeUnit.SECONDS);}catch(InterruptedException ignored){}return new Page<>(Collections.singletonList(new Album("99","Stale Album","Old","8","",tracks)),1,1,false);}offsets.add(offset);if(largeSearch){ArrayList<Album> values=new ArrayList<>();for(int i=1;i<=30;i++)values.add(albumValue(""+i));return new Page<>(values,30,30,false);}return offset==0?new Page<>(Arrays.asList(albumValue("1"),albumValue("2")),3,2,true):new Page<>(Collections.singletonList(albumValue("3")),3,3,false);}
        private Album albumValue(String id){return new Album(id,"Album "+id,"Test Artist","9","",tracks);}
        public Album album(String id){call();lastAlbum=id;if(blockAlbum){entered.countDown();try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}}return albumValue(id);}
        public Artist artist(String id){call();return new Artist(id,"Test Artist","A real biography fixture for layout validation.","",tracks,Collections.singletonList(albumValue("1")));}
        public PlaybackResource resolve(Track track){throw new UnsupportedOperationException();}
        public void logout(){call();loggedIn=false;}
    }
}
