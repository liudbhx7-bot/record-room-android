package com.recordroom.vinyl;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import static org.junit.Assert.*;

/** Exercises the actual native View and its public gesture callback contract. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "w512dp-h726dp-mdpi", fontScale = 1f)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public class TurntableInteractionTest {
    private static final int WIDTH = 512, STAGE_HEIGHT = 540, CORAL = 0xffd7867e;
    private static final String[] MATERIAL_NAMES = {
            "black", "translucent-purple", "purple-splatter", "purple-cloud",
            "purple-swirl", "blue-splatter", "green-marble", "violet-splatter", "smoke-lilac"
    };
    private ActivityController<Activity> controller;
    private Activity activity;
    private FrameLayout container;

    @Before public void createNativeActivity() {
        controller = Robolectric.buildActivity(Activity.class);
        activity = controller.get();
        activity.setTheme(android.R.style.Theme_Material_Light_NoActionBar);
        controller.setup().visible();
        container = new FrameLayout(activity);
        container.setBackgroundColor(CORAL);
        activity.setContentView(container);
        layout(container, WIDTH, 726);
    }

    @After public void destroyNativeActivity() {
        if (controller != null) controller.pause().stop().destroy();
    }

    @Test public void needleDragSeeksPlaysParksAndRespectsReducedMotion() throws Exception {
        TurntableView turntable = attachTurntable();
        turntable.setActive(true);
        turntable.setMotionEnabled(true);
        turntable.setPlaying(false);
        turntable.setProgress(0);
        CallbackRecorder callbacks = new CallbackRecorder(turntable);
        turntable.setNeedleListener(callbacks);

        // The actual arm starts vertical. Move its shaft around the real pivot to 23 degrees.
        // The 14–32 degree groove range places 23 degrees at exactly half the track.
        dragArm(turntable, 0, 23);
        assertEquals(Arrays.asList("pause", "seek", "play"), callbacks.events);
        assertEquals(1, callbacks.positions.size());
        assertEquals(.5f, callbacks.positions.get(0), .015f);
        assertTrue((Boolean) field(turntable, "playing"));
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600));
        assertEquals(23f, number(turntable, "armAngle"), .08f);
        System.out.println("NEEDLE_GESTURE parked_to_middle events=" + callbacks.events
                + " progress=" + callbacks.positions.get(0));

        // Returning a playing arm to its rest must not accidentally seek or restart playback.
        callbacks.clear();
        dragArm(turntable, 23, 0);
        assertTrue("Lifting or parking must pause", callbacks.events.contains("pause"));
        assertFalse("Parking must not seek", callbacks.events.contains("seek"));
        assertFalse("Parking must not play", callbacks.events.contains("play"));
        assertTrue(callbacks.positions.isEmpty());
        assertFalse((Boolean) field(turntable, "playing"));
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600));
        assertEquals(0f, number(turntable, "armAngle"), .08f);
        assertNull(field(turntable, "spinAnimator"));
        System.out.println("NEEDLE_GESTURE playing_to_rest events=" + callbacks.events);

        // Reduced motion suppresses automatic motion, while deliberate direct manipulation works.
        callbacks.clear();
        turntable.setMotionEnabled(false);
        turntable.setProgress(0);
        dragArm(turntable, 0, 23);
        assertEquals(Arrays.asList("pause", "seek", "play"), callbacks.events);
        assertEquals(.5f, callbacks.positions.get(0), .015f);
        assertEquals(23f, number(turntable, "armAngle"), .08f);
        assertNull("Reduced motion must not run the platter animator", field(turntable, "spinAnimator"));
        assertNull("Reduced motion must not run the arm animator", field(turntable, "armAnimator"));
        float rotation = number(turntable, "rotation");
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1500));
        assertEquals("The platter must stay still", rotation, number(turntable, "rotation"), .001f);
        System.out.println("NEEDLE_GESTURE reduced_motion events=" + callbacks.events
                + " progress=" + callbacks.positions.get(0) + " rotation=" + rotation);
    }

    @Test public void needleReleaseUsesFinalPointerPosition() throws Exception {
        TurntableView turntable = attachTurntable();
        turntable.setActive(true);
        turntable.setMotionEnabled(false);
        CallbackRecorder callbacks = new CallbackRecorder(turntable);
        turntable.setNeedleListener(callbacks);
        float px = number(turntable, "pivotX"), py = number(turntable, "pivotY");
        float length = number(turntable, "radius") * .98f;
        long down = SystemClock.uptimeMillis();

        // The finger travels over the record, then releases back on the rest without
        // an intervening MOVE. A stale MOVE must not seek or restart playback.
        send(turntable, down, down, MotionEvent.ACTION_DOWN, px, py + length);
        send(turntable, down, down + 16, MotionEvent.ACTION_MOVE,
                px - sine(23) * length, py + cosine(23) * length);
        send(turntable, down, down + 32, MotionEvent.ACTION_UP, px, py + length);
        assertTrue(callbacks.events.contains("pause"));
        assertFalse("Releasing on the rest must not seek", callbacks.events.contains("seek"));
        assertFalse("Releasing on the rest must not restart playback", callbacks.events.contains("play"));
        assertEquals(0f, number(turntable, "armAngle"), .08f);

        // Likewise, the final on-disc position wins over the previous outer groove sample.
        callbacks.clear(); down += 64;
        send(turntable, down, down, MotionEvent.ACTION_DOWN, px, py + length);
        send(turntable, down, down + 16, MotionEvent.ACTION_MOVE,
                px - sine(14) * length, py + cosine(14) * length);
        send(turntable, down, down + 32, MotionEvent.ACTION_UP,
                px - sine(23) * length, py + cosine(23) * length);
        assertEquals(Arrays.asList("pause", "seek", "play"), callbacks.events);
        assertEquals(.5f, callbacks.positions.get(0), .015f);

        // A short gesture can deliver only DOWN and UP; it must still locate the track.
        turntable.setPlaying(false); turntable.setProgress(0); callbacks.clear(); down += 64;
        send(turntable, down, down, MotionEvent.ACTION_DOWN, px, py + length);
        send(turntable, down, down + 16, MotionEvent.ACTION_UP,
                px - sine(23) * length, py + cosine(23) * length);
        assertEquals(Arrays.asList("pause", "seek", "play"), callbacks.events);
        assertEquals(.5f, callbacks.positions.get(0), .015f);
    }

    @Test public void renderAllNineRealMaterialsAndSelectedSwatches() throws Exception {
        File output = outputDirectory();
        TurntableView turntable = attachTurntable();
        turntable.setActive(false);
        turntable.setMotionEnabled(false);
        turntable.setPlaying(true);
        turntable.setProgress(.35f);
        try (InputStream input = activity.getAssets().open("demo/night-v2.png")) {
            Bitmap cover = BitmapFactory.decodeStream(input);
            assertNotNull("The real generated demo cover must load from app assets", cover);
            turntable.setArtwork(cover);
        }
        assertEquals("All generated record variants must be exercised", 9, DiscMaterials.COUNT);
        for (int style = 0; style < MATERIAL_NAMES.length; style++) {
            turntable.setStyle(style);
            layout(turntable, WIDTH, STAGE_HEIGHT);
            awaitMaterials();
            Bitmap material = (Bitmap) field(turntable, "recordTexture");
            assertRealBitmap("Main material " + style, material);
            assertNotNull("Main view must use its decoded material shader", field(turntable, "materialShader"));
            assertEquals(style, ((Number) field(turntable, "disc")).intValue());
            writeSnapshot(turntable, WIDTH, STAGE_HEIGHT, output,
                    "turntable-material-" + style + "-" + MATERIAL_NAMES[style] + "-api34.png");
            System.out.println("MATERIAL_DECODE style=" + style + " source=" + MATERIAL_NAMES[style]
                    + " decoded=" + material.getWidth() + "x" + material.getHeight()
                    + " bytes=" + material.getAllocationByteCount());
        }

        container.removeAllViews();
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(8, 8, 8, 8);
        row.setBackgroundColor(CORAL);
        DiscSwatchView[] swatches = new DiscSwatchView[9];
        for (int style = 0; style < swatches.length; style++) {
            DiscSwatchView swatch = new DiscSwatchView(activity, style);
            swatch.setSelected(style == 2);
            row.addView(swatch, new LinearLayout.LayoutParams(0, 80, 1));
            swatches[style] = swatch;
        }
        container.addView(row, new FrameLayout.LayoutParams(WIDTH, 96));
        layout(row, WIDTH, 96);
        awaitMaterials();
        for (int style = 0; style < swatches.length; style++) {
            assertRealBitmap("Swatch " + style, (Bitmap) field(swatches[style], "bitmap"));
            assertNotNull("Swatch must render its real material", field(swatches[style], "shader"));
            assertEquals(style == 2, swatches[style].isSelected());
        }
        AccessibilityNodeInfo selected = swatches[2].createAccessibilityNodeInfo();
        assertTrue("Selected material must be exposed as checkable", selected.isCheckable());
        assertTrue("Selected material must be announced as checked", selected.isChecked());
        selected.recycle();
        writeSnapshot(row, WIDTH, 96, output, "disc-materials-nine-swatches-api34.png");
    }

    private TurntableView attachTurntable() {
        TurntableView turntable = new TurntableView(activity);
        container.addView(turntable, new FrameLayout.LayoutParams(WIDTH, STAGE_HEIGHT));
        layout(container, WIDTH, 726);
        layout(turntable, WIDTH, STAGE_HEIGHT);
        return turntable;
    }

    /** Sends real Android events at a shaft point, not direct calls to gesture implementation methods. */
    private static void dragArm(TurntableView turntable, float startDegrees, float endDegrees) throws Exception {
        float px = number(turntable, "pivotX"), py = number(turntable, "pivotY");
        float length = number(turntable, "radius") * .98f;
        long down = SystemClock.uptimeMillis();
        send(turntable, down, down, MotionEvent.ACTION_DOWN,
                px - sine(startDegrees) * length, py + cosine(startDegrees) * length);
        float middle = (startDegrees + endDegrees) / 2;
        send(turntable, down, down + 16, MotionEvent.ACTION_MOVE,
                px - sine(middle) * length, py + cosine(middle) * length);
        send(turntable, down, down + 32, MotionEvent.ACTION_MOVE,
                px - sine(endDegrees) * length, py + cosine(endDegrees) * length);
        send(turntable, down, down + 48, MotionEvent.ACTION_UP,
                px - sine(endDegrees) * length, py + cosine(endDegrees) * length);
    }

    private static void send(View view, long down, long at, int action, float x, float y) {
        MotionEvent event = MotionEvent.obtain(down, at, action, x, y, 0);
        try { assertTrue("The actual View must consume the needle gesture", view.dispatchTouchEvent(event)); }
        finally { event.recycle(); }
    }
    private static float sine(float degrees) { return (float) Math.sin(Math.toRadians(degrees)); }
    private static float cosine(float degrees) { return (float) Math.cos(Math.toRadians(degrees)); }

    private static void awaitMaterials() throws Exception {
        Field io = DiscMaterials.class.getDeclaredField("IO");
        io.setAccessible(true);
        ((ExecutorService) io.get(null)).submit(() -> {}).get(30, TimeUnit.SECONDS);
        Field approved = ApprovedArtwork.class.getDeclaredField("IO");approved.setAccessible(true);
        ((ExecutorService) approved.get(null)).submit(() -> {}).get(30, TimeUnit.SECONDS);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }
    private static void assertRealBitmap(String label, Bitmap bitmap) {
        assertNotNull(label + " must come from decoded app assets, not a placeholder", bitmap);
        assertFalse(label + " must remain usable", bitmap.isRecycled());
        assertTrue(label + " must contain pixels", bitmap.getWidth() > 0 && bitmap.getHeight() > 0);
        assertTrue(label + " must preserve source alpha", bitmap.hasAlpha());
    }
    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }
    private static float number(Object target, String name) throws Exception { return ((Number) field(target, name)).floatValue(); }
    private static void layout(View view, int width, int height) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, width, height);
    }
    private static File outputDirectory() {
        String value = System.getProperty("vinyl.output.dir");
        assertNotNull("The portable runner must set vinyl.output.dir", value);
        File directory = new File(value);
        assertTrue("Could not create native render output directory", directory.isDirectory() || directory.mkdirs());
        return directory;
    }
    private static void writeSnapshot(View view, int width, int height, File directory, String name) throws Exception {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(CORAL);
        view.draw(canvas);
        try (FileOutputStream output = new FileOutputStream(new File(directory, name))) {
            assertTrue("PNG export failed", bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
        } finally { bitmap.recycle(); }
        System.out.println("NATIVE_MATERIAL_RENDER " + name + " " + width + "x" + height);
    }

    /** The callback updates the View just as a real service-state observer would. */
    private static final class CallbackRecorder implements TurntableView.NeedleListener {
        final TurntableView view;
        final List<String> events = new ArrayList<>();
        final List<Float> positions = new ArrayList<>();
        CallbackRecorder(TurntableView view) { this.view = view; }
        @Override public void onNeedleSeek(float progress) {
            events.add("seek"); positions.add(progress); view.setProgress(progress);
        }
        @Override public void onNeedlePlay() { events.add("play"); view.setPlaying(true); }
        @Override public void onNeedlePause() { events.add("pause"); view.setPlaying(false); }
        void clear() { events.clear(); positions.clear(); }
    }
}
