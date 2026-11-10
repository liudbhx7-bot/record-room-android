package com.recordroom.vinyl;

import android.app.Activity;
import android.content.ClipData;
import android.view.DragEvent;
import android.view.View;
import android.view.ViewGroup;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
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
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=34,qualifiers="w320dp-h480dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
public class QueueInteractionTest {
    // These are input/state tests, not pixel assertions. Legacy graphics supplies
    // the platform drag Surface without mixing a fake Surface into native Skia.
    // Real View long-click, drag token, ListView dispatch and scrolling still run.
    private ActivityController<Activity> controller;
    private QueueListView list;
    private ArrayList<Track> tracks;
    private final ArrayList<String> actions=new ArrayList<>();
    @Before public void createList() {
        actions.clear();
        controller=Robolectric.buildActivity(Activity.class).setup().visible();
        list=new QueueListView(controller.get());
        list.setActions(new QueueListView.Actions(){
            public void play(int index){actions.add("play:"+index);}
            public void menu(int index){actions.add("menu:"+index);}
            public void move(int from,int to){actions.add("move:"+from+":"+to);}
        });
        tracks=new ArrayList<>();
        for(int i=0;i<200;i++)tracks.add(new Track("queue-"+i,"Queue song "+i,"Artist","Album","local","file:///track"+i,"",180000,i));
        list.submit(tracks,0);controller.get().setContentView(list);layout();
        assertTrue(list.getChildCount()>=3);
        assertTrue("Visible row count stays bounded for a 200-song queue",list.getChildCount()<20);
    }
    @After public void closeList(){controller.pause().stop().destroy();}
    private void idle(){Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();}
    private void layout(){list.measure(View.MeasureSpec.makeMeasureSpec(320,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(320,View.MeasureSpec.EXACTLY));list.layout(0,0,320,320);idle();}
    private float center(int child){View row=list.getChildAt(child);return (row.getTop()+row.getBottom())/2f;}
    private Object startFromLabel(int child) {
        ViewGroup row=(ViewGroup)list.getChildAt(child);
        ViewGroup labels=(ViewGroup)row.getChildAt(0);
        assertTrue("Long pressing the actual title starts platform drag",labels.getChildAt(0).performLongClick());
        Object root=ReflectionHelpers.callInstanceMethod(list,"getViewRootImpl");
        Object token=ReflectionHelpers.getField(root,"mLocalDragState");assertNotNull(token);
        assertTrue(event(DragEvent.ACTION_DRAG_STARTED,100,center(child),token));return token;
    }
    private boolean event(int action,float x,float y,Object token) {
        DragEvent event=DragEvent.obtain(action,x,y,0,0,token,null,null,null,null,false);
        try{return list.dispatchDragEvent(event);}finally{event.recycle();}
    }
    @Test public void titleLongPressHighlightsAndUsesFinalDropPositionExactlyOnce() {
        Object token=startFromLabel(0);
        event(DragEvent.ACTION_DRAG_LOCATION,100,center(1),token);
        assertTrue(list.getChildAt(1).isActivated());
        event(DragEvent.ACTION_DROP,100,center(2),token);
        event(DragEvent.ACTION_DROP,100,center(2),token);
        event(DragEvent.ACTION_DRAG_ENDED,100,center(2),token);
        assertEquals(java.util.Collections.singletonList("move:0:2"),actions);
        for(int i=0;i<list.getChildCount();i++)assertFalse(list.getChildAt(i).isActivated());
    }
    @Test public void submittedQueueInvalidatesOldDragAndExternalPayloadIsRejected() {
        Object token=startFromLabel(0);
        ArrayList<Track> changed=new ArrayList<>(tracks);java.util.Collections.swap(changed,0,1);
        list.submit(changed,1);layout();
        event(DragEvent.ACTION_DROP,100,center(2),token);
        event(DragEvent.ACTION_DRAG_ENDED,100,center(2),token);
        assertTrue(actions.isEmpty());
        assertFalse(event(DragEvent.ACTION_DRAG_STARTED,100,20,Integer.valueOf(0)));
        event(DragEvent.ACTION_DROP,100,center(2),Integer.valueOf(0));assertTrue(actions.isEmpty());
    }
    @Test public void edgeScrollMovesViewportAndCancellationStopsItWithoutReorder() {
        Object token=startFromLabel(0);
        int before=list.getFirstVisiblePosition();int beforeTop=list.getChildAt(0).getTop();
        // The Activity's window traversal can resize the manually measured list.
        // Target its actual bottom edge rather than a stale 320px assumption.
        float bottomEdge=list.getHeight()-5;
        event(DragEvent.ACTION_DRAG_LOCATION,100,bottomEdge,token);
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(500));
        assertTrue("Holding a drag at the lower edge scrolls the real ListView",list.getFirstVisiblePosition()>before||list.getChildAt(0).getTop()<beforeTop);
        event(DragEvent.ACTION_DRAG_ENDED,100,bottomEdge,token);
        int position=list.getFirstVisiblePosition(),top=list.getChildAt(0).getTop();
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(500));
        assertEquals(position,list.getFirstVisiblePosition());assertEquals(top,list.getChildAt(0).getTop());assertTrue(actions.isEmpty());
    }
    @Test public void recycledRowsPlayAndMenuTheirNewTrack() {
        View recycled=list.getAdapter().getView(0,null,list);
        View bound=list.getAdapter().getView(150,recycled,list);
        assertSame(recycled,bound);
        ViewGroup row=(ViewGroup)bound;
        row.getChildAt(0).performClick();row.getChildAt(1).performClick();
        assertEquals(java.util.Arrays.asList("play:150","menu:150"),actions);
        list.submit(tracks,0);row.getChildAt(0).performClick();
        assertEquals("A stale bound row must not act after submit",2,actions.size());
    }
}
