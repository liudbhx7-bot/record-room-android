package com.recordroom.vinyl;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Color;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.LruCache;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Shared, size-bounded decoding for the original record material PNGs. */
final class DiscMaterials {
    static final int COUNT = 9;
    static final String[] NAMES = {
            "经典黑胶", "透明紫", "奶油紫泼墨", "紫色云纹", "紫色旋涡", "蓝色玻璃泼墨", "青绿大理石", "紫蓝泼墨", "烟紫晶胶"
    };
    private static final String[] FILES = {
            "disc-classic-black-v1.png", "disc-translucent-purple-v1.png",
            "disc-cream-purple-splatter-v2.png", "disc-purple-cloud-v1.png",
            "disc-purple-swirl-v1.png", "disc-blue-radial-v2.png", "disc-green-marble-v1.png",
            "disc-purple-splatter-v1.png", "disc-smoke-lilac-v1.png"
    };
    // A shared inner circle excludes generated cutout fringes without editing the source PNGs.
    // The generated outer rims are approximately .49 of the image width from the centre.
    private static final float SOURCE_RADIUS = .46f;
    private static final int[] ALPHAS = {255, 184, 244, 202, 186, 255, 220, 235, 255};
    private static final int[] COLORS = {
            0xff0b0c0e, 0xff9c8ab5, 0xffe9ddd5, 0xff6b597f,
            0xffc6b7d5, 0xff7396aa, 0xff9ab765, 0xff82708f, 0xffb9a9ca
    };
    private static final Object LOCK = new Object();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(20 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap bitmap) { return bitmap.getAllocationByteCount(); }
    };
    private static final HashMap<String, ArrayList<Request>> PENDING = new HashMap<>();
    // One decoder keeps transient full-resolution allocations bounded when every swatch is visible.
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "vinyl-materials");
        thread.setDaemon(true);
        return thread;
    });

    interface Callback { void onLoaded(Bitmap bitmap); }

    static final class Request {
        private volatile Callback callback;
        Request(Callback callback) { this.callback = callback; }
        void cancel() { callback = null; }
        boolean isActive() { return callback != null; }
        void deliver(Bitmap bitmap) {
            MAIN.post(() -> {
                Callback current = callback;
                callback = null;
                if (current != null) current.onLoaded(bitmap);
            });
        }
    }

    private DiscMaterials() { }
    static String assetPath(int style) { int normalized=normalize(style);return normalized==8?ApprovedArtwork.SMOKE_DISC:"materials/"+FILES[normalized]; }
    static int normalize(int style) { return Math.max(0, Math.min(COUNT - 1, style)); }
    static int alpha(int style) { return ALPHAS[normalize(style)]; }
    static int color(int style) { return COLORS[normalize(style)]; }

    /** Preserve photographed grooves and luminance while following the current sleeve palette. */
    static ColorMatrixColorFilter tintFilter(int tint) {
        float[] matrix = new float[20];
        int[] channels = {Color.red(tint), Color.green(tint), Color.blue(tint)};
        float[] luminance = {.2126f, .7152f, .0722f};
        for (int row = 0; row < 3; row++) {
            float strength = .58f * channels[row] / 255f;
            for (int column = 0; column < 3; column++)
                matrix[row * 5 + column] = strength * luminance[column] + (row == column ? .08f : 0);
            matrix[row * 5 + 4] = .34f * channels[row];
        }
        matrix[18] = 1;
        return new ColorMatrixColorFilter(matrix);
    }

    /** Native fallback uses the same transform as the bitmap, never the cover image. */
    static int tintedColor(int style, int tint) {
        int source = color(style);
        float luminance = .2126f * Color.red(source) + .7152f * Color.green(source) + .0722f * Color.blue(source);
        return Color.rgb(tintChannel(Color.red(source), Color.red(tint), luminance),
                tintChannel(Color.green(source), Color.green(tint), luminance),
                tintChannel(Color.blue(source), Color.blue(tint), luminance));
    }
    private static int tintChannel(int source, int tint, float luminance) {
        return Math.round(.08f * source + .58f * tint / 255f * luminance + .34f * tint);
    }

    static int sizeForDiameter(int diameter) {
        int required = (int) Math.ceil(Math.max(1, diameter) / (SOURCE_RADIUS * 2f));
        return Math.min(1280, Math.max(128, ((required + 127) / 128) * 128));
    }

    static Request load(Context context, int style, int visibleDiameter, Callback callback) {
        final int material = normalize(style), edge = sizeForDiameter(visibleDiameter);
        final String key = material + ":" + edge;
        Request request = new Request(callback);
        Bitmap cached;
        boolean schedule = false;
        synchronized (LOCK) {
            cached = CACHE.get(key);
            if (cached == null) {
                ArrayList<Request> waiting = PENDING.get(key);
                if (waiting == null) {
                    waiting = new ArrayList<>();
                    PENDING.put(key, waiting);
                    schedule = true;
                }
                waiting.add(request);
            }
        }
        if (cached != null) request.deliver(cached);
        if (schedule) {
            Context app = context.getApplicationContext();
            IO.execute(() -> decodeAndDeliver(app, material, edge, key));
        }
        return request;
    }

    private static void decodeAndDeliver(Context context, int style, int edge, String key) {
        synchronized (LOCK) {
            ArrayList<Request> waiting = PENDING.get(key);
            boolean needed = false;
            if (waiting != null) for (Request request : waiting) needed |= request.isActive();
            if (!needed) { PENDING.remove(key); return; }
        }
        Bitmap bitmap = null;
        try {
            String file = assetPath(style);
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream input = context.getAssets().open(file)) { BitmapFactory.decodeStream(input, null, bounds); }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new IOException("Invalid material dimensions");
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            options.inSampleSize = 1;
            while (Math.min(bounds.outWidth, bounds.outHeight) / (options.inSampleSize * 2) >= edge) options.inSampleSize *= 2;
            try (InputStream input = context.getAssets().open(file)) { bitmap = BitmapFactory.decodeStream(input, null, options); }
            if (bitmap != null && Math.max(bitmap.getWidth(), bitmap.getHeight()) > edge) {
                float scale = edge / (float) Math.max(bitmap.getWidth(), bitmap.getHeight());
                Bitmap smaller = Bitmap.createScaledBitmap(bitmap, Math.max(1, Math.round(bitmap.getWidth() * scale)),
                        Math.max(1, Math.round(bitmap.getHeight() * scale)), true);
                // This full-size intermediate has never been exposed to a View or display list.
                if (smaller != bitmap) bitmap.recycle();
                bitmap = smaller;
            }
        } catch (IOException | RuntimeException error) {
            Log.w("DiscMaterials", "Could not decode record material " + style, error);
        } catch (OutOfMemoryError error) {
            Log.w("DiscMaterials", "Insufficient memory for record material " + style);
            bitmap = null;
        }
        ArrayList<Request> waiting;
        synchronized (LOCK) {
            if (bitmap != null) CACHE.put(key, bitmap);
            waiting = PENDING.remove(key);
        }
        if (waiting != null) for (Request request : waiting) if (request.isActive()) request.deliver(bitmap);
    }

    /** Both the large record and miniature buttons use precisely the same source crop. */
    static void place(Bitmap bitmap, BitmapShader shader, Matrix matrix, float x, float y, float radius) {
        float scale = radius / (Math.min(bitmap.getWidth(), bitmap.getHeight()) * SOURCE_RADIUS);
        matrix.reset();
        matrix.setScale(scale, scale);
        matrix.postTranslate(x - bitmap.getWidth() * scale / 2f, y - bitmap.getHeight() * scale / 2f);
        shader.setLocalMatrix(matrix);
    }
}
