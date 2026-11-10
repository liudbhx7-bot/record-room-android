package com.recordroom.vinyl;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import java.util.ArrayList;
import java.util.Collections;
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
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=34,qualifiers="w320dp-h480dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public class UprightAlbumInteractionTest {
    ActivityController<Activity> controller;AlbumStackView stack;final ArrayList<Integer> opened=new ArrayList<>();int oldCallbackCalls;
    @Before public void setup(){controller=Robolectric.buildActivity(Activity.class).setup().visible();stack=new AlbumStackView(controller.get());stack.setUpright(true);stack.setEntries(entries(12));stack.setOnAlbumIndexClickListener(opened::add);stack.setOnAlbumClickListener(tracks->oldCallbackCalls++);controller.get().setContentView(stack);layout();}
    @After public void teardown(){ReflectionHelpers.callStaticMethod(ValueAnimator.class,"setDurationScale",ReflectionHelpers.ClassParameter.from(float.class,1f));controller.pause().stop().destroy();}
    ArrayList<AlbumStackView.Entry> entries(int count){ArrayList<AlbumStackView.Entry> items=new ArrayList<>();for(int i=0;i<count;i++)items.add(new AlbumStackView.Entry("real-"+i,"Album "+i,"Artist","",Collections.emptyList()));return items;}
    void layout(){stack.measure(View.MeasureSpec.makeMeasureSpec(320,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(480,View.MeasureSpec.EXACTLY));stack.layout(0,0,320,480);Shadows.shadowOf(Looper.getMainLooper()).idle();Bitmap b=Bitmap.createBitmap(320,480,Bitmap.Config.ARGB_8888);try{stack.draw(new Canvas(b));}finally{b.recycle();}}
    ValueAnimator animator(){return ReflectionHelpers.getField(stack,"sleeveAnimator");}
    void finish(){ValueAnimator a=animator();if(a!=null)a.end();layout();}
    void touch(int action,long down,long time,float x,float y){MotionEvent e=MotionEvent.obtain(down,time,action,x,y,0);try{assertTrue(stack.onTouchEvent(e));}finally{e.recycle();}}
    void tap(float x,float y){long now=SystemClock.uptimeMillis();touch(MotionEvent.ACTION_DOWN,now,now,x,y);touch(MotionEvent.ACTION_UP,now,now+40,x,y);}
    @Test public void cyclicArrowsWrapBothEndsForTwoAndLargeCataloguesWithoutOpening(){
        stack.setLooping(true);
        for(int count:new int[]{2,1200}){
            stack.setEntries(entries(count));stack.setScrollPosition(count-1);layout();
            assertTrue(stack.selectAlbum(1));assertEquals(0,stack.getSelectedIndex());
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(400));stack.computeScroll();layout();
            assertEquals(0,stack.getScrollPosition(),.01f);assertTrue(stack.selectAlbum(-1));assertEquals(count-1,stack.getSelectedIndex());
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(400));stack.computeScroll();layout();
            assertEquals(count-1,stack.getScrollPosition(),.01f);assertTrue(opened.isEmpty());assertFalse(stack.isSleeveOpeningOrOpen());
        }
    }
    @Test public void cyclicRealSwipesCrossBothEdgesAndRetractTheOldRecord(){
        stack.setLooping(true);stack.setEntries(entries(2));stack.setScrollPosition(0);stack.openSelectedSleeve();finish();
        float spacing=ReflectionHelpers.getField(stack,"spacing");long now=SystemClock.uptimeMillis();
        touch(MotionEvent.ACTION_DOWN,now,now,160,240);touch(MotionEvent.ACTION_MOVE,now,now+300,160+spacing,240);touch(MotionEvent.ACTION_UP,now,now+800,160+spacing,240);
        assertEquals(1,stack.getSelectedIndex());assertFalse(stack.isSleeveOpeningOrOpen());assertTrue(opened.isEmpty());finish();
        stack.setScrollPosition(1);layout();now=SystemClock.uptimeMillis();
        touch(MotionEvent.ACTION_DOWN,now,now,200,240);touch(MotionEvent.ACTION_MOVE,now,now+300,200-spacing,240);touch(MotionEvent.ACTION_UP,now,now+800,200-spacing,240);
        assertEquals(0,stack.getSelectedIndex());assertFalse(stack.isSleeveOpeningOrOpen());assertTrue(opened.isEmpty());
    }
    @Test public void oneAlbumDoesNotDuplicateOrHapticAndLoopRestorationKeepsTheRealKey(){
        stack.setLooping(true);stack.setEntries(entries(1));layout();assertFalse(stack.isLooping());assertFalse(stack.selectAlbum(1));assertFalse(stack.selectAlbum(-1));
        assertEquals(-1L,(long)ReflectionHelpers.<Long>getField(stack,"lastSelectionHaptic"));
        stack.setEntries(entries(1200));stack.setScrollPosition(1199);stack.setExpanded(true);finish();assertEquals(1199,stack.getSelectedIndex());assertTrue(stack.isSleeveOpen());
        ArrayList<AlbumStackView.Entry> changed=entries(1200);changed.add(0,new AlbumStackView.Entry("new","New","Artist","",Collections.emptyList()));stack.setEntries(changed);layout();
        assertEquals(1200,stack.getSelectedIndex());assertTrue(stack.isSleeveOpen());assertEquals(-1L,(long)ReflectionHelpers.<Long>getField(stack,"lastSelectionHaptic"));
    }
    @Test public void reducedMotionCyclicNavigationAndExtractionFinishAtTheirRealTargets(){
        stack.setLooping(true);stack.setEntries(entries(2));ReflectionHelpers.callStaticMethod(ValueAnimator.class,"setDurationScale",ReflectionHelpers.ClassParameter.from(float.class,0f));
        stack.selectAlbum(-1);assertEquals(1,stack.getSelectedIndex());assertEquals(1,stack.getScrollPosition(),.01f);stack.performClick();assertTrue(stack.isSleeveOpen());assertTrue(opened.isEmpty());
        stack.selectAlbum(1);assertEquals(0,stack.getSelectedIndex());assertEquals(0,stack.getScrollPosition(),.01f);assertFalse(stack.isSleeveOpen());stack.performClick();assertTrue(stack.isSleeveOpen());stack.performClick();assertEquals(Collections.singletonList(0),opened);
    }
    @Test public void firstRealTapOnlyUnpacksAndSecondCompletedTapOpensTracks(){
        tap(160,244);assertTrue(stack.isSleeveOpeningOrOpen());assertFalse(stack.isSleeveOpen());assertNotNull(animator());animator().setCurrentPlayTime(280);assertTrue(stack.getSleeveOpenProgress()>0);assertFalse(stack.isSleeveOpen());stack.performClick();assertTrue(opened.isEmpty());assertEquals(0,oldCallbackCalls);
        finish();assertTrue(stack.isSleeveOpen());tap(105,244);assertEquals(Collections.singletonList(0),opened);assertEquals(0,oldCallbackCalls);
    }
    @Test public void exposedRecordReceivesRealTapBeforeNeighborWithoutNavigatingDuringExtraction(){
        stack.openSelectedSleeve();animator().setCurrentPlayTime(600);Bitmap frame=Bitmap.createBitmap(320,480,Bitmap.Config.ARGB_8888);try{stack.draw(new Canvas(frame));}finally{frame.recycle();}assertFalse("The tap is during extraction, not after the animator has finished",stack.isSleeveOpen());
        int extractingHit=ReflectionHelpers.callInstanceMethod(stack,"hitTest",ReflectionHelpers.ClassParameter.from(float.class,250f),ReflectionHelpers.ClassParameter.from(float.class,244f));
        assertEquals("The exposed record belongs to its selected sleeve during extraction",0,extractingHit);tap(250,244);assertTrue(opened.isEmpty());assertEquals(0,stack.getSelectedIndex());
        finish();assertTrue(stack.isSleeveOpen());tap(285,244);assertEquals("A tap beyond the cover but inside the record opens this album",Collections.singletonList(0),opened);assertEquals(0,stack.getSelectedIndex());assertEquals(0,oldCallbackCalls);
    }
    @Test public void horizontalDragChangesSelectionAndRetractsWithoutOpeningTracks(){
        stack.openSelectedSleeve();finish();long now=SystemClock.uptimeMillis();touch(MotionEvent.ACTION_DOWN,now,now,280,245);touch(MotionEvent.ACTION_MOVE,now,now+150,100,245);touch(MotionEvent.ACTION_UP,now,now+400,100,245);
        assertTrue(stack.getSelectedIndex()>0);assertFalse(stack.isSleeveOpeningOrOpen());assertTrue(opened.isEmpty());finish();assertEquals(0,stack.getSleeveOpenProgress(),.001f);assertEquals(-1,(int)ReflectionHelpers.<Integer>getField(stack,"sleeveIndex"));
    }
    @Test public void verticalGestureNeverOpensOrSelectsInHorizontalShelf(){
        long now=SystemClock.uptimeMillis();touch(MotionEvent.ACTION_DOWN,now,now,160,240);touch(MotionEvent.ACTION_MOVE,now,now+100,160,120);touch(MotionEvent.ACTION_UP,now,now+200,160,120);assertEquals(0,stack.getSelectedIndex());assertFalse(stack.isSleeveOpeningOrOpen());assertTrue(opened.isEmpty());
    }
    @Test public void rapidSelectionCannotTransferAnOldRecordOrEnterDuringTransition(){
        stack.openSelectedSleeve();finish();stack.selectAlbum(1);assertEquals(0,(int)ReflectionHelpers.<Integer>getField(stack,"sleeveIndex"));assertEquals(1,stack.getSelectedIndex());assertFalse(stack.isSleeveOpeningOrOpen());
        stack.performClick();assertTrue(stack.isSleeveOpeningOrOpen());assertFalse(stack.isSleeveOpen());stack.selectAlbum(1);finish();assertEquals(2,stack.getSelectedIndex());assertFalse(stack.isSleeveOpeningOrOpen());assertEquals(0,stack.getSleeveOpenProgress(),.001f);assertTrue(opened.isEmpty());
        stack.performClick();assertEquals(2,(int)ReflectionHelpers.<Integer>getField(stack,"sleeveIndex"));finish();stack.performClick();assertEquals(Collections.singletonList(2),opened);
    }
    @Test public void pendingPositionAndOpenRestoreReachFinalKeyWithoutHaptics(){
        AlbumStackView restored=new AlbumStackView(controller.get());restored.setUpright(true);restored.setEntries(entries(1200));restored.setScrollPosition(100);restored.setExpanded(true);ArrayList<Boolean> restoredStates=new ArrayList<>();restored.setOnSleeveStateChangedListener(()->restoredStates.add(restored.isSleeveOpeningOrOpen()));controller.get().setContentView(restored);stack=restored;layout();assertEquals(100,stack.getSelectedIndex());assertTrue(stack.isSleeveOpeningOrOpen());finish();assertTrue(stack.isSleeveOpen());assertEquals(-1L,(long)ReflectionHelpers.<Long>getField(stack,"lastSelectionHaptic"));assertFalse("Restoration must not emit a false user-close event",restoredStates.contains(false));
    }
    @Test public void metadataReorderPreservesOpenRealAlbumKeyAndResizeDoesNotRestartAnimation(){
        stack.setScrollPosition(5);stack.openSelectedSleeve();finish();ArrayList<AlbumStackView.Entry> changed=entries(12);changed.add(0,new AlbumStackView.Entry("new","New","Artist","",Collections.emptyList()));stack.setEntries(changed);layout();assertEquals(6,stack.getSelectedIndex());assertTrue(stack.isSleeveOpen());assertEquals(6,(int)ReflectionHelpers.<Integer>getField(stack,"sleeveIndex"));
        stack.measure(View.MeasureSpec.makeMeasureSpec(726,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(280,View.MeasureSpec.EXACTLY));stack.layout(0,0,726,280);assertTrue(stack.isSleeveOpen());assertNull(animator());assertTrue(opened.isEmpty());
    }
    @Test public void catalogueRebuildKeepsQueuedOpenForTheSameSelectedKey(){
        stack.openSelectedSleeve();finish();stack.selectAlbum(1);stack.openSelectedSleeve();assertTrue(stack.isSleeveOpeningOrOpen());ArrayList<AlbumStackView.Entry> changed=entries(15);changed.add(0,new AlbumStackView.Entry("inserted","Inserted","Artist","",Collections.emptyList()));stack.setEntries(changed);assertEquals(2,stack.getSelectedIndex());assertTrue(stack.isSleeveOpeningOrOpen());finish();finish();assertTrue(stack.isSleeveOpen());assertEquals(2,(int)ReflectionHelpers.<Integer>getField(stack,"sleeveIndex"));assertTrue(opened.isEmpty());
    }
    @Test public void extremelyWideShortViewportStillDrawsSelectedAlbumWithinBoundedWindow(){
        stack.setEntries(entries(1200));stack.setScrollPosition(600);stack.measure(View.MeasureSpec.makeMeasureSpec(2000,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(100,View.MeasureSpec.EXACTLY));stack.layout(0,0,2000,100);Bitmap b=Bitmap.createBitmap(2000,100,Bitmap.Config.ARGB_8888);try{stack.draw(new Canvas(b));Object[] drawn=ReflectionHelpers.getField(stack,"drawn");int count=ReflectionHelpers.getField(stack,"drawnCount");assertTrue(count<=18);boolean selected=false;for(int i=0;i<count;i++)selected|=ReflectionHelpers.<Integer>getField(drawn[i],"index")==600;assertTrue("The current sleeve remains in the finite render window",selected);}finally{b.recycle();}
    }
    @Test public void lifecycleStopsAnimationWithoutErasingOpenIntentOrDispatchingNavigation(){
        ArrayList<Boolean> states=new ArrayList<>();stack.setOnSleeveStateChangedListener(()->states.add(stack.isSleeveOpeningOrOpen()));stack.openSelectedSleeve();animator().setCurrentPlayTime(350);stack.onWindowVisibilityChanged(View.GONE);assertNull(animator());assertTrue(stack.isSleeveOpeningOrOpen());assertTrue(stack.isSleeveOpen());assertFalse(states.contains(false));
        stack.onWindowVisibilityChanged(View.VISIBLE);controller.get().setContentView(new FrameLayout(controller.get()));assertNull(animator());assertTrue(stack.isSleeveOpeningOrOpen());controller.get().setContentView(stack);layout();assertTrue(stack.isSleeveOpen());assertTrue(opened.isEmpty());
    }
    @Test public void disabledSystemAnimationFinishesImmediatelyAndKeyboardUsesTwoStepContract(){
        ReflectionHelpers.callStaticMethod(ValueAnimator.class,"setDurationScale",ReflectionHelpers.ClassParameter.from(float.class,0f));stack.onKeyDown(KeyEvent.KEYCODE_DPAD_RIGHT,new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_RIGHT));assertEquals(1,stack.getSelectedIndex());stack.onKeyDown(KeyEvent.KEYCODE_ENTER,new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_ENTER));assertTrue(stack.isSleeveOpen());assertNull(animator());assertTrue(opened.isEmpty());stack.onKeyDown(KeyEvent.KEYCODE_ENTER,new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_ENTER));assertEquals(Collections.singletonList(1),opened);stack.setExpanded(false);assertFalse(stack.isSleeveOpen());assertEquals(0,stack.getSleeveOpenProgress(),.001f);
    }
}
