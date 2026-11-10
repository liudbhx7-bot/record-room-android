package com.recordroom.vinyl;

import android.app.Application;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Looper;
import android.view.View;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.*;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=34)
@LooperMode(LooperMode.Mode.PAUSED)
public class FullCatalogEntryTest {
    ActivityController<MainActivity> controller;ServiceController<PlayerService> service;MainActivity activity;Catalog provider;
    static Track track(String id){return new Track(id,"歌曲 "+id,"歌手","完整专辑","网易云音乐","netease://song/"+id,"",180000,Integer.parseInt(id),"歌手","","","netease","9","8");}
    static class Store implements ProviderSession {public String readCookies(){return "";}public void writeCookies(String value){}public void clear(){}public long generation(){return 0;}}
    static class Catalog implements MusicProvider {
        int albumCalls,searchCalls;boolean failed;
        public String id(){return "netease";}public QrLogin beginQrLogin(){return null;}public LoginPoll pollQrLogin(String key){return null;}
        public Account account(){return new Account("","","",0,false);}public List<Playlist> playlists(String id){return Collections.emptyList();}
        public List<Track> likedTracks(String id){return Collections.emptyList();}public List<Track> playlistTracks(String id){return Collections.emptyList();}
        public Page<Track> searchTracks(String q,int offset,int limit){searchCalls++;return new Page<>(Arrays.asList(track("1"),track("2")),2,2,false);}
        public Page<Album> searchAlbums(String q,int offset,int limit){return new Page<>(Collections.emptyList(),0,0,false);}
        public Album album(String id)throws ProviderException{albumCalls++;assertEquals("9",id);if(failed)throw new ProviderException(ProviderException.Kind.NETWORK,"网络不可用");return new Album("9","完整专辑","歌手","8","",Arrays.asList(track("1"),track("2"),track("3")));}
        public Artist artist(String id){return new Artist(id,"歌手","","",Collections.emptyList(),Collections.emptyList());}
        public PlaybackResource resolve(Track track)throws ProviderException{throw new ProviderException(ProviderException.Kind.UNAVAILABLE,"test");}public void logout(){}
    }
    @Before public void setup()throws Exception{
        Application app=RuntimeEnvironment.getApplication();app.getSharedPreferences(PlayerService.PREFS,0).edit().clear().commit();app.getSharedPreferences("MainActivity",0).edit().clear().commit();
        provider=new Catalog();OnlineMusic.useProviderForTests(app,provider,new Store());
        service=Robolectric.buildService(PlayerService.class).create();Shadows.shadowOf(app).setComponentNameAndServiceForBindService(new ComponentName(app,PlayerService.class),service.get().onBind(new Intent(app,PlayerService.class)));
        controller=Robolectric.buildActivity(MainActivity.class).setup().visible();activity=controller.get();drain();
    }
    @After public void cleanup(){if(controller!=null)controller.pause().stop().destroy();if(service!=null)service.destroy();Shadows.shadowOf(Looper.getMainLooper()).idle();}
    @Test public void openingOneSyncedFavouriteFetchesEveryTrackInRemoteAlbum()throws Exception{
        invoke("openShelfAlbum",new Class<?>[]{ArrayList.class,android.graphics.RectF.class,float.class},new ArrayList<>(Collections.singletonList(track("2"))),null,0f);drain();
        assertEquals(1,provider.albumCalls);Map<?,?> pages=(Map<?,?>)field(activity,"albumPages");assertEquals(1,pages.size());
        List<?> songs=(List<?>)field(pages.values().iterator().next(),"songs");assertEquals(3,songs.size());assertEquals("1",((Track)songs.get(0)).id);assertEquals("3",((Track)songs.get(2)).id);
    }
    @Test public void failedAlbumFetchNeverLabelsSavedSubsetAsFullAlbum()throws Exception{
        provider.failed=true;invoke("openShelfAlbum",new Class<?>[]{ArrayList.class,android.graphics.RectF.class,float.class},new ArrayList<>(Collections.singletonList(track("2"))),null,0f);drain();
        assertEquals(1,provider.albumCalls);assertTrue(((Map<?,?>)field(activity,"albumPages")).isEmpty());
    }
    @Test public void publicSongSearchDoesNotRequireAnOwnedOrCollectedPlaylist()throws Exception{
        android.os.Bundle b=new android.os.Bundle();b.putString("query","未收藏歌曲");invoke("showProvider",new Class<?>[]{String.class,android.os.Bundle.class},"netease",b);
        ProviderLibraryPage page=(ProviderLibraryPage)((Map<?,?>)field(activity,"providerPages")).get("netease");
        ((ExecutorService)field(page,"worker")).submit(()->{}).get(10,TimeUnit.SECONDS);Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(1,provider.searchCalls);assertEquals(2,((List<?>)field(page,"results")).size());
    }
    @Test public void neteaseLogoutAlsoClosesCatalogSearchAndInvalidatesItsPendingState()throws Exception{
        android.os.Bundle b=new android.os.Bundle();b.putString("query","未收藏歌曲");invoke("showProvider",new Class<?>[]{String.class,android.os.Bundle.class},"netease",b);
        ProviderLibraryPage page=(ProviderLibraryPage)((Map<?,?>)field(activity,"providerPages")).get("netease");
        invoke("onOnlineLoggedOut",new Class<?>[0]);
        assertTrue((Boolean)field(page,"closed"));assertFalse(((Map<?,?>)field(activity,"providerPages")).containsKey("netease"));
        Shadows.shadowOf(Looper.getMainLooper()).idle();assertTrue(((List<?>)field(page,"results")).isEmpty());
    }
    @Test public void qqImageVersionIdentifiersAndNormalAlbumIdsGroupTogether(){
        Track a=new Track("a","a","artist","album","QQ音乐","qq://song/a","",1,1,"artist","","","qq","0028zVSS3kX46p_5","artist");
        Track b=new Track("b","b","artist","album","QQ音乐","qq://song/b","",1,2,"artist","","","qq","0028zVSS3kX46p","artist");
        assertEquals(a.albumKey(),b.albumKey());assertEquals("0028zVSS3kX46p",Track.fromJson(a.toJson()).albumId);
    }
    private void drain()throws Exception{((ExecutorService)field(activity,"io")).submit(()->{}).get(10,TimeUnit.SECONDS);Shadows.shadowOf(Looper.getMainLooper()).idle();}
    private static Object field(Object value,String name)throws Exception{Field f=value.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(value);}
    private void invoke(String name,Class<?>[] types,Object... args)throws Exception{Method m=MainActivity.class.getDeclaredMethod(name,types);m.setAccessible(true);m.invoke(activity,args);}
}
