package com.recordroom.vinyl;

import android.app.Activity;
import android.animation.ValueAnimator;
import android.os.Bundle;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import static org.junit.Assert.*;

/** Regression: restoring a shelf item that is only present on a later network page. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=34,qualifiers="w412dp-h860dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
public class ArtistShelfRestoreReviewTest {
    private static Object field(Object object,String name)throws Exception{Field field=object.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(object);}
    private static void layout(FrameLayout page){page.measure(View.MeasureSpec.makeMeasureSpec(412,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(860,View.MeasureSpec.EXACTLY));page.layout(0,0,412,860);Shadows.shadowOf(Looper.getMainLooper()).idle();}
    private static View find(View view,String description){if(description.equals(String.valueOf(view.getContentDescription())))return view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){View found=find(((ViewGroup)view).getChildAt(i),description);if(found!=null)return found;}return null;}
    private static void drain(FrameLayout page)throws Exception{for(int i=0;i<8;i++){((ExecutorService)field(page,"worker")).submit(()->{}).get(5,TimeUnit.SECONDS);Shadows.shadowOf(Looper.getMainLooper()).idle();layout(page);}}
    @Test public void lateCatalogPagesPreserveRestoredAlbumIdentityAndOpenIntent()throws Exception{
        ActivityController<Activity> controller=Robolectric.buildActivity(Activity.class).setup().visible();
        Catalog provider=new Catalog();MemorySession session=new MemorySession();OnlineMusic.useProviderForTests(controller.get(),provider,session);
        final int[] opened={0},played={0};
        FrameLayout page=ArtistPage.create(controller.get(),Collections.singletonList(provider.seed),provider.seed,new ArtistPage.Actions(){
            public void back(){}public void play(ArrayList<Track> songs,int index,boolean shuffle){played[0]++;}
            public void album(ArrayList<Track> songs){opened[0]++;}public void share(String title,String subtitle,ArrayList<Track> songs){}
        });
        Bundle state=new Bundle();state.putInt("chapter",2);state.putFloat("album_position",100);state.putString("album_key","netease\u001falbum\u001falbum100");state.putBoolean("albums_expanded",true);
        ArtistPage.restoreState(page,state);controller.get().setContentView(page);layout(page);
        try{
            assertTrue(provider.started.await(5,TimeUnit.SECONDS));provider.release.countDown();
            for(int i=0;i<8;i++){((ExecutorService)field(page,"worker")).submit(()->{}).get(5,TimeUnit.SECONDS);Shadows.shadowOf(Looper.getMainLooper()).idle();layout(page);}
            assertTrue((Boolean)field(page,"catalogComplete"));
            Bundle restored=ArtistPage.saveState(page);
            assertEquals("Later catalog pages must not overwrite the pending restored identity",state.getString("album_key"),restored.getString("album_key"));
            assertEquals(100f,restored.getFloat("album_position"),.01f);
            assertTrue("Restoring a selected album must preserve the user's open intent",restored.getBoolean("albums_expanded"));
            assertEquals(0,opened[0]);assertEquals(0,played[0]);
        }finally{provider.release.countDown();controller.pause().stop().destroy();Shadows.shadowOf(Looper.getMainLooper()).idle();OnlineMusic.useProviderForTests(controller.get(),null,null);}
    }
    @Test public void changingChapterCancelsLateRequestedAlbumWithoutPlaying()throws Exception{lateRequestedAlbum(0);}
    @Test public void changingSelectionCancelsLateRequestedAlbumWithoutPlaying()throws Exception{lateRequestedAlbum(1);}
    @Test public void closingSleeveCancelsLateRequestedAlbumWithoutPlaying()throws Exception{lateRequestedAlbum(2);}
    @Test public void leavingDuringOpeningPreservesIntentWithoutEnteringTracks()throws Exception{
        ActivityController<Activity> controller=Robolectric.buildActivity(Activity.class).setup().visible();final int[] opened={0},played={0};
        Track song=new Track("local0","Song","Artist","Album","local","asset:///demo/night-1.wav","",180000,1);
        FrameLayout page=ArtistPage.create(controller.get(),Collections.singletonList(song),song,new ArtistPage.Actions(){public void back(){}public void play(ArrayList<Track> songs,int index,boolean shuffle){played[0]++;}public void album(ArrayList<Track> songs){opened[0]++;}public void share(String title,String subtitle,ArrayList<Track> songs){}});
        controller.get().setContentView(page);ArtistPage.selectChapter(page,2);layout(page);
        try{
            find(page,"打开专辑").performClick();AlbumStackView stack=(AlbumStackView)field(field(page,"albumBrowser"),"stack");
            ValueAnimator animation=(ValueAnimator)field(stack,"sleeveAnimator");assertNotNull(animation);animation.setCurrentPlayTime(200);
            assertTrue(stack.getSleeveOpenProgress()>0);assertFalse(stack.isSleeveOpen());
            assertTrue("A half-finished opening still expresses an open intent",ArtistPage.saveState(page).getBoolean("albums_expanded"));
            ArtistPage.selectChapter(page,0);ArtistPage.selectChapter(page,2);layout(page);
            assertTrue(((AlbumStackView)field(field(page,"albumBrowser"),"stack")).isSleeveOpeningOrOpen());assertEquals(0,opened[0]);assertEquals(0,played[0]);
        }finally{controller.pause().stop().destroy();Shadows.shadowOf(Looper.getMainLooper()).idle();}
    }
    private void lateRequestedAlbum(int change)throws Exception{
        ActivityController<Activity> controller=Robolectric.buildActivity(Activity.class).setup().visible();
        Catalog provider=new Catalog();provider.total=2;provider.blockAlbum=true;provider.release.countDown();OnlineMusic.useProviderForTests(controller.get(),provider,new MemorySession());final int[] opened={0},played={0};
        FrameLayout page=ArtistPage.create(controller.get(),Collections.singletonList(provider.seed),provider.seed,new ArtistPage.Actions(){public void back(){}public void play(ArrayList<Track> songs,int index,boolean shuffle){played[0]++;}public void album(ArrayList<Track> songs){opened[0]++;}public void share(String title,String subtitle,ArrayList<Track> songs){}});
        controller.get().setContentView(page);layout(page);
        try{
            drain(page);ArtistPage.selectChapter(page,2);layout(page);
            View open=find(page,"打开专辑");assertNotNull(open);open.performClick();
            Object stack=field(field(page,"albumBrowser"),"stack");ValueAnimator animation=(ValueAnimator)field(stack,"sleeveAnimator");assertNotNull(animation);animation.end();
            assertEquals("Opening the sleeve must not open tracks",0,opened[0]);assertEquals(0,played[0]);
            View tracks=find(page,"查看曲目");assertNotNull(tracks);tracks.performClick();assertTrue(provider.albumEntered.await(5,TimeUnit.SECONDS));
            if(change==0)ArtistPage.selectChapter(page,0);else{View control=find(page,change==1?"下一张专辑":"收起唱片");assertNotNull(control);control.performClick();}
            provider.albumRelease.countDown();drain(page);
            assertEquals("A late response must not replace the user's newer view",0,opened[0]);assertEquals(0,played[0]);assertFalse((Boolean)field(page,"albumLoading"));
        }finally{provider.albumRelease.countDown();controller.pause().stop().destroy();Shadows.shadowOf(Looper.getMainLooper()).idle();OnlineMusic.useProviderForTests(controller.get(),null,null);}
    }
    private static final class MemorySession implements ProviderSession{public String readCookies(){return "";}public void writeCookies(String value){}public void clear(){}public long generation(){return 0;}}
    private static final class Catalog implements MusicProvider{
        final CountDownLatch started=new CountDownLatch(1),release=new CountDownLatch(1);
        final CountDownLatch albumEntered=new CountDownLatch(1),albumRelease=new CountDownLatch(1);int total=120;boolean blockAlbum;
        final Track seed=new Track("song0","Song","Artist","Album 0","网易云音乐","netease://song/song0","",180000,1,"Artist","","","netease","album0","artist0");
        Album item(int index){return new Album("album"+index,"Album "+index,"Artist","artist0","",Collections.emptyList());}
        public String id(){return "netease";}
        public Artist artist(String id){started.countDown();try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}return new Artist(id,"Artist","Biography","",Collections.singletonList(seed),Collections.singletonList(item(0)));}
        public Page<Album> artistAlbums(String id,int offset,int limit){List<Album> rows=new ArrayList<>();int end=Math.min(total,offset+limit);for(int i=offset;i<end;i++)rows.add(item(i));return new Page<>(rows,total,end,end<total);}
        public Album album(String id){if(!blockAlbum)throw new AssertionError("State restoration must not load tracks");albumEntered.countDown();boolean done=false;while(!done)try{albumRelease.await(5,TimeUnit.SECONDS);done=true;}catch(InterruptedException ignored){}return new Album(id,"Album","Artist","artist0","",Collections.singletonList(seed));}
        public QrLogin beginQrLogin(){throw new UnsupportedOperationException();}public LoginPoll pollQrLogin(String key){throw new UnsupportedOperationException();}public Account account(){throw new UnsupportedOperationException();}public List<Playlist> playlists(String userId){throw new UnsupportedOperationException();}public List<Track> likedTracks(String userId){throw new UnsupportedOperationException();}public List<Track> playlistTracks(String playlistId){throw new UnsupportedOperationException();}public Page<Album> searchAlbums(String query,int offset,int limit){throw new UnsupportedOperationException();}public PlaybackResource resolve(Track track){throw new UnsupportedOperationException();}public void logout(){}
    }
}
