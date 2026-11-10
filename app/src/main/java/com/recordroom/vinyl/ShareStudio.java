package com.recordroom.vinyl;

import android.app.Activity;
import android.app.Dialog;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Matrix;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Bundle;
import android.os.Looper;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.Window;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Native share-card designer. Artwork decoding and PNG export never run on the UI thread. */
public final class ShareStudio {
    public static final int SAVE_REQUEST = 117;
    private static final int EXPORT_WIDTH = 1600;
    private static final int EXPORT_HEIGHT = 2000;
    private static final String[] LAYOUT_NAMES = {"唱片封套", "极简封面", "纸面拼贴"};

    private final Activity activity;
    private final Context application;
    private final String title, subtitle;
    private final ArrayList<Track> tracks;
    private final int background, disc;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ArrayList<Button> layoutButtons = new ArrayList<>();
    private Dialog dialog;
    private CardPreview preview;
    private TextView status, count;
    private Button saveButton, shareButton;
    private ArtworkBundle artwork = new ArtworkBundle();
    private int layout;
    private boolean ready, busy, closed;
    private File pendingSaveFile;
    private boolean waitingSave;
    private int renderingAction = -1;
    private Runnable onClosed;

    public ShareStudio(Activity activity, String title, String subtitle, List<Track> tracks,
                       int background, int disc) {
        this.activity = activity;
        application = activity.getApplicationContext();
        this.title = title == null || title.trim().isEmpty() ? "唱片时刻" : title.trim();
        this.subtitle = subtitle == null ? "" : subtitle.trim();
        this.tracks = tracks == null ? new ArrayList<>() : new ArrayList<>(tracks);
        this.background = background | 0xff000000;
        this.disc = DiscMaterials.normalize(disc);
    }

    /** Small recreation state; generated images stay in the app's restricted share cache. */
    public Bundle snapshot() {
        if (closed && !waitingSave) return null;
        Bundle state = new Bundle();
        state.putString("title", title); state.putString("subtitle", subtitle);
        state.putInt("background", background); state.putInt("disc", disc);
        state.putInt("layout", layout); state.putBoolean("visible", isVisible());
        state.putBoolean("waiting_save", waitingSave);
        state.putInt("rendering_action", renderingAction);
        if (waitingSave && pendingSaveFile != null) state.putString("pending_file", pendingSaveFile.getName());
        ArrayList<String> candidates = new ArrayList<>(); HashSet<String> seen = new HashSet<>();
        ThemeStore themes = new ThemeStore(application);
        for (Track track : tracks) {
            if (track == null) continue;
            String custom = themes.artwork(track.albumKey());
            String uri = custom.isEmpty() ? track.artworkUri : custom;
            if (uri.isEmpty() || !seen.add(uri)) continue;
            candidates.add(track.toJson().toString());
            if (candidates.size() == 8) break;
        }
        state.putStringArrayList("tracks", candidates);
        return state;
    }

    /** Restores a headless receiver immediately; show() is deferred to the host's page restore. */
    public static ShareStudio restore(Activity activity, Bundle state) {
        if (state == null) return null;
        ArrayList<Track> tracks = new ArrayList<>();
        ArrayList<String> candidates = state.getStringArrayList("tracks");
        if (candidates != null) for (int i = 0; i < Math.min(8, candidates.size()); i++) {
            try { tracks.add(Track.fromJson(new org.json.JSONObject(candidates.get(i)))); }
            catch (org.json.JSONException | RuntimeException ignored) { }
        }
        ShareStudio studio = new ShareStudio(activity, state.getString("title"), state.getString("subtitle"),
                tracks, state.getInt("background", 0xffde8b7c), state.getInt("disc"));
        studio.layout = Math.floorMod(state.getInt("layout"), LAYOUT_NAMES.length);
        studio.waitingSave = state.getBoolean("waiting_save");
        int action = state.getInt("rendering_action", -1);
        studio.renderingAction = action == 0 || action == 1 ? action : -1;
        String name = state.getString("pending_file", "");
        if (studio.waitingSave && name.matches("share-[a-f0-9]{32}\\.png")) {
            try {
                File file = new File(ShareImageProvider.exportDirectory(studio.application), name);
                ShareImageProvider.uriForFile(studio.application, file);
                studio.pendingSaveFile = file;
            } catch (IOException | RuntimeException ignored) { }
        }
        if (studio.waitingSave) studio.renderingAction = -1;
        return studio;
    }

