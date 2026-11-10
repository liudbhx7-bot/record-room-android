package com.recordroom.vinyl;

import android.app.Dialog;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Looper;
import android.view.View;
import android.widget.EditText;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.*;
import org.robolectric.annotation.*;
import java.lang.reflect.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=34,qualifiers="w512dp-h726dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ActivityStateTest {
    private ServiceController<PlayerService> service;
    private ActivityController<MainActivity> controller;
    @Before public void setup()throws Exception {
        service=Robolectric.buildService(PlayerService.class).create();
        android.app.Application app=RuntimeEnvironment.getApplication();
        Shadows.shadowOf(app).setComponentNameAndServiceForBindService(new ComponentName(app,PlayerService.class),service.get().onBind(new Intent(app,PlayerService.class)));
        controller=Robolectric.buildActivity(MainActivity.class).setup().visible();drain();
    }
    @After public void close(){if(controller!=null)controller.pause().stop().destroy();service.destroy();}
    private Object get(String key)throws Exception{Field f=MainActivity.class.getDeclaredField(key);f.setAccessible(true);return f.get(controller.get());}
    private void set(String key,Object value)throws Exception{Field f=MainActivity.class.getDeclaredField(key);f.setAccessible(true);f.set(controller.get(),value);}
    private void call(String key,Class<?>[] types,Object... values)throws Exception{Method m=MainActivity.class.getDeclaredMethod(key,types);m.setAccessible(true);m.invoke(controller.get(),values);}
    private void drain()throws Exception{((ExecutorService)get("io")).submit(()->{}).get(10,TimeUnit.SECONDS);Shadows.shadowOf(Looper.getMainLooper()).idle();}
    private void layout(Dialog dialog){View decor=dialog.getWindow().getDecorView();for(int i=0;i<2;i++){decor.measure(View.MeasureSpec.makeMeasureSpec(512,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(726,View.MeasureSpec.EXACTLY));decor.layout(0,0,512,726);Shadows.shadowOf(Looper.getMainLooper()).idle();}}
    private Bundle snapshot()throws Exception{Bundle b=new Bundle();call("onSaveInstanceState",new Class<?>[]{Bundle.class},b);return b;}
    private void recreate(Bundle state)throws Exception{controller.pause().stop().destroy();controller=Robolectric.buildActivity(MainActivity.class).create(state).start().resume().visible();drain();}
    @Test public void processStyleRecreationRestoresSearchAndNestedPages()throws Exception {
        call("showShelf",new Class<?>[0]);EditText search=(EditText)get("shelfSearch");search.setVisibility(View.VISIBLE);search.setText("夜");
        call("showAlbum",new Class<?>[]{ArrayList.class},new ArrayList<>(DemoCatalog.load(controller.get())));
        call("showQueue",new Class<?>[0]);Bundle state=snapshot();
        recreate(state);
        assertEquals("夜",((EditText)get("shelfSearch")).getText().toString());assertTrue(((Dialog)get("queueDialog")).isShowing());assertEquals(3,((Number)get("fullPages")).intValue());
        ((Dialog)get("queueDialog")).onBackPressed();drain();assertTrue(((Dialog)get("albumDialog")).isShowing());
    }
    @Test public void coverPickerResultKeepsOriginalAlbumAcrossRecreation()throws Exception {
        ArrayList<Track> demos=new ArrayList<>(DemoCatalog.load(controller.get()));String target=demos.get(0).albumKey();
        set("artAlbum",target);Bundle state=snapshot();recreate(state);
        String picked="content://test-covers/original-album.png";
        final byte[] png;try(java.io.InputStream source=controller.get().getAssets().open("demo/night-v2.png")){png=source.readAllBytes();}
        Shadows.shadowOf(controller.get().getContentResolver()).registerInputStreamSupplier(Uri.parse(picked),()->new java.io.ByteArrayInputStream(png));
        call("onActivityResult",new Class<?>[]{int.class,int.class,Intent.class},102,Activity.RESULT_OK,new Intent().setData(Uri.parse(picked)));
        drain();
        assertEquals(picked,new ThemeStore(controller.get()).artwork(target));assertEquals("",get("artAlbum"));
        String orphan="content://test-covers/orphan.png";
        call("onActivityResult",new Class<?>[]{int.class,int.class,Intent.class},102,Activity.RESULT_OK,new Intent().setData(Uri.parse(orphan)));
        assertEquals("",new ThemeStore(controller.get()).artwork(""));assertEquals(picked,new ThemeStore(controller.get()).artwork(target));
    }
    @Test public void closingShareUnregistersWindowWithoutLosingShelf()throws Exception {
        call("showShelf",new Class<?>[0]);Dialog shelf=(Dialog)get("shelfDialog");
        for(int i=0;i<3;i++){
            call("showShare",new Class<?>[]{String.class,String.class,List.class},"Title","Artist",DemoCatalog.load(controller.get()));
            ShareStudio studio=(ShareStudio)get("shareStudio");assertTrue(studio.isVisible());studio.close();drain();
            assertEquals(1,((ArrayList<?>)get("dialogs")).size());assertTrue(shelf.isShowing());assertNull(get("shareStudio"));
        }
    }
    @Test public void albumArtistLabelOpensThatArtistAndQueueBackReturnsToAlbum()throws Exception {
        Track seed=new Track("collaboration","Song","Guest Singer","Collection","local","asset:///demo/night-1.wav","",18000,1,"Album Artist");
        @SuppressWarnings("unchecked") ArrayList<Track> library=(ArrayList<Track>)get("library");library.add(seed);
        call("showAlbum",new Class<?>[]{ArrayList.class},new ArrayList<>(java.util.Collections.singletonList(seed)));
        drain();Dialog album=(Dialog)get("albumDialog");layout(album);View label=findText(album.getWindow().getDecorView(),"Album Artist");assertNotNull(label);label.performClick();drain();
        Dialog artist=(Dialog)get("artistDialog");layout(artist);assertNotNull(findText(artist.getWindow().getDecorView(),"Album Artist"));artist.dismiss();drain();
        call("showQueue",new Class<?>[0]);Dialog queue=(Dialog)get("queueDialog");queue.onBackPressed();drain();assertTrue(album.isShowing());
    }
    @Test public void saveResultBeforeLibraryReadyDoesNotRestoreAnAlreadyConsumedPicker()throws Exception {
        call("showShare",new Class<?>[]{String.class,String.class,List.class},"Pending Card","Artist",DemoCatalog.load(controller.get()));
        ShareStudio original=(ShareStudio)get("shareStudio");drainShare(original);
        Dialog originalDialog=(Dialog)shareField(original,"dialog");View save=findText(originalDialog.getWindow().getDecorView(),"保存图片");
        assertNotNull(save);assertTrue(save.isEnabled());save.performClick();drainShare(original);
        org.robolectric.shadows.ShadowActivity.IntentForResult first=Shadows.shadowOf(controller.get()).getNextStartedActivityForResult();
        assertNotNull(first);assertEquals(ShareStudio.SAVE_REQUEST,first.requestCode);assertTrue(original.hasPendingSave());
        Bundle state=snapshot();assertTrue(state.getBundle("share_session").getBoolean("waiting_save"));
        controller.pause().stop().destroy();controller=Robolectric.buildActivity(MainActivity.class);
        CountDownLatch entered=new CountDownLatch(1),releaseLibrary=new CountDownLatch(1);
        ((ExecutorService)get("io")).execute(()->{entered.countDown();try{releaseLibrary.await();}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}});
        assertTrue(entered.await(5,TimeUnit.SECONDS));
        try {
            controller.create(state).start().resume().visible();
            assertFalse("Library IO is deliberately held to reproduce early Activity result",(Boolean)get("libraryReady"));
            ShareStudio receiver=(ShareStudio)get("shareStudio");assertNotNull(receiver);assertFalse(receiver.isVisible());assertTrue(receiver.hasPendingSave());
            assertNotNull(get("pendingPages"));
            call("onActivityResult",new Class<?>[]{int.class,int.class,Intent.class},ShareStudio.SAVE_REQUEST,Activity.RESULT_CANCELED,null);
            @SuppressWarnings("unchecked") ArrayList<Bundle> pending=(ArrayList<Bundle>)get("pendingPages");
            Bundle pendingShare=null;for(Bundle page:pending)if("share".equals(page.getString("type")))pendingShare=page.getBundle("share");
            assertNotNull(pendingShare);assertFalse("Deferred page must record that picker result was consumed",pendingShare.getBoolean("waiting_save"));
        } finally {releaseLibrary.countDown();}
        drain();ShareStudio restored=(ShareStudio)get("shareStudio");assertNotNull(restored);assertTrue(restored.isVisible());drainShare(restored);
        assertFalse(restored.hasPendingSave());assertNull("Restoring the page must not open a second system picker",Shadows.shadowOf(controller.get()).getNextStartedActivityForResult());
        Dialog restoredDialog=(Dialog)shareField(restored,"dialog");View saveAgain=findText(restoredDialog.getWindow().getDecorView(),"保存图片");
        assertNotNull(saveAgain);assertTrue("Save must be enabled after early cancellation and page restore",saveAgain.isEnabled());
        saveAgain.performClick();drainShare(restored);
        assertNotNull("A new explicit save must still launch its own picker",Shadows.shadowOf(controller.get()).getNextStartedActivityForResult());
        call("onActivityResult",new Class<?>[]{int.class,int.class,Intent.class},ShareStudio.SAVE_REQUEST,Activity.RESULT_CANCELED,null);
    }
    private Object shareField(ShareStudio studio,String key)throws Exception{Field f=ShareStudio.class.getDeclaredField(key);f.setAccessible(true);return f.get(studio);}
    private void drainShare(ShareStudio studio)throws Exception{((ExecutorService)shareField(studio,"worker")).submit(()->{}).get(20,TimeUnit.SECONDS);Shadows.shadowOf(Looper.getMainLooper()).idle();}
    private View findText(View view,String text){if(view instanceof android.widget.TextView&&text.contentEquals(((android.widget.TextView)view).getText()))return view;if(view instanceof android.view.ViewGroup){android.view.ViewGroup group=(android.view.ViewGroup)view;for(int i=0;i<group.getChildCount();i++){View found=findText(group.getChildAt(i),text);if(found!=null)return found;}}return null;}

}
