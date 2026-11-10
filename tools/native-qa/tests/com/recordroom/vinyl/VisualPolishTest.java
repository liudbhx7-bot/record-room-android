package com.recordroom.vinyl;

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

/** Native visual evidence plus real touch feedback; never substitutes for device acceptance. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=34,qualifiers="w512dp-h726dp-mdpi",fontScale=1f)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public class VisualPolishTest {
    private ActivityController<Activity> controller;
    private Activity activity;
    private FrameLayout host;
    @Before public void setup(){
        controller=Robolectric.buildActivity(Activity.class);activity=controller.get();
        activity.setTheme(android.R.style.Theme_Material_Light_NoActionBar);controller.setup().visible();
        host=new FrameLayout(activity);activity.setContentView(host);layout(host,512,726);
    }
    @After public void teardown(){if(controller!=null)controller.pause().stop().destroy();}

    @Test public void hardwarePressHasVisibleTravelAndOnlyReleaseActivates()throws Exception{
        for(String name:new String[]{"play","previous","next"}){
            host.removeAllViews();PlayerControls.Hardware button=new PlayerControls.Hardware(activity,name);
            host.addView(button,new FrameLayout.LayoutParams(58,66));layout(host,512,726);layout(button,58,66);
            int[] clicks={0};button.setOnClickListener(v->clicks[0]++);
            Bitmap idle=render(button,58,66,"visual-polish-hardware-"+name+"-idle.png");
            long down=SystemClock.uptimeMillis();event(button,down,down,MotionEvent.ACTION_DOWN,29,24);
            assertTrue("Native down must show a pressed cap",button.isPressed());assertEquals(0,clicks[0]);
            Bitmap pressed=render(button,58,66,"visual-polish-hardware-"+name+"-pressed.png");
            assertFalse("The cap must visibly respond before release",idle.sameAs(pressed));
            event(button,down,down+16,MotionEvent.ACTION_UP,29,24);idleMain();
            assertEquals("Release must activate exactly once",1,clicks[0]);
            // Let the framework's pressed-state duration elapse before the next gesture.
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(200));
            down=SystemClock.uptimeMillis();event(button,down,down,MotionEvent.ACTION_DOWN,29,24);
            event(button,down,down+16,MotionEvent.ACTION_CANCEL,29,24);idleMain();
            assertFalse("Cancelled touch must release the cap",button.isPressed());assertEquals(1,clicks[0]);
            // The whole 58x66 control, including its legend, remains a usable target.
            down=SystemClock.uptimeMillis();event(button,down,down,MotionEvent.ACTION_DOWN,29,56);
            event(button,down,down+16,MotionEvent.ACTION_UP,29,56);idleMain();assertEquals(2,clicks[0]);
            idle.recycle();pressed.recycle();
        }
    }

    @Test public void lightingChangesOnlyDiscSurfaceWhileCoverAndArmStayStable()throws Exception{
        TurntableView view=new TurntableView(activity);host.addView(view,new FrameLayout.LayoutParams(512,540));
        layout(host,512,726);layout(view,512,540);view.setStyle(2);view.setMotionEnabled(false);view.setActive(false);
        view.setPlaying(true);view.setProgress(.35f);
        try(InputStream input=activity.getAssets().open("demo/night-v2.png")){view.setArtwork(BitmapFactory.decodeStream(input));}
        Field io=DiscMaterials.class.getDeclaredField("IO");io.setAccessible(true);
        ((ExecutorService)io.get(null)).submit(()->{}).get(30,TimeUnit.SECONDS);idleMain();
        Field approved=ApprovedArtwork.class.getDeclaredField("IO");approved.setAccessible(true);
        ((ExecutorService)approved.get(null)).submit(()->{}).get(30,TimeUnit.SECONDS);idleMain();
        view.setLighting(0);Bitmap dim=render(view,512,540,"visual-polish-turntable-soft-light-api34.png");
        view.setLighting(1);Bitmap bright=render(view,512,540,"visual-polish-turntable-full-light-api34.png");
        assertFalse("Lighting control must change the platter pixels",dim.sameAs(bright));
        int cx=Math.round(number(view,"cx")),cy=Math.round(number(view,"cy"));float radius=number(view,"radius");
        int sampleY=Math.round(cy-radius*.18f);
        assertEquals("Lighting must preserve the actual album image",dim.getPixel(cx,sampleY),bright.getPixel(cx,sampleY));
        assertEquals("Lighting cannot move or tint the tonearm mount",dim.getPixel(Math.round(number(view,"pivotX")),Math.round(number(view,"pivotY"))),bright.getPixel(Math.round(number(view,"pivotX")),Math.round(number(view,"pivotY"))));
        dim.recycle();bright.recycle();
    }

    private static float number(Object target,String name)throws Exception{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return ((Number)f.get(target)).floatValue();}
    private static void idleMain(){Shadows.shadowOf(Looper.getMainLooper()).idle();}
    private static void layout(View view,int w,int h){view.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));view.layout(0,0,w,h);}
    private static void event(View view,long down,long at,int action,float x,float y){MotionEvent e=MotionEvent.obtain(down,at,action,x,y,0);try{assertTrue(view.dispatchTouchEvent(e));}finally{e.recycle();}}
    private static Bitmap render(View view,int w,int h,String name)throws Exception{
        Bitmap b=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);Canvas c=new Canvas(b);c.drawColor(0xffd7867e);view.draw(c);
        String folder=System.getProperty("vinyl.output.dir");assertNotNull(folder);File out=new File(folder);assertTrue(out.isDirectory()||out.mkdirs());
        try(FileOutputStream stream=new FileOutputStream(new File(out,name))){assertTrue(b.compress(Bitmap.CompressFormat.PNG,100,stream));}return b;
    }
}
