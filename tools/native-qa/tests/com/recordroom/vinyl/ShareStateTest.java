package com.recordroom.vinyl;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Looper;
import android.os.Parcel;
import android.widget.TextView;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
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
import org.robolectric.shadows.ShadowActivity;
import org.robolectric.shadows.ShadowToast;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=34,qualifiers="w412dp-h860dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ShareStateTest {
    private ActivityController<Activity> controller;
    private ShareStudio studio;
    @Before public void setup(){openActivity();}
    @After public void cleanup(){if(studio!=null)studio.close();if(controller!=null)controller.pause().stop().destroy();}
    private void openActivity(){controller=Robolectric.buildActivity(Activity.class).setup().visible();}
    private void recreate(){if(studio!=null)studio.close();controller.pause().stop().destroy();studio=null;openActivity();}
    private static Object field(Object target,String name)throws Exception{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}
    private static Object call(Object target,String name,Class<?>[] types,Object...args)throws Exception{Method m=target.getClass().getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(target,args);}
    private static void idle(){Shadows.shadowOf(Looper.getMainLooper()).idle();}
    private static Bundle parcelled(Bundle state){Parcel p=Parcel.obtain();try{p.writeBundle(state);p.setDataPosition(0);return p.readBundle(ShareStateTest.class.getClassLoader());}finally{p.recycle();}}
    private void drain()throws Exception{((ExecutorService)field(studio,"worker")).submit(()->{}).get(20,TimeUnit.SECONDS);idle();}
    private void show()throws Exception{
        ArrayList<Track> tracks=new ArrayList<>();
        tracks.add(new Track("n","Blue Night","Record Room","Night Walk","demo","asset:///demo/night-1.wav","asset:///demo/night-v2.png",18000,1));
        studio=new ShareStudio(controller.get(),"Night Walk","Record Room",tracks,0xffde8b7c,2);studio.show();drain();
    }
    private File exportToPicker()throws Exception{
        call(studio,"export",new Class<?>[]{boolean.class},false);drain();
        ShadowActivity.IntentForResult request=Shadows.shadowOf(controller.get()).getNextStartedActivityForResult();
        assertNotNull(request);assertEquals(ShareStudio.SAVE_REQUEST,request.requestCode);
        assertEquals(Intent.ACTION_CREATE_DOCUMENT,request.intent.getAction());
        assertTrue(studio.hasPendingSave());
        return (File)field(studio,"pendingSaveFile");
    }

    @Test public void selectedTemplateSurvivesRealParcelAndDialogRecreation()throws Exception{
        show();call(studio,"selectLayout",new Class<?>[]{int.class},2);
        int[] closed={0};studio.setOnClosed(()->closed[0]++);
        Bundle state=parcelled(studio.snapshot());assertTrue(state.getBoolean("visible"));
        studio.close();studio.close();assertEquals(1,closed[0]);assertNull(studio.snapshot());
        recreate();studio=ShareStudio.restore(controller.get(),state);assertFalse(studio.isVisible());
        studio.show();drain();assertEquals(2,field(studio,"layout"));
        assertEquals("3 / 3",((TextView)field(studio,"count")).getText().toString());
        assertNull(Shadows.shadowOf(controller.get()).getNextStartedActivityForResult());
    }

    @Test public void pendingPngIsSavedAfterRecreationWithoutRelaunchingPicker()throws Exception{
        show();File exported=exportToPicker();byte[] expected=Files.readAllBytes(exported.toPath());
        assertTrue(expected.length>20000);Bundle state=parcelled(studio.snapshot());recreate();
        studio=ShareStudio.restore(controller.get(),state);assertTrue(studio.hasPendingSave());assertFalse(studio.isVisible());
        assertNull(Shadows.shadowOf(controller.get()).getNextStartedActivityForResult());
        Uri target=Uri.parse("content://test.documents/restored-card");CountDownLatch copied=new CountDownLatch(1);
        ByteArrayOutputStream bytes=new ByteArrayOutputStream(){@Override public void close(){copied.countDown();}};
        Shadows.shadowOf(controller.get().getContentResolver()).registerOutputStream(target,bytes);
        assertTrue(studio.onActivityResult(ShareStudio.SAVE_REQUEST,Activity.RESULT_OK,new Intent().setData(target)));
        assertTrue("Actual PNG copy did not finish",copied.await(10,TimeUnit.SECONDS));idle();
        assertArrayEquals(expected,bytes.toByteArray());assertFalse(studio.hasPendingSave());
        assertTrue("Recreation must retain granted export file",exported.isFile());
    }

    @Test public void missingOrInvalidCachedFileDoesNotSaveUnrelatedDataAndCancellationClearsWait()throws Exception{
        show();File exported=exportToPicker();Bundle valid=parcelled(studio.snapshot());
        Bundle invalid=new Bundle(valid);invalid.putString("pending_file","../private.png");recreate();
        studio=ShareStudio.restore(controller.get(),invalid);assertTrue(studio.hasPendingSave());assertNull(field(studio,"pendingSaveFile"));
        studio.onActivityResult(ShareStudio.SAVE_REQUEST,Activity.RESULT_OK,new Intent().setData(Uri.parse("content://test.documents/invalid")));
        assertEquals("没有可保存的图片，请重新生成",ShadowToast.getTextOfLatestToast());assertFalse(studio.hasPendingSave());
        studio.close();studio=ShareStudio.restore(controller.get(),valid);
        studio.onActivityResult(ShareStudio.SAVE_REQUEST,Activity.RESULT_CANCELED,null);assertFalse(studio.hasPendingSave());
        assertTrue(exported.delete());studio.close();studio=ShareStudio.restore(controller.get(),valid);
        assertNull(field(studio,"pendingSaveFile"));
        studio.onActivityResult(ShareStudio.SAVE_REQUEST,Activity.RESULT_OK,new Intent().setData(Uri.parse("content://test.documents/missing")));
        assertFalse(studio.hasPendingSave());assertEquals("没有可保存的图片，请重新生成",ShadowToast.getTextOfLatestToast());
    }

    @Test public void interruptedExportResumesOnceAfterArtworkReload()throws Exception{
        show();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        ((ExecutorService)field(studio,"worker")).execute(()->{entered.countDown();try{release.await();}catch(InterruptedException ignored){Thread.currentThread().interrupt();}});
        assertTrue(entered.await(5,TimeUnit.SECONDS));call(studio,"export",new Class<?>[]{boolean.class},false);
        Bundle state=parcelled(studio.snapshot());assertEquals(0,state.getInt("rendering_action",-1));
        Activity old=controller.get();recreate();release.countDown();
        studio=ShareStudio.restore(controller.get(),state);studio.show();drain();drain();
        assertTrue(studio.hasPendingSave());assertNotNull(Shadows.shadowOf(controller.get()).getNextStartedActivityForResult());
        assertNull("Resume must not launch the destination picker twice",Shadows.shadowOf(controller.get()).getNextStartedActivityForResult());
        assertNull("An old Activity must never launch a late picker",Shadows.shadowOf(old).getNextStartedActivityForResult());
    }

    @Test public void snapshotBoundsArtworkCandidatesForLargeQueues(){
        ArrayList<Track> tracks=new ArrayList<>();for(int i=0;i<500;i++)tracks.add(new Track("id"+i,"Song","Artist","Album"+i,"local","file:///"+i,"file:///cover"+i,1000,1));
        studio=new ShareStudio(controller.get(),"Queue","500 songs",tracks,0xffde8b7c,2);
        Bundle state=parcelled(studio.snapshot());assertEquals(8,state.getStringArrayList("tracks").size());
    }
}
