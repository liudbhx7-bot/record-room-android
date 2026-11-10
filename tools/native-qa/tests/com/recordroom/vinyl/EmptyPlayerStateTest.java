package com.recordroom.vinyl;

import android.app.Application;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Looper;
import android.widget.TextView;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=34, qualifiers="w512dp-h726dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class EmptyPlayerStateTest {
    private static Object field(Object owner,String name)throws Exception {
        Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(owner);
    }
    private static void drain(Object owner,String executor)throws Exception {
        ((ExecutorService)field(owner,executor)).submit(()->{}).get(20,TimeUnit.SECONDS);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }
    private static ArrayList<Track> single(Track t) { ArrayList<Track> tracks=new ArrayList<>();tracks.add(t);return tracks; }
    private static void assertEmpty(MainActivity activity)throws Exception {
        assertEquals("唱片机",((TextView)field(activity,"title")).getText().toString());
        assertEquals("选择一张唱片开始播放",((TextView)field(activity,"artist")).getText().toString());
        assertEquals("",field(activity,"shownId"));
        assertNull(field(activity,"currentArtwork"));
        assertNull(field(field(activity,"turntable"),"artwork"));
        assertNull(field(field(activity,"dockCover"),"artwork"));
    }

    @Test public void removingLastSongClearsUiAndRejectsPendingCover()throws Exception {
        Application app=RuntimeEnvironment.getApplication();
        ServiceController<PlayerService> sc=Robolectric.buildService(PlayerService.class).create();
        PlayerService service=sc.get();
        Shadows.shadowOf(app).setComponentNameAndServiceForBindService(new ComponentName(app,PlayerService.class),service.onBind(new Intent(app,PlayerService.class)));
        ActivityController<MainActivity> ac=Robolectric.buildActivity(MainActivity.class).setup().visible();
        MainActivity activity=ac.get();CountDownLatch release=new CountDownLatch(1);
        try {
            drain(activity,"io");
            List<Track> demos=DemoCatalog.load(app);assertTrue(demos.size()>3);
            service.setQueue(single(demos.get(0)),0,false);drain(activity,"io");
            assertNotNull("Start with a real decoded cover",field(activity,"currentArtwork"));
            assertEquals(demos.get(0).title,((TextView)field(activity,"title")).getText().toString());
            CountDownLatch blocked=new CountDownLatch(1);
            ((ExecutorService)field(activity,"io")).submit(()->{blocked.countDown();try{release.await(10,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}});
            assertTrue(blocked.await(5,TimeUnit.SECONDS));
            service.setQueue(single(demos.get(3)),0,false);
            service.removeQueueItem(0);
            assertNull(service.getCurrent());assertEmpty(activity);
            release.countDown();drain(activity,"io");
            assertEmpty(activity);
        } finally {release.countDown();ac.pause().stop().destroy();sc.destroy();}
    }

    @Test public void emptyQueueCancelsNotificationAndRejectsOldArtworkPublish()throws Exception {
        ServiceController<PlayerService> sc=Robolectric.buildService(PlayerService.class).create();
        PlayerService service=sc.get();
        try {
            Track track=DemoCatalog.load(service).get(0);
            service.setQueue(single(track),0,false);drain(service,"artworkIO");
            NotificationManager manager=(NotificationManager)service.getSystemService(Context.NOTIFICATION_SERVICE);
            assertNotNull(Shadows.shadowOf(manager).getNotification(42));
            long oldGeneration=(Long)field(service,"artworkGeneration");
            String oldSignature=(String)field(service,"artworkSignature");
            service.removeQueueItem(0);
            assertNull("Last-song removal must remove the stale media notification",Shadows.shadowOf(manager).getNotification(42));
            Bitmap stale=Bitmap.createBitmap(8,8,Bitmap.Config.ARGB_8888);
            Method complete=PlayerService.class.getDeclaredMethod("completeArtwork",long.class,String.class,Bitmap.class);complete.setAccessible(true);
            complete.invoke(service,oldGeneration,oldSignature,stale);
            assertTrue(stale.isRecycled());drain(service,"artworkIO");
            assertNull(Shadows.shadowOf(manager).getNotification(42));
        } finally {sc.destroy();}
    }
}
