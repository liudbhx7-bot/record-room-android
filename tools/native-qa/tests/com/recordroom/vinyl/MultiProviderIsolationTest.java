package com.recordroom.vinyl;

import android.app.Activity;
import android.content.Context;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
import static org.junit.Assert.*;

/** Real native state transitions with isolated provider fixtures; no network login or audio claim. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=34, qualifiers="w412dp-h860dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public class MultiProviderIsolationTest {
    private static final String[] PROVIDERS={"netease","qq","qishui"};
    private Context context;
    private ActivityController<Activity> activity;
    private ServiceController<PlayerService> service;
    private FrameLayout artist;
    private List<Track> played;
    private int playedAt=-1;

    @Before public void setup(){
        context=RuntimeEnvironment.getApplication();
        context.getSharedPreferences(PlayerService.PREFS,Context.MODE_PRIVATE).edit().clear().commit();
    }
    @After public void close()throws Exception{
        if(activity!=null)activity.pause().stop().destroy();
        if(service!=null)service.destroy();
        drainPlayerWrites();
        OnlineMusic.useProviderForTests(context,null,null);
    }
    private static Track track(String provider){return track(provider,"shared-id");}
    private static Track track(String provider,String id){
        String source="netease".equals(provider)?"网易云音乐":"qq".equals(provider)?"QQ音乐":"qishui".equals(provider)?"汽水音乐":"local";
        return new Track(id,provider+" track","Shared Artist",provider+" album",source,
                provider.isEmpty()?"asset:///demo/night-1.wav":provider+"://song/"+id,"",180000,1,
                "Shared Artist","","2024",provider,provider+"-album","shared-artist");
    }
    private static List<Track> mixed(){return Arrays.asList(track("netease"),track("qq"),track("qishui"),track(""));}
    private static List<String> identities(List<Track> tracks){ArrayList<String> ids=new ArrayList<>();for(Track t:tracks)ids.add(LibraryStore.identity(t));return ids;}
    private static List<Track> except(List<Track> tracks,String provider){ArrayList<Track> result=new ArrayList<>();for(Track t:tracks)if(!provider.equals(OnlineMusic.providerId(t)))result.add(t);return result;}
    private static Object field(Object target,String name)throws Exception{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}
    private void drainPlayerWrites()throws Exception{((ExecutorService)field(PlayerStateStore.get(context),"writer")).submit(()->{}).get(10,TimeUnit.SECONDS);}
    private void coldCatalog()throws Exception{Field f=OnlineCatalog.class.getDeclaredField("memory");f.setAccessible(true);((Map<?,?>)f.get(null)).clear();}
    private void logout(String id)throws Exception{logout(new FixtureProvider(id,null));}
    private void logout(MusicProvider provider)throws Exception{
        AtomicBoolean complete=new AtomicBoolean();
        OnlineMusic.logout(context,provider,(committed,failure)->{assertTrue("Credential removal must commit",committed);assertNull(failure);complete.set(true);});
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(!complete.get()&&System.nanoTime()<deadline){Shadows.shadowOf(Looper.getMainLooper()).idle();Thread.sleep(5);}
        assertTrue("Logout callback did not finish",complete.get());
    }
    private void stageAll()throws Exception{
        for(String id:PROVIDERS){OnlineCatalog.stage(context,id,id+"-account",mixed());OnlineCatalog.persist(context,id);}
    }

    @Test public void neteaseLogoutKeepsOtherProvidersInNameOnlyArtistSongsAndAlbums()throws Exception{
        MemorySession session=new MemorySession();FixtureProvider provider=new FixtureProvider("netease",session);
        OnlineMusic.useProviderForTests(context,provider,session);
        activity=Robolectric.buildActivity(Activity.class).setup().visible();
        new LibraryStore(context).create("Mixed source playlist",mixed());
        // A local name-only browse has no catalog identity tying it to one provider.
        artist=ArtistPage.create(activity.get(),mixed(),track(""),new ArtistPage.Actions(){
            public void back(){}public void album(ArrayList<Track> songs){}
            public void share(String title,String subtitle,ArrayList<Track> songs){}
            public void play(ArrayList<Track> songs,int index,boolean shuffle){played=new ArrayList<>(songs);playedAt=index;}
        });
        activity.get().setContentView(artist);
        for(int i=0;i<3;i++){((ExecutorService)field(artist,"worker")).submit(()->{}).get(10,TimeUnit.SECONDS);Shadows.shadowOf(Looper.getMainLooper()).idle();}
        ArtistPage.selectChapter(artist,ArtistLibraryData.ALBUMS);
        ArtistLibraryData before=(ArtistLibraryData)field(artist,"data");
        assertEquals(4,before.songs.size());assertEquals(4,before.albums.size());assertFalse(before.providerSongs);
        logout(provider);
        ArtistLibraryData after=(ArtistLibraryData)field(artist,"data");
        List<String> retained=identities(except(mixed(),"netease"));
        assertEquals("Only NetEase songs leave the artist page",retained,identities(after.songs));
        ArrayList<Track> albumTracks=new ArrayList<>();for(ArtistLibraryData.Collection album:after.albums)albumTracks.addAll(album.tracks);assertEquals("Album views retain QQ, Qishui and local entries",retained,identities(albumTracks));
        assertEquals(ArtistLibraryData.ALBUMS,ArtistPage.chapter(artist));assertFalse(after.providerSongs);
        ArtistPage.selectChapter(artist,ArtistLibraryData.SONGS);
        artist.measure(View.MeasureSpec.makeMeasureSpec(412,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(860,View.MeasureSpec.EXACTLY));artist.layout(0,0,412,860);
        View row=find(artist,"播放 qq track");assertNotNull("QQ song remains actionable",row);row.performClick();
        assertEquals(retained,identities(played));assertEquals("qq",played.get(playedAt).provider);
    }

    @Test public void catalogFiltersForeignTracksAndAccountSwitchOnlyReplacesItsOwnSource()throws Exception{
        stageAll();coldCatalog();
        for(String id:PROVIDERS){assertEquals(Collections.singletonList(LibraryStore.identity(track(id))),identities(OnlineCatalog.load(context,id)));assertEquals(id+"-account",OnlineCatalog.accountId(context,id));}
        Track replacement=track("qq","next-account-song");
        OnlineCatalog.stage(context,"qq","another-qq-account",Arrays.asList(replacement,track("netease"),track("qishui")));
        OnlineCatalog.persist(context,"qq");coldCatalog();
        assertEquals(Collections.singletonList(LibraryStore.identity(replacement)),identities(OnlineCatalog.load(context,"qq")));
        assertEquals("another-qq-account",OnlineCatalog.accountId(context,"qq"));
        for(String id:Arrays.asList("netease","qishui")){assertEquals(id+"-account",OnlineCatalog.accountId(context,id));assertEquals(Collections.singletonList(LibraryStore.identity(track(id))),identities(OnlineCatalog.load(context,id)));}
    }

    @Test public void eachProviderLogoutClearsOnlyItsOwnCatalogOnDisk()throws Exception{
        for(String removed:PROVIDERS){
            stageAll();logout(removed);coldCatalog();
            for(String id:PROVIDERS){
                if(id.equals(removed)){assertTrue(OnlineCatalog.load(context,id).isEmpty());assertEquals("",OnlineCatalog.accountId(context,id));}
                else{assertEquals(id+"-account",OnlineCatalog.accountId(context,id));assertEquals(Collections.singletonList(LibraryStore.identity(track(id))),identities(OnlineCatalog.load(context,id)));}
            }
        }
    }

    @Test public void liveQueueLogoutPreservesOtherSourceSelectionProgressAndSettings()throws Exception{
        service=Robolectric.buildService(PlayerService.class).create();PlayerService player=service.get();
        List<Track> queue=mixed();
        for(int removed=0;removed<PROVIDERS.length;removed++){
            int selected=(removed+1)%PROVIDERS.length;
            player.setQueue(queue,selected,false);player.seekTo(17000);player.setPlayMode(PlayerService.MODE_SHUFFLE);player.setVolume(.37f);
            logout(PROVIDERS[removed]);
            assertEquals(identities(except(queue,PROVIDERS[removed])),identities(player.getQueue()));
            assertEquals(LibraryStore.identity(queue.get(selected)),LibraryStore.identity(player.getCurrent()));
            assertEquals(17000,player.getPositionMs());assertFalse(player.isPlaying());assertEquals(PlayerService.MODE_SHUFFLE,player.getPlayMode());assertEquals(.37f,player.getVolume(),.001f);
            PlayerStateStore.State saved=PlayerStateStore.get(context).restore();
            assertEquals(identities(player.getQueue()),identities(saved.queue.tracks));assertEquals(player.getIndex(),saved.index);assertEquals(17000,saved.position);
        }
    }

    @Test public void removingCurrentSourceChoosesRetainedNeighborWithoutForeignProgress()throws Exception{
        service=Robolectric.buildService(PlayerService.class).create();PlayerService player=service.get();
        List<Track> queue=mixed();
        for(int removed=0;removed<PROVIDERS.length;removed++){
            player.setQueue(queue,removed,false);player.seekTo(21000);player.setVolume(.42f);
            logout(PROVIDERS[removed]);
            assertEquals(identities(except(queue,PROVIDERS[removed])),identities(player.getQueue()));
            assertEquals(LibraryStore.identity(queue.get(removed+1)),LibraryStore.identity(player.getCurrent()));
            assertEquals(0,player.getPositionMs());assertFalse(player.isPlaying());assertEquals(.42f,player.getVolume(),.001f);
        }
    }

    @Test public void logoutWithoutServicePreservesOtherSourceThroughColdRestore()throws Exception{
        service=Robolectric.buildService(PlayerService.class).create();PlayerService player=service.get();
        player.setQueue(mixed(),1,false);player.seekTo(19000);player.setPlayMode(PlayerService.MODE_REPEAT_ONE);player.setVolume(.29f);
        service.destroy();service=null;drainPlayerWrites();
        logout("netease");logout("qishui");drainPlayerWrites();
        Field singleton=PlayerStateStore.class.getDeclaredField("instance");singleton.setAccessible(true);singleton.set(null,null);
        service=Robolectric.buildService(PlayerService.class).create();player=service.get();
        assertEquals(identities(Arrays.asList(track("qq"),track(""))),identities(player.getQueue()));
        assertEquals("qq",player.getCurrent().provider);assertEquals(0,player.getIndex());assertEquals(19000,player.getPositionMs());
        assertEquals(PlayerService.MODE_REPEAT_ONE,player.getPlayMode());assertEquals(.29f,player.getVolume(),.001f);
    }

    private static View find(View view,String name){
        if(name.contentEquals(view.getContentDescription()==null?"":view.getContentDescription())||(view instanceof TextView&&name.contentEquals(((TextView)view).getText())))return view;
        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){View found=find(group.getChildAt(i),name);if(found!=null)return found;}}
        return null;
    }
    private static final class MemorySession implements ProviderSession{
        private long generation;
        public String readCookies(){return "";}public void writeCookies(String value){}public void clear(){generation++;}public long generation(){return generation;}
    }
    private static final class FixtureProvider implements MusicProvider{
        private final String id;private final MemorySession session;
        FixtureProvider(String id,MemorySession session){this.id=id;this.session=session;}
        public String id(){return id;}public void logout(){if(session!=null)session.clear();}
        public Artist artist(String artistId){return new Artist(artistId,"Shared Artist","Fixture biography","",Collections.singletonList(track(id)),Collections.emptyList());}
        public QrLogin beginQrLogin(){throw new UnsupportedOperationException();}public LoginPoll pollQrLogin(String key){throw new UnsupportedOperationException();}
        public Account account(){throw new UnsupportedOperationException();}public List<Playlist> playlists(String userId){throw new UnsupportedOperationException();}
        public List<Track> likedTracks(String userId){throw new UnsupportedOperationException();}public List<Track> playlistTracks(String playlistId){throw new UnsupportedOperationException();}
        public Page<Album> searchAlbums(String query,int offset,int limit){throw new UnsupportedOperationException();}public Album album(String id){throw new UnsupportedOperationException();}
        public PlaybackResource resolve(Track track){throw new AssertionError("Isolation tests must not request remote audio");}
    }
}
