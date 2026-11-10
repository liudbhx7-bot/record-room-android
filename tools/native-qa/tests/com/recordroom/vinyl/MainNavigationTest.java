package com.recordroom.vinyl;

import android.app.Dialog;
import android.content.ComponentName;
import android.content.Intent;
import org.junit.Before;
import org.junit.After;
import org.robolectric.android.controller.ServiceController;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.android.controller.ActivityController;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=34, qualifiers="w512dp-h726dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class MainNavigationTest {
    private ServiceController<PlayerService> serviceController;
    @Before public void bindRealPlayer() {
        serviceController=Robolectric.buildService(PlayerService.class).create();
        android.app.Application app=RuntimeEnvironment.getApplication();
        Shadows.shadowOf(app).setComponentNameAndServiceForBindService(new ComponentName(app,PlayerService.class),serviceController.get().onBind(new Intent(app,PlayerService.class)));
    }
    @After public void destroyPlayer() { if(serviceController!=null)serviceController.destroy(); }

    private Object get(Object o,String name)throws Exception { Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o); }
    private void call(Object o,String name,Class<?>[] types,Object... args)throws Exception {Method m=o.getClass().getDeclaredMethod(name,types);m.setAccessible(true);m.invoke(o,args);}
    private void drain(MainActivity a)throws Exception {((ExecutorService)get(a,"io")).submit(()->{}).get(10,TimeUnit.SECONDS);Shadows.shadowOf(Looper.getMainLooper()).idle();}
    private void layout(Dialog dialog){View decor=dialog.getWindow().getDecorView();for(int i=0;i<2;i++){decor.measure(View.MeasureSpec.makeMeasureSpec(512,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(726,View.MeasureSpec.EXACTLY));decor.layout(0,0,512,726);Shadows.shadowOf(Looper.getMainLooper()).idle();}}
    private View find(View v,String description){if(description.contentEquals(v.getContentDescription()==null?"":v.getContentDescription()))return v;if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++){View hit=find(g.getChildAt(i),description);if(hit!=null)return hit;}}return null;}

    @Test public void rotationPreservesShelfQueryAndNestedBackStack()throws Exception {
        ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup().visible();
        MainActivity a=c.get();
        try {
            drain(a);call(a,"showShelf",new Class<?>[0]);
            EditText search=(EditText)get(a,"shelfSearch");search.setVisibility(View.VISIBLE);search.setText("夜");
            ArrayList<Track> songs=new ArrayList<>(DemoCatalog.load(a));
            call(a,"showAlbum",new Class<?>[]{ArrayList.class,String.class},songs,"我的收藏");
            call(a,"showQueue",new Class<?>[0]);
            Dialog oldQueue=(Dialog)get(a,"queueDialog");
            RuntimeEnvironment.setQualifiers("w726dp-h512dp-land-mdpi");a.onConfigurationChanged(a.getResources().getConfiguration());
            drain(a);
            assertFalse(oldQueue.isShowing());
            assertEquals("夜",((EditText)get(a,"shelfSearch")).getText().toString());
            assertEquals(View.VISIBLE,((View)get(a,"shelfSearch")).getVisibility());
            assertEquals(3,((Number)get(a,"fullPages")).intValue());
            Dialog queue=(Dialog)get(a,"queueDialog");assertTrue(queue.isShowing());queue.onBackPressed();drain(a);
            assertTrue(((Dialog)get(a,"albumDialog")).isShowing());
            assertEquals(2,((Number)get(a,"fullPages")).intValue());
            ((Dialog)get(a,"albumDialog")).onBackPressed();Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(400));drain(a);
            assertTrue(((Dialog)get(a,"shelfDialog")).isShowing());
            assertEquals(1,((Number)get(a,"fullPages")).intValue());
        } finally {c.pause().stop().destroy();}
    }

    @Test public void albumReturnWaitsForAnimationAndIgnoresDuplicateBack()throws Exception {
        ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup().visible();MainActivity a=c.get();
        try {
            drain(a);call(a,"showShelf",new Class<?>[0]);Dialog shelf=(Dialog)get(a,"shelfDialog");
            call(a,"showAlbum",new Class<?>[]{ArrayList.class},new ArrayList<>(DemoCatalog.load(a)));drain(a);
            Dialog album=(Dialog)get(a,"albumDialog");View page=album.getWindow().getDecorView();
            page.measure(View.MeasureSpec.makeMeasureSpec(512,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(726,View.MeasureSpec.EXACTLY));page.layout(0,0,512,726);
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(600));
            album.onBackPressed();album.onBackPressed();assertTrue("Return has a visible transition",album.isShowing());
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(400));drain(a);
            assertFalse(album.isShowing());assertTrue(shelf.isShowing());assertEquals(1,((Number)get(a,"fullPages")).intValue());
        } finally {c.pause().stop().destroy();}
    }

    @Test public void rotationKeepsRestoredAppearanceRegisteredAfterOldDismissCallbacks()throws Exception {
        ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup().visible();
        MainActivity a=c.get();
        try {
            drain(a);call(a,"showAppearance",new Class<?>[0]);drain(a);
            Dialog previous=(Dialog)get(a,"appearanceDialog");
            assertTrue(previous.isShowing());
            assertEquals(View.INVISIBLE,((View)get(a,"details")).getVisibility());
            RuntimeEnvironment.setQualifiers("w726dp-h512dp-land-mdpi");
            a.onConfigurationChanged(a.getResources().getConfiguration());
            // Old dismiss messages must not clear fields belonging to the replacement.
            drain(a);
            Dialog restored=(Dialog)get(a,"appearanceDialog");
            assertNotNull("Old dismissal must not clear the replacement dialog",restored);
            assertNotSame(previous,restored);
            assertFalse(previous.isShowing());assertTrue(restored.isShowing());
            assertTrue(((ArrayList<?>)get(a,"dialogs")).contains(restored));
            assertTrue(((java.util.Map<?,?>)get(a,"pageRestorers")).containsKey(restored));
            assertEquals(View.INVISIBLE,((View)get(a,"details")).getVisibility());
            assertFalse((Boolean)get(a,"chromeVisible"));
            restored.onBackPressed();drain(a);
            assertNull(get(a,"appearanceDialog"));
            assertEquals(View.VISIBLE,((View)get(a,"details")).getVisibility());
            assertTrue((Boolean)get(a,"chromeVisible"));
        } finally {c.pause().stop().destroy();}
    }

    @Test public void searchDebouncesAndCanSelectLastOfTwelveHundredSongs()throws Exception {
        ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup().visible();MainActivity a=c.get();
        try {
            drain(a);PlayerService service=serviceController.get();
            @SuppressWarnings("unchecked") ArrayList<Track> library=(ArrayList<Track>)get(a,"library");library.clear();
            for(int i=0;i<1200;i++)library.add(new Track("test"+i,"检索歌曲"+i,"测试音乐人","同一专辑","测试","asset:///demo/night-1.wav","",18000,i));
            call(a,"showShelf",new Class<?>[0]);EditText input=(EditText)get(a,"shelfSearch");input.setText("不应执行");input.setText("检索");input.setText("检索歌曲");
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(150));assertEquals("",get(a,"searchQuery"));
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100));assertEquals("检索歌曲",get(a,"searchQuery"));
            Dialog shelf=(Dialog)get(a,"shelfDialog");layout(shelf);
            LibraryBrowserList list=(LibraryBrowserList)get(a,"shelfResults");list.setSelection(list.getCount()-1);
            layout(shelf);assertEquals(list.getCount()-1,list.getLastVisiblePosition());assertTrue(list.getChildCount()<20);
            View last=find(list,"检索歌曲1199，测试音乐人");assertNotNull(last);last.performClick();
            assertEquals(0,((ArrayList<?>)get(a,"dialogs")).size());assertEquals("test1199",service.getCurrent().id);assertEquals(1200,service.getQueue().size());
        } finally {c.pause().stop().destroy();}
    }

    @Test public void coralUsesDarkTextAndClearingCoverInvalidatesPendingLoad()throws Exception {
        assertEquals(DesignSystem.INK,MainActivity.readableInk(0xffee9996));
        assertEquals(android.graphics.Color.WHITE,MainActivity.readableInk(0xff19141e));
        CoverView cover=new CoverView(RuntimeEnvironment.getApplication());
        cover.setSource("asset:///demo/night-v2.png",120);cover.clear();
        CoverView.IO.submit(()->{}).get(10,TimeUnit.SECONDS);Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertNull(cover.getDrawable());
    }
}
