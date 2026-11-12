package com.recordroom.vinyl;

import android.app.Application;
import android.app.Dialog;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.media.MediaMetadata;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
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
import org.robolectric.annotation.LooperMode;
import static org.junit.Assert.*;

/** Actual Activity/page controls with a deterministic MediaSession boundary; no vendor login or audio claims. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=34, qualifiers="w412dp-h860dp-mdpi", fontScale=1f)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public class MainActivitySourceTest {
    private ActivityController<MainActivity> controller;
    private ServiceController<CountingService> serviceController;
    private MainActivity activity;
    private FakeAccess access;
    private FakeSession session;
    private ExternalMusicBridge bridge;

    /** Keeps the real service binding/queue, recording routed controls without pretending to play audio. */
    public static class CountingService extends PlayerService {
        int pauseCalls, toggleCalls, nextCalls, previousCalls, seekCalls;
        @Override public void pausePlayback() { pauseCalls++; super.pausePlayback(); }
        @Override public void toggle() { toggleCalls++; }
        @Override public void next() { nextCalls++; }
        @Override public void previous() { previousCalls++; }
        @Override public void seekTo(long position) { seekCalls++; }
    }

    @Before public void setup() throws Exception {
        Application app=RuntimeEnvironment.getApplication();
        app.getSharedPreferences(PlayerService.PREFS,0).edit().clear().commit();
        app.getSharedPreferences("MainActivity",0).edit().clear().commit();
        new ThemeStore(app).needleSound(false);
        serviceController=Robolectric.buildService(CountingService.class).create();
        Shadows.shadowOf(app).setComponentNameAndServiceForBindService(new ComponentName(app,PlayerService.class),
                service().onBind(new Intent(app,PlayerService.class)));
        controller=Robolectric.buildActivity(MainActivity.class).setup().visible();activity=controller.get();drain();
        ((ExternalMusicBridge)main("externalMusic")).close();
        access=new FakeAccess();session=new FakeSession();access.sessions.add(session);
        bridge=new ExternalMusicBridge(access,new Handler(Looper.getMainLooper()),state->{
            try { call("refresh"); } catch(Exception error) { throw new AssertionError(error); }
        });
        set(activity,"externalMusic",bridge);bridge.start();
        layout(activity.getWindow().getDecorView(),412,860);
    }
    @After public void cleanup() throws Exception {
        if(controller!=null)controller.pause().stop().destroy();
        if(serviceController!=null)serviceController.destroy();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }
    private CountingService service(){return serviceController.get();}

    @Test public void actualSourceButtonPausesInternalAndRoutesMetadataAndTransportToSelectedSession()throws Exception{
        int pauses=service().pauseCalls;List<Track> internalQueue=new ArrayList<>(service().getQueue());
        chooseQqThroughPage();drain();
        assertEquals(pauses+1,service().pauseCalls);
        assertEquals("qq_music",activity.getPreferences(0).getString("external_music_source",""));
        assertEquals("会话中的歌曲",((TextView)main("title")).getText().toString());
        assertEquals("会话中的音乐人",((TextView)main("artist")).getText().toString());
        assertSame(session.artwork,main("currentArtwork"));
        ((View)main("play")).performClick();((View)main("previous")).performClick();((View)main("next")).performClick();
        call("seekPlayback",new Class<?>[]{long.class},30000L);
        assertEquals(Arrays.asList("pause","previous","next","seek:30000"),session.commands);
        assertEquals(0,service().toggleCalls);assertEquals(0,service().nextCalls);
        assertEquals(0,service().previousCalls);assertEquals(0,service().seekCalls);
        assertEquals(internalQueue,service().getQueue());
    }

    @Test public void lyricNotificationsWithCopiedCoverDoNotLiftNeedleOrReplaceRecord()throws Exception{
        chooseQqThroughPage();drain();
        TurntableView view=(TurntableView)main("turntable");
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(3,TimeUnit.SECONDS);
        Bitmap original=(Bitmap)main("currentArtwork");
        for(int line=0;line<8;line++){
            session.artwork=original.copy(Bitmap.Config.ARGB_8888,false);
            session.lyric="第"+line+"句滚动歌词";
            session.changed.run();
            assertSame("Same song's metadata refresh must keep the record artwork",original,main("currentArtwork"));
            assertEquals("Lyric refresh must not raise and lower the tonearm",false,field(view,"artworkChanging"));
        }
    }

    @Test public void sameSongArtworkRefreshAndBufferingPreserveStylusButExplicitPauseRaisesIt()throws Exception{
        chooseQqThroughPage();drain();Shadows.shadowOf(Looper.getMainLooper()).idleFor(3,TimeUnit.SECONDS);
        TurntableView view=(TurntableView)main("turntable");
        session.artwork=Bitmap.createBitmap(32,32,Bitmap.Config.ARGB_8888);session.artwork.eraseColor(0xffca594b);
        session.songTitle="平台更新的显示文字";session.changed.run();
        assertEquals(false,field(view,"artworkChanging"));assertSame(session.artwork,main("currentArtwork"));
        assertEquals("Same stable song ID keeps colour updates separate from record changes",true,field(view,"playing"));
        session.playbackState=PlaybackState.STATE_BUFFERING;session.changed.run();assertEquals(true,field(view,"playing"));
        session.playbackState=PlaybackState.STATE_PLAYING;session.changed.run();assertEquals(true,field(view,"playing"));
        session.playbackState=PlaybackState.STATE_PAUSED;session.changed.run();assertEquals(false,field(view,"playing"));
        call("returnToPlayer");call("refresh");
        view.setMotionEnabled(true);view.setActive(true);
        assertTrue("Player must be visible for the replacement animation",view.isShown());
        session.playbackState=PlaybackState.STATE_PLAYING;session.songId="genuinely-new-song";
        session.artwork=Bitmap.createBitmap(32,32,Bitmap.Config.ARGB_8888);session.artwork.eraseColor(0xff46c27d);session.changed.run();
        assertEquals("A different pressing image on the same album updates without lifting",false,field(view,"artworkChanging"));
        session.albumTitle="下一张唱片";session.songId="other-album-song";
        session.artwork=Bitmap.createBitmap(32,32,Bitmap.Config.ARGB_8888);session.artwork.eraseColor(0xff94ad04);session.changed.run();
        assertEquals("Changing album and sleeve still animates the replacement",true,field(view,"artworkChanging"));
    }

    @Test public void rebuildingLayoutRetainsCenterCoverAndNewBufferingSourceDoesNotInheritOldSpin()throws Exception{
        chooseQqThroughPage();drain();Bitmap art=(Bitmap)main("currentArtwork");
        call("buildMain");call("refresh");assertSame(art,field(main("turntable"),"artwork"));
        FakeSession other=new FakeSession();other.pkg="com.luna.music";other.playbackState=PlaybackState.STATE_BUFFERING;access.sessions.add(other);
        call("selectExternalSource",new Class<?>[]{String.class},"qishui_music");
        assertEquals("No playback yet in this different source",false,field(main("turntable"),"playing"));
    }

    @Test public void withdrawnArtworkUriCannotOverwriteCurrentCoverAfterDecodeFinishes()throws Exception{
        chooseQqThroughPage();drain();Bitmap original=(Bitmap)main("currentArtwork");
        Bitmap cover=Bitmap.createBitmap(24,24,Bitmap.Config.ARGB_8888);cover.eraseColor(0xffcc5522);
        File file=new File(activity.getCacheDir(),"external-delayed.png");try(FileOutputStream out=new FileOutputStream(file)){cover.compress(Bitmap.CompressFormat.PNG,100,out);}
        java.util.concurrent.CountDownLatch entered=new java.util.concurrent.CountDownLatch(1),release=new java.util.concurrent.CountDownLatch(1);
        ((ExecutorService)main("io")).submit(()->{entered.countDown();try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException failure){Thread.currentThread().interrupt();}});assertTrue(entered.await(5,TimeUnit.SECONDS));
        try{session.artwork=null;session.artUri=android.net.Uri.fromFile(file).toString();session.changed.run();session.artUri="";session.changed.run();}
        finally{release.countDown();}
        drain();assertSame("Withdrawn URI is not current metadata",original,main("currentArtwork"));
    }

    @Test public void missingSeekDisablesActualControlsAndLocalButtonRestoresNativeRouting()throws Exception{
        session.actions=PlaybackState.ACTION_PLAY|PlaybackState.ACTION_PAUSE;
        chooseQqThroughPage();
        assertFalse(((View)main("seek")).isEnabled());assertFalse(((View)main("previous")).isEnabled());assertFalse(((View)main("next")).isEnabled());
        TurntableView view=(TurntableView)main("turntable");assertEquals(false,field(view,"needleSeekingEnabled"));
        float px=number(view,"pivotX"),py=number(view,"pivotY"),length=number(view,"radius")*.98f;
        long time=SystemClock.uptimeMillis();float x=px-(float)Math.sin(Math.toRadians(23))*length,y=py+(float)Math.cos(Math.toRadians(23))*length;
        touch(view,time,0,px,py+length);touch(view,time,2,x,y);touch(view,time,1,x,y);
        assertTrue(session.commands.isEmpty());
        Dialog dialog=(Dialog)main("sourcesDialog");findText(dialog.getWindow().getDecorView(),"切回资料库").performClick();
        assertEquals("",bridge.getSelectedProviderId());assertFalse(activity.getPreferences(0).contains("external_music_source"));
        assertEquals(Arrays.asList("pause"),session.commands);
        assertTrue(((View)main("seek")).isEnabled());assertTrue(((View)main("play")).isEnabled());assertEquals(true,field(view,"needleSeekingEnabled"));
        ((View)main("play")).performClick();assertEquals(1,service().toggleCalls);
        assertEquals("Local playback never sends another external command",1,session.commands.size());
    }

    @Test public void returningFromQueueKeepsTheDeckTimelineAttachedAndAccessible()throws Exception{
        PlaybackTimeline deck=(PlaybackTimeline)main("seek");
        assertSame(activity.getWindow().getDecorView(),deck.slider.getRootView());
        call("showQueue");drain();Dialog dialog=(Dialog)main("queueDialog");
        PlaybackTimeline queue=(PlaybackTimeline)main("queueTimeline");
        assertNotSame(deck.slider,queue.slider);
        assertSame(dialog.getWindow().getDecorView(),queue.slider.getRootView());
        dialog.dismiss();drain();
        assertSame(activity.getWindow().getDecorView(),deck.slider.getRootView());
        assertNull(main("queueTimeline"));
        android.os.Bundle target=new android.os.Bundle();
        target.putFloat(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE,500f);
        int count=service().seekCalls;
        assertTrue(deck.slider.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId(),target));
        assertEquals("TalkBack seek reaches the owned service without a touch gesture",count+1,service().seekCalls);
    }

    @Test public void revocationAndActivityPauseResumeClearExternalDataAndBalanceObservers()throws Exception{
        chooseQqThroughPage();assertNotNull(main("currentArtwork"));
        access.permission=false;access.changed.run();
        assertFalse(((View)main("play")).isEnabled());assertFalse(((View)main("seek")).isEnabled());
        assertNull(main("currentArtwork"));assertEquals("QQ音乐",((TextView)main("title")).getText().toString());
        assertTrue(((TextView)main("status")).getText().toString().contains("通知使用权"));
        Runnable stale=session.changed;controller.pause();int removes=session.unsubscribes;
        stale.run();assertEquals(removes,session.unsubscribes);assertEquals(1,access.stops);
        controller.resume();assertEquals(2,access.starts);
        assertEquals("qq_music",bridge.getSelectedProviderId());assertEquals(ExternalMusicBridge.Status.PERMISSION_REQUIRED,bridge.getState().status);
    }

    @Test public void sourcePageSeparatesDirectEntriesAndUnconfiguredAppleFromOptionalUnauthorizedLinkAndRenders()throws Exception{
        access.permission=false;access.installed=false;access.sessions.clear();bridge.refresh();call("showMusicSources");
        MusicSourcesPage page=(MusicSourcesPage)main("sourcesPage");Dialog dialog=(Dialog)main("sourcesDialog");
        View decor=dialog.getWindow().getDecorView();layout(decor,412,860);Shadows.shadowOf(Looper.getMainLooper()).idle();layout(decor,412,860);
        assertNotNull(findText(page,"账号与在线曲库"));
        assertNotNull(findText(page,"QQ 音乐 · 账号与曲库"));assertNull(findText(page,"汽水音乐 · 账号与曲库"));
        assertNotNull(findText(page,"Apple Music · 账号与曲库"));
        assertNotNull(findText(page,"可选 · 官方 App 联动"));assertNotNull(findText(page,"先开启播放联动权限"));
        @SuppressWarnings("unchecked") Map<String,Button> choose=(Map<String,Button>)field(page,"choose");
        for(Button button:choose.values())assertFalse(button.isEnabled());
        assertNotNull(findText(page,"未检测到QQ音乐，请先安装官方 App"));assertFalse(bridge.getState().connected);
        save(decor,"sources-not-configured-api34.png");
        ScrollView scroll=(ScrollView)page.getChildAt(0);save(scroll.getChildAt(0),"sources-not-configured-full-native.png");
    }

    @Test public void directAccountEntriesOpenNativeUnauthenticatedPagesWithoutClaimingLogin()throws Exception{
        call("showMusicSources");MusicSourcesPage sources=(MusicSourcesPage)main("sourcesPage");
        String[] ids={"qq","applemusic"},labels={"QQ 音乐 · 账号与曲库","Apple Music · 账号与曲库"};
        for(int i=0;i<ids.length;i++){
            View entry=findText(sources,labels[i]);assertNotNull(entry);assertTrue(entry.isEnabled());entry.performClick();
            @SuppressWarnings("unchecked") Map<String,ProviderLibraryPage> pages=(Map<String,ProviderLibraryPage>)main("providerPages");
            ProviderLibraryPage page=pages.get(ids[i]);assertNotNull("Direct entry opens a native provider page",page);
            for(int pass=0;pass<3;pass++){((ExecutorService)field(page,"worker")).submit(()->{}).get(10,TimeUnit.SECONDS);Shadows.shadowOf(Looper.getMainLooper()).idle();}
            MusicProvider.Account account=(MusicProvider.Account)field(page,"account");assertNotNull(account);assertFalse("Opening an entry is not successful account authorization",account.loggedIn);
            assertTrue(((Dialog)field(page,"dialog")).isShowing());page.close();assertFalse(pages.containsKey(ids[i]));
        }
    }

    private void chooseQqThroughPage()throws Exception{
        call("showMusicSources");MusicSourcesPage page=(MusicSourcesPage)main("sourcesPage");
        @SuppressWarnings("unchecked") Map<String,Button> choose=(Map<String,Button>)field(page,"choose");
        assertTrue(choose.get("qq_music").isEnabled());choose.get("qq_music").performClick();
    }
    private void drain()throws Exception{
        ((ExecutorService)main("io")).submit(()->{}).get(10,TimeUnit.SECONDS);Shadows.shadowOf(Looper.getMainLooper()).idle();
    }
    private Object main(String key)throws Exception{return field(activity,key);}
    private static Object field(Object target,String key)throws Exception{Field f=target.getClass().getDeclaredField(key);f.setAccessible(true);return f.get(target);}
    private static void set(Object target,String key,Object value)throws Exception{Field f=target.getClass().getDeclaredField(key);f.setAccessible(true);f.set(target,value);}
    private static float number(Object target,String key)throws Exception{return ((Number)field(target,key)).floatValue();}
    private void call(String name)throws Exception{call(name,new Class<?>[0]);}
    private void call(String name,Class<?>[] types,Object...args)throws Exception{Method m=MainActivity.class.getDeclaredMethod(name,types);m.setAccessible(true);m.invoke(activity,args);}
    private static View findText(View view,String text){if(view instanceof TextView&&text.contentEquals(((TextView)view).getText()))return view;if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){View found=findText(group.getChildAt(i),text);if(found!=null)return found;}}return null;}
    private static void layout(View view,int w,int h){view.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));view.layout(0,0,w,h);}
    private static void touch(View view,long time,int action,float x,float y){MotionEvent event=MotionEvent.obtain(time,time+(action==2?16:action==1?32:0),action,x,y,0);try{view.dispatchTouchEvent(event);}finally{event.recycle();}}
    private static void save(View view,String name)throws Exception{
        Bitmap bitmap=Bitmap.createBitmap(view.getWidth(),view.getHeight(),Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(bitmap);
        // The full scroll-content export needs the same native paper behind its transparent body.
        android.graphics.drawable.Drawable paper=DesignSystem.paper(view.getContext());paper.setBounds(0,0,bitmap.getWidth(),bitmap.getHeight());paper.draw(canvas);view.draw(canvas);
        File file=new File(System.getProperty("vinyl.output.dir"),name);file.getParentFile().mkdirs();try(FileOutputStream out=new FileOutputStream(file)){bitmap.compress(Bitmap.CompressFormat.PNG,100,out);}bitmap.recycle();
    }
    private static final class FakeAccess implements ExternalMusicBridge.Access{
        boolean permission=true,installed=true;int starts,stops;Runnable changed;
        List<ExternalMusicBridge.Session> sessions=new ArrayList<>();
        public boolean hasAccess(){return permission;}public boolean isConnected(){return true;}public boolean isInstalled(String name){return installed;}
        public void start(Runnable callback){starts++;changed=callback;}public void stop(){stops++;changed=null;}public void stopSessionWatch(){}
        public List<ExternalMusicBridge.Session> activeSessions(){return sessions;}
        public Intent launchIntent(String name){return installed?new Intent(Intent.ACTION_MAIN).setPackage(name):null;}
    }
    private static final class FakeSession implements ExternalMusicBridge.Session{
        Bitmap artwork=Bitmap.createBitmap(24,24,Bitmap.Config.ARGB_8888); String lyric="",songId="stable-song-123",songTitle="会话中的歌曲",albumTitle="会话中的专辑",artUri="",pkg="com.tencent.qqmusic"; int playbackState=PlaybackState.STATE_PLAYING;
        final List<String> commands=new ArrayList<>();Runnable changed,destroyed;int unsubscribes;
        long actions=PlaybackState.ACTION_PLAY|PlaybackState.ACTION_PAUSE|PlaybackState.ACTION_SKIP_TO_PREVIOUS|PlaybackState.ACTION_SKIP_TO_NEXT|PlaybackState.ACTION_SEEK_TO;
        FakeSession(){artwork.eraseColor(0xff33678f);}
        public String packageName(){return pkg;}public Object token(){return "activity-test-session-"+pkg;}
        public MediaMetadata metadata(){return new MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_MEDIA_ID,songId).putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE,lyric).putString(MediaMetadata.METADATA_KEY_TITLE,songTitle).putString(MediaMetadata.METADATA_KEY_ARTIST,"会话中的音乐人").putString(MediaMetadata.METADATA_KEY_ALBUM,albumTitle).putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART,artwork).putString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI,artUri).putLong(MediaMetadata.METADATA_KEY_DURATION,120000).build();}
        public PlaybackState playback(){return new PlaybackState.Builder().setState(playbackState,10000,1,0).setActions(actions).build();}
        public void subscribe(Runnable change,Runnable destroy){changed=change;destroyed=destroy;}public void unsubscribe(){unsubscribes++;}
        public void play(){commands.add("play");}public void pause(){commands.add("pause");}public void previous(){commands.add("previous");}public void next(){commands.add("next");}public void seekTo(long position){commands.add("seek:"+position);}public boolean toggle(){commands.add("toggle");return true;}
    }
}