    public boolean isVisible() { return !closed && dialog != null && dialog.isShowing(); }
    public boolean hasPendingSave() { return waitingSave; }
    public void setOnClosed(Runnable callback) { onClosed = callback; }

    public Dialog show() {
        if (dialog != null && dialog.isShowing()) return dialog;
        if (closed) throw new IllegalStateException("This share studio is closed");
        dialog = new Dialog(activity, android.R.style.Theme_Material_Light_NoActionBar);
        LinearLayout page = new LinearLayout(activity); page.setOrientation(LinearLayout.VERTICAL);
        page.setBackground(DesignSystem.paper(activity));
        page.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });

        LinearLayout bar = new LinearLayout(activity); bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(12), 0, dp(12), 0);
        Button back = button("×", 0xff28252b, Color.TRANSPARENT, this::close);
        back.setTextSize(26); back.setContentDescription("关闭分享卡");
        bar.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));
        TextView heading = text("分享这一刻", 18, DesignSystem.INK); heading.setTypeface(null, Typeface.BOLD); heading.setGravity(Gravity.CENTER);
        bar.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        count = text("1 / 3", 12, DesignSystem.MUTED); count.setGravity(Gravity.END);
        bar.addView(count, new LinearLayout.LayoutParams(dp(48), -2));
        page.addView(bar, new LinearLayout.LayoutParams(-1, dp(54)));

        FrameLayout stage = new FrameLayout(activity);
        stage.setPadding(dp(22), dp(10), dp(22), dp(10));
        preview = new CardPreview(activity);
        preview.setContentDescription(this.title + "，" + LAYOUT_NAMES[layout] + "分享卡预览，左右滑动切换布局");
        stage.addView(preview, new FrameLayout.LayoutParams(-1, -1));
        page.addView(stage, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout selector = new LinearLayout(activity); selector.setGravity(Gravity.CENTER);
        selector.setPadding(dp(20), dp(8), dp(20), dp(8));
        for (int i = 0; i < LAYOUT_NAMES.length; i++) {
            final int index = i;
            Button option = button(LAYOUT_NAMES[i], 0xff332c32, 0xffe7e0d5, () -> selectLayout(index));
            option.setTextSize(12); option.setContentDescription("选择" + LAYOUT_NAMES[i] + "布局");
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(48), 1);
            lp.leftMargin = dp(i == 0 ? 0 : 6); selector.addView(option, lp); layoutButtons.add(option);
        }
        page.addView(selector);
        status = text("正在加载封面…", 12, DesignSystem.MUTED); status.setGravity(Gravity.CENTER);
        status.setPadding(dp(16), dp(3), dp(16), dp(12)); page.addView(status);
        LinearLayout commands = new LinearLayout(activity); commands.setGravity(Gravity.CENTER_VERTICAL);
        commands.setPadding(dp(20), 0, dp(20), dp(18));
        saveButton = button("保存图片", DesignSystem.ACCENT, 0xffe8e1ed, () -> export(false));
        shareButton = button("分享图片", Color.WHITE, DesignSystem.ACCENT, () -> export(true));
        saveButton.setEnabled(false); shareButton.setEnabled(false);
        LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(0, dp(50), 1); left.rightMargin = dp(10);
        commands.addView(saveButton, left); commands.addView(shareButton, new LinearLayout.LayoutParams(0, dp(50), 1)); page.addView(commands);
        dialog.setContentView(page); dialog.setOnDismissListener(d -> close());
        dialog.show(); Window window = dialog.getWindow();
        if (window != null) {
            window.setLayout(-1, -1); window.setStatusBarColor(DesignSystem.PAPER); window.setNavigationBarColor(DesignSystem.PAPER);
            window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        }
        page.requestApplyInsets(); selectLayout(layout); loadArtwork();
        return dialog;
    }

    private void selectLayout(int selected) {
        if (closed || busy) return;
        layout = (selected + LAYOUT_NAMES.length) % LAYOUT_NAMES.length;
        if (preview != null) {
            preview.setContentDescription(title + "，" + LAYOUT_NAMES[layout] + "分享卡预览，左右滑动切换布局");
            preview.invalidate();
        }
        if (count != null) count.setText((layout + 1) + " / " + LAYOUT_NAMES.length);
        for (int i = 0; i < layoutButtons.size(); i++) {
            Button button = layoutButtons.get(i); button.setSelected(i == layout);
            button.setBackground(ripple(i == layout ? DesignSystem.LILAC : Color.TRANSPARENT, dp(8)));button.setTextColor(i==layout?DesignSystem.ACCENT:DesignSystem.MUTED);
            button.setTypeface(null, i == layout ? Typeface.BOLD : Typeface.NORMAL);
        }
    }

    private void loadArtwork() {
        final Context app = application;
        final ArrayList<Track> requestedTracks = new ArrayList<>(tracks);
        final String discPath = DiscMaterials.assetPath(disc);
        final Handler handler = main;
        final WeakReference<ShareStudio> owner = new WeakReference<>(this);
        worker.execute(() -> {
            ArtworkBundle result = new ArtworkBundle();
            result.paper = decodeAsset(app, "design/paper-ivory-fiber-v1.png", 1280);
            result.sleeve = decodeAsset(app, "design/sleeve-ivory-window-v1.png", 1280);
            result.disc = decodeAsset(app, discPath, 1280);
            ThemeStore themes = new ThemeStore(app);
            HashSet<String> seen = new HashSet<>();
            int attempted = 0;
            for (Track track : requestedTracks) {
                if (Thread.currentThread().isInterrupted()) return;
                if (track == null) continue;
                String custom = themes.artwork(track.albumKey());
                String uri = custom.isEmpty() ? track.artworkUri : custom;
                if (uri.isEmpty() || !seen.add(uri)) continue;
                if (attempted++ == 8) break;
                Bitmap image = LocalMusicImporter.loadArtwork(app, uri, result.covers.isEmpty() ? 1200 : 720);
                if (image != null) result.covers.add(image);
                if (result.covers.size() == 4) break;
            }
            if(themes.followCoverColors()&&!result.covers.isEmpty())result.recordTint=AlbumPalette.from(result.covers.get(0)).record;
            handler.post(() -> {
                ShareStudio studio = owner.get();
                if (studio == null || studio.closed) return;
                studio.artwork = result; studio.ready = true; studio.setBusy(false);
                if (studio.preview != null) studio.preview.invalidate();
                int resume = studio.renderingAction;
                studio.renderingAction = -1;
                if (resume >= 0 && !studio.waitingSave) studio.export(resume == 1);
            });
        });
    }

    private void setBusy(boolean value) {
        busy = value;
        if (saveButton != null) saveButton.setEnabled(ready && !busy && !waitingSave);
        if (shareButton != null) shareButton.setEnabled(ready && !busy);
        for (Button button : layoutButtons) button.setEnabled(!busy);
        if (status != null) status.setText(busy ? "正在生成高清图片…"
                : artwork.covers.isEmpty() ? "封面暂未加载 · 可导出文字卡"
                : EXPORT_WIDTH + " × " + EXPORT_HEIGHT + " · PNG");
    }

    private void export(boolean share) {
        if (!ready || busy || closed || (!share && waitingSave)) return;
        renderingAction = share ? 1 : 0;
        setBusy(true);
        final Snapshot snapshot = renderSnapshot();
        final Context app = application;
        final Handler handler = main;
        final WeakReference<ShareStudio> owner = new WeakReference<>(this);
        worker.execute(() -> {
            File file = null; String error = null;
            try { file = writeExport(app, snapshot); }
            catch (IOException | RuntimeException exception) { error = "图片生成失败，请重试"; }
            catch (OutOfMemoryError exception) { error = "内存不足，请稍后再试"; }
            final File exported = file; final String message = error;
            handler.post(() -> {
                ShareStudio studio = owner.get();
                if (studio == null || studio.closed || studio.activity.isFinishing() || studio.activity.isDestroyed()) return;
                studio.renderingAction = -1;
                studio.setBusy(false);
                if (exported == null) { studio.toast(message == null ? "图片生成失败" : message); return; }
                if (share) studio.sendImage(exported); else studio.chooseSaveLocation(exported);
            });
        });
    }

    private void sendImage(File file) {
        try {
            Uri uri = ShareImageProvider.uriForFile(application, file);
            Intent intent = new Intent(Intent.ACTION_SEND).setType("image/png");
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.setClipData(ClipData.newUri(activity.getContentResolver(), "唱片分享卡", uri));
            Intent chooser = Intent.createChooser(intent, "分享图片");
            chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            activity.startActivity(chooser);
        } catch (IOException | RuntimeException exception) { toast("暂时无法打开分享面板"); }
    }

    private void chooseSaveLocation(File file) {
        pendingSaveFile = file; waitingSave = true; setBusy(false);
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE); intent.setType("image/png");
        intent.putExtra(Intent.EXTRA_TITLE, "唱片分享卡-" + (layout + 1) + ".png");
        try { activity.startActivityForResult(intent, SAVE_REQUEST); }
        catch (RuntimeException exception) { pendingSaveFile = null; waitingSave = false; setBusy(false); toast("无法打开文件保存位置"); }
    }

    public boolean onActivityResult(int request, int result, Intent data) {
        if (request != SAVE_REQUEST) return false;
        File file = pendingSaveFile; pendingSaveFile = null; waitingSave = false;
        if (!closed) setBusy(false);
        if (result != Activity.RESULT_OK) return true;
        Uri destination = data == null ? null : data.getData();
        if (destination == null || file == null || !file.isFile()) { toast("没有可保存的图片，请重新生成"); return true; }
        if (!"content".equals(destination.getScheme())) { toast("文件保存位置不可用"); return true; }
        final Context app = application;
        final WeakReference<Activity> owner = new WeakReference<>(activity);
        final WeakReference<ShareStudio> studioOwner = new WeakReference<>(this);
        final Handler handler = main;
        final ExecutorService saver = Executors.newSingleThreadExecutor();
        if (!closed) { setBusy(true); if (status != null) status.setText("正在保存…"); }
        saver.execute(() -> {
            boolean success = false;
            try (InputStream input = new FileInputStream(file);
                 OutputStream output = app.getContentResolver().openOutputStream(destination, "w")) {
                if (output == null) throw new IOException("No output stream");
                byte[] buffer = new byte[32 * 1024]; int read;
                while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
                output.flush(); success = true;
            } catch (IOException | RuntimeException exception) {
                // The selected destination can fail independently of the already generated card.
            } finally { saver.shutdown(); }
            final boolean saved = success;
            handler.post(() -> {
                ShareStudio studio = studioOwner.get();
                if (studio != null && !studio.closed) studio.setBusy(false);
                Activity current = owner.get();
                if (current != null && !current.isFinishing() && !current.isDestroyed())
                    Toast.makeText(current, saved ? "图片已保存" : "保存失败，请重新选择位置", Toast.LENGTH_SHORT).show();
            });
        });
        return true;
    }

    public void close() {
        if (closed) return; closed = true; worker.shutdownNow();
        if (dialog != null && dialog.isShowing()) dialog.dismiss();
        // Existing URI grants and a pending system document picker may still use the exported file.
        artwork = new ArtworkBundle();
        Runnable callback = onClosed; onClosed = null;
        if (callback != null) callback.run();
    }

    private Snapshot renderSnapshot() { return new Snapshot(title, subtitle, background, disc, layout, artwork); }

    private final class CardPreview extends View {
        private float downX, downY; private boolean cancelled;
        private final int slop;
        CardPreview(Context context) {
            super(context); slop = ViewConfiguration.get(context).getScaledTouchSlop(); setFocusable(true); setClickable(true);
        }
        @Override protected void onDraw(Canvas canvas) {
            float width = Math.min(getWidth(), getHeight() * .8f), height = width * 1.25f;
            if (width <= 0 || height <= 0) return;
            float left = (getWidth() - width) / 2f, top = (getHeight() - height) / 2f;
            Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG); shadow.setColor(0x18000000);
            canvas.drawRoundRect(left + dp(3), top + dp(5), left + width + dp(3), top + height + dp(5), dp(3), dp(3), shadow);
            canvas.save(); canvas.translate(left, top); render(canvas, width, height, renderSnapshot()); canvas.restore();
        }
        @Override public boolean onTouchEvent(MotionEvent event) {
            if (busy) return true;
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = event.getX(); downY = event.getY(); cancelled = false; return true;
                case MotionEvent.ACTION_MOVE:
                    if (Math.abs(event.getY() - downY) > Math.abs(event.getX() - downX) + slop) cancelled = true;
                    return true;
                case MotionEvent.ACTION_CANCEL: cancelled = true; return true;
                case MotionEvent.ACTION_UP:
                    float distance = event.getX() - downX;
                    if (!cancelled && Math.abs(distance) > Math.max(dp(32), slop * 2)) selectLayout(layout + (distance < 0 ? 1 : -1));
                    else if (!cancelled && Math.abs(distance) < slop && Math.abs(event.getY() - downY) < slop) performClick();
                    return true;
                default: return super.onTouchEvent(event);
            }
        }
        @Override public boolean performClick() { return super.performClick(); }
    }

    private static final class ArtworkBundle {
        Bitmap paper, disc, sleeve;int recordTint; final ArrayList<Bitmap> covers = new ArrayList<>();
    }
    private static final class Snapshot {
        final String title, subtitle; final int background, discStyle, layout,recordTint;
        final Bitmap paper, disc, sleeve; final ArrayList<Bitmap> covers;
        Snapshot(String title, String subtitle, int background, int discStyle, int layout, ArtworkBundle bundle) {
            this.title = title; this.subtitle = subtitle; this.background = background; this.discStyle = discStyle; this.layout = layout;
            paper = bundle.paper; disc = bundle.disc;sleeve=bundle.sleeve;recordTint=bundle.recordTint; covers = new ArrayList<>(bundle.covers);
        }
        Bitmap cover(int index) { return covers.isEmpty() ? null : covers.get(index % covers.size()); }
    }

    private static File writeExport(Context context, Snapshot snapshot) throws IOException {
        File directory = ShareImageProvider.exportDirectory(context);
        File[] old = directory.listFiles();
        if (old != null) for (File file : old) {
            if (file.getName().matches("share-[a-f0-9]{32}\\.png") && file.lastModified() < System.currentTimeMillis() - 7L * 86400000L) file.delete();
        }
        File target = new File(directory, "share-" + UUID.randomUUID().toString().replace("-", "") + ".png");
        Bitmap bitmap = Bitmap.createBitmap(EXPORT_WIDTH, EXPORT_HEIGHT, Bitmap.Config.ARGB_8888);
        try {
            render(new Canvas(bitmap), EXPORT_WIDTH, EXPORT_HEIGHT, snapshot);
            if (Thread.currentThread().isInterrupted()) throw new IOException("Export cancelled");
            try (OutputStream output = new FileOutputStream(target)) {
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) throw new IOException("PNG encoding failed");
            }
            return target;
        } catch (IOException | RuntimeException exception) { target.delete(); throw exception; }
        finally { bitmap.recycle(); }
    }

    /** The same renderer drives the visible preview and the high-resolution export. */
    private static void render(Canvas canvas, float width, float height, Snapshot card) {
        canvas.save(); canvas.scale(width / 1000f, height / 1250f); canvas.clipRect(0, 0, 1000, 1250);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        int paperColor=card.layout==0?card.background:DesignSystem.PAPER;canvas.drawColor(paperColor);
        if (card.paper != null) {
            paint.setAlpha(card.layout==0?32:255); canvas.drawBitmap(card.paper, null, new RectF(0, 0, 1000, 1250), paint); paint.setAlpha(255);
        }
        int ink = contrast(paperColor);
        if (card.layout == 0) {
            drawDisc(canvas, card, 740, 420, 285, paint);
            drawSleeve(canvas,card,new RectF(95,190,635,730),paint);
            block(canvas,"A RECORD OF THIS MOMENT",85,790,830,21,muted(ink),1,true,Layout.Alignment.ALIGN_NORMAL);
            block(canvas, card.title, 85,860,830,64,ink,2,true,Layout.Alignment.ALIGN_NORMAL);
            block(canvas, card.subtitle,85,1030,830,29,muted(ink),2,false,Layout.Alignment.ALIGN_NORMAL);
        } else if (card.layout == 1) {
            drawPhoto(canvas, card.cover(0), new RectF(90, 95, 910, 915), 0, paint, true);
            block(canvas, card.title, 90, 960, 820, 56, ink, 2, true, Layout.Alignment.ALIGN_NORMAL);
            block(canvas, card.subtitle, 90, 1101, 820, 28, muted(ink), 2, false, Layout.Alignment.ALIGN_NORMAL);
        } else {
            drawPhoto(canvas, card.cover(0), new RectF(-70, -70, 470, 470), -12, paint, false);
            drawPhoto(canvas, card.cover(1), new RectF(645, 70, 1100, 525), 12, paint, false);
            drawPhoto(canvas, card.cover(2), new RectF(-85, 875, 370, 1330), 13, paint, false);
            drawPhoto(canvas, card.cover(3), new RectF(660, 935, 1090, 1365), -13, paint, false);
            block(canvas, card.title, 105, 550, 790, 78, ink, 2, true, Layout.Alignment.ALIGN_CENTER);
            block(canvas, card.subtitle, 140, 823, 720, 31, muted(ink), 2, false, Layout.Alignment.ALIGN_CENTER);
        }
        paint.setColor(ink == Color.WHITE ? 0x30ffffff : 0x3029272e);canvas.drawRect(85,1178,150,1180,paint);
        block(canvas, "唱片机", 40, 1210, 920, 19, muted(ink), 1, false, Layout.Alignment.ALIGN_CENTER);
        canvas.restore();
    }

    /** Geometry defines a clean circular window; the generated paper provides surface detail only. */
    private static void drawSleeve(Canvas canvas,Snapshot card,RectF bounds,Paint paint){
        canvas.save();canvas.rotate(-5,bounds.centerX(),bounds.centerY());
        paint.setColor(0x22000000);canvas.drawRect(bounds.left+8,bounds.top+10,bounds.right+8,bounds.bottom+10,paint);
        float radius=bounds.width()*.205f;
        Path window=new Path();window.addCircle(bounds.centerX(),bounds.centerY(),radius,Path.Direction.CW);
        canvas.save();canvas.clipPath(window);crop(canvas,card.cover(0),new RectF(bounds.centerX()-radius,bounds.centerY()-radius,bounds.centerX()+radius,bounds.centerY()+radius),paint);canvas.restore();
        Path paper=new Path();paper.setFillType(Path.FillType.EVEN_ODD);paper.addRect(bounds,Path.Direction.CW);paper.addCircle(bounds.centerX(),bounds.centerY(),radius,Path.Direction.CW);
        paint.setColor(DesignSystem.PAPER);canvas.drawPath(paper,paint);
        canvas.save();canvas.clipPath(paper);
        if(card.sleeve!=null){int w=card.sleeve.getWidth(),h=card.sleeve.getHeight();canvas.drawBitmap(card.sleeve,new Rect(Math.round(w*.065f),Math.round(h*.078f),Math.round(w*.935f),Math.round(h*.938f)),bounds,paint);}
        else if(card.paper!=null)canvas.drawBitmap(card.paper,null,bounds,paint);
        canvas.restore();paint.setColor(0x2275627e);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(1.2f);canvas.drawRect(bounds,paint);canvas.drawCircle(bounds.centerX(),bounds.centerY(),radius,paint);paint.setStyle(Paint.Style.FILL);canvas.restore();
    }

    private static void drawDisc(Canvas canvas, Snapshot card, float cx, float cy, float radius, Paint paint) {
        paint.setColor(0x26000000); canvas.drawCircle(cx + 11, cy + 14, radius, paint);
        paint.setColor(card.recordTint==0?DiscMaterials.color(card.discStyle):DiscMaterials.tintedColor(card.discStyle,card.recordTint)); canvas.drawCircle(cx, cy, radius, paint);
        if (card.disc != null) {
            BitmapShader material = new BitmapShader(card.disc, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            DiscMaterials.place(card.disc, material, new Matrix(), cx, cy, radius);
            paint.setShader(material); paint.setAlpha(DiscMaterials.alpha(card.discStyle));
            if(card.recordTint!=0)paint.setColorFilter(DiscMaterials.tintFilter(card.recordTint));
            canvas.drawCircle(cx, cy, radius, paint);
            paint.setShader(null);paint.setColorFilter(null); paint.setAlpha(255);
        } else {
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(1.4f); paint.setColor(0xff49434a);
            for (float r = radius * .5f; r < radius * .98f; r += radius * .025f) canvas.drawCircle(cx, cy, r, paint);
            paint.setStyle(Paint.Style.FILL);
        }
        float label = radius * DesignSystem.RECORD_LABEL_RATIO;
        canvas.save(); Path circle = new Path(); circle.addCircle(cx, cy, label, Path.Direction.CW); canvas.clipPath(circle);
        crop(canvas, card.cover(0), new RectF(cx - label, cy - label, cx + label, cy + label), paint); canvas.restore();
        paint.setColor(0xffc6c1ba); canvas.drawCircle(cx, cy, radius * .016f, paint);
    }

    private static void drawPhoto(Canvas canvas, Bitmap image, RectF bounds, float angle, Paint paint, boolean border) {
        canvas.save(); canvas.rotate(angle, bounds.centerX(), bounds.centerY());
        paint.setColor(0x30000000); canvas.drawRect(bounds.left + 9, bounds.top + 12, bounds.right + 9, bounds.bottom + 12, paint);
        if (border) { paint.setColor(0xffeee8de); canvas.drawRect(bounds.left - 9, bounds.top - 9, bounds.right + 9, bounds.bottom + 9, paint); }
        crop(canvas, image, bounds, paint); canvas.restore();
    }

    private static void crop(Canvas canvas, Bitmap image, RectF bounds, Paint paint) {
        paint.setAlpha(255); paint.setColor(0xffd0c6b9);
        if (image == null) {
            canvas.drawRect(bounds, paint); paint.setColor(0xff393039);
            float radius = Math.min(bounds.width(), bounds.height()) * .3f;
            canvas.drawCircle(bounds.centerX(), bounds.centerY(), radius, paint);
            paint.setColor(0xffa9958a); canvas.drawCircle(bounds.centerX(), bounds.centerY(), radius * .38f, paint); return;
        }
        float scale = Math.max(bounds.width() / image.getWidth(), bounds.height() / image.getHeight());
        int sw = Math.max(1, Math.min(image.getWidth(), Math.round(bounds.width() / scale)));
        int sh = Math.max(1, Math.min(image.getHeight(), Math.round(bounds.height() / scale)));
        int sx = (image.getWidth() - sw) / 2, sy = (image.getHeight() - sh) / 2;
        canvas.drawBitmap(image, new Rect(sx, sy, sx + sw, sy + sh), bounds, paint);
    }

    private static void block(Canvas canvas, String value, float left, float top, int width,
                              float size, int color, int lines, boolean bold, Layout.Alignment alignment) {
        if (value == null || value.isEmpty()) return;
        TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG); text.setColor(color); text.setTextSize(size);
        text.setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
        StaticLayout layout = StaticLayout.Builder.obtain(value, 0, value.length(), text, width)
                .setAlignment(alignment).setIncludePad(false).setLineSpacing(5, 1)
                .setMaxLines(lines).setEllipsize(TextUtils.TruncateAt.END).build();
        canvas.save(); canvas.translate(left, top); layout.draw(canvas); canvas.restore();
    }

    private static Bitmap decodeAsset(Context context, String asset, int max) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
            try (InputStream input = context.getAssets().open(asset)) { BitmapFactory.decodeStream(input, null, bounds); }
            BitmapFactory.Options options = new BitmapFactory.Options(); options.inSampleSize = 1;
            while (Math.max(bounds.outWidth, bounds.outHeight) / options.inSampleSize > max * 1.4f) options.inSampleSize *= 2;
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            try (InputStream input = context.getAssets().open(asset)) { return BitmapFactory.decodeStream(input, null, options); }
        } catch (IOException | RuntimeException exception) { return null; }
    }
    private static int contrast(int color) { return (.2126 * Color.red(color) + .7152 * Color.green(color) + .0722 * Color.blue(color)) > 150 ? 0xff222126 : Color.WHITE; }
    private static int muted(int color) { return color == Color.WHITE ? 0xddffffff : 0xb3222126; }
    private Button button(String value, int ink, int color, Runnable action) {
        Button view = new Button(activity); view.setText(value); view.setTextColor(ink); view.setTextSize(14); view.setAllCaps(false);
        view.setMinWidth(0); view.setMinimumWidth(0); view.setMinHeight(0); view.setMinimumHeight(0); view.setPadding(dp(8), 0, dp(8), 0);
        view.setBackground(ripple(color, dp(25))); view.setOnClickListener(v -> action.run()); return view;
    }
    private TextView text(String value, int size, int color) { TextView view = new TextView(activity); view.setText(value); view.setTextSize(size); view.setTextColor(color); return view; }
    private static GradientDrawable round(int color, int radius) { GradientDrawable value = new GradientDrawable(); value.setColor(color); value.setCornerRadius(radius); return value; }
    private static RippleDrawable ripple(int color, int radius) { return new RippleDrawable(ColorStateList.valueOf(0x25000000), round(color, radius), round(Color.WHITE, radius)); }
    private void toast(String value) { if (!activity.isFinishing() && !activity.isDestroyed()) Toast.makeText(activity, value, Toast.LENGTH_SHORT).show(); }
    private int dp(float value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
}
