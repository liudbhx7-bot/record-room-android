package com.recordroom.vinyl;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.os.Looper;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.Shadows;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 34, qualifiers = "w512dp-h726dp-mdpi", fontScale = 1f)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class NativeRenderTest {
    private static final String PROJECT = System.getProperty("vinyl.project.dir");
    private static final String ASSETS = PROJECT + "/app/src/main/assets/demo/";
    private static final File OUTPUT = new File(System.getProperty("vinyl.output.dir"));
    private static void snapshot(View view, int width, int height, String name) throws Exception {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, width, height);
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(0xffd7867e);
        view.draw(canvas);
        try (FileOutputStream out = new FileOutputStream(new File(OUTPUT, name))) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); }
        bitmap.recycle();
        System.out.println("NATIVE_RENDER " + name + " " + view.getClass().getName() + " " + width + "x" + height);
    }
    private static ArrayList<ArrayList<Track>> albums() throws Exception {
        JSONArray values = new JSONArray(Files.readString(Path.of(ASSETS + "catalog.json")));
        LinkedHashMap<String, ArrayList<Track>> groups = new LinkedHashMap<>();
        for (int i = 0; i < values.length(); i++) {
            JSONObject o = values.getJSONObject(i);
            String art = o.getString("artworkUri").replace("asset:///demo/", "file://" + ASSETS);
            Track t = new Track(o.getString("id"), o.getString("title"), o.getString("artist"), o.getString("album"), o.getString("source"), o.getString("uri"), art, o.getLong("durationMs"), o.getInt("trackNumber"));
            groups.computeIfAbsent(t.albumKey(), k -> new ArrayList<>()).add(t);
        }
        return new ArrayList<>(groups.values());
    }
    @Test public void renderActualViews() throws Exception {
        OUTPUT.mkdirs();
        ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup().visible();
        Activity activity = controller.get();
        activity.setTheme(android.R.style.Theme_Material_NoActionBar);
        FrameLayout container = new FrameLayout(activity);
        activity.setContentView(container);
        Bitmap art = BitmapFactory.decodeFile(ASSETS + "night.jpg");
        TurntableView turntable = new TurntableView(activity);
        turntable.setStyle(2); turntable.setArtwork(art); turntable.setActive(false); turntable.setPlaying(true); turntable.setProgress(.12f);
        container.addView(turntable, new FrameLayout.LayoutParams(512, 540));
        snapshot(turntable, 512, 540, "turntable-splatter-playing-api34.png");
        turntable.setPlaying(false);
        snapshot(turntable, 512, 540, "turntable-splatter-paused-api34.png");
        turntable.setStyle(0); turntable.setPlaying(true);
        snapshot(turntable, 512, 540, "turntable-black-playing-api34.png");
        container.removeAllViews();
        AlbumStackView stack = new AlbumStackView(activity);
        stack.setAlbums(albums());
        container.addView(stack, new FrameLayout.LayoutParams(512, 620));
        container.measure(View.MeasureSpec.makeMeasureSpec(512, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(620, View.MeasureSpec.EXACTLY));
        container.layout(0,0,512,620);
        for(int i=0;i<24;i++) { Thread.sleep(50); Shadows.shadowOf(Looper.getMainLooper()).idle(); }
        snapshot(stack, 512, 620, "album-stack-three-api34.png");
        container.removeAllViews();
        LinearLayout controls = new LinearLayout(activity); controls.setOrientation(LinearLayout.HORIZONTAL); controls.setPadding(24,0,24,0);
        controls.addView(new PlayerControls.Hardware(activity,"play"), new LinearLayout.LayoutParams(66,80));
        controls.addView(new View(activity), new LinearLayout.LayoutParams(0,80,1));
        controls.addView(new PlayerControls.Hardware(activity,"previous"), new LinearLayout.LayoutParams(66,80));
        View gap=new View(activity);controls.addView(gap,new LinearLayout.LayoutParams(14,80));
        controls.addView(new PlayerControls.Hardware(activity,"next"), new LinearLayout.LayoutParams(66,80));
        container.addView(controls,new FrameLayout.LayoutParams(512,80));
        snapshot(controls,512,80,"hardware-controls-api34.png");
        controller.pause().stop().destroy();
    }
}
