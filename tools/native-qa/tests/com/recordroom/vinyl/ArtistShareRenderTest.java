package com.recordroom.vinyl;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.pm.ProviderInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.net.Uri;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.android.controller.ActivityController;
import static org.junit.Assert.*;

/** Uses the unified runner's real application resource APK; never starts a share target. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "w412dp-h860dp-mdpi", fontScale = 1f)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ArtistShareRenderTest {
    private static File outputDirectory() throws IOException {
        String path = System.getProperty("vinyl.output.dir");
        if (path == null || path.isBlank()) throw new IllegalStateException("vinyl.output.dir is required");
        File directory = new File(path);
        Files.createDirectories(directory.toPath());
        return directory;
    }

    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static Object call(Object target, String name, Class<?>[] types, Object... args) throws Exception {
        Method method = target.getClass().getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(target, args);
    }

    private static void snapshot(View view, int width, int height, File output, String name) throws Exception {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, width, height);
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        try {
            view.draw(new Canvas(bitmap));
            try (FileOutputStream stream = new FileOutputStream(new File(output, name))) {
                assertTrue("PNG encoding failed", bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream));
            }
        } finally {
            bitmap.recycle();
        }
        System.out.println("NATIVE_ARTIST_SHARE_RENDER " + name + " " + width + "x" + height);
    }

    private static View text(View root, String label) {
        if (root instanceof TextView && label.contentEquals(((TextView) root).getText())) return root;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = text(group.getChildAt(i), label);
                if (found != null) return found;
            }
        }
        return null;
    }

    /** Covers are scheduled by the initial draw; wait for both decoding workers and main callbacks. */
    private static void awaitArtistArtwork(FrameLayout artist) throws Exception {
        Object loader = field(artist, "covers");
        Map<?, ?> pending = (Map<?, ?>) field(loader, "waiters");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (!pending.isEmpty() && System.nanoTime() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            if (!pending.isEmpty()) Thread.sleep(25);
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertTrue("Artist artwork decoding did not finish", pending.isEmpty());
        Object browser=field(artist,"albumBrowser");if(browser!=null){
            AlbumStackView stack=(AlbumStackView)field(browser,"stack");java.util.Set<?> stackPending=(java.util.Set<?>)field(stack,"pending");
            while(!stackPending.isEmpty()&&System.nanoTime()<deadline){Shadows.shadowOf(Looper.getMainLooper()).idle();if(!stackPending.isEmpty())Thread.sleep(15);}
            Shadows.shadowOf(Looper.getMainLooper()).idle();assertTrue("Stack artwork decoding did not finish",stackPending.isEmpty());
            android.util.LruCache<?,?> images=(android.util.LruCache<?,?>)field(stack,"covers");assertFalse("Real album artwork must load in the native stack",images.snapshot().isEmpty());
        }
    }

    private static ArrayList<Track> demoTracks() {
        ArrayList<Track> tracks = new ArrayList<>();
        tracks.add(new Track("n", "蓝色的夜", "唱片室", "NIGHT WALK", "演示音乐",
                "asset:///demo/night-1.wav", "asset:///demo/night-v2.png", 18000, 1));
        tracks.add(new Track("s", "阳光之后", "唱片室", "SUN ROOM", "演示音乐",
                "asset:///demo/sun-1.wav", "asset:///demo/sun-v2.png", 18000, 1));
        tracks.add(new Track("f", "林间漫步", "唱片室", "FOREST NOTES", "演示音乐",
                "asset:///demo/forest-1.wav", "asset:///demo/forest-v2.png", 18000, 1));
        return tracks;
    }

    @Test public void nativeUiExportsAndRestrictedUris() throws Exception {
        File output = outputDirectory();
        ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup().visible();
        Activity activity = controller.get();
        ShareStudio studio = null;
        try {
            ArrayList<Track> tracks = demoTracks();
            ArrayList<Track> library = new ArrayList<>(tracks);
            library.add(new Track("other", "另一首歌", "另一位音乐人", "另一个专辑", "演示音乐",
                    "asset:///demo/night-1.wav", "asset:///demo/night-v2.png", 18000, 1));
            int[] played = {-1};
            boolean[] shuffled = {false};
            FrameLayout artist = ArtistPage.create(activity, library, tracks.get(0), new ArtistPage.Actions() {
                public void back() { }
                public void play(ArrayList<Track> songs, int index, boolean shuffle) {
                    played[0] = songs.size();
                    shuffled[0] = shuffle;
                }
                public void album(ArrayList<Track> songs) { }
                public void share(String title, String subtitle, ArrayList<Track> songs) { }
            });
            activity.setContentView(artist);
            snapshot(artist, 412, 860, output, "artist-first-pass.png");
            awaitArtistArtwork(artist);
            snapshot(artist, 412, 860, output, "artist-page-api34.png");
            View shuffle = text(artist, "播放推荐");
            assertNotNull(shuffle);
            shuffle.performClick();
            assertEquals("An unrelated artist must not enter the queue", 3, played[0]);
            assertFalse(shuffled[0]);
            ArtistPage.selectChapter(artist, 2);
            assertEquals("The actual album chapter must be rendered", 2, ArtistPage.chapter(artist));
            snapshot(artist,412,860,output,"artist-albums-first-pass.png");
            awaitArtistArtwork(artist);
            snapshot(artist,412,860,output,"artist-albums-deck-api34.png");

            studio = new ShareStudio(activity, "NIGHT WALK", "唱片室 · 原创演示音乐", tracks, 0xffd77c6c, 2);
            Dialog dialog = studio.show();
            ((ExecutorService) field(studio, "worker")).submit(() -> { }).get(20, TimeUnit.SECONDS);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertTrue("Share artwork did not become ready", (Boolean) field(studio, "ready"));
            Object bundle = field(studio, "artwork");
            assertEquals("Real bundled covers must load", 3, ((ArrayList<?>) field(bundle, "covers")).size());
            assertNotNull("Paper image missing from resource APK", field(bundle, "paper"));
            assertNotNull("Approved sleeve missing from resource APK", field(bundle, "sleeve"));
            assertNotNull("Disc image missing from resource APK", field(bundle, "disc"));
            assertEquals("Only the primary cover determines the shared record palette",
                    AlbumPalette.from((Bitmap)((ArrayList<?>)field(bundle,"covers")).get(0)).record,field(bundle,"recordTint"));

            ShareImageProvider provider = new ShareImageProvider();
            ProviderInfo info = new ProviderInfo();
            info.authority = ShareImageProvider.AUTHORITY;
            info.exported = false;
            info.grantUriPermissions = true;
            provider.attachInfo(activity, info);
            Uri generatedUri = null;
            for (int layout = 0; layout < 3; layout++) {
                call(studio, "selectLayout", new Class<?>[] {int.class}, layout);
                snapshot(dialog.getWindow().getDecorView(), 412, 860, output,
                        "share-layout-" + layout + "-api34.png");

                // Exercise the real PNG renderer directly, without opening ACTION_SEND or a document picker.
                Object card = call(studio, "renderSnapshot", new Class<?>[0]);
                Method export = ShareStudio.class.getDeclaredMethod("writeExport", Context.class, card.getClass());
                export.setAccessible(true);
                File image = (File) export.invoke(null, activity, card);
                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(image.getAbsolutePath(), bounds);
                assertEquals(1600, bounds.outWidth);
                assertEquals(2000, bounds.outHeight);
                assertTrue("Export is unexpectedly empty", image.length() > 20000);
                Files.copy(image.toPath(), new File(output, "share-export-" + layout + ".png").toPath(),
                        StandardCopyOption.REPLACE_EXISTING);

                generatedUri = ShareImageProvider.uriForFile(activity, image);
                try (ParcelFileDescriptor descriptor = provider.openFile(generatedUri, "r")) {
                    assertEquals(image.length(), descriptor.getStatSize());
                }
                assertEquals("image/png", provider.getType(generatedUri));
            }

            for (String suffix : Arrays.asList("../secret.png", "..%2Fsecret.png", "a.png", "/share-123.png",
                    "share-01234567890123456789012345678901.png?x=y")) {
                try {
                    provider.openFile(Uri.parse("content://" + ShareImageProvider.AUTHORITY + "/" + suffix), "r");
                    fail("Unsafe URI accepted: " + suffix);
                } catch (FileNotFoundException expected) { }
            }
            try {
                provider.openFile(generatedUri, "rw");
                fail("Write mode accepted");
            } catch (FileNotFoundException expected) { }
            File outsideShareDirectory = new File(activity.getCacheDir(),
                    "share-01234567890123456789012345678901.png");
            try (FileOutputStream stream = new FileOutputStream(outsideShareDirectory)) {
                stream.write(1);
            }
            try {
                ShareImageProvider.uriForFile(activity, outsideShareDirectory);
                fail("File outside cache/shares accepted");
            } catch (IOException expected) { }
            System.out.println("ARTIST_SHARE_QA: native rendering, real artwork, three PNG exports, URI confinement passed");
        } finally {
            if (studio != null) studio.close();
            controller.pause().stop().destroy();
        }
    }
}
