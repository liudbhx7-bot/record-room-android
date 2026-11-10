package com.recordroom.vinyl;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.ArrayList;
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

@RunWith(RobolectricTestRunner.class)
@Config(sdk=34,qualifiers="w512dp-h726dp-mdpi",fontScale=1f)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public class InteractionTimingTest {
    private ActivityController<Activity> controller;
    private Activity activity;
    private FrameLayout host;
    @Before public void setup(){
        controller=Robolectric.buildActivity(Activity.class);activity=controller.get();
        activity.setTheme(android.R.style.Theme_Material_Light_NoActionBar);controller.setup().visible();
        host=new FrameLayout(activity);activity.setContentView(host);layout(host,512,726);
    }
    @After public void teardown(){if(controller!=null)controller.pause().stop().destroy();}

    @Test public void replacementLiftsBeforeFadingAndLowersAfterNewRecordArrives()throws Exception{
        TurntableView view=turntable();Bitmap first=art("demo/night-v2.png"),second=art("demo/sun-v2.png");
        view.setMotionEnabled(false);view.setArtwork(first);view.setPlaying(true);view.setProgress(.5f);
        view.setMotionEnabled(true);view.setActive(true);
        view.setArtwork(second);ValueAnimator animation=(ValueAnimator)field(view,"artworkAnimator");assertNotNull(animation);
        animation.setCurrentPlayTime(180);
        assertSame(first,field(view,"artwork"));assertEquals(1f,number(view,"artworkAlpha"),.001f);
        assertTrue(number(view,"armAngle")>0&&number(view,"armAngle")<23);
        render(view,"record-transition-1-lifting.png");
        animation.setCurrentPlayTime(440);
        assertSame(first,field(view,"artwork"));assertEquals(0f,number(view,"armAngle"),.001f);
        assertTrue(number(view,"artworkAlpha")>0&&number(view,"artworkAlpha")<1);
        render(view,"record-transition-2-fading-out.png");
        animation.setCurrentPlayTime(620);
        assertSame(second,field(view,"artwork"));assertEquals(0f,number(view,"armAngle"),.001f);
        assertTrue(number(view,"artworkAlpha")>0&&number(view,"artworkAlpha")<1);
        render(view,"record-transition-3-fading-in.png");
        animation.setCurrentPlayTime(900);
        assertEquals(1f,number(view,"artworkAlpha"),.001f);assertTrue(number(view,"armAngle")>0&&number(view,"armAngle")<23);
        render(view,"record-transition-4-lowering.png");
        animation.end();assertSame(second,field(view,"artwork"));assertFalse((Boolean)field(view,"artworkChanging"));
        assertEquals(23f,number(view,"armAngle"),.001f);
        assertTrue("Visual transition cannot alter the service-supplied playing state",(Boolean)field(view,"playing"));
    }

    @Test public void sameRecordAndReducedMotionNeverFadeAndInterruptionKeepsLatestCover()throws Exception{
        TurntableView view=turntable();Bitmap first=art("demo/night-v2.png"),second=art("demo/sun-v2.png"),third=art("demo/forest-v2.png");
        view.setMotionEnabled(false);view.setArtwork(first);view.setPlaying(true);view.setProgress(.25f);
        view.setMotionEnabled(true);view.setActive(true);view.beginRecordChange();view.setArtwork(first);
        assertNull("Same-album track switch must keep the disc",field(view,"artworkAnimator"));
        view.setArtwork(second);((ValueAnimator)field(view,"artworkAnimator")).setCurrentPlayTime(440);
        view.setArtwork(third);assertSame(third,field(view,"pendingArtwork"));
        view.setMotionEnabled(false);
        assertNull(field(view,"artworkAnimator"));assertSame(third,field(view,"artwork"));
        assertEquals(1f,number(view,"artworkAlpha"),.001f);assertEquals(18.5f,number(view,"armAngle"),.001f);
        view.setArtwork(first);assertSame(first,field(view,"artwork"));assertNull(field(view,"artworkAnimator"));
        assertNull(field(view,"spinAnimator"));assertNull(field(view,"armAnimator"));
    }

    @Test public void fractionalShelfPositionSurvivesRefreshResizeAndDeferredRestore()throws Exception{
        ArrayList<ArrayList<Track>> albums=albums();AlbumStackView shelf=new AlbumStackView(activity);
        host.addView(shelf,new FrameLayout.LayoutParams(512,540));layout(shelf,512,540);shelf.setAlbums(albums);
        shelf.setScrollPosition(8.25f);assertEquals(8.25f,shelf.getScrollPosition(),.001f);
        shelf.setAlbums(albums);assertEquals("Refreshing covers cannot snap fractional browsing position",8.25f,shelf.getScrollPosition(),.001f);
        layout(shelf,726,380);assertEquals(8.25f,shelf.getScrollPosition(),.001f);
        AlbumStackView restored=new AlbumStackView(activity);restored.setScrollPosition(shelf.getScrollPosition());
        restored.setAlbums(albums);layout(restored,512,540);assertEquals(8.25f,restored.getScrollPosition(),.001f);
        restored.setScrollPosition(Float.NaN);restored.setScrollPosition(Float.POSITIVE_INFINITY);
        assertEquals("Invalid restored values cannot corrupt the viewport",8.25f,restored.getScrollPosition(),.001f);
        restored.setScrollPosition(10000);assertTrue(restored.getScrollPosition()>=0&&restored.getScrollPosition()<albums.size());
    }

    @Test public void shelfFlingContinuesAfterReleaseAndTouchStopsWithoutOpeningAlbum()throws Exception{
        AlbumStackView shelf=new AlbumStackView(activity);host.addView(shelf,new FrameLayout.LayoutParams(512,540));
        layout(host,512,726);layout(shelf,512,540);shelf.setAlbums(albums());shelf.setScrollPosition(8);
        int[] opens={0};shelf.setOnAlbumClickListener(songs->opens[0]++);
        long down=SystemClock.uptimeMillis();event(shelf,down,down,MotionEvent.ACTION_DOWN,256,420);
        event(shelf,down,down+16,MotionEvent.ACTION_MOVE,256,360);
        event(shelf,down,down+32,MotionEvent.ACTION_MOVE,256,280);
        event(shelf,down,down+48,MotionEvent.ACTION_UP,256,220);
        float released=shelf.getScrollPosition();assertTrue(released>8);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(120));shelf.computeScroll();
        assertTrue("Fling must continue beyond the direct drag",shelf.getScrollPosition()>released);
        down=SystemClock.uptimeMillis();event(shelf,down,down,MotionEvent.ACTION_DOWN,256,260);
        float stopped=shelf.getScrollPosition();event(shelf,down,down+16,MotionEvent.ACTION_UP,256,260);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500));shelf.computeScroll();
        assertEquals(stopped,shelf.getScrollPosition(),.001f);assertEquals("Stopping a fling must not open a sleeve",0,opens[0]);
    }

    private TurntableView turntable()throws Exception{TurntableView v=new TurntableView(activity);host.addView(v,new FrameLayout.LayoutParams(512,540));layout(host,512,726);layout(v,512,540);v.setStyle(2);Field io=DiscMaterials.class.getDeclaredField("IO");io.setAccessible(true);((java.util.concurrent.ExecutorService)io.get(null)).submit(()->{}).get(30,java.util.concurrent.TimeUnit.SECONDS);Shadows.shadowOf(Looper.getMainLooper()).idle();return v;}
    private Bitmap art(String path)throws Exception{try(InputStream in=activity.getAssets().open(path)){Bitmap b=BitmapFactory.decodeStream(in);assertNotNull(b);return b;}}
    private ArrayList<ArrayList<Track>> albums(){ArrayList<ArrayList<Track>> result=new ArrayList<>();for(int i=0;i<30;i++){ArrayList<Track> group=new ArrayList<>();group.add(new Track("shelf"+i,"歌曲"+i,"测试音乐人","专辑"+i,"测试","asset:///demo/night-1.wav","asset:///demo/night-v2.png",18000,1));result.add(group);}return result;}
    private static Object field(Object o,String name)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}
    private static float number(Object o,String name)throws Exception{return ((Number)field(o,name)).floatValue();}
    private static void layout(View v,int w,int h){v.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));v.layout(0,0,w,h);}
    private static void event(View v,long down,long at,int action,float x,float y){MotionEvent e=MotionEvent.obtain(down,at,action,x,y,0);try{assertTrue(v.dispatchTouchEvent(e));}finally{e.recycle();}}
    private static void render(View v,String name)throws Exception{Bitmap b=Bitmap.createBitmap(512,540,Bitmap.Config.ARGB_8888);Canvas c=new Canvas(b);c.drawColor(0xffd7867e);v.draw(c);File out=new File(System.getProperty("vinyl.output.dir"));assertTrue(out.isDirectory()||out.mkdirs());try(FileOutputStream stream=new FileOutputStream(new File(out,name))){assertTrue(b.compress(Bitmap.CompressFormat.PNG,100,stream));}finally{b.recycle();}}
}
