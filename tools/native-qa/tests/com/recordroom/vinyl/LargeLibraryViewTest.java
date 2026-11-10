package com.recordroom.vinyl;

import android.app.Activity;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ListView;
import org.robolectric.util.ReflectionHelpers;
import java.util.ArrayList;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=34,qualifiers="w320dp-h480dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public class LargeLibraryViewTest {
    private ActivityController<Activity> controller;
    private LibraryBrowserList list;
    private final List<String> actions=new ArrayList<>();
    private ArrayList<Track> songs;
    private ArrayList<ArrayList<Track>> albums;
    @Before public void setup(){
        controller=Robolectric.buildActivity(Activity.class).setup().visible();
        list=new LibraryBrowserList(controller.get(),new ThemeStore(controller.get()));
        list.setActions(new LibraryBrowserList.Actions(){
            public void album(ArrayList<Track> values,View origin){assertTrue(origin instanceof CoverView);actions.add("album:"+values.get(0).id);}
            public void albumMenu(ArrayList<Track> values){actions.add("album-menu:"+values.get(0).id);}
            public void play(ArrayList<Track> values,int index){assertEquals(1200,values.size());actions.add("play:"+values.get(index).id+":"+index);}
            public void songMenu(Track value){actions.add("song-menu:"+value.id);}
            public void artist(Track value){actions.add("artist:"+value.id);}
        });
        songs=new ArrayList<>();albums=new ArrayList<>();for(int i=0;i<1200;i++){
            Track track=new Track("song"+i,"Song "+i,"Artist","Album "+i,"test","asset:///demo/night-1.wav","",18000,1);
            songs.add(track);ArrayList<Track> album=new ArrayList<>();album.add(track);albums.add(album);
        }
        controller.get().setContentView(list);
    }
    @After public void teardown(){controller.pause().stop().destroy();}

    @Test public void twelveHundredSongsRemainBoundedAndLastSongCanPlayFromFullResultSet(){
        long begin=System.nanoTime();list.submit(new ArrayList<>(),songs,new ArrayList<>(),true);layout(320,480);
        long loaded=System.nanoTime();assertEquals(1201,list.getCount());assertBounded();
        list.setSelection(1200);layout(320,480);View last=find(list,"Song 1199，Artist");assertNotNull(last);last.performClick();last.performLongClick();
        assertEquals(java.util.Arrays.asList("play:song1199:1199","song-menu:song1199"),actions);assertBounded();
        System.out.println("LARGE_LIBRARY_HARNESS songs=1200 submit_and_layout_ms="+((loaded-begin)/1000000.0)+" scroll_to_last_ms="+((System.nanoTime()-loaded)/1000000.0)+" attached_rows="+list.getChildCount()+" device_benchmark=false");
    }

    @Test public void twelveHundredAlbumsAdaptColumnsAndReachLastCoverWithRecycledRows(){
        long begin=System.nanoTime();list.submit(albums,new ArrayList<>(),new ArrayList<>(),false);layout(320,480);
        assertEquals("Approved compact grid uses three columns without a count header",400,list.getCount());assertBounded();
        View first=list.getAdapter().getView(0,null,list);View rebound=list.getAdapter().getView(399,first,list);assertSame(first,rebound);
        ViewGroup row=(ViewGroup)rebound;assertEquals(3,row.getChildCount());row.getChildAt(2).performClick();row.getChildAt(2).performLongClick();
        assertEquals(java.util.Arrays.asList("album:song1199","album-menu:song1199"),actions);
        list.setSelection(399);layout(320,480);assertNotNull(find(list,"Album 1199，Artist"));assertBounded();
        layout(390,480);assertEquals("Standard width uses the approved three-column grid",400,list.getCount());
        list.setSelection(399);layout(390,480);assertNotNull(find(list,"Album 1199，Artist"));assertBounded();
        assertTrue("Only attached rows hold cover Views",countCovers(list)<=list.getChildCount()*3);
        System.out.println("LARGE_LIBRARY_HARNESS albums=1200 submit_layout_and_last_ms="+((System.nanoTime()-begin)/1000000.0)+" attached_rows="+list.getChildCount()+" attached_covers="+countCovers(list)+" device_benchmark=false");
    }

    @Test public void recycledSongCallbacksFollowBindingAndOldGenerationCannotAct(){
        list.submit(new ArrayList<>(),songs,new ArrayList<>(),true);layout(320,480);
        View row=list.getAdapter().getView(1,null,list);View rebound=list.getAdapter().getView(1200,row,list);assertSame(row,rebound);
        rebound.performClick();rebound.performLongClick();assertEquals(java.util.Arrays.asList("play:song1199:1199","song-menu:song1199"),actions);
        // This row is deliberately detached. Deliver its actual listener directly after invalidation:
        // View.performLongClick would fall through into a context menu that requires a parent.
        Object listenerInfo=ReflectionHelpers.getField(rebound,"mListenerInfo");
        View.OnLongClickListener longClick=ReflectionHelpers.getField(listenerInfo,"mOnLongClickListener");
        list.submit(new ArrayList<>(),new ArrayList<>(),new ArrayList<>(),true);rebound.performClick();assertFalse(longClick.onLongClick(rebound));assertEquals(2,actions.size());
        layout(320,480);assertEquals(1,list.getCount());
    }

    @Test public void wideGridHasFourColumnsAndArtistResultsAreActionable(){
        RuntimeEnvironment.setQualifiers("w726dp-h512dp-land-mdpi");
        list.submit(albums,songs,new ArrayList<>(songs.subList(0,1)),true);layout(726,512);
        ViewGroup row=(ViewGroup)list.getAdapter().getView(1,null,list);assertEquals(4,row.getChildCount());
        list.setSelection(list.getCount()-1);layout(726,512);View artist=find(list,"查看音乐人 Artist");assertNotNull(artist);artist.performClick();assertEquals(java.util.Collections.singletonList("artist:song0"),actions);assertBounded();
    }

    @Test public void twelveHundredArtistSongsAndAlbumsUseOneBoundedViewportWithCompleteCallbacks(){
        ArrayList<Track> tracks = new ArrayList<>();
        for (int i = 0; i < 1200; i++) tracks.add(new Track("artist-song"+i,"Artist Song "+i,"Large Artist","Artist Album "+i,"test","asset:///demo/night-1.wav","",180000,1));
        ArrayList<String> calls = new ArrayList<>();
        long started = System.nanoTime();
        FrameLayout page = ArtistPage.create(controller.get(),tracks,tracks.get(0),new ArtistPage.Actions(){
            public void back(){calls.add("back");}
            public void play(ArrayList<Track> queue,int index,boolean shuffle){assertEquals(tracks,queue);assertFalse(shuffle);calls.add("play:"+index);}
            public void album(ArrayList<Track> album){assertEquals(1,album.size());calls.add("album:"+album.get(0).id);}
            public void share(String title,String subtitle,ArrayList<Track> queue){assertEquals(tracks,queue);calls.add("share:"+title);}
        });
        controller.get().setContentView(page);ArtistPage.selectChapter(page,1);layoutPage(page,320,480);
        ListView viewport=(ListView)find(page,"音乐人资料库");assertNotNull(viewport);
        assertEquals("All 1200 artist songs plus one honest source header",1201,viewport.getCount());
        assertTrue(viewport.getChildCount()>0);assertTrue(viewport.getChildCount()<20);
        assertNull(find(page,"播放 Artist Song 1199"));
        long constructed=System.nanoTime();
        viewport.setSelection(1200);layoutPage(page,320,480);
        View lastSong=find(page,"播放 Artist Song 1199");assertNotNull(lastSong);lastSong.performClick();
        assertEquals(java.util.Collections.singletonList("play:1199"),calls);
        assertTrue(viewport.getChildCount()<20);
        View firstSong=viewport.getAdapter().getView(1,null,viewport);
        View reboundSong=viewport.getAdapter().getView(1200,firstSong,viewport);assertSame(firstSong,reboundSong);
        find(reboundSong,"播放 Artist Song 1199").performClick();
        ArtistPage.selectChapter(page,2);layoutPage(page,320,480);
        View firstAlbum=find(page,"打开专辑");assertNotNull(firstAlbum);
        View nextAlbum=find(page,"下一张专辑");assertNotNull(nextAlbum);
        for(int i=1;i<1200;i++){assertTrue("Every album remains reachable",nextAlbum.isEnabled());nextAlbum.performClick();}
        layoutPage(page,320,480);
        assertNotNull(find(page,"Artist Album 1199"));View finalCard=find(page,"打开专辑");assertNotNull(finalCard);
        assertSame("The native deck reuses the same front cover through 1200 real albums",firstAlbum,finalCard);
        finalCard.performClick();Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(1000));finalCard.performClick();finalCard.performClick();
        assertTrue("The final album keeps forward navigation enabled for circular browsing",nextAlbum.isEnabled());
        nextAlbum.performClick();layoutPage(page,320,480);assertNotNull(find(page,"Artist Album 0"));
        assertEquals("Wrapping selection does not itself open or play an album",4,calls.size());
        View wrappedFirst=find(page,"打开专辑");assertNotNull(wrappedFirst);assertSame(firstAlbum,wrappedFirst);
        wrappedFirst.performClick();Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(1300));wrappedFirst.performClick();
        assertEquals("The wrapped first album callback uses its real track ID","album:artist-song0",calls.get(calls.size()-1));
        View previousAlbum=find(page,"上一张专辑");assertNotNull(previousAlbum);assertTrue(previousAlbum.isEnabled());
        previousAlbum.performClick();layoutPage(page,320,480);assertNotNull(find(page,"Artist Album 1199"));
        assertEquals("Reverse wrapping also preserves the two-step open contract",5,calls.size());
        View wrappedLast=find(page,"打开专辑");assertNotNull(wrappedLast);assertSame(firstAlbum,wrappedLast);
        wrappedLast.performClick();Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(1300));wrappedLast.performClick();
        assertTrue("No eager album-cover View inflation",countArtistCovers(page)<=3);
        assertEquals(java.util.Arrays.asList("play:1199","play:1199","album:artist-song1199","album:artist-song1199","album:artist-song0","album:artist-song1199"),calls);
        ArtistPage.selectChapter(page,1);layoutPage(page,320,480);
        ListView returned=(ListView)find(page,"音乐人资料库");assertNotNull(returned);assertTrue(returned.getChildCount()<20);
        assertNotNull("Switching chapters preserves the song scroll position",find(page,"播放 Artist Song 1199"));
        System.out.println("LARGE_LIBRARY_HARNESS artist_songs=1200 artist_albums=1200 create_and_layout_ms="+((constructed-started)/1000000.0)+" scroll_rebind_callbacks_ms="+((System.nanoTime()-constructed)/1000000.0)+" attached_rows="+returned.getChildCount()+" attached_covers="+countArtistCovers(page)+" device_benchmark=false");
    }
    private static void layoutPage(View page,int width,int height){for(int i=0;i<2;i++){page.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));page.layout(0,0,width,height);Shadows.shadowOf(Looper.getMainLooper()).idle();}}
    private static int countArtistCovers(View view){int n=view.getClass().getName().equals("com.recordroom.vinyl.ArtistPage$CoverView")?1:0;if(view instanceof ViewGroup){ViewGroup g=(ViewGroup)view;for(int i=0;i<g.getChildCount();i++)n+=countArtistCovers(g.getChildAt(i));}return n;}

    private void layout(int width,int height){list.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));list.layout(0,0,width,height);Shadows.shadowOf(Looper.getMainLooper()).idle();list.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));list.layout(0,0,width,height);}
    private void assertBounded(){assertTrue("The viewport must have visible content",list.getChildCount()>0);assertTrue("No eager 1200-row inflation",list.getChildCount()<20);}
    private static int countCovers(View view){int n=view instanceof CoverView?1:0;if(view instanceof ViewGroup){ViewGroup g=(ViewGroup)view;for(int i=0;i<g.getChildCount();i++)n+=countCovers(g.getChildAt(i));}return n;}
    private static View find(View view,String text){if(text.contentEquals(view.getContentDescription()==null?"":view.getContentDescription()))return view;if(view instanceof ViewGroup){ViewGroup g=(ViewGroup)view;for(int i=0;i<g.getChildCount();i++){View found=find(g.getChildAt(i),text);if(found!=null)return found;}}return null;}
}
