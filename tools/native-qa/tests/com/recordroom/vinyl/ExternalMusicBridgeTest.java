package com.recordroom.vinyl;

import android.content.Intent;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=34)
public class ExternalMusicBridgeTest {
    private FakeAccess access;
    private ExternalMusicBridge bridge;
    private final List<ExternalMusicBridge.State> events = new ArrayList<>();
    private static final String QQ = "com.tencent.qqmusic", SODA = "com.luna.music", APPLE = "com.apple.android.music";

    @Before public void setup() {
        access = new FakeAccess();
        bridge = new ExternalMusicBridge(access, new Handler(Looper.getMainLooper()), events::add);
    }
    @After public void cleanup() { bridge.close(); }

    @Test public void widgetUsesSelectedLinkedTitleAndCoverInsteadOfItsPausedLocalQueue() {
        FakeSession soda = song(SODA,"current","外部正在播放");
        access.sessions.add(soda);
        bridge.disableTimelineTicks();
        bridge.selectProvider("qishui_music");bridge.start();
        android.content.Context context = org.robolectric.RuntimeEnvironment.getApplication();
        Track local = DemoCatalog.load(context).get(0);
        VinylWidgetProvider.Snapshot snapshot=VinylWidgetProvider.snapshot(context,local,false,true,bridge.getState());
        assertEquals("外部正在播放",snapshot.title);
        assertSame(bridge.getState().artwork,snapshot.cover);
        assertNotNull(snapshot.link);
        assertNotEquals(local.id,snapshot.current.id);
        access.permission=false;bridge.refresh();
        snapshot=VinylWidgetProvider.snapshot(context,local,false,true,bridge.getState());
        assertEquals("汽水音乐",snapshot.title);
        assertNull(snapshot.cover);
    }

    @Test public void registryIsClosedAndInvalidSelectionCannotReplaceSource() {
        assertEquals(3, ExternalMusicBridge.providers().size());
        assertEquals(QQ, ExternalMusicBridge.providerForId("qq_music").packageName);
        assertEquals(SODA, ExternalMusicBridge.providerForId("qishui_music").packageName);
        assertEquals(APPLE, ExternalMusicBridge.providerForId("apple_music").packageName);
        assertTrue(bridge.selectProvider("qq_music"));
        assertFalse(bridge.selectProvider("com.tencent.qqmusic"));
        assertFalse(bridge.selectProvider("https://arbitrary.example/song"));
        assertEquals("qq_music", bridge.getSelectedProviderId());
        assertNull(bridge.getLaunchIntent("other"));
        assertEquals(QQ, bridge.getLaunchIntent("qq_music").getPackage());
        assertTrue(bridge.selectProvider(null));
        assertEquals("", bridge.getState().selectedProviderId);
    }

    @Test public void reportsInstallationPermissionConnectionAndNoSessionSeparately() {
        access.installed.clear(); access.permission = false; access.connected = false;
        bridge.selectProvider("qq_music"); bridge.start();
        assertEquals(ExternalMusicBridge.Status.NOT_INSTALLED, bridge.getState().status);
        access.installed.add(QQ); bridge.refresh();
        assertEquals(ExternalMusicBridge.Status.PERMISSION_REQUIRED, bridge.getState().status);
        assertEquals(0, access.queries);
        access.permission = true; bridge.refresh();
        assertEquals(ExternalMusicBridge.Status.CONNECTING, bridge.getState().status);
        assertEquals(0, access.queries);
        access.connected = true; bridge.refresh();
        assertEquals(ExternalMusicBridge.Status.NO_ACTIVE_SESSION, bridge.getState().status);
        FakeSession session = new FakeSession(QQ, "q1"); access.sessions.add(session); bridge.refresh();
        assertTrue(bridge.getState().connected);
        assertTrue(bridge.getState().statusMessage.contains("尚未提供"));
        assertEquals(-1, bridge.getState().durationMs); assertEquals(-1, bridge.getState().positionMs);
        assertFalse(bridge.getState().canSeek); assertNull(bridge.getState().artwork);
    }

