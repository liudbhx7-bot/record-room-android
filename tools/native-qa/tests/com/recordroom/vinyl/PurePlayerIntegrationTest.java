package com.recordroom.vinyl;

import android.app.Dialog;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Color;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
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

/** Verifies the pure-player UI against real bundled covers and the owned playback service. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=34,qualifiers="w412dp-h860dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class PurePlayerIntegrationTest {
    private ServiceController<PlayerService> service;
    private ActivityController<MainActivity> controller;
    @Before public void setup()throws Exception{
        android.app.Application app=RuntimeEnvironment.getApplication();
        app.getSharedPreferences("vinyl_appearance",0).edit().clear().commit();app.getSharedPreferences(PlayerService.PREFS,0).edit().clear().commit();OnlineCatalog.clear(app);
        service=Robolectric.buildService(PlayerService.class).create();
        Shadows.shadowOf(app).setComponentNameAndServiceForBindService(new ComponentName(app,PlayerService.class),service.get().onBind(new Intent(app,PlayerService.class)));
        controller=Robolectric.buildActivity(MainActivity.class).setup().visible();drain();
    }
    @After public void cleanup(){if(controller!=null)controller.pause().stop().destroy();if(service!=null)service.destroy();Shadows.shadowOf(Looper.getMainLooper()).idle();}
    private Object field(Object target,String name)throws Exception{Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(target);}
    private Object main(String name)throws Exception{return field(controller.get(),name);}
    private void call(String name)throws Exception{Method method=MainActivity.class.getDeclaredMethod(name);method.setAccessible(true);method.invoke(controller.get());}
    private void drain()throws Exception{for(int i=0;i<3;i++){((ExecutorService)main("io")).submit(()->{}).get(10,TimeUnit.SECONDS);Shadows.shadowOf(Looper.getMainLooper()).idle();}}
    private void noLyrics(View view){
        if(view instanceof TextView)assertFalse(((TextView)view).getText().toString().contains("歌词"));
        if(view.getContentDescription()!=null)assertFalse(view.getContentDescription().toString().contains("歌词"));
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)noLyrics(((ViewGroup)view).getChildAt(i));
    }
    @Test public void actualCoverChangesBothPlayerSurfaceAndDiscWhilePauseAndQueueArePreserved()throws Exception{
        List<Track> tracks=DemoCatalog.load(controller.get());Track first=tracks.get(0),second=null;
        for(Track track:tracks)if(!track.artworkUri.equals(first.artworkUri)){second=track;break;}assertNotNull(second);
        service.get().setQueue(new ArrayList<>(tracks),0,false);call("refresh");drain();
        ThemeStore themes=(ThemeStore)main("themes");assertTrue(themes.followCoverColors());
        int firstBackground=(Integer)main("playerSurfaceColor"),firstTint=themes.recordTint(first.albumKey());assertNotEquals(Color.TRANSPARENT,firstTint);
        AlbumPalette expected=AlbumPalette.from((android.graphics.Bitmap)main("currentArtwork"));assertEquals(DesignSystem.deckColor(themes,first.albumKey()),firstBackground);assertTrue("Automatic graphite deck stays dark",Color.luminance(firstBackground)<.06);assertEquals(expected.record,firstTint);
        int index=tracks.indexOf(second);service.get().setQueue(new ArrayList<>(tracks),index,false);call("refresh");drain();
        assertNotEquals(firstBackground,(int)(Integer)main("playerSurfaceColor"));assertNotEquals(firstTint,themes.recordTint(second.albumKey()));
        assertFalse(service.get().isPlaying());assertEquals(tracks.size(),service.get().getQueueSize());assertEquals(second.id,service.get().getCurrent().id);
        ThemeStore cold=new ThemeStore(controller.get());assertEquals(themes.background(second.albumKey()),cold.background(second.albumKey()));assertEquals(themes.recordTint(second.albumKey()),cold.recordTint(second.albumKey()));
    }
    @Test public void manualChoiceIsRetainedAndNewPurePlayerHasNoLyricsEntryOrController()throws Exception{
        ThemeStore themes=(ThemeStore)main("themes");themes.save("",false,1,0xffe1d9cf);themes.followCoverColors(false);
        List<Track> tracks=DemoCatalog.load(controller.get());service.get().setQueue(new ArrayList<>(tracks),0,false);call("applyAppearance");call("refresh");drain();
        assertEquals(0xffe1d9cf,(int)(Integer)main("playerSurfaceColor"));assertEquals(0,themes.recordTint(tracks.get(0).albumKey()));
        noLyrics(controller.get().getWindow().getDecorView());call("showSettings");noLyrics(((Dialog)main("settingsDialog")).getWindow().getDecorView());
        for(Field f:MainActivity.class.getDeclaredFields())assertFalse(f.getType().getSimpleName().contains("Lyrics"));
        assertThrows(ClassNotFoundException.class,()->Class.forName("com.recordroom.vinyl.LyricsController"));
    }
}
