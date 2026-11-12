package com.recordroom.vinyl;

import android.app.Activity;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Looper;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RemoteViews;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONArray;
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
import org.robolectric.shadows.ShadowPendingIntent;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** Runs the production widget renderer and setup flow; no playback intents are sent. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "w512dp-h726dp-mdpi", fontScale = 1f)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class WidgetRenderTest {
    private static final File OUTPUT = new File(System.getProperty("vinyl.output.dir"));

    private static Field field(Class<?> type, String name) throws Exception {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try { Field f = c.getDeclaredField(name); f.setAccessible(true); return f; }
            catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }

    private static Object value(Object target, String name) throws Exception { return field(target.getClass(), name).get(target); }

    private static ExecutorService providerIO() throws Exception {
        return (ExecutorService)field(VinylWidgetProvider.class, "IO").get(null);
    }

    private static void drainProvider() throws Exception {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        providerIO().submit(() -> { }).get(20, TimeUnit.SECONDS);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private static void waitPreviews(WidgetAppearanceActivity activity) throws Exception {
        ((ExecutorService)value(activity, "previewsIO")).submit(() -> { }).get(20, TimeUnit.SECONDS);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        ImageView preview = (ImageView)value(activity, "preview");
        assertNotNull("The single stand must display the selected rendered widget", preview.getDrawable());
    }

    private static void png(Bitmap bitmap, String name) throws Exception {
        OUTPUT.mkdirs();
        try (FileOutputStream stream = new FileOutputStream(new File(OUTPUT, name))) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream));
        }
        System.out.println("NATIVE_WIDGET_RENDER " + name + " " + bitmap.getWidth() + "x" + bitmap.getHeight()
                + " bytes=" + bitmap.getAllocationByteCount());
    }

    private static void screenshot(View view, int width, int height, String name) throws Exception {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, width, height);
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(bitmap));
        png(bitmap, name);
        bitmap.recycle();
    }

    private static void bounded(Bitmap bitmap) {
        assertTrue("Widget image width exceeds the IPC budget", bitmap.getWidth() <= 512);
        assertTrue("Widget image height exceeds the IPC budget", bitmap.getHeight() <= 384);
        assertTrue("Widget image allocation exceeds 768 KiB", bitmap.getAllocationByteCount() <= 512 * 384 * 4);
    }

    /** Inspect the RemoteViews action payload rather than sending a click or a service broadcast. */
    private static PendingIntent clickIntent(RemoteViews views, int viewId) throws Exception {
        for (Object action : (List<?>)value(views, "mActions")) {
            if (!action.getClass().getSimpleName().contains("SetOnClickResponse")) continue;
            if (field(action.getClass(), "viewId").getInt(action) != viewId) continue;
            Object response = value(action, "mResponse");
            return (PendingIntent)value(response, "mPendingIntent");
        }
        throw new AssertionError("No PendingIntent for widget view " + viewId);
    }

    private static void transport(Context context, RemoteViews views, int id, String action) throws Exception {
        PendingIntent intent = clickIntent(views, id);
        ShadowPendingIntent shadow = Shadows.shadowOf(intent);
        assertTrue("Transport must start the owned foreground playback service", shadow.isForegroundService());
        assertTrue("Widget PendingIntent must be immutable", shadow.isImmutable());
        assertEquals(new ComponentName(context, PlayerService.class), shadow.getSavedIntent().getComponent());
        assertEquals(action, shadow.getSavedIntent().getAction());
    }

    private static void save(Context context, int id, VinylWidgetProvider.Options options) throws Exception {
        AtomicReference<Boolean> completed = new AtomicReference<>();
        VinylWidgetProvider.saveAndUpdate(context, id, options, completed::set);
        drainProvider();
        assertEquals("Configuration did not finish after updating its RemoteViews", Boolean.TRUE, completed.get());
    }

    private static ActivityController<WidgetAppearanceActivity> configure(Context context, int id) {
        Intent intent = new Intent(context, WidgetAppearanceActivity.class);
        if (id != AppWidgetManager.INVALID_APPWIDGET_ID)
            intent.setAction(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id);
        return Robolectric.buildActivity(WidgetAppearanceActivity.class, intent).setup().visible();
    }

    private static ScrollView scroll(View view) {
        if (view instanceof ScrollView) return (ScrollView)view;
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup)view;
            for (int i = 0; i < group.getChildCount(); i++) {
                ScrollView found = scroll(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    @Test public void renderWidgetsAndValidateConfiguration() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("vinyl_widgets", Context.MODE_PRIVATE).edit().clear().commit();
        context.getSharedPreferences(PlayerService.PREFS, Context.MODE_PRIVATE).edit().clear().commit();
        context.getSharedPreferences("record_room_collections", Context.MODE_PRIVATE).edit().clear().commit();
        List<Track> demos = DemoCatalog.load(context);
        assertFalse("The test must use the application's real assets", demos.isEmpty());
        JSONArray queue = new JSONArray();
        for (Track track : demos) queue.put(track.toJson());
        context.getSharedPreferences(PlayerService.PREFS, Context.MODE_PRIVATE).edit()
                .putString("queue", queue.toString()).putInt("index", 0).putLong("position", 3200).commit();
        new ThemeStore(context).save(null, false, 2, ThemeStore.COLORS[1]);
        LibraryStore history = new LibraryStore(context);
        for (Track track : demos) history.remember(track);
        Track current = demos.get(0);
        VinylWidgetProvider.Snapshot state = providerIO().submit(() ->
                VinylWidgetProvider.snapshot(context, current, true, false)).get(20, TimeUnit.SECONDS);
        assertNotNull("Actual artwork must be decoded from the catalog", state.cover);
        assertNotNull("Generated disc material must be loaded", state.disc);
        assertTrue("The renderer must receive real listening-history covers", state.recentCount > 0);
        for (Bitmap cover : state.recentCovers) assertNotNull("A real history cover failed to decode", cover);

        String[] names = {"turntable", "cover", "radio", "recent"};
        for (int style = 0; style < 4; style++) {
            VinylWidgetProvider.Options options = new VinylWidgetProvider.Options(style, VinylWidgetProvider.COLOR_THEME, ThemeStore.COLORS[1], false);
            final int selectedStyle = style;
            Bitmap image = providerIO().submit(() ->
                    VinylWidgetProvider.render(context, state, options, 320, 190)).get(20, TimeUnit.SECONDS);
            bounded(image);
            png(image, "widget-" + names[selectedStyle] + "-api34.png");
            RemoteViews views = VinylWidgetProvider.remoteViews(context, 101, state, options, image, 320);
            transport(context, views, R.id.widget_previous, PlayerService.ACTION_PREVIOUS);
            transport(context, views, R.id.widget_play, PlayerService.ACTION_TOGGLE);
            transport(context, views, R.id.widget_next, PlayerService.ACTION_NEXT);
            ShadowPendingIntent settings = Shadows.shadowOf(clickIntent(views, R.id.widget_configure));
            assertTrue(settings.isActivity());
            assertEquals(new ComponentName(context, WidgetAppearanceActivity.class), settings.getSavedIntent().getComponent());
            assertEquals(101, settings.getSavedIntent().getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1));

            // At the smallest supported width, all three actual RemoteViews hit areas remain usable.
            Bitmap small = providerIO().submit(() ->
                    VinylWidgetProvider.render(context, state, options, 180, 140)).get(20, TimeUnit.SECONDS);
            bounded(small);
            View host = VinylWidgetProvider.remoteViews(context, 101, state, options, small, 180)
                    .apply(context, new android.widget.FrameLayout(context));
            host.measure(View.MeasureSpec.makeMeasureSpec(180, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(140, View.MeasureSpec.EXACTLY));
            host.layout(0, 0, 180, 140);
            for (int id : new int[]{R.id.widget_previous, R.id.widget_play, R.id.widget_next, R.id.widget_configure}) {
                View button = host.findViewById(id);
                assertTrue("Small-widget transport target is narrower than 48dp", button.getWidth() >= 48);
                assertTrue("Small-widget transport target is shorter than 48dp", button.getHeight() >= 48);
            }
        }
        VinylWidgetProvider.Options transparent = new VinylWidgetProvider.Options(VinylWidgetProvider.COVER,
                VinylWidgetProvider.COLOR_ARTWORK, ThemeStore.COLORS[1], true);
        Bitmap clear = providerIO().submit(() ->
                VinylWidgetProvider.render(context, state, transparent, 900, 900)).get(20, TimeUnit.SECONDS);
        bounded(clear);
        assertEquals("Transparent background must retain actual alpha", 0, android.graphics.Color.alpha(clear.getPixel(0, 0)));

        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        ShadowAppWidgetManager shadowManager = Shadows.shadowOf(manager);
        AppWidgetProviderInfo provider = new AppWidgetProviderInfo();
        provider.provider = new ComponentName(context, VinylWidgetProvider.class);
        provider.configure = new ComponentName(context, WidgetAppearanceActivity.class);
        provider.initialLayout = R.layout.vinyl_widget;
        shadowManager.addBoundWidget(101, provider);
        shadowManager.addBoundWidget(202, provider);
        shadowManager.addBoundWidget(303, provider);
        VinylWidgetProvider.Options first = new VinylWidgetProvider.Options(VinylWidgetProvider.TURNTABLE,
                VinylWidgetProvider.COLOR_FIXED, 0xffc58d78, false);
        VinylWidgetProvider.Options second = new VinylWidgetProvider.Options(VinylWidgetProvider.RADIO,
                VinylWidgetProvider.COLOR_ARTWORK, 0xff748c80, true);
        save(context, 101, first);
        save(context, 202, second);
        assertEquals(first.signature(), VinylWidgetProvider.options(context, 101).signature());
        assertEquals(second.signature(), VinylWidgetProvider.options(context, 202).signature());
        assertNotNull(shadowManager.getViewFor(101));
        assertNotNull(shadowManager.getViewFor(202));
        save(context, AppWidgetManager.INVALID_APPWIDGET_ID, new VinylWidgetProvider.Options(
                VinylWidgetProvider.TURNTABLE, VinylWidgetProvider.COLOR_THEME, ThemeStore.COLORS[1], false));
        assertEquals("Changing defaults must not replace an existing instance's settings",
                first.signature(), VinylWidgetProvider.options(context, 101).signature());
        assertEquals(second.signature(), VinylWidgetProvider.options(context, 202).signature());

        ActivityController<WidgetAppearanceActivity> defaults = configure(context, AppWidgetManager.INVALID_APPWIDGET_ID);
        try {
            waitPreviews(defaults.get());
            assertEquals(Activity.RESULT_CANCELED, Shadows.shadowOf(defaults.get()).getResultCode());
            screenshot(defaults.get().getWindow().getDecorView(), 512, 726, "widget-configuration-api34.png");
            ScrollView scroller = scroll(defaults.get().getWindow().getDecorView());
            assertNotNull(scroller);
            scroller.scrollTo(0, scroller.getChildAt(0).getHeight());
            screenshot(defaults.get().getWindow().getDecorView(), 512, 726, "widget-configuration-options-api34.png");
        } finally { defaults.pause().stop().destroy(); }

        ActivityController<WidgetAppearanceActivity> initial = configure(context, 303);
        try {
            waitPreviews(initial.get());
            assertEquals("Unconfirmed initial configuration must be canceled", Activity.RESULT_CANCELED, Shadows.shadowOf(initial.get()).getResultCode());
            ((LinearLayout[])value(initial.get(), "cards"))[VinylWidgetProvider.COVER].performClick();
            ((TextView)value(initial.get(), "save")).performClick();
            drainProvider();
            assertEquals(Activity.RESULT_OK, Shadows.shadowOf(initial.get()).getResultCode());
            assertEquals(303, Shadows.shadowOf(initial.get()).getResultIntent().getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1));
            assertEquals(VinylWidgetProvider.COVER, VinylWidgetProvider.options(context, 303).style);
            assertNotNull("Initial setup must update the widget before reporting success", shadowManager.getViewFor(303));
        } finally { initial.pause().stop().destroy(); }

        ActivityController<WidgetAppearanceActivity> reconfigure = configure(context, 101);
        try {
            waitPreviews(reconfigure.get());
            assertEquals(first.style, field(WidgetAppearanceActivity.class, "style").getInt(reconfigure.get()));
            ((LinearLayout[])value(reconfigure.get(), "cards"))[VinylWidgetProvider.RECENT].performClick();
            ((TextView)value(reconfigure.get(), "save")).performClick();
            drainProvider();
            assertEquals(Activity.RESULT_OK, Shadows.shadowOf(reconfigure.get()).getResultCode());
            assertEquals(VinylWidgetProvider.RECENT, VinylWidgetProvider.options(context, 101).style);
            assertEquals("Reconfiguring one widget must leave the other untouched",
                    second.signature(), VinylWidgetProvider.options(context, 202).signature());
        } finally { reconfigure.pause().stop().destroy(); }
        ActivityController<WidgetAppearanceActivity> canceled = configure(context, 202);
        try {
            waitPreviews(canceled.get());
            ((LinearLayout[])value(canceled.get(), "cards"))[VinylWidgetProvider.TURNTABLE].performClick();
            canceled.get().finish();
            assertEquals(Activity.RESULT_CANCELED, Shadows.shadowOf(canceled.get()).getResultCode());
            assertEquals("Canceled reconfiguration must not write edited choices",
                    second.signature(), VinylWidgetProvider.options(context, 202).signature());
        } finally { canceled.pause().stop().destroy(); }
    }
}
