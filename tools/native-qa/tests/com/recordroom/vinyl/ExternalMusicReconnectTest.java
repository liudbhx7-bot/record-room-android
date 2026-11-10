package com.recordroom.vinyl;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Looper;
import android.provider.Settings;
import java.util.ArrayList;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.shadows.ShadowNotificationListenerService;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;
import org.robolectric.shadows.ShadowMediaSessionManager;
/** Official Android APIs with framework shadows; no device/OEM connection is claimed. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=34) @LooperMode(LooperMode.Mode.PAUSED)
public class ExternalMusicReconnectTest {
    Context context;ExternalMusicBridge bridge;NotificationManager notifications;MediaSessionManager manager;ComponentName component;MediaSession session;
    @Before public void setup(){context=RuntimeEnvironment.getApplication();notifications=(NotificationManager)context.getSystemService(Context.NOTIFICATION_SERVICE);manager=(MediaSessionManager)context.getSystemService(Context.MEDIA_SESSION_SERVICE);component=new ComponentName(context,ExternalMusicNotificationListener.class);ReflectionHelpers.setStaticField(ExternalMusicNotificationListener.class,"connected",false);Shadows.shadowOf(manager).clearControllers();ShadowNotificationListenerService.reset();DelayedSessions.allowed=false;DelayedSessions.queries=0;install("com.luna.music");bridge=new ExternalMusicBridge(context,state->{});bridge.selectProvider("qishui_music");}
    @After public void close(){bridge.close();if(session!=null)session.release();}
    void install(String name){PackageInfo info=new PackageInfo();info.packageName=name;info.applicationInfo=new ApplicationInfo();info.applicationInfo.packageName=name;info.applicationInfo.enabled=true;Shadows.shadowOf(context.getPackageManager()).installPackage(info);}
    void grant(boolean value){Shadows.shadowOf(notifications).setNotificationListenerAccessGranted(component,value);context.getContentResolver().notifyChange(Settings.Secure.getUriFor("enabled_notification_listeners"),null);Shadows.shadowOf(Looper.getMainLooper()).idle();}
    void addSession(String packageName){session=new MediaSession(context,"explicit-fixture");MediaController controller=new MediaController(context,session.getSessionToken());Shadows.shadowOf(controller).setPackageName(packageName);Shadows.shadowOf(controller).setMetadata(new MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE,"Official app track").putLong(MediaMetadata.METADATA_KEY_DURATION,180000).build());Shadows.shadowOf(controller).setPlaybackState(new PlaybackState.Builder().setState(PlaybackState.STATE_PLAYING,5000,1).setActions(PlaybackState.ACTION_PAUSE).build());Shadows.shadowOf(manager).addController(controller);}
    @Test public void authorizedSystemSessionConnectsAfterProcessRestartWithoutListenerCallback(){
        grant(true);addSession("com.luna.music");assertFalse(ExternalMusicNotificationListener.isConnected());bridge.start();assertEquals(ExternalMusicBridge.Status.CONNECTED,bridge.getState().status);assertEquals("Official app track",bridge.getState().title);assertTrue(bridge.getState().playing);
    }
    @Test public void permissionGrantedWhileForegroundRequestsRebindAndDiscoversSession(){
        grant(false);bridge.start();assertEquals(ExternalMusicBridge.Status.PERMISSION_REQUIRED,bridge.getState().status);addSession("com.luna.music");int before=ShadowNotificationListenerService.getRebindRequestCount();grant(true);assertTrue("Grant callback actively asks Android to bind the listener",ShadowNotificationListenerService.getRebindRequestCount()>before);assertTrue(bridge.getState().connected);
        grant(false);assertEquals(ExternalMusicBridge.Status.PERMISSION_REQUIRED,bridge.getState().status);assertNull(bridge.getState().artwork);assertEquals("",bridge.getState().title);assertFalse(bridge.pause());
    }
    @Test public void authorizedSessionQueryWithNoPlaybackReportsNoSessionInsteadOfWaitingForever(){grant(true);bridge.start();assertEquals(ExternalMusicBridge.Status.NO_ACTIVE_SESSION,bridge.getState().status);assertFalse(bridge.getState().connected);}
    @Test public void lookalikePackageCannotMasqueradeAsQishuiSession(){grant(true);addSession("com.luna.music.untrusted");bridge.start();assertEquals(ExternalMusicBridge.Status.NO_ACTIVE_SESSION,bridge.getState().status);assertEquals("",bridge.getState().title);}
    @Test public void oldListenerDestructionCannotClearTheNewLiveConnection(){
        ExternalMusicNotificationListener old=Robolectric.buildService(ExternalMusicNotificationListener.class).create().get(),fresh=Robolectric.buildService(ExternalMusicNotificationListener.class).create().get();old.onListenerConnected();fresh.onListenerConnected();old.onDestroy();assertTrue("A replacement listener owns the live connection",ExternalMusicNotificationListener.isConnected());fresh.onDestroy();assertFalse(ExternalMusicNotificationListener.isConnected());
    }
    @Test @Config(shadows=DelayedSessions.class) public void temporarilyDeniedSystemQueryRetriesWhileForegroundAndStopsAfterPause(){
        grant(true);addSession("com.luna.music");bridge.start();assertEquals(ExternalMusicBridge.Status.CONNECTING,bridge.getState().status);assertTrue(ShadowNotificationListenerService.getRebindRequestCount()>0);int before=DelayedSessions.queries;DelayedSessions.allowed=true;Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(3));assertTrue(DelayedSessions.queries>before);assertTrue(bridge.getState().connected);
        bridge.stop();int queries=DelayedSessions.queries,rebinds=ShadowNotificationListenerService.getRebindRequestCount();Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(40));assertEquals(queries,DelayedSessions.queries);assertEquals(rebinds,ShadowNotificationListenerService.getRebindRequestCount());
    }
    @Test @Config(shadows=FirstQueryDenied.class) public void successfulSnapshotProbeCannotCancelActualSessionRecovery(){
        FirstQueryDenied.queries=0;grant(true);addSession("com.luna.music");bridge.start();
        assertTrue("The initial denied query is followed by a successful status probe",FirstQueryDenied.queries>=2);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(3));
        assertEquals("Recovery must bind the real session, not stop after the status probe",ExternalMusicBridge.Status.CONNECTED,bridge.getState().status);
        assertEquals("Official app track",bridge.getState().title);
        bridge.stop();int queries=FirstQueryDenied.queries;
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(40));
        assertEquals("Paused bridges must not retain the recovery task",queries,FirstQueryDenied.queries);
    }
    @Implements(MediaSessionManager.class) public static class FirstQueryDenied extends ShadowMediaSessionManager {
        static int queries;
        @Implementation @Override protected java.util.List<MediaController> getActiveSessions(ComponentName component){
            if(++queries==1)throw new SecurityException("Fixture: permission becomes effective between refresh and its status snapshot");
            return super.getActiveSessions(component);
        }
    }
    @Test @Config(shadows=DelayedSessions.class) public void revokedPermissionCancelsPendingRecoveryWithoutReadingOtherApps(){
        grant(true);bridge.start();assertEquals(ExternalMusicBridge.Status.CONNECTING,bridge.getState().status);grant(false);int queries=DelayedSessions.queries,rebinds=ShadowNotificationListenerService.getRebindRequestCount();Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(40));assertEquals(queries,DelayedSessions.queries);assertEquals(rebinds,ShadowNotificationListenerService.getRebindRequestCount());assertEquals(ExternalMusicBridge.Status.PERMISSION_REQUIRED,bridge.getState().status);
    }
    @Implements(MediaSessionManager.class) public static class DelayedSessions extends ShadowMediaSessionManager {
        static boolean allowed;static int queries;
        @Implementation @Override protected java.util.List<MediaController> getActiveSessions(ComponentName component){queries++;if(!allowed)throw new SecurityException("Fixture: system listener grant propagation pending");return super.getActiveSessions(component);}
    }
    @Test public void stoppingBridgeCancelsReconnectWorkAndSessionObservation(){grant(true);bridge.start();bridge.stop();int before=ShadowNotificationListenerService.getRebindRequestCount();Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(30));assertEquals(before,ShadowNotificationListenerService.getRebindRequestCount());assertEquals(ExternalMusicBridge.Status.STOPPED,bridge.getState().status);}
}