    @Test public void onlySelectedAllowlistedSourceIsReadAndSessionPriorityCannotStealSelection() {
        FakeSession local = new FakeSession("com.recordroom.vinyl", "internal");
        FakeSession unexpected = new FakeSession("com.example.other", "unknown");
        FakeSession apple = new FakeSession(APPLE, "a1"), qq = song(QQ, "q1", "Selected song");
        access.sessions.addAll(Arrays.asList(local, unexpected, apple, qq));
        bridge.selectProvider("qq_music"); bridge.start();
        assertEquals("Selected song", bridge.getState().title);
        assertEquals(0, local.reads); assertEquals(0, unexpected.reads); assertEquals(0, apple.reads);
        assertEquals(0, apple.subscribes); assertEquals(1, qq.subscribes);
        FakeSession qqHigher = song(QQ, "q2", "Higher priority, same app");
        access.sessions.add(0, qqHigher); bridge.refresh();
        assertEquals("Selected song", bridge.getState().title);
        access.sessions.remove(qq); bridge.refresh();
        assertEquals("Higher priority, same app", bridge.getState().title);
        access.sessions.remove(qqHigher); bridge.refresh();
        assertEquals("qq_music", bridge.getState().selectedProviderId);
        assertEquals(ExternalMusicBridge.Status.NO_ACTIVE_SESSION, bridge.getState().status);
        assertFalse(bridge.play()); assertEquals(0, apple.subscribes);
    }

    @Test public void explicitSwitchDropsOldArtworkAndIgnoresAlreadyPostedCallbacks() {
        FakeSession qq = song(QQ, "q1", "QQ song"), soda = song(SODA, "s1", "Soda song");
        access.sessions.addAll(Arrays.asList(qq, soda));
        bridge.selectProvider("qq_music"); bridge.start(); Runnable late = qq.changed;
        bridge.selectProvider("qishui_music");
        assertEquals(1, qq.unsubscribes); assertEquals(1, soda.subscribes);
        int count = events.size(); late.run();
        assertEquals(count, events.size()); assertEquals("Soda song", bridge.getState().title);
        bridge.selectProvider(null);
        assertEquals(1, soda.unsubscribes); assertNull(bridge.getState().artwork);
        assertEquals(ExternalMusicBridge.Status.NO_SOURCE, bridge.getState().status);
    }

    @Test public void commandsUseCurrentActionsClampSeekAndNeverPredictPlaybackSuccess() {
        FakeSession qq = song(QQ, "q1", "QQ song");
        qq.playback = playback(PlaybackState.STATE_PAUSED, 4000, 0, 100,
                PlaybackState.ACTION_PLAY | PlaybackState.ACTION_SEEK_TO | PlaybackState.ACTION_SKIP_TO_NEXT);
        access.sessions.add(qq); bridge.selectProvider("qq_music"); bridge.start();
        assertTrue(bridge.play()); assertFalse(bridge.getState().playing);
        assertFalse(bridge.pause()); assertFalse(bridge.previous()); assertTrue(bridge.next());
        assertTrue(bridge.seekTo(-300)); assertEquals(0, qq.seek);
        assertTrue(bridge.seekTo(999999)); assertEquals(120000, qq.seek);
        qq.playback = playback(PlaybackState.STATE_PAUSED, 5000, 0, 100, 0);
        assertFalse("Recheck flags even before the next callback", bridge.next());
        assertFalse(bridge.seekTo(1000)); assertEquals(Arrays.asList("play", "next", "seek", "seek"), qq.commands);
    }

