package com.recordroom.vinyl;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;

/** New portrait geometry must preserve real cover data, gestures and saved appearance IDs. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=34,qualifiers="w443dp-h844dp-mdpi",fontScale=1f)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public class ReferenceTurntableTest {
    private ActivityController<Activity> controller;
    private Activity activity;
    private FrameLayout host;
    @Before public void setup(){controller=Robolectric.buildActivity(Activity.class);activity=controller.get();activity.setTheme(android.R.style.Theme_Material_Light_NoActionBar);controller.setup().visible();host=new FrameLayout(activity);activity.setContentView(host);layout(host,443,844);}
    @After public void close(){controller.pause().stop().destroy();}

    @Test public void tallPortraitKeepsWidthBasedRecordAndRealArtwork()throws Exception{
        TurntableView view=table();view.setMotionEnabled(false);
        float r=number(view,"radius"),cx=number(view,"cx"),cy=number(view,"cy");
        assertEquals(195,r,.1);assertEquals(206.5,cx,.1);assertTrue("Tall screen platter moves into the central listening area",cy>340&&cy<390);
        layout(view,443,640);assertEquals(r,number(view,"radius"),0);assertEquals(cx,number(view,"cx"),0);assertTrue("Short screens reserve caption clearance",number(view,"cy")<cy);cy=number(view,"cy");
        Bitmap actual=Bitmap.createBitmap(40,40,Bitmap.Config.ARGB_8888);actual.eraseColor(0xffef2858);view.setArtwork(actual);view.setRecordTint(0xff17679a);awaitAssets();layout(view,443,640);cy=number(view,"cy");
        Bitmap rendered=Bitmap.createBitmap(443,640,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(rendered);canvas.drawColor(0xff344d63);view.draw(canvas);
        assertEquals("Real song cover is never replaced with the reference artist",0xffef2858,rendered.getPixel(Math.round(cx-r*.44f),Math.round(cy)));
        assertSame(actual,field(view,"artwork"));
        File dir=new File(System.getProperty("vinyl.output.dir"));dir.mkdirs();try(FileOutputStream out=new FileOutputStream(new File(dir,"reference-native-turntable.png"))){rendered.compress(Bitmap.CompressFormat.PNG,100,out);}
        view.setReferenceLayout(false);assertNotEquals("Landscape can explicitly restore compact geometry",cx,number(view,"cx"),.1);
    }

    @Test public void pauseStyleChangeAndResumeNeverRestartRecordPhase()throws Exception{
        TurntableView view=table();view.setActive(true);view.setPlaying(true);
        ValueAnimator spin=(ValueAnimator)field(view,"spinAnimator");assertNotNull(spin);spin.setCurrentPlayTime(2370);
        view.setPlaying(false);float paused=number(view,"rotation");assertTrue(paused>60);
        view.setStyle(0);view.setRecordTint(0xffd38a65);layout(view,443,640);assertEquals(paused,number(view,"rotation"),0);
        view.setPlaying(true);ValueAnimator resumed=(ValueAnimator)field(view,"spinAnimator");assertNotNull(resumed);resumed.setCurrentPlayTime(0);
        assertEquals("The reference angle is a drawing offset, not a state reset",paused,number(view,"rotation"),.02);
        view.setMotionEnabled(false);assertNull(field(view,"spinAnimator"));assertEquals(paused,number(view,"rotation"),.02);
    }

    @Test public void retainedGraphiteStylusStillSeeksThroughNativeCallbacks()throws Exception{
        TurntableView view=table();view.setMotionEnabled(false);List<String> calls=new ArrayList<>();float[] seek={-1};
        view.setNeedleListener(new TurntableView.NeedleListener(){public void onNeedlePause(){calls.add("pause");view.setPlaying(false);}public void onNeedleSeek(float p){calls.add("seek");seek[0]=p;}public void onNeedlePlay(){calls.add("play");view.setPlaying(true);}});
        float r=number(view,"radius"),px=number(view,"pivotX"),py=number(view,"pivotY");
        // The retained image's stylus is at x≈34%, y≈98%; map that actual point
        // through the new 372/195 scale and its 18.55% pivot.
        float localX=-r*.102f,localY=r*1.515f;
        long time=SystemClock.uptimeMillis();event(view,time,MotionEvent.ACTION_DOWN,px+localX,py+localY);
        double a=Math.toRadians(23);float x=px+(float)(localX*Math.cos(a)-localY*Math.sin(a)),y=py+(float)(localX*Math.sin(a)+localY*Math.cos(a));
        event(view,time,MotionEvent.ACTION_MOVE,x,y);event(view,time,MotionEvent.ACTION_UP,x,y);
        assertEquals(Arrays.asList("pause","seek","play"),calls);assertEquals(.5f,seek[0],.02f);
    }

    @Test public void sourceWithoutSeekCannotManipulateNeedleButCanStillRevealControls()throws Exception{
        TurntableView view=table();view.setMotionEnabled(false);List<String> calls=new ArrayList<>();int[] clicks={0};
        view.setOnClickListener(v->clicks[0]++);
        view.setNeedleListener(new TurntableView.NeedleListener(){public void onNeedlePause(){calls.add("pause");}public void onNeedleSeek(float p){calls.add("seek");}public void onNeedlePlay(){calls.add("play");}});
        float px=number(view,"pivotX"),py=number(view,"pivotY"),length=number(view,"radius")*.98f;
        long time=SystemClock.uptimeMillis();float x=px-(float)Math.sin(Math.toRadians(23))*length,y=py+(float)Math.cos(Math.toRadians(23))*length;
        view.setNeedleSeekingEnabled(false);
        event(view,time,MotionEvent.ACTION_DOWN,px,py+length);event(view,time,MotionEvent.ACTION_MOVE,x,y);event(view,time,MotionEvent.ACTION_UP,x,y);
        assertTrue("Unsupported drag must not pause, seek, or play",calls.isEmpty());
        event(view,time+64,MotionEvent.ACTION_DOWN,px,py+length);event(view,time+64,MotionEvent.ACTION_UP,px,py+length);
        assertEquals("Tap remains an ordinary show/hide controls click",1,clicks[0]);assertTrue(calls.isEmpty());
        assertFalse(view.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,null));
        view.setNeedleSeekingEnabled(true);
        event(view,time+128,MotionEvent.ACTION_DOWN,px,py+length);event(view,time+128,MotionEvent.ACTION_MOVE,x,y);event(view,time+128,MotionEvent.ACTION_UP,x,y);
        assertEquals("Switching back to a seekable source restores native gestures",Arrays.asList("pause","seek","play"),calls);
    }

    @Test public void losingSeekCapabilityMidDragCannotSendAStaleCommit()throws Exception{
        TurntableView view=table();view.setMotionEnabled(false);List<String> calls=new ArrayList<>();
        view.setNeedleListener(new TurntableView.NeedleListener(){public void onNeedlePause(){calls.add("pause");}public void onNeedleSeek(float p){calls.add("seek");}public void onNeedlePlay(){calls.add("play");}});
        float px=number(view,"pivotX"),py=number(view,"pivotY"),length=number(view,"radius")*.98f;
        long time=SystemClock.uptimeMillis();float x=px-(float)Math.sin(Math.toRadians(23))*length,y=py+(float)Math.cos(Math.toRadians(23))*length;
        event(view,time,MotionEvent.ACTION_DOWN,px,py+length);event(view,time,MotionEvent.ACTION_MOVE,x,y);
        assertEquals(Arrays.asList("pause"),calls);
        view.setNeedleSeekingEnabled(false);event(view,time,MotionEvent.ACTION_UP,x,y);
        assertEquals("Revocation cancels pending seek/play and sends no extra pause",Arrays.asList("pause"),calls);
    }

    @Test public void referenceRingContainsNoBundledCoverAndReorderedSwatchesSaveStableIds()throws Exception{
        TurntableView view=table();view.setStyle(5);awaitAssets();Bitmap material=(Bitmap)field(view,"recordTexture");assertNotNull(material);
        assertEquals("The reference artist label is removed from the reusable material",0,material.getPixel(material.getWidth()/2,material.getHeight()/2));
        assertEquals(0,material.getPixel(0,0));assertTrue(Color.alpha(material.getPixel(material.getWidth()/2,Math.round(material.getHeight()*.14f)))>100);
        activity.getSharedPreferences("vinyl_appearance",0).edit().clear().commit();ThemeStore themes=new ThemeStore(activity);assertEquals(5,themes.disc("fresh"));
        themes.save("",false,2,0xff887799);themes.save("saved",true,6,0xff274836);
        Dialog editor=new AppearanceEditor(activity,themes,"saved","Actual album",null,()->{},()->{},()->{}).show();
        try{Shadows.shadowOf(Looper.getMainLooper()).idle();List<DiscSwatchView> swatches=new ArrayList<>();find(editor.getWindow().getDecorView(),swatches);assertEquals(9,swatches.size());assertEquals(5,swatches.get(2).getStyle());swatches.get(2).performClick();assertEquals(5,new ThemeStore(activity).disc("saved"));assertEquals("Album changes cannot overwrite global appearance",2,themes.disc("unrelated"));assertEquals(0xff274836,themes.colorFor("saved",true));}
        finally{editor.dismiss();}
    }

    private TurntableView table(){TurntableView view=new TurntableView(activity);view.setReferenceLayout(true);host.addView(view,new FrameLayout.LayoutParams(443,844));layout(view,443,844);return view;}
    private static void find(View v,List<DiscSwatchView> list){if(v instanceof DiscSwatchView)list.add((DiscSwatchView)v);if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++)find(g.getChildAt(i),list);}}
    private static Object field(Object v,String name)throws Exception{Field f=v.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(v);}
    private static float number(Object v,String name)throws Exception{return ((Number)field(v,name)).floatValue();}
    private static void awaitAssets()throws Exception{for(Class<?> type:new Class<?>[]{DiscMaterials.class,ApprovedArtwork.class}){Field f=type.getDeclaredField("IO");f.setAccessible(true);((ExecutorService)f.get(null)).submit(()->{}).get(30,TimeUnit.SECONDS);}Shadows.shadowOf(Looper.getMainLooper()).idle();}
    private static void layout(View v,int w,int h){v.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));v.layout(0,0,w,h);}
    private static void event(View v,long time,int action,float x,float y){MotionEvent e=MotionEvent.obtain(time,time+(action==MotionEvent.ACTION_MOVE?16:action==MotionEvent.ACTION_UP?32:0),action,x,y,0);try{assertTrue(v.dispatchTouchEvent(e));}finally{e.recycle();}}
}
