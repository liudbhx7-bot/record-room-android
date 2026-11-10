package com.recordroom.vinyl;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.os.Looper;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowMediaSession;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

/** Captures the real service's framework API payload; does not start audio or send intents. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, shadows = PlayerArtworkTest.RecordingMediaSession.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class PlayerArtworkTest {
    @Implements(MediaSession.class)
    public static class RecordingMediaSession extends ShadowMediaSession {
        MediaMetadata metadata;
        boolean publishedOnMain;
        @Implementation protected void setMetadata(MediaMetadata value) {
            metadata = value;
            publishedOnMain = Looper.myLooper() == Looper.getMainLooper();
        }
    }

    private static Object value(PlayerService service, String name) throws Exception {
        Field field = PlayerService.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(service);
    }

    private static void drainArtwork(PlayerService service) throws Exception {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        ((ExecutorService)value(service, "artworkIO")).submit(() -> { }).get(20, TimeUnit.SECONDS);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private static Bitmap published(PlayerService service, Track track) throws Exception {
        RecordingMediaSession session = Shadow.extract((MediaSession)value(service, "mediaSession"));
        assertTrue("Metadata must be published on the main thread", session.publishedOnMain);
        assertNotNull(session.metadata);
        assertEquals(track.id, session.metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID));
        Bitmap cover = session.metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        assertNotNull("MediaSession must receive the decoded album cover", cover);
        assertSame(value(service, "sessionArtwork"), cover);
        assertTrue(cover.getWidth() <= 384 && cover.getHeight() <= 384);
        assertTrue(cover.getAllocationByteCount() <= 384 * 384 * 4);
        NotificationManager manager = (NotificationManager)service.getSystemService(Context.NOTIFICATION_SERVICE);
        Notification notification = Shadows.shadowOf(manager).getNotification(42);
        assertNotNull("The media notification must be updated", notification);
        assertNotNull("The media notification must include its large artwork icon", notification.getLargeIcon());
        return cover;
    }

    @Test public void coverPublishesAndRejectsAnOldAlbumCallback() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences(PlayerService.PREFS, Context.MODE_PRIVATE).edit().clear().commit();
        context.getSharedPreferences("vinyl_appearance", Context.MODE_PRIVATE).edit().clear().commit();
        List<Track> tracks = DemoCatalog.load(context);
        assertFalse("Use the application's real cover assets", tracks.isEmpty());
        Track first = tracks.get(0);
        int otherIndex = -1;
        for (int i = 1; i < tracks.size(); i++) {
            if (!first.albumKey().equals(tracks.get(i).albumKey())) { otherIndex = i; break; }
        }
        assertTrue("A second real album is needed for the stale-image regression", otherIndex > 0);
        ServiceController<PlayerService> controller = Robolectric.buildService(PlayerService.class).create();
        PlayerService service = controller.get();
        try {
            service.setQueue(tracks, 0, false);
            drainArtwork(service);
            published(service, first);
            long oldToken = (Long)value(service, "artworkGeneration");
            String oldSignature = (String)value(service, "artworkSignature");

            // A -> B -> A: matching the album signature alone must not accept an old callback.
            service.setQueue(tracks, otherIndex, false);
            drainArtwork(service);
            published(service, tracks.get(otherIndex));
            service.setQueue(tracks, 0, false);
            drainArtwork(service);
            Bitmap current = published(service, first);
            assertEquals(oldSignature, value(service, "artworkSignature"));
            Bitmap stale = Bitmap.createBitmap(12, 12, Bitmap.Config.ARGB_8888);
            Method complete = PlayerService.class.getDeclaredMethod("completeArtwork", long.class, String.class, Bitmap.class);
            complete.setAccessible(true);
            complete.invoke(service, oldToken, oldSignature, stale);
            assertTrue("An unpublished obsolete bitmap should be released", stale.isRecycled());
            assertSame("Old work must not replace the current album cover", current, published(service, first));

            new ThemeStore(context).artwork(first.albumKey(), tracks.get(otherIndex).artworkUri);
            drainArtwork(service);
            Bitmap custom = published(service, first);
            assertFalse("The album's custom cover must replace the original pixels", custom.sameAs(current));
            long generation = (Long)value(service, "artworkGeneration");
            Method refresh = PlayerService.class.getDeclaredMethod("updateSession");
            refresh.setAccessible(true);
            for (int i = 0; i < 3; i++) refresh.invoke(service);
            assertEquals("Unchanged covers must not enqueue another decode", generation, (long)(Long)value(service, "artworkGeneration"));
            assertSame(custom, published(service, first));
        } finally { controller.destroy(); }
        assertTrue(((ExecutorService)value(service, "artworkIO")).isShutdown());
        assertNull(value(service, "sessionArtwork"));
    }
}
