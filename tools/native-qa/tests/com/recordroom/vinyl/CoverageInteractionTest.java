package com.recordroom.vinyl;

import android.app.Activity;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.os.Bundle;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Switch;
import android.widget.TextView;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowAppWidgetManager;
import static org.junit.Assert.*;

/** Local behavior regressions; these checks make no claims about unobserved iOS screens. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "w412dp-h860dp-mdpi", fontScale = 1f)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class CoverageInteractionTest {
    private static Object field(Object target, String name) throws Exception {
        Class<?> type = target instanceof Class ? (Class<?>) target : target.getClass();
        Field field = type.getDeclaredField(name); field.setAccessible(true);
        return field.get(target instanceof Class ? null : target);
    }

    private static View text(View view, String label) {
        if (view instanceof TextView && label.contentEquals(((TextView) view).getText())) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = text(group.getChildAt(i), label); if (found != null) return found;
            }
        }
        return null;
    }

    private static View description(View view, String label) {
        if (label.contentEquals(view.getContentDescription() == null ? "" : view.getContentDescription())) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = description(group.getChildAt(i), label); if (found != null) return found;
            }
        }
        return null;
    }

    private static Track track(String id, String artist, String albumArtist, String album, int number) {
        return new Track(id, "歌曲 " + id, artist, album, "本地音乐", "file:///" + id + ".mp3", "", 180000, number, albumArtist);
    }

    private static final class ArtistActions implements ArtistPage.Actions {
        ArrayList<Track> played, album, shared;
        int index = -1;
        boolean shuffle;
        String shareTitle;
        public void back() { }
        public void play(ArrayList<Track> songs, int index, boolean shuffle) { played = songs; this.index = index; this.shuffle = shuffle; }
        public void album(ArrayList<Track> songs) { album = songs; }
        public void share(String title, String subtitle, ArrayList<Track> songs) { shareTitle = title; shared = songs; }
    }

    @Test public void albumArtistEntryUsesItsNameAndCompleteMatchingTracks() {
        ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup();
        try {
            Track second = track("second", "客席乙", "共同乐队", "合作专辑", 2);
            Track first = track("first", "客席甲", "共同乐队", "合作专辑", 1);
            Track solo = track("solo", "共同乐队", "共同乐队", "另一张专辑", 1);
            Track unrelated = track("other", "无关艺人", "无关艺人", "合作专辑", 1);
            ArtistActions actions = new ArtistActions();
            // The seed is not in the library, as can happen before a local scan has completed.
            FrameLayout page = ArtistPage.create(controller.get(), Arrays.asList(second, solo, second, unrelated), first, " 共同乐队 ", actions);
            mount(controller.get(), page);
            assertNotNull(text(page, "共同乐队"));
            assertNotNull(text(page, "推荐歌曲"));
            text(page, "播放推荐").performClick();
            // The native chapter uses real library order; an opened album is still track-number sorted.
            assertEquals(Arrays.asList(second, solo, first), actions.played);
            assertFalse(actions.shuffle); assertEquals(0, actions.index);
            ArtistPage.selectChapter(page, ArtistLibraryData.ALBUMS); layout(page);
            View albumCard = text(page, "打开专辑"); assertNotNull(albumCard); albumCard.performClick();Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(1000));albumCard.performClick();
            assertEquals("Album callback follows track numbers, independent of library scan order", Arrays.asList(first, second), actions.album);
            assertNull("The artist page no longer exposes sharing",description(page,"分享音乐人"));
            assertNull(actions.shared);

            ArtistActions songActions = new ArtistActions();
            FrameLayout songPage = ArtistPage.create(controller.get(), Arrays.asList(first, second, solo), first, songActions);
            mount(controller.get(), songPage);
            assertNotNull(text(songPage, "客席甲"));
            text(songPage, "播放推荐").performClick();
            assertEquals("A song-artist entry must not become an album-artist entry", Arrays.asList(first), songActions.played);

            ArtistActions otherActions = new ArtistActions();
            FrameLayout otherPage = ArtistPage.create(controller.get(), Arrays.asList(unrelated), first, "无关艺人", otherActions);
            mount(controller.get(), otherPage);
            text(otherPage, "播放推荐").performClick();
            assertEquals("An unrelated seed must not leak into the requested artist's queue", Arrays.asList(unrelated), otherActions.played);
        } finally { controller.pause().stop().destroy(); }
    }

    @Test public void artistScrollReachesSongBeyondFirstThirtyAndUsesTheCompleteQueue() {
        ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup();
        try {
            ArrayList<Track> songs = new ArrayList<>();
            for (int i = 0; i < 35; i++) songs.add(track("t" + i, "音乐人甲", "音乐人甲", "长专辑", i + 1));
            ArtistActions actions = new ArtistActions();
            FrameLayout page = ArtistPage.create(controller.get(), songs, songs.get(0), actions);
            mount(controller.get(), page);
            ArtistPage.selectChapter(page, ArtistLibraryData.SONGS); layout(page);
            assertNull(description(page, "播放 歌曲 t34"));
            ListView rows = artistList(page); assertTrue(rows.getChildCount() < 20);
            rows.setSelection(rows.getCount()-1); layout(page);
            View last = description(page, "播放 歌曲 t34"); assertNotNull(last); last.performClick();
            assertEquals(songs, actions.played); assertEquals(34, actions.index); assertFalse(actions.shuffle);
        } finally { controller.pause().stop().destroy(); }
    }

    private static void mount(Activity activity, View page) { activity.setContentView(page); layout(page); }
    private static void layout(View page) {
        for (int i = 0; i < 2; i++) {
            page.measure(View.MeasureSpec.makeMeasureSpec(412, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(860, View.MeasureSpec.EXACTLY));
            page.layout(0, 0, 412, 860); Shadows.shadowOf(Looper.getMainLooper()).idle();
        }
    }
    private static ListView artistList(View page) {
        if (page instanceof ListView) return (ListView) page;
        if (page instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) page).getChildCount(); i++) {
            ListView found = artistList(((ViewGroup) page).getChildAt(i)); if (found != null) return found;
        }
        return null;
    }

    private static ExecutorService providerIO() throws Exception { return (ExecutorService) field(VinylWidgetProvider.class, "IO"); }

    private static void drainProvider() throws Exception {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        providerIO().submit(() -> { }).get(20, TimeUnit.SECONDS);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private static void save(Context context, int id, VinylWidgetProvider.Options options) throws Exception {
        AtomicReference<Boolean> completed = new AtomicReference<>();
        VinylWidgetProvider.saveAndUpdate(context, id, options, completed::set);
        drainProvider(); assertEquals(Boolean.TRUE, completed.get());
    }

    private static ShadowAppWidgetManager widgets(Context context) {
        context.getSharedPreferences("vinyl_widgets", Context.MODE_PRIVATE).edit().clear().commit();
        context.getSharedPreferences(PlayerService.PREFS, Context.MODE_PRIVATE).edit().clear().commit();
        ShadowAppWidgetManager manager = Shadows.shadowOf(AppWidgetManager.getInstance(context));
        AppWidgetProviderInfo info = new AppWidgetProviderInfo();
        info.provider = new ComponentName(context, VinylWidgetProvider.class);
        info.configure = new ComponentName(context, WidgetAppearanceActivity.class);
        info.initialLayout = R.layout.vinyl_widget;
        manager.addBoundWidget(401, info); manager.addBoundWidget(402, info);
        return manager;
    }

    private static VinylWidgetProvider.Options firstOptions() {
        return new VinylWidgetProvider.Options(VinylWidgetProvider.RADIO, VinylWidgetProvider.COLOR_FIXED, 0xff76543e, false);
    }

    private static VinylWidgetProvider.Options secondOptions() {
        return new VinylWidgetProvider.Options(VinylWidgetProvider.COVER, VinylWidgetProvider.COLOR_ARTWORK, 0xffadc0bb, true);
    }

    @Test public void recreatedWidgetDraftCanBeCanceledWithoutChangingEitherInstance() throws Exception {
        Context context = RuntimeEnvironment.getApplication(); widgets(context);
        VinylWidgetProvider.Options first = firstOptions(), second = secondOptions();
        save(context, 401, first); save(context, 402, second);
        Intent intent = new Intent(context, WidgetAppearanceActivity.class).setAction(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 401);
        ActivityController<WidgetAppearanceActivity> controller = Robolectric.buildActivity(WidgetAppearanceActivity.class, intent).setup();
        try {
            WidgetAppearanceActivity activity = controller.get();
            ((LinearLayout[]) field(activity, "cards"))[VinylWidgetProvider.RECENT].performClick();
            ((TextView[]) field(activity, "modes"))[VinylWidgetProvider.COLOR_FIXED].performClick();
            ((TextView[]) field(activity, "swatches"))[3].performClick();
            ((Switch) field(activity, "transparency")).setChecked(true);
            controller.recreate();
            activity = controller.get();
            assertEquals(VinylWidgetProvider.RECENT, field(activity, "style"));
            assertEquals(VinylWidgetProvider.COLOR_FIXED, field(activity, "colorMode"));
            assertEquals(ThemeStore.COLORS[3], field(activity, "color"));
            assertEquals(Boolean.TRUE, field(activity, "transparent"));
            assertEquals(Activity.RESULT_CANCELED, Shadows.shadowOf(activity).getResultCode());
            activity.onBackPressed();
            assertTrue(activity.isFinishing());
            assertEquals(Activity.RESULT_CANCELED, Shadows.shadowOf(activity).getResultCode());
            drainProvider();
            assertEquals(first.signature(), VinylWidgetProvider.options(context, 401).signature());
            assertEquals(second.signature(), VinylWidgetProvider.options(context, 402).signature());
        } finally { controller.pause().stop().destroy(); }
    }

    private static Bitmap hostBitmap(ShadowAppWidgetManager manager, int id) {
        ImageView image = manager.getViewFor(id).findViewById(R.id.widget_art);
        assertNotNull(image.getDrawable()); return ((BitmapDrawable) image.getDrawable()).getBitmap();
    }

    private static Bundle bounds(int minWidth, int maxWidth, int minHeight, int maxHeight) {
        Bundle result = new Bundle();
        result.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, minWidth);
        result.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, maxWidth);
        result.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, minHeight);
        result.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, maxHeight);
        return result;
    }

    private static void resized(Context context, int id, Bundle bounds) throws Exception {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        manager.updateAppWidgetOptions(id, bounds);
        // addBoundWidget has no provider object; dispatch the actual receiver callback explicitly.
        new VinylWidgetProvider().onAppWidgetOptionsChanged(context, manager, id, bounds);
        drainProvider();
    }

    @Test public void hostResizeUsesOrientationAndLeavesOtherWidgetUntouched() throws Exception {
        Context context = RuntimeEnvironment.getApplication(); ShadowAppWidgetManager manager = widgets(context);
        save(context, 401, firstOptions()); save(context, 402, secondOptions());
        Bitmap other = hostBitmap(manager, 402);
        Bundle sizes = bounds(180, 400, 140, 300);
        resized(context, 401, sizes);
        Bitmap portrait = hostBitmap(manager, 401);
        assertEquals(230, portrait.getWidth()); assertEquals(384, portrait.getHeight());
        assertEquals(View.GONE, manager.getViewFor(401).findViewById(R.id.widget_speaker).getVisibility());
        RuntimeEnvironment.setQualifiers("w860dp-h412dp-land-mdpi");
        resized(context, 401, sizes);
        Bitmap landscape = hostBitmap(manager, 401);
        assertEquals(512, landscape.getWidth()); assertEquals(179, landscape.getHeight());
        assertEquals(View.VISIBLE, manager.getViewFor(401).findViewById(R.id.widget_speaker).getVisibility());
        resized(context, 401, bounds(1, 10000, 1, 10000));
        Bitmap bounded = hostBitmap(manager, 401);
        assertTrue(bounded.getWidth() <= 512 && bounded.getHeight() <= 384);
        assertTrue(bounded.getAllocationByteCount() <= 768 * 1024);
        assertSame("Resizing one widget must not republish the other widget", other, hostBitmap(manager, 402));
        assertEquals(firstOptions().signature(), VinylWidgetProvider.options(context, 401).signature());
        assertEquals(secondOptions().signature(), VinylWidgetProvider.options(context, 402).signature());
    }

    @Test public void deletingWidgetCancelsItsQueuedUpdateWithoutTouchingOtherInstance() throws Exception {
        Context context = RuntimeEnvironment.getApplication(); ShadowAppWidgetManager manager = widgets(context);
        save(context, 401, firstOptions()); save(context, 402, secondOptions());
        Bitmap first = hostBitmap(manager, 401), other = hostBitmap(manager, 402);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        Future<?> blocker = providerIO().submit(() -> {
            entered.countDown();
            try { if (!release.await(20, TimeUnit.SECONDS)) throw new AssertionError("Test did not release widget IO"); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
        });
        assertTrue(entered.await(20, TimeUnit.SECONDS));
        try {
            VinylWidgetProvider provider = new VinylWidgetProvider();
            provider.onUpdate(context, AppWidgetManager.getInstance(context), new int[]{401});
            provider.onDeleted(context, new int[]{401});
        } finally { release.countDown(); }
        blocker.get(20, TimeUnit.SECONDS); drainProvider();
        assertFalse(VinylWidgetProvider.configured(context, 401));
        for (String key : context.getSharedPreferences("vinyl_widgets", Context.MODE_PRIVATE).getAll().keySet())
            assertFalse("Deleted widget retained preference " + key, key.startsWith("401:"));
        assertSame("An update queued before deletion must not republish the deleted widget", first, hostBitmap(manager, 401));
        assertSame(other, hostBitmap(manager, 402));
        assertTrue(VinylWidgetProvider.configured(context, 402));
        assertEquals(secondOptions().signature(), VinylWidgetProvider.options(context, 402).signature());
    }
}
