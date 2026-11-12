package com.recordroom.vinyl;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;

/** Native material pixels and real needle gestures, without a player or fabricated audio. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=34,qualifiers="w390dp-h844dp-mdpi",fontScale=1f)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public class DiscDynamicsTest {
    private ActivityController<Activity> controller;
    private Activity activity;
    private FrameLayout host;
    @Before public void setup(){
        controller=Robolectric.buildActivity(Activity.class);activity=controller.get();
        activity.setTheme(android.R.style.Theme_Material_Light_NoActionBar);controller.setup().visible();
        host=new FrameLayout(activity);activity.setContentView(host);layout(host,390,844);
    }
    @After public void cleanup(){controller.pause().stop().destroy();}

    @Test public void coverPaletteTintsActualMaterialButNeverAlbumCoverOrTonearm()throws Exception{
        TurntableView view=turntable();view.setMotionEnabled(false);view.setStyle(8);awaitAssets();
        Bitmap cover=Bitmap.createBitmap(40,40,Bitmap.Config.ARGB_8888);cover.eraseColor(0xffe23568);view.setArtwork(cover);
        Bitmap original=render(view,"record-original-material-native.png");
        view.setRecordTint(0xffd77555);Bitmap warm=render(view,"record-cover-tint-warm-native.png");
        view.setRecordTint(0xff5076c9);Bitmap cool=render(view,"record-cover-tint-cool-native.png");
        float cx=number(view,"cx"),cy=number(view,"cy"),r=number(view,"radius");
        int x=Math.round(cx-r*.70f),y=Math.round(cy),warmPixel=warm.getPixel(x,y),coolPixel=cool.getPixel(x,y);
        assertTrue("Red cover palette must produce a warmer material",Color.red(warmPixel)>Color.red(coolPixel)+15);
        assertTrue("Blue cover palette must produce a cooler material",Color.blue(coolPixel)>Color.blue(warmPixel)+15);
        assertEquals(0xffe23568,warm.getPixel(Math.round(cx-r*.2f),Math.round(cy)));
        assertEquals(0xffe23568,cool.getPixel(Math.round(cx-r*.2f),Math.round(cy)));
        int px=Math.round(number(view,"pivotX")),py=Math.round(number(view,"pivotY"));
        assertEquals("Needle metal cannot receive the disc tint",warm.getPixel(px,py),cool.getPixel(px,py));
        assertSame("Tint cannot allocate or recolor the actual source cover",cover,field(view,"artwork"));
        assertNotEquals("Photographed surface must retain spatial texture",coolPixel,cool.getPixel(Math.round(cx-r*.76f),Math.round(cy-r*.22f)));
        view.setRecordTint(0);assertTrue("Manual mode must restore the exact original material",original.sameAs(render(view,"record-manual-restored-native.png")));
        view.setRecordTint(0xff5076c9);
        setField(view,"materialShader",null);setField(view,"recordTexture",null);
        Bitmap fallback=render(view,"record-cover-tint-fallback-native.png");
        assertTrue(Color.blue(fallback.getPixel(x,y))>Color.red(fallback.getPixel(x,y)));
    }

    @Test public void edgeLampStaysAtLowerLeftAsRecordTurns()throws Exception{
        TurntableView view=turntable();view.setMotionEnabled(false);view.setStyle(5);view.setRecordTint(0xffec9b4d);awaitAssets();
        float x=number(view,"cx"),y=number(view,"cy"),r=number(view,"radius");
        int nearX=Math.round(x-r*.789f),nearY=Math.round(y+r*.575f);
        int farX=Math.round(x+r*.789f),farY=Math.round(y-r*.575f);
        for(float rotation:new float[]{0f,125f}){
            setField(view,"rotation",rotation);view.setLighting(0);
            Bitmap off=render(view,"lumen-lamp-off-"+(int)rotation+".png");
            view.setLighting(1);Bitmap lit=render(view,"lumen-lamp-on-"+(int)rotation+".png");
            assertTrue("The same physical lip should light at every record angle",
                    Color.red(lit.getPixel(nearX,nearY))-Color.red(off.getPixel(nearX,nearY))>45);
            assertTrue("The opposite rim is not an LED ring",
                    Math.abs(Color.red(lit.getPixel(farX,farY))-Color.red(off.getPixel(farX,farY)))<8);
            lit.recycle();off.recycle();
        }
    }

    @Test public void contactScaleStartsWhenNeedleReachesRecordAndLiftRestoresWithoutMovingHitGeometry()throws Exception{
        TurntableView view=turntable();view.setActive(true);
        float[] geometry={number(view,"cx"),number(view,"cy"),number(view,"radius"),number(view,"pivotX"),number(view,"pivotY")};
        view.setPlaying(true);ValueAnimator arm=(ValueAnimator)field(view,"armAnimator");assertNotNull(arm);
        assertEquals(1f,number(view,"recordScale"),.0001f);
        arm.setCurrentPlayTime(100);assertNull("A parked or travelling needle cannot enlarge the disc early",field(view,"recordScaleAnimator"));
        arm.end();ValueAnimator contact=(ValueAnimator)field(view,"recordScaleAnimator");assertNotNull(contact);assertEquals(220,contact.getDuration());
        contact.setCurrentPlayTime(110);assertTrue(number(view,"recordScale")>1&&number(view,"recordScale")<1.025f);
        contact.end();assertEquals(1.025f,number(view,"recordScale"),.0001f);render(view,"record-needle-landed-native.png");
        String[] names={"cx","cy","radius","pivotX","pivotY"};for(int i=0;i<names.length;i++)assertEquals(geometry[i],number(view,names[i]),0);
        ArrayList<String> events=new ArrayList<>();view.setNeedleListener(new TurntableView.NeedleListener(){public void onNeedleSeek(float p){events.add("seek");}public void onNeedlePlay(){events.add("play");view.setPlaying(true);}public void onNeedlePause(){events.add("pause");view.setPlaying(false);}});
        float r=geometry[2],px=geometry[3],py=geometry[4];float[] from=point(px,py,-r*.058f,r*1.58f,14),to=point(px,py,-r*.058f,r*1.58f,0);
        long down=SystemClock.uptimeMillis();event(view,down,down,MotionEvent.ACTION_DOWN,from[0],from[1]);event(view,down,down+16,MotionEvent.ACTION_MOVE,to[0],to[1]);
        assertTrue((Boolean)field(view,"needleDragging"));ValueAnimator lift=(ValueAnimator)field(view,"recordScaleAnimator");assertNotNull(lift);lift.end();assertEquals(1f,number(view,"recordScale"),.0001f);
        event(view,down,down+32,MotionEvent.ACTION_UP,to[0],to[1]);assertEquals(Arrays.asList("pause","pause"),events);
        assertFalse((Boolean)field(view,"playing"));render(view,"record-needle-lifted-native.png");
    }

    @Test public void reducedMotionAndDetachmentSnapAndCancelScaleWithoutChangingPlaybackState()throws Exception{
        TurntableView view=turntable();view.setActive(true);view.setMotionEnabled(false);view.setPlaying(true);
        assertEquals(1.025f,number(view,"recordScale"),.0001f);assertNull(field(view,"recordScaleAnimator"));
        view.setPlaying(false);assertEquals(1f,number(view,"recordScale"),.0001f);
        view.setMotionEnabled(true);view.setPlaying(true);((ValueAnimator)field(view,"armAnimator")).end();
        ValueAnimator contact=(ValueAnimator)field(view,"recordScaleAnimator");assertNotNull(contact);contact.setCurrentPlayTime(70);
        view.setMotionEnabled(false);assertEquals(1.025f,number(view,"recordScale"),.0001f);assertNull(field(view,"recordScaleAnimator"));
        view.setMotionEnabled(true);view.setPlaying(false);assertNotNull(field(view,"recordScaleAnimator"));host.removeView(view);
        assertNull(field(view,"recordScaleAnimator"));assertEquals(1f,number(view,"recordScale"),.0001f);assertFalse((Boolean)field(view,"playing"));
    }

    @Test public void raisedDeckRetainsTheChosenChassisFinish()throws Exception{
        TurntableView view=turntable();view.setMotionEnabled(false);view.setStyle(8);awaitAssets();
        // Measure the moulded upper surface, above the sleeve and away from the lettering.
        Bitmap lilac=render(view,"fuguang-upper-deck-manual-native.png");
        Bitmap charcoal=Bitmap.createBitmap(view.getWidth(),view.getHeight(),Bitmap.Config.ARGB_8888);
        Canvas c=new Canvas(charcoal);c.drawColor(0xff252220);view.draw(c);
        int changed=0,n=0;
        for(int y=3;y<22;y+=2)for(int x=210;x<370;x+=2){
            int a=lilac.getPixel(x,y),b=charcoal.getPixel(x,y);
            if(a!=DesignSystem.LILAC)changed++;
            assertTrue("A manually selected finish must still tint the moulded ledge",
                    Color.blue(a)-Color.blue(b)>40);n++;
        }
        assertTrue("Raised deck needs visible local shading",changed>n/2);
        assertEquals("The parent finish remains visible below the deck",DesignSystem.LILAC,lilac.getPixel(3,view.getHeight()-3));
        lilac.recycle();charcoal.recycle();
    }

    private TurntableView turntable()throws Exception{TurntableView view=new TurntableView(activity);host.addView(view,new FrameLayout.LayoutParams(390,440));layout(host,390,844);layout(view,390,440);awaitAssets();return view;}
    private static float[] point(float x,float y,float localX,float localY,float degrees){double angle=Math.toRadians(degrees);return new float[]{x+(float)(localX*Math.cos(angle)-localY*Math.sin(angle)),y+(float)(localX*Math.sin(angle)+localY*Math.cos(angle))};}
    private static Object field(Object target,String name)throws Exception{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}
    private static void setField(Object target,String name,Object value)throws Exception{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);f.set(target,value);}
    private static float number(Object target,String name)throws Exception{return ((Number)field(target,name)).floatValue();}
    private static void awaitAssets()throws Exception{for(Class<?> type:new Class<?>[]{DiscMaterials.class,ApprovedArtwork.class}){Field f=type.getDeclaredField("IO");f.setAccessible(true);((ExecutorService)f.get(null)).submit(()->{}).get(30,TimeUnit.SECONDS);}Shadows.shadowOf(Looper.getMainLooper()).idle();}
    private static void layout(View view,int width,int height){view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));view.layout(0,0,width,height);}
    private static void event(View view,long down,long time,int action,float x,float y){MotionEvent e=MotionEvent.obtain(down,time,action,x,y,0);try{assertTrue(view.dispatchTouchEvent(e));}finally{e.recycle();}}
    private static Bitmap render(View view,String name)throws Exception{Bitmap bitmap=Bitmap.createBitmap(view.getWidth(),view.getHeight(),Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(bitmap);canvas.drawColor(DesignSystem.LILAC);view.draw(canvas);File dir=new File(System.getProperty("vinyl.output.dir"));assertTrue(dir.isDirectory()||dir.mkdirs());try(FileOutputStream out=new FileOutputStream(new File(dir,name))){assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,out));}return bitmap;}
}
