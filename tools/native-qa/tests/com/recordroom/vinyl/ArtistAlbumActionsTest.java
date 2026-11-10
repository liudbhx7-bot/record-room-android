package com.recordroom.vinyl;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=34,qualifiers="w412dp-h860dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE) @LooperMode(LooperMode.Mode.PAUSED)
public class ArtistAlbumActionsTest {
    ActivityController<Activity> controller;FrameLayout page;ArrayList<Track> viewed,played;
    @Before public void start(){controller=Robolectric.buildActivity(Activity.class).setup().visible();}
    @After public void stop(){controller.pause().stop().destroy();Shadows.shadowOf(Looper.getMainLooper()).idle();OnlineMusic.useProviderForTests(controller.get(),null,null);}
    static Track track(String id,String album){return new Track(id,"歌曲 "+id,"音乐人",album,"本地音乐","content://audio/"+id,"asset:///demo/night-v2.png",180000,1);}
    void show(){List<Track> songs=Arrays.asList(track("one","专辑一"),track("two","专辑一"),track("three","专辑二"));page=ArtistPage.create(controller.get(),songs,songs.get(0),new ArtistPage.Actions(){public void back(){}public void album(ArrayList<Track> tracks){viewed=tracks;}public void play(ArrayList<Track> tracks,int index,boolean shuffle){played=tracks;}public void share(String t,String s,ArrayList<Track> tracks){}});controller.get().setContentView(page);ArtistPage.selectChapter(page,2);Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(300));layout(page,412,860);}
    static void layout(View view,int width,int height){view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));view.layout(0,0,width,height);}
    AlbumStackView stack(){Object browser=ReflectionHelpers.getField(page,"albumBrowser");return ReflectionHelpers.getField(browser,"stack");}
    static View text(View root,String label){return AggregateSearchPageTest.findText(root,label);}
    @Test public void firstSleeveTapImmediatelyEnablesTracksAndPlayBeforeAnimationFinishes(){
        show();AlbumStackView stack=stack();stack.performClick();assertTrue(stack.isSleeveOpeningOrOpen());assertFalse(stack.isSleeveOpen());
        View tracks=text(page,"查看曲目"),play=text(page,"播放专辑");assertNotNull(tracks);assertNotNull(play);assertTrue(tracks.isEnabled());assertTrue(play.isEnabled());assertNull(viewed);assertNull(played);
        tracks.performClick();assertEquals(2,viewed.size());assertEquals("one",viewed.get(0).id);play.performClick();assertEquals(2,played.size());assertEquals("two",played.get(1).id);
    }
    @Test public void realTapDuringCenteringSelectsTheNewSleeveAndKeepsItsActions(){
        show();AlbumStackView stack=stack();stack.performClick();ValueAnimator animation=ReflectionHelpers.getField(stack,"sleeveAnimator");animation.setCurrentPlayTime(120);
        Bitmap frame=Bitmap.createBitmap(stack.getWidth(),stack.getHeight(),Bitmap.Config.ARGB_8888);try{stack.draw(new Canvas(frame));}finally{frame.recycle();}
        float targetX=-1,targetY=-1;outer:for(int y=8;y<stack.getHeight()-8;y+=6)for(int x=8;x<stack.getWidth()-8;x+=4){int hit=ReflectionHelpers.callInstanceMethod(stack,"hitTest",ReflectionHelpers.ClassParameter.from(float.class,(float)x),ReflectionHelpers.ClassParameter.from(float.class,(float)y));if(hit==1){targetX=x;targetY=y;break outer;}}
        assertTrue("Second real sleeve must be reachable",targetX>=0);long now=SystemClock.uptimeMillis();for(int action:new int[]{MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP}){MotionEvent e=MotionEvent.obtain(now,now+(action==MotionEvent.ACTION_UP?35:0),action,targetX,targetY,0);try{stack.onTouchEvent(e);}finally{e.recycle();}}
        assertEquals(1,stack.getSelectedIndex());assertTrue(stack.isSleeveOpeningOrOpen());assertNotNull(text(page,"查看曲目"));text(page,"查看曲目").performClick();assertEquals("three",viewed.get(0).id);assertNull(played);
    }
    @Test public void hiddenInterruptedShelfStopsBothAnimatorsAndRestoresOnlyTheLastIntent(){
        show();AlbumStackView stack=stack();stack.performClick();ValueAnimator old=ReflectionHelpers.getField(stack,"sleeveAnimator");old.setCurrentPlayTime(600);
        stack.selectAlbum(1);stack.openSelectedSleeve();ValueAnimator retract=ReflectionHelpers.getField(stack,"sleeveAnimator");assertNotNull(retract);assertTrue(stack.isSleeveOpeningOrOpen());
        stack.onWindowVisibilityChanged(View.GONE);assertNull(ReflectionHelpers.getField(stack,"sleeveAnimator"));assertTrue(stack.isSleeveOpen());assertEquals(1,stack.getSelectedIndex());
        old.setCurrentPlayTime(760);retract.setCurrentPlayTime(300);Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(2));
        assertNull(ReflectionHelpers.getField(stack,"sleeveAnimator"));assertEquals(1,stack.getSelectedIndex());assertTrue(stack.isSleeveOpen());assertNull(viewed);assertNull(played);
        stack.onWindowVisibilityChanged(View.VISIBLE);assertNotNull(text(page,"查看曲目"));text(page,"播放专辑").performClick();assertEquals("three",played.get(0).id);
    }
    @Test public void directArtistWithNoSongSeedLoadsIdentityAndTheCompleteAlbumForImmediateAction()throws Exception{
        java.util.concurrent.atomic.AtomicInteger artistCalls=new java.util.concurrent.atomic.AtomicInteger(),albumCalls=new java.util.concurrent.atomic.AtomicInteger();
        MusicProvider.Album preview=new MusicProvider.Album("album-real","完整专辑","真实歌手","artist-real","",java.util.Collections.emptyList());
        MusicProvider provider=(MusicProvider)java.lang.reflect.Proxy.newProxyInstance(MusicProvider.class.getClassLoader(),new Class<?>[]{MusicProvider.class},(proxy,method,args)->{
            switch(method.getName()){
                case "id":return "netease";
                case "artist":assertEquals("artist-real",args[0]);artistCalls.incrementAndGet();return new MusicProvider.Artist("artist-real","真实歌手","完整简介","",java.util.Collections.emptyList(),java.util.Collections.singletonList(preview));
                case "artistAlbums":return new MusicProvider.Page<>(java.util.Collections.singletonList(preview),1,1,false);
                case "album":assertEquals("album-real",args[0]);albumCalls.incrementAndGet();return new MusicProvider.Album("album-real","完整专辑","真实歌手","artist-real","",Arrays.asList(track("first","完整专辑"),track("last","完整专辑")));
                default:throw new AssertionError("Unexpected provider method: "+method.getName());
            }
        });
        OnlineMusic.useProviderForTests(controller.get(),provider,new ProviderSession(){public String readCookies(){return "";}public void writeCookies(String value){}public void clear(){}public long generation(){return 0;}});
        MusicProvider.Artist searchResult=new MusicProvider.Artist("artist-real","真实歌手","","",java.util.Collections.emptyList(),java.util.Collections.emptyList());
        page=ArtistPage.create(controller.get(),java.util.Collections.emptyList(),"netease",searchResult,new ArtistPage.Actions(){public void back(){}public void album(ArrayList<Track> tracks){viewed=tracks;}public void play(ArrayList<Track> tracks,int i,boolean shuffle){played=tracks;}public void share(String t,String s,ArrayList<Track> tracks){}});
        controller.get().setContentView(page);drain();assertNull(ReflectionHelpers.getField(page,"seed"));assertEquals(1,artistCalls.get());assertNull(viewed);assertNull(played);
        ArtistPage.selectChapter(page,2);Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(300));layout(page,412,860);stack().performClick();assertFalse(stack().isSleeveOpen());text(page,"查看曲目").performClick();drain();assertEquals(1,albumCalls.get());assertEquals(2,viewed.size());assertEquals("last",viewed.get(1).id);assertNull(played);
        text(page,"播放专辑").performClick();drain();assertEquals(2,albumCalls.get());assertEquals(2,played.size());
    }
    void drain()throws Exception{for(int i=0;i<5;i++){java.util.concurrent.ExecutorService worker=ReflectionHelpers.getField(page,"worker");worker.submit(()->{}).get(5,java.util.concurrent.TimeUnit.SECONDS);Shadows.shadowOf(Looper.getMainLooper()).idle();layout(page,412,860);}}

}
