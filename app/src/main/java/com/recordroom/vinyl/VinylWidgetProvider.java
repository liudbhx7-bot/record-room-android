package com.recordroom.vinyl;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.LruCache;
import android.view.View;
import android.widget.RemoteViews;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/** Four static, state-driven home-screen surfaces for this app's own player. */
public class VinylWidgetProvider extends AppWidgetProvider {
    static final int TURNTABLE = 0, COVER = 1, RADIO = 2, RECENT = 3;
    static final int COLOR_THEME = 0, COLOR_ARTWORK = 1, COLOR_FIXED = 2;
    static final String[] STYLE_NAMES = {"黑胶唱机", "专辑封面", "复古收音机", "最近播放"};
    static final String[] STYLE_DETAILS = {"唱片与唱臂，跟随播放状态", "把正在听的专辑放在桌面", "音轨显示窗与实体按键", "当前音乐和最近听过的唱片"};
    private static final String PREFS = "vinyl_widgets";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> new Thread(r, "vinyl-widget"));
    private static final AtomicLong SERIAL = new AtomicLong();
    private static final Map<Integer, Long> GENERATIONS = new ConcurrentHashMap<>();
    private static final Map<Integer, String> LAST_RENDER = new HashMap<>(); // IO thread only
    private static final LruCache<String, Bitmap> IMAGES = new LruCache<String, Bitmap>(8 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap bitmap) { return bitmap.getAllocationByteCount(); }
    };
    private static final LruCache<String, PaletteEntry> PALETTES = new LruCache<>(32);
    private static final class PaletteEntry {
        final WeakReference<Bitmap> bitmap;
        final int generation;
        final AlbumPalette palette;
        PaletteEntry(Bitmap bitmap,AlbumPalette palette){this.bitmap=new WeakReference<>(bitmap);generation=bitmap.getGenerationId();this.palette=palette;}
    }
    private static Runnable pendingUpdate;

    static final class Options {
        final int style, colorMode, color;
        final boolean transparent;
        Options(int style, int colorMode, int color, boolean transparent) {
            this.style = Math.max(TURNTABLE, Math.min(RECENT, style));
            this.colorMode = Math.max(COLOR_THEME, Math.min(COLOR_FIXED, colorMode));
            this.color = color | 0xff000000;
            this.transparent = transparent;
        }
        Options withStyle(int value) { return new Options(value, colorMode, color, transparent); }
        String signature() { return style + ":" + colorMode + ":" + color + ":" + transparent; }
    }

    interface Completion { void finished(boolean success); }

    static Options options(Context context, int id) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String key = id == AppWidgetManager.INVALID_APPWIDGET_ID || !prefs.getBoolean(id + ":saved", false) ? "default:" : id + ":";
        return new Options(prefs.getInt(key + "style", TURNTABLE), prefs.getInt(key + "colorMode", COLOR_THEME),
                prefs.getInt(key + "color", ThemeStore.COLORS[1]), prefs.getBoolean(key + "transparent", false));
    }

    static boolean configured(Context context, int id) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(id + ":saved", false);
    }

    private static boolean store(Context context, int id, Options options) {
        String key = id == AppWidgetManager.INVALID_APPWIDGET_ID ? "default:" : id + ":";
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt(key + "style", options.style).putInt(key + "colorMode", options.colorMode)
                .putInt(key + "color", options.color).putBoolean(key + "transparent", options.transparent)
                .putBoolean(key + "saved", true).commit();
    }

    /** Configuration completes only after this instance has received its first RemoteViews. */
    static void saveAndUpdate(Context context, int id, Options options, Completion completion) {
        Context app = context.getApplicationContext();
        MAIN.post(() -> {
            Track current = PlayerService.widgetTrack(app);
            boolean playing = PlayerService.widgetPlaying();
            long serial = SERIAL.incrementAndGet();
            if (id != AppWidgetManager.INVALID_APPWIDGET_ID) GENERATIONS.put(id, serial);
            IO.execute(() -> {
                boolean success = false;
                try {
                    success = store(app, id, options);
                    if (success && id != AppWidgetManager.INVALID_APPWIDGET_ID) {
                        Snapshot snapshot = snapshot(app, current, playing, false);
                        success = push(app, AppWidgetManager.getInstance(app), id, snapshot, options, serial, true);
                    }
                } catch (RuntimeException ignored) { success = false; }
                final boolean result = success;
                if (completion != null) MAIN.post(() -> completion.finished(result));
            });
        });
    }

    @Override public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        PendingResult result = goAsync();
        requestUpdate(context, ids, true, () -> { if (result != null) result.finish(); });
    }

    @Override public void onAppWidgetOptionsChanged(Context context, AppWidgetManager manager, int id, Bundle options) {
        PendingResult result = goAsync();
        requestUpdate(context, new int[]{id}, true, () -> { if (result != null) result.finish(); });
    }

    @Override public void onDeleted(Context context, int[] ids) {
        Context app = context.getApplicationContext();
        for (int id : ids) GENERATIONS.remove(id);
        IO.execute(() -> {
            SharedPreferences.Editor edit = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
            for (int id : ids) {
                String key = id + ":";
                edit.remove(key + "style").remove(key + "colorMode").remove(key + "color")
                        .remove(key + "transparent").remove(key + "saved");
                LAST_RENDER.remove(id);
            }
            edit.apply();
        });
    }

    /** Called by PlayerService; repeated volume/state changes are coalesced, never frame-driven. */
    public static void updateAll(Context context) {
        Context app = context.getApplicationContext();
        MAIN.post(() -> {
            if (pendingUpdate != null) MAIN.removeCallbacks(pendingUpdate);
            pendingUpdate = () -> {
                pendingUpdate = null;
                AppWidgetManager manager = AppWidgetManager.getInstance(app);
                int[] ids = manager.getAppWidgetIds(new ComponentName(app, VinylWidgetProvider.class));
                if (ids.length > 0) requestUpdate(app, ids, false, null);
            };
            MAIN.postDelayed(pendingUpdate, 120);
        });
    }

    private static void requestUpdate(Context context, int[] ids, boolean force, Runnable done) {
        Context app = context.getApplicationContext();
        int[] targets = ids == null ? new int[0] : ids.clone();
        Runnable capture = () -> {
            Track current = PlayerService.widgetTrack(app);
            boolean playing = PlayerService.widgetPlaying();
            long serial = SERIAL.incrementAndGet();
            for (int id : targets) GENERATIONS.put(id, serial);
            IO.execute(() -> {
                try {
                    if (targets.length == 0) return;
                    Snapshot snapshot = snapshot(app, current, playing, false);
                    AppWidgetManager manager = AppWidgetManager.getInstance(app);
                    for (int id : targets) {
                        if (!Long.valueOf(serial).equals(GENERATIONS.get(id))) continue;
                        Options choice = options(app, id);
                        if (!configured(app, id)) store(app, id, choice);
                        try { push(app, manager, id, snapshot, choice, serial, force); }
                        catch (RuntimeException ignored) { /* A host can delete a widget while its update is in flight. */ }
                    }
                } finally { if (done != null) done.run(); }
            });
        };
        if (Looper.myLooper() == Looper.getMainLooper()) capture.run(); else MAIN.post(capture);
    }

    private static boolean push(Context app, AppWidgetManager manager, int id, Snapshot snapshot, Options choice, long serial, boolean force) {
        if (!force && !Long.valueOf(serial).equals(GENERATIONS.get(id))) return false;
        AppWidgetProviderInfo info = manager.getAppWidgetInfo(id);
        if (info == null || !new ComponentName(app, VinylWidgetProvider.class).equals(info.provider)) return false;
        Bundle size = manager.getAppWidgetOptions(id);
        boolean landscape = app.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        int width = size.getInt(landscape ? AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH : AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 320);
        int height = size.getInt(landscape ? AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT : AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 180);
        width = Math.max(180, Math.min(640, width > 0 ? width : 320));
        height = Math.max(140, Math.min(640, height > 0 ? height : 180));
        String signature = snapshot.signature + ":" + choice.signature() + ":" + width + ":" + height;
        if (!force && signature.equals(LAST_RENDER.get(id))) return true;
        Bitmap image = render(app, snapshot, choice, width, height);
        if (!force && !Long.valueOf(serial).equals(GENERATIONS.get(id))) return false;
        RemoteViews views = remoteViews(app, id, snapshot, choice, image, width);
        manager.updateAppWidget(id, views); // Full replacement releases the prior image in the host.
        LAST_RENDER.put(id, signature);
        return true;
    }

    static RemoteViews remoteViews(Context context, int id, Snapshot snapshot, Options choice, Bitmap bitmap, int widthDp) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.vinyl_widget);
        views.setImageViewBitmap(R.id.widget_art, bitmap);
        views.setViewVisibility(R.id.widget_speaker, choice.style >= RADIO && widthDp >= 224 ? View.VISIBLE : View.GONE);
        Intent open = new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openIntent = PendingIntent.getActivity(context, 210, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.widget_open, openIntent);
        views.setOnClickPendingIntent(R.id.widget_main, openIntent);
        views.setContentDescription(R.id.widget_main, STYLE_NAMES[choice.style] + "。" + snapshot.title + "，" + snapshot.artist + "。打开唱片机");
        boolean hasTrack = snapshot.current != null;
        views.setOnClickPendingIntent(R.id.widget_previous, hasTrack ? PlayerService.transportIntent(context, PlayerService.ACTION_PREVIOUS, 213) : openIntent);
        views.setOnClickPendingIntent(R.id.widget_play, hasTrack ? PlayerService.transportIntent(context, PlayerService.ACTION_TOGGLE, 211) : openIntent);
        views.setOnClickPendingIntent(R.id.widget_next, hasTrack ? PlayerService.transportIntent(context, PlayerService.ACTION_NEXT, 212) : openIntent);
        views.setContentDescription(R.id.widget_previous, hasTrack ? "上一首" : "打开应用选择歌曲");
        views.setContentDescription(R.id.widget_play, hasTrack ? (snapshot.playing ? "暂停" : "播放") : "打开应用选择歌曲");
        views.setContentDescription(R.id.widget_next, hasTrack ? "下一首" : "打开应用选择歌曲");
        Intent configure = new Intent(context, WidgetAppearanceActivity.class)
                .setAction(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE)
                .setData(android.net.Uri.parse("recordroom://widget/" + id))
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id);
        PendingIntent configuration = PendingIntent.getActivity(context, id, configure, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.widget_configure, configuration);
        views.setContentDescription(R.id.widget_configure, "更换小组件样式");
        return views;
    }

    static final class Snapshot {
        final Track current;
        final boolean playing;
        final String title, artist, signature;
        final Bitmap cover, disc;
        final int color, discStyle, recordTint;
        final AlbumPalette artworkPalette;
        final float angle;
        final List<Bitmap> recentCovers;
        final int recentCount;
        Snapshot(Track current, boolean playing, Bitmap cover, Bitmap disc, int color, int discStyle, int recordTint, AlbumPalette artworkPalette, float angle,
                 List<Bitmap> recentCovers, int recentCount, String signature) {
            this.current = current; this.playing = playing; this.cover = cover; this.disc = disc; this.color = color; this.discStyle = discStyle; this.recordTint=recordTint;this.artworkPalette=artworkPalette;this.angle = angle;
            this.recentCovers = recentCovers; this.recentCount = recentCount; this.signature = signature;
            title = current == null ? "放上一张唱片" : current.title;
            artist = current == null ? "打开唱片机，开始聆听" : current.artist;
        }
    }

    /** Must run on an IO thread: decoding never occurs in BroadcastReceiver or Activity callbacks. */
    static Snapshot snapshot(Context context, Track current, boolean playing, boolean preview) {
        loadImage(context,"design/paper-ivory-fiber-v1.png",true);
        ThemeStore themes = new ThemeStore(context);
        List<Track> recent = new LibraryStore(context).recent();
        if (preview && current == null) {
            List<Track> demos = DemoCatalog.load(context);
            if (!demos.isEmpty()) current = demos.get(0);
            if (recent.isEmpty()) recent = demos;
        }
        String album = current == null ? null : current.albumKey();
        String coverUri = coverUri(themes, current);
        Bitmap cover = loadImage(context, coverUri, false);
        AlbumPalette palette=paletteFor(coverUri,cover);
        if(palette!=null)themes.coverPalette(album,palette);
        int discStyle = DiscMaterials.normalize(themes.disc(album));
        Bitmap disc = loadImage(context, DiscMaterials.assetPath(discStyle), true);
        int color = themes.background(album);
        int recordTint=themes.recordTint(album);
        long position = context.getSharedPreferences(PlayerService.PREFS, Context.MODE_PRIVATE).getLong("position", 0);
        float angle = themes.motion() ? (position % 10000) * .036f : 0;
        ArrayList<Bitmap> recentCovers = new ArrayList<>();
        HashSet<String> albums = new HashSet<>();
        StringBuilder key = new StringBuilder(current == null ? "empty" : current.source + ":" + current.id + ":" + current.title + ":" + current.artist);
        key.append(':').append(coverUri).append(':').append(cover==null?0:cover.getGenerationId()).append(':').append(discStyle).append(':').append(color).append(':').append(recordTint).append(':').append(palette==null?0:palette.background).append(':').append(palette==null?0:palette.record).append(':').append(playing).append(':').append(Math.round(angle));
        if (current != null) albums.add(current.albumKey());
        for (Track item : recent) {
            if (recentCovers.size() >= 4) break;
            if (item == null || !albums.add(item.albumKey())) continue;
            String uri = coverUri(themes, item);
            recentCovers.add(loadImage(context, uri, false));
            key.append('|').append(item.albumKey()).append(':').append(uri);
        }
        return new Snapshot(current, playing, cover, disc, color, discStyle, recordTint,palette,angle, recentCovers, recentCovers.size(), key.toString());
    }

    /** Called only while preparing a snapshot, never by render or each playback tick. */
    static AlbumPalette paletteFor(String uri,Bitmap cover){
        if(cover==null||cover.isRecycled())return null;
        synchronized(PALETTES){
            PaletteEntry cached=PALETTES.get(uri);
            if(cached!=null&&cached.bitmap.get()==cover&&cached.generation==cover.getGenerationId())return cached.palette;
            AlbumPalette palette=AlbumPalette.from(cover);PALETTES.put(uri,new PaletteEntry(cover,palette));return palette;
        }
    }

    private static String coverUri(ThemeStore themes, Track track) {
        if (track == null) return "";
        String override = themes.artwork(track.albumKey());
        return override.isEmpty() ? track.artworkUri : override;
    }

    private static Bitmap loadImage(Context context, String uri, boolean asset) {
        if (uri == null || uri.isEmpty()) return null;
        String key = (asset ? "asset:" : "art:") + uri;
        Bitmap cached = IMAGES.get(key);
        if (cached != null && !cached.isRecycled()) return cached;
        Bitmap image = null;
        try {
            if (asset) {
                BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
                try (InputStream input = context.getAssets().open(uri)) { BitmapFactory.decodeStream(input, null, bounds); }
                BitmapFactory.Options decode = new BitmapFactory.Options();
                int edge = Math.max(bounds.outWidth, bounds.outHeight); decode.inSampleSize = 1;
                while (edge / decode.inSampleSize > 512) decode.inSampleSize *= 2;
                try (InputStream input = context.getAssets().open(uri)) { image = BitmapFactory.decodeStream(input, null, decode); }
            } else image = LocalMusicImporter.loadArtwork(context, uri, 256);
            if (image != null) {
                int limit = asset ? 512 : 256;
                int edge = Math.max(image.getWidth(), image.getHeight());
                if (edge > limit) {
                    Bitmap smaller = Bitmap.createScaledBitmap(image, Math.max(1, image.getWidth() * limit / edge), Math.max(1, image.getHeight() * limit / edge), true);
                    if (smaller != image) image.recycle();
                    image = smaller;
                }
                IMAGES.put(key, image);
            }
        } catch (Exception ignored) { }
        return image;
    }

    /** Each RemoteViews carries at most 512 × 384 ARGB pixels (768 KiB). */
    static Bitmap render(Context context, Snapshot state, Options options, int widthDp, int heightDp) {
        float width = Math.max(180, widthDp), height = Math.max(140, heightDp);
        float scale = Math.min(2f, Math.min(512f / width, 384f / height));
        Bitmap bitmap = Bitmap.createBitmap(Math.max(1, Math.round(width * scale)), Math.max(1, Math.round(height * scale)), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap); canvas.scale(scale, scale);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        int base = backgroundColor(state,options);
        int recordTint=recordColor(state,options);
        int ink = options.transparent || luminance(base) < .62f ? Color.WHITE : DesignSystem.INK;
        float mainHeight = height - 52;
        if (!options.transparent) {
            glass(canvas, p, new RectF(0, 0, width, height), base);
            Bitmap paper=IMAGES.get("asset:design/paper-ivory-fiber-v1.png");
            if(paper!=null){int save=canvas.save();Path rounded=new Path();rounded.addRoundRect(new RectF(0,0,width,height),20,20,Path.Direction.CW);canvas.clipPath(rounded);p.setAlpha(20);canvas.drawBitmap(paper,null,new RectF(0,0,width,height),p);p.setAlpha(255);canvas.restoreToCount(save);}
        }
        if (options.transparent) p.setShadowLayer(2, 0, 1, 0x66000000);
        switch (options.style) {
            case COVER: coverWidget(canvas, p, state, width, mainHeight, ink); break;
            case RADIO: radioWidget(canvas, p, state, width, mainHeight, ink); break;
            case RECENT: recentWidget(canvas, p, state, width, mainHeight, ink); break;
            default: turntableWidget(canvas, p, state, width, mainHeight, ink,recordTint);
        }
        p.clearShadowLayer();
        controls(canvas, p, state.playing, options.style >= RADIO && width >= 224, width, height, ink);
        p.setColor(withAlpha(ink, 24)); canvas.drawCircle(width - 26, 26, 16, p);
        PlayerControls.symbol(canvas, p, "more", width - 35, 17, 18, ink);
        return bitmap;
    }

    static int backgroundColor(Snapshot state,Options options){return options.colorMode==COLOR_FIXED?options.color:options.colorMode==COLOR_ARTWORK&&state.artworkPalette!=null?state.artworkPalette.background:state.color;}
    static int recordColor(Snapshot state,Options options){return options.colorMode==COLOR_FIXED?0:options.colorMode==COLOR_ARTWORK&&state.artworkPalette!=null?state.artworkPalette.record:state.recordTint;}

    private static void glass(Canvas c, Paint p, RectF bounds, int base) {
        p.setShader(null);p.setColor(base);c.drawRoundRect(bounds,20,20,p);
        p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1);p.setColor(0x25ffffff);
        c.drawRoundRect(new RectF(.6f,.6f,bounds.right-.6f,bounds.bottom-.6f),20,20,p);p.setStyle(Paint.Style.FILL);
    }

    private static void turntableWidget(Canvas c, Paint p, Snapshot s, float w, float h, int ink,int recordTint) {
        if (w > h * 1.65f) {
            float r = Math.min(h * .46f, w * .21f), cx = r+10, cy = h * .52f;
            platter(c, p, s, cx, cy, r,recordTint);
            float left=cx+r+24;
            text(c,p,s.playing?"正在播放":"唱片机",left,29,w-left-42,10,withAlpha(ink,190),false,false);
            text(c,p,s.title,left,Math.max(58,h*.52f),w-left-18,16,ink,true,false);
            text(c,p,s.artist,left,Math.max(78,h*.52f+22),w-left-18,12,withAlpha(ink,205),false,false);
        } else {
            float r = Math.min(w * .38f, Math.max(35, (h - 38) * .47f));
            platter(c, p, s, w * .47f, r + 10, r,recordTint);
            centered(c, p, s.title, w / 2, h - 20, w - 28, 14, ink, true);
            centered(c, p, s.artist, w / 2, h - 3, w - 28, 10, withAlpha(ink, 190), false);
        }
    }

    private static void coverWidget(Canvas c, Paint p, Snapshot s, float w, float h, int ink) {
        if (w > h * 1.6f) {
            float edge = Math.min(h - 22, w * .35f); cover(c, p, s.cover, new RectF(16, (h - edge) / 2, 16 + edge, (h + edge) / 2), 10);
            float left = edge + 31;
            text(c, p, s.playing ? "正在播放" : "专辑封面", left, 30, w - left - 42, 10, withAlpha(ink, 180), false, false);
            text(c, p, s.title, left, Math.max(58, h * .53f), w - left - 16, 17, ink, true, false);
            text(c, p, s.artist, left, Math.max(80, h * .53f + 22), w - left - 16, 12, withAlpha(ink, 195), false, false);
        } else {
            float edge = Math.min(w - 52, h - 47);
            cover(c, p, s.cover, new RectF((w - edge) / 2, 11, (w + edge) / 2, 11 + edge), 12);
            centered(c, p, s.title, w / 2, h - 19, w - 28, 14, ink, true);
            centered(c, p, s.artist, w / 2, h - 3, w - 28, 10, withAlpha(ink, 190), false);
        }
    }

    private static void radioWidget(Canvas c, Paint p, Snapshot s, float w, float h, int ink) {
        float speaker=Math.min(w*.13f,Math.max(22,h*.29f)),cx=w-speaker-23,cy=h*.56f;
        p.setColor(withAlpha(ink,100));p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(.7f);
        for(float r=speaker;r>3;r-=3)c.drawCircle(cx,cy,r,p);p.setStyle(Paint.Style.FILL);
        float right=cx-speaker-16;
        p.setColor(0xffa99a80);c.drawRoundRect(18,23,right+3,h-12,4,4,p);
        p.setColor(0xff514b3e);c.drawRoundRect(21,26,right,h-15,3,3,p);
        text(c,p,s.playing?"NOW PLAYING":"ON THE RECORD",30,44,right-40,9,0xffe6d6ad,false,false);
        text(c,p,s.title,30,Math.max(64,h*.56f),right-40,14,0xfffff1cf,true,false);
        if(h>115)text(c,p,s.artist,30,h-28,right-40,11,0xffe6d6ad,false,false);
    }

    private static void recentWidget(Canvas c, Paint p, Snapshot s, float w, float h, int ink) {
        boolean tall = h >= 150;
        float header = tall ? 64 : 41, thumb = tall ? 48 : 30;
        cover(c, p, s.cover, new RectF(17, 13, 17 + thumb, 13 + thumb), 7);
        float left = 29 + thumb;
        if (tall) text(c, p, s.playing ? "NOW PLAYING" : "ON THE RECORD", left, 24, w - left - 42, 9, withAlpha(ink, 190), false, true);
        text(c, p, s.title, left, tall ? 44 : 27, w - left - 36, tall ? 16 : 13, ink, false, true);
        text(c, p, s.artist, left, tall ? 61 : 42, w - left - 18, 10, withAlpha(ink, 185), false, false);
        float labelY = header + (tall ? 22 : 19);
        text(c, p, "最近播放", 18, labelY, w - 36, 10, withAlpha(ink, 190), false, true);
        float edge = Math.max(20, Math.min((w - 63) / 4, h - labelY - 11));
        if (s.recentCount == 0) {
            text(c, p, "听过的唱片会出现在这里", 18, labelY + 21, w - 36, 11, withAlpha(ink, 170), false, false);
        } else {
            for (int i = 0; i < s.recentCount; i++) {
                float x = 18 + i * ((w - 36 - edge) / 3);
                cover(c, p, s.recentCovers.get(i), new RectF(x, labelY + 7, x + edge, labelY + 7 + edge), 7);
            }
        }
    }

    private static void platter(Canvas c, Paint p, Snapshot s, float cx, float cy, float r,int recordTint) {
        p.setColor(0x26000000); c.drawCircle(cx + 2, cy + 4, r, p);
        c.save(); c.rotate(s.angle, cx, cy);
        if (s.disc != null) {
            BitmapShader material = new BitmapShader(s.disc, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            DiscMaterials.place(s.disc, material, new Matrix(), cx, cy, r);
            p.setColor(Color.WHITE); p.setAlpha(DiscMaterials.alpha(s.discStyle)); p.setShader(material);
            if(recordTint!=0)p.setColorFilter(DiscMaterials.tintFilter(recordTint));
            c.drawCircle(cx, cy, r, p); p.setShader(null); p.setColorFilter(null);p.setAlpha(255);
        } else {
            p.setColor(recordTint==0?0xff151719:DiscMaterials.tintedColor(s.discStyle,recordTint)); c.drawCircle(cx, cy, r, p); p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(.55f);
            p.setColor(0xff333538); for (float q = r * .5f; q < r * .98f; q += Math.max(2, r / 24)) c.drawCircle(cx, cy, q, p); p.setStyle(Paint.Style.FILL);
        }
        float label=r*DesignSystem.RECORD_LABEL_RATIO;Path circle = new Path(); circle.addCircle(cx, cy, label, Path.Direction.CW);
        c.save(); c.clipPath(circle); cover(c, p, s.cover, new RectF(cx-label,cy-label,cx+label,cy+label), 0); c.restore();
        p.setColor(0xffe5dfce); c.drawCircle(cx, cy, Math.max(1.5f, r * .018f), p); c.restore();
        float px = cx + r * .96f, py = cy - r * .72f;
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(.8f); p.setColor(0x40000000); c.drawCircle(px, py, r * .20f, p); p.setStyle(Paint.Style.FILL);
        c.save(); c.translate(px, py); c.rotate(s.playing ? 19 : 0);
        float length = r * 1.38f;
        p.setColor(0x2c000000); c.drawRoundRect(-r * .01f, r * .04f, r * .07f, length + r * .05f, 2, 2, p);
        p.setShader(new LinearGradient(-r * .025f, 0, r * .025f, 0, new int[]{0xff4d5351, 0xfff1f0e6, 0xff7b827e}, null, Shader.TileMode.CLAMP));
        c.drawRect(-r * .024f, 0, r * .024f, length, p); p.setShader(null);
        p.setColor(0xff202323); c.drawRect(-r * .09f, -r * .10f, r * .09f, r * .11f, p);
        p.setColor(0xff303534); c.drawRect(-r * .12f, -r * .04f, r * .10f, r * .08f, p);
        p.setColor(0xff111414); c.drawRect(-r * .035f, length - r * .08f, r * .035f, length + r * .03f, p);
        c.translate(0, length); c.rotate(28); c.drawRoundRect(-r * .06f, -r * .025f, r * .065f, r * .20f, 2, 2, p);
        c.restore();
    }

    private static void cover(Canvas c, Paint p, Bitmap artwork, RectF destination, float corners) {
        p.setShader(null); p.setColor(0xff26292a);
        c.drawRoundRect(destination, corners, corners, p);
        if (artwork == null) {
            float radius = Math.min(destination.width(), destination.height()) * .27f;
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(1.1f); p.setColor(0x55ffffff);
            c.drawCircle(destination.centerX(), destination.centerY(), radius, p);
            c.drawCircle(destination.centerX(), destination.centerY(), radius * .34f, p); p.setStyle(Paint.Style.FILL); return;
        }
        c.save(); Path clip = new Path(); clip.addRoundRect(destination, corners, corners, Path.Direction.CW); c.clipPath(clip);
        int edge = Math.min(artwork.getWidth(), artwork.getHeight());
        Rect source = new Rect((artwork.getWidth() - edge) / 2, (artwork.getHeight() - edge) / 2, (artwork.getWidth() + edge) / 2, (artwork.getHeight() + edge) / 2);
        p.setColor(Color.WHITE); c.drawBitmap(artwork, source, destination, p); c.restore();
    }

    private static void controls(Canvas c, Paint p, boolean playing, boolean radio, float w, float h, int ink) {
        float left = 14, available = w - 28, cy = h - 26;
        if (radio) {
            p.setColor(withAlpha(ink, 65));
            for (int i = 0; i < 6; i++) c.drawRoundRect(17, h - 42 + i * 5, 56, h - 40 + i * 5, 1, 1, p);
            left += 52; available -= 52;
        }
        String[] symbols = {"previous", playing ? "pause" : "play", "next"};
        for (int i = 0; i < 3; i++) {
            float cx = left + available * (i + .5f) / 3;
            if (radio) {
                float half = available / 6 - 3;
                p.setColor(0x23000000); c.drawRoundRect(cx - half, cy - 14, cx + half, cy + 17, 4, 4, p);
                p.setShader(new LinearGradient(0, cy - 15, 0, cy + 14, new int[]{withAlpha(ink, 55), withAlpha(ink, 20)}, null, Shader.TileMode.CLAMP));
                c.drawRoundRect(cx - half, cy - 16, cx + half, cy + 13, 4, 4, p); p.setShader(null);
            }
            PlayerControls.symbol(c, p, symbols[i], cx - 12, cy - 12, 24, ink);
        }
    }

    private static void text(Canvas c, Paint p, String value, float x, float baseline, float maxWidth, float size, int ink, boolean bold, boolean mono) {
        if (maxWidth <= 3) return;
        p.setShader(null); p.setStyle(Paint.Style.FILL); p.setColor(ink); p.setTextSize(size); p.setTextAlign(Paint.Align.LEFT);
        p.setTypeface(mono ? Typeface.MONOSPACE : Typeface.create(bold ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        String clipped = TextUtils.ellipsize(value == null ? "" : value, new TextPaint(p), maxWidth, TextUtils.TruncateAt.END).toString();
        c.drawText(clipped, x, baseline, p);
    }

    private static void centered(Canvas c, Paint p, String value, float x, float y, float width, float size, int ink, boolean bold) {
        p.setTextSize(size); p.setTypeface(Typeface.create(bold ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        String clipped = TextUtils.ellipsize(value, new TextPaint(p), width, TextUtils.TruncateAt.END).toString();
        text(c, p, clipped, x - p.measureText(clipped) / 2, y, width, size, ink, bold, false);
    }

    private static float luminance(int color) { return (.2126f * Color.red(color) + .7152f * Color.green(color) + .0722f * Color.blue(color)) / 255f; }
    private static int withAlpha(int color, int alpha) { return (color & 0x00ffffff) | (alpha << 24); }
    private static int mix(int a, int b, float f) { return Color.rgb(Math.round(Color.red(a) * (1 - f) + Color.red(b) * f), Math.round(Color.green(a) * (1 - f) + Color.green(b) * f), Math.round(Color.blue(a) * (1 - f) + Color.blue(b) * f)); }
}