    @Test public void toggleOnlySessionReceivesTargetedToggleAndUnknownTimelineDisablesDrag() {
        FakeSession qq = new FakeSession(QQ, "q1");
        qq.playback = playback(PlaybackState.STATE_PAUSED, -1, 0, 0,
                PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_SEEK_TO);
        access.sessions.add(qq); bridge.selectProvider("qq_music"); bridge.start();
        assertFalse(bridge.getState().canSeek); assertFalse(bridge.seekTo(50));
        assertTrue(bridge.play()); assertEquals(Arrays.asList("toggle"), qq.commands);
        qq.playback = playback(PlaybackState.STATE_PLAYING, 0, 1, 0, PlaybackState.ACTION_PLAY_PAUSE);
        assertTrue(bridge.pause()); assertEquals(Arrays.asList("toggle", "toggle"), qq.commands);
        qq.playback = playback(PlaybackState.STATE_BUFFERING, 0, 0, 0, PlaybackState.ACTION_PLAY_PAUSE);
        assertFalse(bridge.togglePlayPause());
    }

    @Test public void revocationClearsMetadataAndUnsubscribesBeforeAnyFurtherCommand() {
        FakeSession qq = song(QQ, "q1", "Private now playing");
        qq.playback = playback(PlaybackState.STATE_PLAYING, 4000, 1, 100, PlaybackState.ACTION_PAUSE);
        access.sessions.add(qq); bridge.selectProvider("qq_music"); bridge.start();
        assertNotNull(bridge.getState().artwork);
        access.permission = false;
        assertFalse(bridge.pause()); assertEquals(0, qq.commands.size());
        assertEquals(ExternalMusicBridge.Status.PERMISSION_REQUIRED, bridge.getState().status);
        assertEquals("", bridge.getState().title); assertNull(bridge.getState().artwork);
        assertFalse(bridge.getState().connected); assertFalse(bridge.getState().accessGranted);
        assertEquals(1, qq.unsubscribes); assertTrue(access.removedSessionWatches > 0);
    }

    @Test public void platformDenialAndDisconnectCannotLeaveAStaleConnectedState() {
        FakeSession qq = song(QQ, "q1", "QQ song"); access.sessions.add(qq);
        bridge.selectProvider("qq_music"); bridge.start();
        access.denyQuery = true; bridge.refresh();
        assertEquals(ExternalMusicBridge.Status.SESSION_UNAVAILABLE, bridge.getState().status);
        assertFalse(bridge.getState().connected); assertNull(bridge.getState().artwork);
        access.denyQuery = false; bridge.refresh(); assertTrue(bridge.getState().connected);
        access.connected = false; access.changed.run();
        assertEquals(ExternalMusicBridge.Status.CONNECTING, bridge.getState().status);
        assertFalse(bridge.play()); assertEquals(2, qq.unsubscribes);
    }

    @Test public void stopCloseAndRestartBalanceListenersAndLateEventsCannotResurrectState() {
        FakeSession qq = song(QQ, "q1", "QQ song"); access.sessions.add(qq);
        bridge.selectProvider("qq_music"); bridge.start(); bridge.start();
        Runnable late = qq.changed; Runnable lateAccess = access.changed;
        bridge.stop(); int count = events.size(); late.run(); lateAccess.run();
        assertEquals(count, events.size()); assertEquals(1, access.starts); assertEquals(1, access.stops);
        assertEquals(1, qq.unsubscribes); assertFalse(bridge.play());
        assertEquals(ExternalMusicBridge.Status.STOPPED, bridge.getState().status);
        assertEquals("qq_music", bridge.getSelectedProviderId()); assertNull(bridge.getState().artwork);
        bridge.start(); assertEquals(2, access.starts); assertEquals(2, qq.subscribes);
        bridge.close(); count = events.size(); bridge.start(); late.run();
        assertEquals(count, events.size()); assertEquals(2, qq.unsubscribes); assertEquals(2, access.stops);
        assertFalse(bridge.selectProvider("apple_music"));
    }

    @Test public void destroyedSessionIsNotReattachedFromLaggingSystemList() {
        FakeSession qq = song(QQ, "q1", "Ended"), apple = song(APPLE, "a1", "Other app");
        access.sessions.addAll(Arrays.asList(qq, apple)); bridge.selectProvider("qq_music"); bridge.start();
        qq.destroyed.run();
        assertEquals(1, qq.subscribes); assertEquals(1, qq.unsubscribes);
        assertEquals(ExternalMusicBridge.Status.NO_ACTIVE_SESSION, bridge.getState().status);
        assertEquals("qq_music", bridge.getSelectedProviderId()); assertEquals(0, apple.subscribes);
        FakeSession replacement = song(QQ, "q2", "New session"); access.sessions.add(replacement); bridge.refresh();
        assertEquals("New session", bridge.getState().title);
    }

