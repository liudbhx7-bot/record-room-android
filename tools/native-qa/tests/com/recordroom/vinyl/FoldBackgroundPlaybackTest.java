package com.recordroom.vinyl;

import android.content.ComponentName;
import android.content.Intent;
import android.os.Looper;
import androidx.media3.exoplayer.ExoPlayer;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.*;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;

/** Real service/Activity and local decoder; no claim about physical fold hardware or QQ entitlement. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=34, qualifiers="w412dp-h900dp-port-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
public class FoldBackgroundPlaybackTest {
    private ServiceController<PlayerService> serviceController;
    private ActivityController<MainActivity> controller;
    private PlayerService service;
    private MainActivity activity;
    private static Object get(Object object,String name)throws Exception {Field f=object.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(object);}
    private static void put(Object object,String name,Object value)throws Exception {Field f=object.getClass().getDeclaredField(name);f.setAccessible(true);f.set(object,value);}
    @Before public void setup()throws Exception {
        serviceController=Robolectric.buildService(PlayerService.class).create();service=serviceController.get();
        android.app.Application app=RuntimeEnvironment.getApplication();
        Shadows.shadowOf(app).setComponentNameAndServiceForBindService(new ComponentName(app,PlayerService.class),service.onBind(new Intent(app,PlayerService.class)));
        controller=Robolectric.buildActivity(MainActivity.class).setup().visible();activity=controller.get();drain();
        service.setPlayMode(PlayerService.MODE_REPEAT_ONE);service.setQueue(DemoCatalog.load(activity),0,true);
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
        while(!service.isPlaying()&&System.nanoTime()<until){Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20));Thread.sleep(5);}
        assertTrue(service.getError(),service.isPlaying());drain();
    }
    private void drain()throws Exception {((ExecutorService)get(activity,"io")).submit(()->{}).get(10,TimeUnit.SECONDS);CoverView.IO.submit(()->{}).get(10,TimeUnit.SECONDS);Shadows.shadowOf(Looper.getMainLooper()).idle();}
    @After public void cleanup(){if(controller!=null)controller.pause().stop().destroy();if(serviceController!=null)serviceController.destroy();}

    @Test public void invisibleActivityUnsubscribesButPlaybackAndTransportContinue()throws Exception {
        assertFalse(((List<?>)get(service,"listeners")).isEmpty());
        String old=(String)get(activity,"shownId");ExoPlayer engine=(ExoPlayer)get(service,"player");
        controller.pause().stop();
        assertTrue("Invisible UI must not receive 500 ms progress refreshes",((List<?>)get(service,"listeners")).isEmpty());
        assertTrue(service.isPlaying());assertSame(engine,get(service,"player"));
        TurntableView table=(TurntableView)get(activity,"turntable");assertFalse((Boolean)get(table,"active"));assertNull(get(table,"spinAnimator"));
        service.onStartCommand(new Intent(activity,PlayerService.class).setAction(PlayerService.ACTION_NEXT),0,2);
        Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals("Hidden Activity must not rebuild track UI",old,get(activity,"shownId"));
        controller.restart().start().resume();drain();
        assertEquals(service.getCurrent().id,get(activity,"shownId"));assertEquals(1,((List<?>)get(service,"listeners")).size());
    }

    @Test public void repeatedFoldAndRotateKeepPlayerQueuePositionAndRecordPose()throws Exception {
        ExoPlayer engine=(ExoPlayer)get(service,"player");String selected=service.getCurrent().id;
        ArrayList<String> identities=new ArrayList<>();for(Track t:service.getQueue())identities.add(LibraryStore.identity(t));
        service.seekTo(5000);drain();
        for(String size:new String[]{"w840dp-h900dp-port-mdpi","w900dp-h600dp-land-mdpi","w412dp-h900dp-port-mdpi"}){
            TurntableView previous=(TurntableView)get(activity,"turntable");put(previous,"rotation",137f);
            RuntimeEnvironment.setQualifiers(size);activity.onConfigurationChanged(activity.getResources().getConfiguration());drain();
            assertSame("Resizing must not create another audio player",engine,get(service,"player"));assertTrue(service.isPlaying());assertEquals(selected,service.getCurrent().id);
            ArrayList<String> now=new ArrayList<>();for(Track t:service.getQueue())now.add(LibraryStore.identity(t));assertEquals(identities,now);
            assertTrue("Resizing must not seek back to zero",service.getPositionMs()>=4900);
            TurntableView current=(TurntableView)get(activity,"turntable");assertNotSame(previous,current);
            assertEquals("Fold must not reset the disc to its initial angle",137f,(Float)get(current,"rotation"),.1f);
            // Actual emulator smoke checks the spin animator; legacy Robolectric marks rebuilt windows GONE.
            assertTrue("Rebuilt player remains active",(Boolean)get(current,"active"));
            assertEquals("Stable playing needle stays on the groove",(Float)get(current,"armGoal"),(Float)get(current,"armAngle"),.1f);
            assertNull("Size change must not trigger another needle drop",get(current,"armAnimator"));
            assertFalse((Boolean)get(previous,"active"));assertNull(get(previous,"spinAnimator"));
        }
    }
}
