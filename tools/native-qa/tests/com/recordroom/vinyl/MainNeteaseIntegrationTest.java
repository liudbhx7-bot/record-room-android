package com.recordroom.vinyl;

import android.app.Activity;
import android.app.Application;
import android.app.Dialog;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.os.Looper;
import android.os.Parcel;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import androidx.media3.datasource.ByteArrayDataSource;
import androidx.media3.datasource.DataSource;
import androidx.media3.exoplayer.ExoPlayer;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowActivity;
import static org.junit.Assert.*;

/** Real MainActivity -> provider page -> album -> owned Media3 service; network bytes are fixtures. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=34,qualifiers="w412dp-h860dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public class MainNeteaseIntegrationTest {
    private static byte[] wav;
    private ServiceController<IntegrationService> serviceController;
    private ActivityController<MainActivity> controller;
    private FixtureProvider provider;
    private MemorySession session;
    public static class IntegrationService extends PlayerService {
        @Override protected DataSource.Factory createDataSourceFactory(PlaybackResource resource){
            return resource==null?super.createDataSourceFactory(null):()->new ByteArrayDataSource(wav);
        }
    }
    @Before public void setup()throws Exception{
        Application app=RuntimeEnvironment.getApplication();app.getSharedPreferences(PlayerService.PREFS,0).edit().clear().commit();OnlineCatalog.clear(app);
        try(java.io.InputStream in=app.getAssets().open("demo/night-1.wav")){wav=in.readAllBytes();}
        session=new MemorySession();provider=new FixtureProvider(session);OnlineMusic.useProviderForTests(app,provider,session);
        new ThemeStore(app).needleSound(false);
        serviceController=Robolectric.buildService(IntegrationService.class).create();
        Shadows.shadowOf(app).setComponentNameAndServiceForBindService(new ComponentName(app,PlayerService.class),service().onBind(new Intent(app,PlayerService.class)));
        controller=Robolectric.buildActivity(MainActivity.class).setup().visible();drain();
    }
    @After public void cleanup()throws Exception{
        provider.logoutRelease.countDown();if(controller!=null)controller.pause().stop().destroy();if(serviceController!=null)serviceController.destroy();
        Shadows.shadowOf(Looper.getMainLooper()).idle();OnlineMusic.useProviderForTests(RuntimeEnvironment.getApplication(),null,null);
    }
    private PlayerService service(){return serviceController.get();}
    private static Object field(Object target,String key)throws Exception{Class<?> type=target.getClass();while(type!=null)try{Field f=type.getDeclaredField(key);f.setAccessible(true);return f.get(target);}catch(NoSuchFieldException missing){type=type.getSuperclass();}throw new NoSuchFieldException(key);}
    private Object main(String key)throws Exception{return field(controller.get(),key);}
    private void call(String name,Class<?>[] types,Object...args)throws Exception{Method method=MainActivity.class.getDeclaredMethod(name,types);method.setAccessible(true);method.invoke(controller.get(),args);}
    private void drain()throws Exception{
        for(int i=0;i<4;i++){
            Field identity=OnlineMusic.class.getDeclaredField("identityWorker");identity.setAccessible(true);((ExecutorService)identity.get(null)).submit(()->{}).get(10,TimeUnit.SECONDS);
            ((ExecutorService)main("io")).submit(()->{}).get(10,TimeUnit.SECONDS);Shadows.shadowOf(Looper.getMainLooper()).idle();
            NeteasePage page=(NeteasePage)main("neteasePage");if(page!=null){ExecutorService worker=(ExecutorService)field(page,"worker");if(!worker.isShutdown())worker.submit(()->{}).get(10,TimeUnit.SECONDS);}
            Shadows.shadowOf(Looper.getMainLooper()).idle();
        }
    }
    private void layout(Dialog dialog){View decor=dialog.getWindow().getDecorView();for(int i=0;i<2;i++){decor.measure(View.MeasureSpec.makeMeasureSpec(412,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(860,View.MeasureSpec.EXACTLY));decor.layout(0,0,412,860);Shadows.shadowOf(Looper.getMainLooper()).idle();}}
    private static View text(View view,String label){if(view instanceof TextView&&label.contentEquals(((TextView)view).getText()))return view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){View found=text(((ViewGroup)view).getChildAt(i),label);if(found!=null)return found;}return null;}
    private static View described(View view,String label){if(label.contentEquals(view.getContentDescription()==null?"":view.getContentDescription()))return view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){View found=described(((ViewGroup)view).getChildAt(i),label);if(found!=null)return found;}return null;}
    private static ListView list(View view){if(view instanceof ListView)return (ListView)view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){ListView found=list(((ViewGroup)view).getChildAt(i));if(found!=null)return found;}return null;}
    private void openFromSettings()throws Exception{
        call("showSettings",new Class<?>[0]);Dialog settings=(Dialog)main("settingsDialog");layout(settings);
        View entry=described(settings.getWindow().getDecorView(),"音乐账户与联动");assertNotNull("Grouped settings opens account management",entry);entry.performClick();drain();Dialog sources=(Dialog)main("sourcesDialog");layout(sources);View netease=described(sources.getWindow().getDecorView(),"网易云音乐 · 账号与曲库");assertNotNull(netease);netease.performClick();drain();assertNotNull(main("neteasePage"));assertTrue(((Dialog)main("neteaseDialog")).isShowing());
    }
    private void tapOnline(String label)throws Exception{
        NeteasePage page=(NeteasePage)main("neteasePage");ListView rows=(ListView)field(page,"list");int position=-1;
        for(int i=0;i<rows.getCount();i++)if(label.equals(field(rows.getAdapter().getItem(i),"title"))){position=i;break;}
        assertTrue("Missing real provider row: "+label,position>=0);rows.setSelection(position);layout((Dialog)main("neteaseDialog"));View row=described(rows,label);assertNotNull(row);row.performClick();drain();
    }
    private void await(BooleanSupplier ready)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);while(!ready.getAsBoolean()&&System.nanoTime()<end){Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20));Thread.sleep(5);}assertTrue("Player did not reach expected state: "+service().getError(),ready.getAsBoolean());}
    private static ArrayList<String> ids(List<Track> tracks){ArrayList<String> ids=new ArrayList<>();for(Track t:tracks)ids.add(LibraryStore.identity(t));return ids;}
    private Bundle snapshot()throws Exception{Bundle state=new Bundle();call("onSaveInstanceState",new Class<?>[]{Bundle.class},state);Parcel p=Parcel.obtain();try{p.writeBundle(state);p.setDataPosition(0);return p.readBundle(MainNeteaseIntegrationTest.class.getClassLoader());}finally{p.recycle();}}

    @Test public void settingsPlaylistAndLastTrackReachOurActualMedia3Queue()throws Exception{
        openFromSettings();assertEquals("42",main("neteaseAccountId"));tapOnline("Fixture Playlist");
        Dialog album=(Dialog)main("albumDialog");assertNotNull(album);layout(album);ListView tracks=list(album.getWindow().getDecorView());assertEquals(42,tracks.getCount());
        tracks.setSelection(40);layout(album);View last=described(tracks,"Remote Song 39，0:18");assertNotNull(last);last.performClick();
        await(()->service().isPlaying()&&service().isFullDurationVerified());
        assertEquals(ids(provider.tracks),ids(service().getQueue()));assertEquals(39,service().getIndex());assertEquals("139",service().getCurrent().id);
        assertTrue(field(service(),"player") instanceof ExoPlayer);assertEquals(18000,service().getDurationMs());assertEquals("139",provider.resolvedId);
        assertNull("Browsing and playback must not open the native NetEase app",Shadows.shadowOf(controller.get()).getNextStartedActivity());
        System.out.println("MAIN_NETEASE_INTEGRATION settings_playlist_last_track_media3_queue=passed fixture_audio=true device_sound=false");
    }
    @Test public void unplayedRemoteAlbumRestoresAfterActivityParcelRecreation()throws Exception{
        openFromSettings();NeteasePage page=(NeteasePage)main("neteasePage");((EditText)field(page,"search")).setText("Remote");Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400));drain();tapOnline("Remote Album");
        assertFalse("Remote playback is deliberately never started",service().getQueue().stream().anyMatch(OnlineMusic::isRemote));assertEquals(40,OnlineCatalog.load(controller.get()).size());
        Bundle state=snapshot();controller.pause().stop().destroy();controller=Robolectric.buildActivity(MainActivity.class).create(state).start().resume().visible();drain();
        Dialog album=(Dialog)main("albumDialog");assertNotNull("Unplayed remote metadata must survive Activity replacement",album);assertTrue(album.isShowing());layout(album);
        ListView rows=list(album.getWindow().getDecorView());assertEquals(42,rows.getCount());assertNotNull(text(album.getWindow().getDecorView(),"Remote Album"));
        rows.setSelection(40);layout(album);assertNotNull(described(rows,"Remote Song 39，0:18"));assertNotNull(main("neteasePage"));
        assertFalse(service().getQueue().stream().anyMatch(OnlineMusic::isRemote));
    }
    @Test public void officialWebIntentAndNullDataSuccessRefreshAccount()throws Exception{
        provider.loggedIn=false;openFromSettings();tapOnline("扫码登录");tapOnline("官方网页登录");
        ShadowActivity.IntentForResult request=Shadows.shadowOf(controller.get()).getNextStartedActivityForResult();assertNotNull(request);assertEquals(103,request.requestCode);
        assertEquals(NeteaseWebLoginActivity.class.getName(),request.intent.getComponent().getClassName());assertTrue(request.intent.getExtras()==null||request.intent.getExtras().isEmpty());
        int reads=provider.accountReads;provider.loggedIn=true;call("onActivityResult",new Class<?>[]{int.class,int.class,Intent.class},103,Activity.RESULT_OK,null);drain();
        assertTrue("Null Intent data must not swallow official login success",provider.accountReads>reads);assertEquals("42",main("neteaseAccountId"));tapOnline("Fixture Playlist");assertNotNull(main("albumDialog"));
    }
    @Test public void logoutClearsRemoteMetadataAndQueueWithoutInterruptingLocalPlayback()throws Exception{
        openFromSettings();tapOnline("Fixture Playlist");((Dialog)main("albumDialog")).dismiss();drain();
        Track local=DemoCatalog.load(controller.get()).get(0);service().setQueue(Arrays.asList(provider.tracks.get(0),local),1,true);await(service()::isPlaying);service().seekTo(5000);await(()->service().getPositionMs()>=5000);
        Object engine=field(service(),"player");tapOnline("退出网易云账户");drain();
        assertFalse(provider.loggedIn);assertEquals(Collections.singletonList(LibraryStore.identity(local)),ids(service().getQueue()));assertSame(engine,field(service(),"player"));assertTrue(service().isPlaying());assertTrue(service().getPositionMs()>=5000);
        assertTrue(OnlineCatalog.load(controller.get()).isEmpty());@SuppressWarnings("unchecked")List<Track> library=(List<Track>)main("library");assertFalse(library.stream().anyMatch(OnlineMusic::isRemote));assertEquals("",main("neteaseAccountId"));
    }

    @Test public void canceledOfficialLoginReturnsToUsableHomeAndGeneratesFreshQr()throws Exception{
        provider.loggedIn=false;openFromSettings();tapOnline("扫码登录");NeteasePage page=(NeteasePage)main("neteasePage");String old=((MusicProvider.QrLogin)field(page,"qr")).key;
        tapOnline("官方网页登录");call("onActivityResult",new Class<?>[]{int.class,int.class,Intent.class},103,Activity.RESULT_CANCELED,null);drain();
        assertNull("Cancelled web flow must not leave a stopped but visible QR",field(page,"qrBitmap"));tapOnline("扫码登录");
        assertNotEquals(old,((MusicProvider.QrLogin)field(page,"qr")).key);assertEquals(2,provider.qrStarts);
    }
    @Test public void confirmedAccountChangeClearsOldDataEvenWhenPlaylistFetchFails()throws Exception{
        openFromSettings();tapOnline("Fixture Playlist");((Dialog)main("albumDialog")).dismiss();drain();Track local=DemoCatalog.load(controller.get()).get(0);
        service().setQueue(Arrays.asList(provider.tracks.get(0),local),1,false);provider.accountId="43";provider.failPlaylists=true;
        ((NeteasePage)main("neteasePage")).refreshAccount();drain();assertEquals("43",main("neteaseAccountId"));assertTrue(OnlineCatalog.load(controller.get()).isEmpty());
        assertEquals(Collections.singletonList(LibraryStore.identity(local)),ids(service().getQueue()));assertEquals("43",OnlineCatalog.accountId(controller.get()));
    }
    @Test public void committedLogoutCleansReplacementActivityEvenAfterOriginalPageCloses()throws Exception{
        openFromSettings();tapOnline("Fixture Playlist");((Dialog)main("albumDialog")).dismiss();drain();Track local=DemoCatalog.load(controller.get()).get(0);service().setQueue(Arrays.asList(provider.tracks.get(0),local),1,false);
        provider.blockLogout=true;NeteasePage page=(NeteasePage)main("neteasePage");ListView rows=(ListView)field(page,"list");rows.setSelection(rows.getCount()-1);layout((Dialog)main("neteaseDialog"));described(rows,"退出网易云账户").performClick();assertTrue(provider.logoutEntered.await(5,TimeUnit.SECONDS));
        controller.pause().stop().destroy();controller=Robolectric.buildActivity(MainActivity.class).setup().visible();((ExecutorService)main("io")).submit(()->{}).get(10,TimeUnit.SECONDS);Shadows.shadowOf(Looper.getMainLooper()).idle();
        provider.logoutRelease.countDown();drain();assertFalse(provider.loggedIn);assertEquals("",main("neteaseAccountId"));assertTrue(OnlineCatalog.load(controller.get()).isEmpty());
        assertEquals(Collections.singletonList(LibraryStore.identity(local)),ids(service().getQueue()));@SuppressWarnings("unchecked") List<Track> library=(List<Track>)main("library");assertFalse(library.stream().anyMatch(OnlineMusic::isRemote));
    }
    @Test public void logoutAfterServiceDestroyFiltersTheSavedQueueBeforeColdRestore()throws Exception{
        openFromSettings();Track local=DemoCatalog.load(controller.get()).get(0);service().setQueue(Arrays.asList(provider.tracks.get(0),local),1,false);service().seekTo(5000);
        provider.blockLogout=true;NeteasePage page=(NeteasePage)main("neteasePage");ListView rows=(ListView)field(page,"list");rows.setSelection(rows.getCount()-1);layout((Dialog)main("neteaseDialog"));described(rows,"退出网易云账户").performClick();assertTrue(provider.logoutEntered.await(5,TimeUnit.SECONDS));
        controller.pause().stop().destroy();controller=null;serviceController.destroy();serviceController=null;provider.logoutRelease.countDown();
        Field identity=OnlineMusic.class.getDeclaredField("identityWorker");identity.setAccessible(true);((ExecutorService)identity.get(null)).submit(()->{}).get(10,TimeUnit.SECONDS);Shadows.shadowOf(Looper.getMainLooper()).idle();
        serviceController=Robolectric.buildService(IntegrationService.class).create();assertEquals(Collections.singletonList(LibraryStore.identity(local)),ids(service().getQueue()));assertEquals(5000,service().getPositionMs());assertFalse(service().isPlaying());
    }
    @Test public void albumArtistAndGuestSongArtistRouteToTheirOwnRemoteIds()throws Exception{
        Track guest=new Track("501","Guest Song","Guest Singer","Group Album","网易云音乐","netease://song/501","",18000,1,"Group Artist","","","netease","51","61","99");
        call("showAlbum",new Class<?>[]{ArrayList.class},new ArrayList<>(Collections.singletonList(guest)));Dialog album=(Dialog)main("albumDialog");layout(album);text(album.getWindow().getDecorView(),"Group Artist").performClick();drain();assertEquals("99",provider.lastArtist);
        call("showArtist",new Class<?>[]{Track.class},guest);drain();assertEquals("61",provider.lastArtist);
    }

    @Test public void cacheEvictionNeverRestoresASilentlyTruncatedRemoteCollection()throws Exception{
        ArrayList<Track> large=new ArrayList<>();for(int i=0;i<10001;i++)large.add(new Track(""+(10000+i),"Large Song "+i,"Artist","Large Collection","网易云音乐","netease://song/"+(10000+i),"",18000,i+1,"Artist","","","netease","51","61"));
        call("rememberOnline",new Class<?>[]{List.class},large);call("showAlbum",new Class<?>[]{ArrayList.class,String.class},large,"Large Collection");drain();assertEquals(10000,OnlineCatalog.load(controller.get()).size());
        Bundle state=snapshot();controller.pause().stop().destroy();controller=Robolectric.buildActivity(MainActivity.class).create(state).start().resume().visible();drain();
        assertNull("Missing metadata must not turn10001songs into an apparently complete10000song page",main("albumDialog"));
        assertEquals("在线曲目缓存不完整，请从对应音乐来源重新打开歌单或专辑",org.robolectric.shadows.ShadowToast.getTextOfLatestToast());
    }

    @Test public void albumArtistAlbumBackRestoresBothOriginalPagesAndSearchQuery()throws Exception{
        openFromSettings();
        NeteasePage origin=(NeteasePage)main("neteasePage");EditText query=(EditText)field(origin,"search");query.setText("Native query");
        // Open the real host album flow, then its real musician link and another album.
        call("showAlbum",new Class<?>[]{ArrayList.class},new ArrayList<>(provider.tracks));Dialog first=(Dialog)main("albumDialog");layout(first);
        text(first.getWindow().getDecorView(),"Remote Artist").performClick();drain();Dialog artist=(Dialog)main("artistDialog");
        android.widget.FrameLayout artistPage=(android.widget.FrameLayout)main("artistPage");
        for(int i=0;i<3;i++){((ExecutorService)field(artistPage,"worker")).submit(()->{}).get(10,TimeUnit.SECONDS);Shadows.shadowOf(Looper.getMainLooper()).idle();}
        assertTrue("Search remains underneath the new musician",((Dialog)field(origin,"dialog")).isShowing());
        assertSame("Opening the native artist must not replace the original search",origin,main("neteasePage"));
        ArtistPage.selectChapter(artistPage,ArtistLibraryData.ALBUMS);layout(artist);
        View card=text(artistPage,"打开专辑");assertNotNull(card);card.performClick();Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(1000));card.performClick();
        for(int i=0;i<3;i++){((ExecutorService)field(artistPage,"worker")).submit(()->{}).get(10,TimeUnit.SECONDS);Shadows.shadowOf(Looper.getMainLooper()).idle();}
        drain();Dialog second=(Dialog)main("albumDialog");assertNotSame(first,second);assertTrue(first.isShowing());
        second.dismiss();drain();assertSame(first,main("albumDialog"));assertTrue(artist.isShowing());
        artist.dismiss();drain();assertSame(origin,main("neteasePage"));assertEquals("Native query",query.getText().toString());assertTrue(first.isShowing());
        first.dismiss();drain();assertNull(main("albumDialog"));assertTrue(((Dialog)field(origin,"dialog")).isShowing());
    }

    private static final class MemorySession implements ProviderSession{
        long generation;public String readCookies(){return "";}public void writeCookies(String ignored){}public void clear(){generation++;}public long generation(){return generation;}
    }
    private static final class FixtureProvider implements MusicProvider{
        final MemorySession session;final ArrayList<Track> tracks=new ArrayList<>();volatile boolean loggedIn=true,failPlaylists,blockLogout;volatile String resolvedId="",accountId="42",lastArtist="";int accountReads,qrStarts;final CountDownLatch logoutEntered=new CountDownLatch(1),logoutRelease=new CountDownLatch(1);
        FixtureProvider(MemorySession session){this.session=session;for(int i=0;i<40;i++)tracks.add(new Track(""+(100+i),"Remote Song "+i,"Remote Artist","Remote Album","网易云音乐","netease://song/"+(100+i),"",18000,i+1,"Remote Artist","","","netease","51","61"));}
        public String id(){return "netease";}public QrLogin beginQrLogin(){qrStarts++;return new QrLogin("fixture-only-"+qrStarts,"https://music.163.com/login?codekey=fixture-only-"+qrStarts,System.currentTimeMillis()+120000);}
        public LoginPoll pollQrLogin(String key){return new LoginPoll(LoginStatus.WAITING_SCAN,"");}
        public Account account(){accountReads++;return new Account(accountId,"Integration Fixture","",0,loggedIn);}
        public List<Playlist> playlists(String uid)throws ProviderException{if(failPlaylists)throw new ProviderException(ProviderException.Kind.NETWORK,"Fixture playlist network failure");return Collections.singletonList(new Playlist("71","Fixture Playlist","","","42",true,tracks.size()));}
        public List<Track> likedTracks(String uid){return tracks;}public List<Track> playlistTracks(String id){return tracks;}
        public Page<Album> searchAlbums(String query,int offset,int limit){return new Page<>(Collections.singletonList(album("51")),1,1,false);}
        public Album album(String id){return new Album(id,"Remote Album","Remote Artist","61","",tracks);}
        public Artist artist(String id){lastArtist=id;return new Artist(id,"Remote Artist","Fixture biography","",tracks,Collections.singletonList(album("51")));}
        public PlaybackResource resolve(Track track){resolvedId=track.id;return new PlaybackResource("https://fixture.invalid/"+track.id+".wav",Collections.emptyMap(),System.currentTimeMillis()+60000,18000);}
        public void logout(){if(blockLogout){logoutEntered.countDown();try{logoutRelease.await(10,TimeUnit.SECONDS);}catch(InterruptedException failure){Thread.currentThread().interrupt();}}loggedIn=false;session.clear();}
    }
}