    @Test public void positionUsesElapsedRealtimeSpeedAndRemoteBoundsWithoutInventingUnknowns() {
        assertEquals(4000, ExternalMusicBridge.estimatePosition(playback(PlaybackState.STATE_PLAYING, 1000, 1.5f, 1000, 0), 10000, 3000));
        assertEquals(10000, ExternalMusicBridge.estimatePosition(playback(PlaybackState.STATE_PLAYING, 9000, 1, 1000, 0), 10000, 5000));
        assertEquals(1000, ExternalMusicBridge.estimatePosition(playback(PlaybackState.STATE_PAUSED, 1000, 1, 1000, 0), 10000, 5000));
        assertEquals(0, ExternalMusicBridge.estimatePosition(playback(PlaybackState.STATE_REWINDING, 1000, -1, 1000, 0), 10000, 5000));
        assertEquals(-1, ExternalMusicBridge.estimatePosition(playback(PlaybackState.STATE_PLAYING, -1, 1, 1000, 0), 10000, 5000));
        assertEquals(1000, ExternalMusicBridge.estimatePosition(playback(PlaybackState.STATE_PLAYING, 1000, 1, 0, 0), -1, 5000));
    }

    private static PlaybackState playback(int state, long position, float speed, long time, long actions) {
        return new PlaybackState.Builder().setState(state, position, speed, time).setActions(actions).build();
    }
    private static FakeSession song(String pkg, String token, String title) {
        FakeSession session = new FakeSession(pkg, token);
        session.metadata = new MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, "Session artist")
                .putString(MediaMetadata.METADATA_KEY_ALBUM, "Session album")
                .putLong(MediaMetadata.METADATA_KEY_DURATION, 120000)
                .putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)).build();
        return session;
    }
    private static final class FakeAccess implements ExternalMusicBridge.Access {
        boolean permission = true, connected = true, denyQuery;
        int starts, stops, queries, removedSessionWatches;
        Runnable changed;
        Set<String> installed = new HashSet<>(Arrays.asList(QQ, SODA, APPLE));
        List<ExternalMusicBridge.Session> sessions = new ArrayList<>();
        public boolean hasAccess() { return permission; }
        public boolean isConnected() { return connected; }
        public boolean isInstalled(String pkg) { return installed.contains(pkg); }
        public void start(Runnable callback) { starts++; changed = callback; }
        public void stop() { stops++; changed = null; }
        public void stopSessionWatch() { removedSessionWatches++; }
        public List<ExternalMusicBridge.Session> activeSessions() {
            queries++; if (denyQuery) throw new SecurityException("Fixture revocation race"); return sessions;
        }
        public Intent launchIntent(String pkg) { return new Intent(Intent.ACTION_MAIN).setPackage(pkg); }
    }
    private static final class FakeSession implements ExternalMusicBridge.Session {
        final String pkg, token;
        MediaMetadata metadata; PlaybackState playback;
        int reads, subscribes, unsubscribes; long seek;
        Runnable changed, destroyed;
        List<String> commands = new ArrayList<>();
        FakeSession(String pkg, String token) { this.pkg = pkg; this.token = token; }
        public String packageName() { return pkg; }
        public Object token() { return token; }
        public MediaMetadata metadata() { reads++; return metadata; }
        public PlaybackState playback() { return playback; }
        public void subscribe(Runnable change, Runnable destroy) { subscribes++; changed = change; destroyed = destroy; }
        public void unsubscribe() { unsubscribes++; }
        public void play() { commands.add("play"); }
        public void pause() { commands.add("pause"); }
        public void previous() { commands.add("previous"); }
        public void next() { commands.add("next"); }
        public void seekTo(long position) { commands.add("seek"); seek = position; }
        public boolean toggle() { commands.add("toggle"); return true; }
    }
}
