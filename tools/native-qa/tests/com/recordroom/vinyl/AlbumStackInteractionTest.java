package com.recordroom.vinyl;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Looper;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import java.util.ArrayList;
import java.util.Arrays;
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
import org.robolectric.shadows.ShadowSystemClock;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=34,qualifiers="w320dp-h480dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public class AlbumStackInteractionTest {
    ActivityController<Activity> controller;AlbumStackView stack;
    @Before public void setup(){controller=Robolectric.buildActivity(Activity.class).setup().visible();stack=new AlbumStackView(controller.get());controller.get().setContentView(stack);}
    @After public void teardown(){controller.pause().stop().destroy();}
    Track track(String id,String album){return new Track(id,"Song "+id,"Artist",album,"local","asset:///demo/night-1.wav","",180000,1);}
    void layout(){stack.measure(View.MeasureSpec.makeMeasureSpec(320,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(480,View.MeasureSpec.EXACTLY));stack.layout(0,0,320,480);Shadows.shadowOf(Looper.getMainLooper()).idle();}
    ArrayList<AlbumStackView.Entry> entries(int count){ArrayList<AlbumStackView.Entry> result=new ArrayList<>();for(int i=0;i<count;i++)result.add(new AlbumStackView.Entry("album-"+i,"Album "+i,"Artist","",Collections.emptyList()));return result;}
    void touch(int action,long at,float y){MotionEvent event=MotionEvent.obtain(at,at,action,160,y,0);try{assertTrue(stack.onTouchEvent(event));}finally{event.recycle();}}
    @Test public void repeatedSongGroupsProduceOneAlbumAndOneRealTrackList(){
        Track first=track("1","Same Album"),second=track("2","Same Album");ArrayList<ArrayList<Track>> input=new ArrayList<>();input.add(new ArrayList<>(Arrays.asList(first)));input.add(new ArrayList<>(Arrays.asList(second,first)));stack.setAlbums(input);layout();assertEquals(1,stack.getAlbumCount());ArrayList<Track> opened=new ArrayList<>();stack.setOnAlbumClickListener(opened::addAll);stack.performClick();assertEquals(Arrays.asList(first,second),opened);
    }
    @Test public void metadataOnlyCatalogKeepsLastAlbumReachableWithoutInventedSongs(){
        stack.setExpanded(true);stack.setEntries(entries(1200));layout();stack.setScrollPosition(1199);int[] selected={-1};stack.setOnAlbumIndexClickListener(index->selected[0]=index);assertFalse(stack.selectAlbum(1));stack.performClick();assertEquals(1199,selected[0]);
        Bitmap b=Bitmap.createBitmap(320,480,Bitmap.Config.ARGB_8888);try{stack.draw(new Canvas(b));int drawn=ReflectionHelpers.getField(stack,"drawnCount");assertTrue(drawn>0&&drawn<=18);}finally{b.recycle();}
    }
    @Test public void dragSelectionUsesRestrainedHapticsAndRestorationIsSilent(){
        stack.setExpanded(true);stack.setEntries(entries(12));layout();stack.setScrollPosition(0);assertEquals(-1L,(long)ReflectionHelpers.<Long>getField(stack,"lastSelectionHaptic"));
        long at=SystemClock.uptimeMillis();touch(MotionEvent.ACTION_DOWN,at,360);touch(MotionEvent.ACTION_MOVE,at+80,150);assertEquals(1,stack.getSelectedIndex());assertEquals(HapticFeedbackConstants.CLOCK_TICK,Shadows.shadowOf(stack).lastHapticFeedbackPerformed());long first=ReflectionHelpers.getField(stack,"lastSelectionHaptic");
        stack.selectAlbum(1);assertEquals(first,(long)ReflectionHelpers.<Long>getField(stack,"lastSelectionHaptic"));touch(MotionEvent.ACTION_CANCEL,at+100,150);stack.setScrollPosition(8);assertEquals(first,(long)ReflectionHelpers.<Long>getField(stack,"lastSelectionHaptic"));
        ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(100));stack.selectAlbum(1);assertTrue((long)ReflectionHelpers.<Long>getField(stack,"lastSelectionHaptic")>first);
        long second=ReflectionHelpers.getField(stack,"lastSelectionHaptic");stack.setHapticFeedbackEnabled(false);ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(100));stack.selectAlbum(1);assertEquals(second,(long)ReflectionHelpers.<Long>getField(stack,"lastSelectionHaptic"));
    }
    @Test public void collapsedMainShelfAlsoTicksOnUserSelectionAndPreservesKeyWhenCatalogGrows(){
        ArrayList<AlbumStackView.Entry> original=entries(10);stack.setEntries(original);layout();stack.setScrollPosition(4);stack.selectAlbum(1);assertEquals(5,stack.getSelectedIndex());assertEquals(HapticFeedbackConstants.CLOCK_TICK,Shadows.shadowOf(stack).lastHapticFeedbackPerformed());
        stack.setScrollPosition(5);ArrayList<AlbumStackView.Entry> changed=new ArrayList<>();changed.add(new AlbumStackView.Entry("new","New Album","Artist","",Collections.emptyList()));changed.addAll(original);stack.setEntries(changed);assertEquals(6,stack.getSelectedIndex());stack.setExpanded(true);assertEquals(6,stack.getSelectedIndex());stack.setExpanded(false);assertEquals(6,stack.getSelectedIndex());
    }
}
