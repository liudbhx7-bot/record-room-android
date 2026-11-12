package com.recordroom.vinyl;

import android.app.Activity;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ComponentName;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.LinearGradient;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.RectF;
import android.graphics.drawable.RippleDrawable;
import java.util.ArrayList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Per-widget setup, reconfiguration, and an in-app gallery for new-widget defaults. */
public final class WidgetAppearanceActivity extends Activity {
    private static final String[] MODE_NAMES = {"随唱机", "随封面", "自选"};
    private static final String[] STYLE_ICONS = {"disc", "album", "signal", "clock"};
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService previewsIO = Executors.newSingleThreadExecutor(r -> new Thread(r, "vinyl-widget-preview"));
    private final Runnable playbackChanged = this::refreshPreview;
    private final LinearLayout[] cards = new LinearLayout[4];
    private final TextView[] cardLabels = new TextView[4], modes = new TextView[3];
    private final PlayerControls.Icon[] icons = new PlayerControls.Icon[4];
    private final TextView[] swatches = new TextView[ThemeStore.COLORS.length];
    private final ArrayList<TextView> secondaryLabels = new ArrayList<>();
    private LinearLayout screen, modeList;
    private ImageView preview;
    private FrameLayout stage;
    private TextView styleName, title, dimensions, modeLabel, modeValue;
    private PlayerControls.Icon back;
    private int page, muted, accent, edge;
    private static final int LIGHT = 0xfffcf6ee;
    private int widgetId = AppWidgetManager.INVALID_APPWIDGET_ID;
    private int style, colorMode, color;
    private boolean transparent, saving;
    private long previewGeneration;
    private Future<?> previewTask;
    private TextView save, pin;
    private HorizontalScrollView palette;
    private Switch transparency;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        widgetId = getIntent().getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID);
        Intent canceled = new Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId);
        setResult(RESULT_CANCELED, canceled);
        boolean configureAction = AppWidgetManager.ACTION_APPWIDGET_CONFIGURE.equals(getIntent().getAction());
        if (configureAction && widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return; }
        if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            AppWidgetProviderInfo info = AppWidgetManager.getInstance(this).getAppWidgetInfo(widgetId);
            if (info == null || !new ComponentName(this, VinylWidgetProvider.class).equals(info.provider)) { finish(); return; }
        }
        VinylWidgetProvider.Options options = VinylWidgetProvider.options(this, widgetId);
        style = state == null ? options.style : state.getInt("style", options.style);
        colorMode = state == null ? options.colorMode : state.getInt("colorMode", options.colorMode);
        color = state == null ? options.color : state.getInt("color", options.color);
        transparent = state == null ? options.transparent : state.getBoolean("transparent", options.transparent);
        style = Math.max(0, Math.min(3, style));
        colorMode = Math.max(0, Math.min(2, colorMode));
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        if (Build.VERSION.SDK_INT >= 29) getWindow().setNavigationBarContrastEnforced(false);
        build();
        applyPalette(DesignSystem.currentTint(this));
    }

    private void build() {
        screen = column();
        screen.setGravity(Gravity.CENTER_HORIZONTAL);
        screen.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            } else view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        setContentView(screen);
        // Keep a single comfortable reading column on open foldables and wide launchers.
        int width = getResources().getConfiguration().screenWidthDp > 600 ? dp(600) : -1;
        LinearLayout header = row();
        header.setPadding(dp(8), 0, dp(20), 0);
        back = new PlayerControls.Icon(this, "back");
        back.setContentDescription("返回");
        back.setOnClickListener(v -> finish());
        header.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));
        title = label("桌面小组件", 18, LIGHT);
        header.addView(title);
        screen.addView(header, new LinearLayout.LayoutParams(width, dp(56)));

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setFillViewport(true);
        screen.addView(scroll, new LinearLayout.LayoutParams(width, 0, 1));
        LinearLayout body = column();
        body.setPadding(dp(20), dp(6), dp(20), dp(12));
        scroll.addView(body);
        body.addView(caption("当前样式"));
        LinearLayout nameRow = row();
        styleName = label("", 29, LIGHT);
        styleName.setTypeface(Typeface.SERIF, Typeface.BOLD);
        styleName.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        nameRow.addView(styleName, new LinearLayout.LayoutParams(0, -2, 1));
        dimensions = label("4 × 2", 12, LIGHT);
        dimensions.setContentDescription("默认桌面尺寸，四列两行");
        dimensions.setPadding(dp(10), dp(4), dp(10), dp(4));
        nameRow.addView(dimensions);
        body.addView(nameRow, spaced(-1, -2, 4));

        stage = new FrameLayout(this);
        stage.setClipToOutline(true);
        stage.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override public void getOutline(View view, android.graphics.Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(18));
            }
        });
        preview = new ImageView(this);
        preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        preview.setPadding(dp(20), dp(22), dp(20), dp(22));
        stage.addView(preview, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        int stageHeight = Math.max(174, Math.min(310, Math.round(getResources().getConfiguration().screenWidthDp * .57f)));
        body.addView(stage, spaced(-1, dp(stageHeight), 12));
        body.addView(caption("切换样式"), spaced(-1, -2, 18));
        LinearLayout choices = row();
        for (int i = 0; i < cards.length; i++) {
            final int selected = i;
            LinearLayout tile = column();
            tile.setPadding(dp(2), dp(8), dp(2), dp(6));
            tile.setGravity(Gravity.CENTER);
            tile.setFocusable(true);
            tile.setOnClickListener(v -> { style = selected; updateChoices(); refreshPreview(); });
            icons[i] = new PlayerControls.Icon(this, STYLE_ICONS[i]);
            icons[i].setClickable(false);
            icons[i].setFocusable(false);
            icons[i].setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            tile.addView(icons[i], new LinearLayout.LayoutParams(dp(32), dp(28)));
            cardLabels[i] = label(VinylWidgetProvider.STYLE_NAMES[i], 12, LIGHT);
            cardLabels[i].setGravity(Gravity.CENTER);
            cardLabels[i].setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            tile.addView(cardLabels[i]);
            cards[i] = tile;
            LinearLayout.LayoutParams space = new LinearLayout.LayoutParams(0, -2, 1);
            if (i > 0) space.leftMargin = dp(5);
            choices.addView(tile, space);
        }
        body.addView(choices, spaced(-1, -2, 8));

        LinearLayout colorRow = row();
        colorRow.setMinimumHeight(dp(52));
        modeLabel = label("组件配色", 15, LIGHT);
        modeValue = label("", 14, LIGHT);
        colorRow.addView(modeLabel, new LinearLayout.LayoutParams(0, -2, 1));
        colorRow.addView(modeValue);
        colorRow.setFocusable(true);
        colorRow.setOnClickListener(v -> {
            modeList.setVisibility(modeList.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
            updateChoices();
        });
        body.addView(colorRow, spaced(-1, -2, 10));

        modeList = row();
        modeList.setVisibility(View.GONE);
        for (int i = 0; i < MODE_NAMES.length; i++) {
            final int mode = i;
            modes[i] = action(MODE_NAMES[i]);
            modes[i].setOnClickListener(v -> {
                colorMode = mode;
                modeList.setVisibility(View.GONE);
                updateChoices();
                colorRow.requestFocus();
                refreshPreview();
            });
            LinearLayout.LayoutParams space = new LinearLayout.LayoutParams(0, -2, 1);
            if (i > 0) space.leftMargin = dp(6);
            modeList.addView(modes[i], space);
        }
        body.addView(modeList);
        palette = new HorizontalScrollView(this);
        palette.setHorizontalScrollBarEnabled(false);
        LinearLayout paletteRow = row();
        paletteRow.setPadding(0, dp(5), 0, dp(5));
        palette.addView(paletteRow);
        for (int i = 0; i < swatches.length; i++) {
            final int index = i;
            TextView swatch = action("");
            swatches[i] = swatch;
            swatch.setOnClickListener(v -> { color = ThemeStore.COLORS[index]; updateChoices(); refreshPreview(); });
            LinearLayout.LayoutParams space = new LinearLayout.LayoutParams(dp(48), dp(48));
            if (i > 0) space.leftMargin = dp(6);
            paletteRow.addView(swatch, space);
        }
        body.addView(palette);
        transparency = new Switch(this);
        transparency.setText("透明背景");
        transparency.setTextSize(15);
        transparency.setMinHeight(dp(50));
        transparency.setChecked(transparent);
        transparency.setOnCheckedChangeListener((v, checked) -> { transparent = checked; refreshPreview(); });
        body.addView(transparency, new LinearLayout.LayoutParams(-1,-2));

        LinearLayout footer = row();
        footer.setPadding(dp(20), dp(8), dp(20), dp(12));
        boolean defaults = widgetId == AppWidgetManager.INVALID_APPWIDGET_ID;
        save = action(defaults ? "保存默认外观" : VinylWidgetProvider.configured(this, widgetId) ? "保存外观" : "添加小组件");
        save.setOnClickListener(v -> save(false));
        footer.addView(save, new LinearLayout.LayoutParams(0, -2, 1));
        if (defaults) {
            pin = action("添加到桌面");
            pin.setOnClickListener(v -> save(true));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
            params.leftMargin = dp(8);
            footer.addView(pin, params);
        }
        screen.addView(footer, new LinearLayout.LayoutParams(width, -2));
        screen.requestApplyInsets();
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }
    private LinearLayout row() {
        LinearLayout layout = new LinearLayout(this);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        return layout;
    }
    private LinearLayout.LayoutParams spaced(int w, int h, int top) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(w, h);
        params.topMargin = dp(top);
        return params;
    }
    private TextView caption(String value) {
        TextView text = label(value, 13, LIGHT);
        secondaryLabels.add(text);
        return text;
    }
    private VinylWidgetProvider.Options choice() { return new VinylWidgetProvider.Options(style, colorMode, color, transparent); }

    private void applyPalette(int pigment) {
        page = DesignSystem.mix(0xff171718, pigment, .27f);
        accent = DesignSystem.mix(pigment, 0xfffff0e2, .58f);
        muted = DesignSystem.mix(LIGHT, page, .24f);
        edge = DesignSystem.mix(page, LIGHT, .29f);
        screen.setBackgroundColor(page);
        getWindow().setStatusBarColor(page);
        getWindow().setNavigationBarColor(page);
        back.setInk(LIGHT);
        for (TextView label : secondaryLabels) label.setTextColor(muted);
        modeValue.setTextColor(muted);
        dimensions.setTextColor(muted);
        dimensions.setBackground(rounded(page, edge, 1, 18));
        stage.setBackground(new Stand(pigment, page));
        transparency.setTextColor(LIGHT);
        int[][] states = {new int[]{android.R.attr.state_checked},new int[]{}};
        transparency.setThumbTintList(new ColorStateList(states,new int[]{accent,muted}));
        transparency.setTrackTintList(new ColorStateList(states,new int[]{edge,DesignSystem.mix(page,LIGHT,.20f)}));
        styleButton(save, pin == null);
        if (pin != null) styleButton(pin, true);
        updateChoices();
    }

    private void styleButton(TextView btn, boolean primary) {
        btn.setTextColor(primary ? Color.BLACK : LIGHT);
        btn.setBackground(touch(primary ? accent : page, primary ? accent : edge, 14));
    }
    private void updateChoices() {
        styleName.setText(VinylWidgetProvider.STYLE_NAMES[style]);
        for (int i = 0; i < cards.length; i++) {
            boolean selected = style == i;
            int ink = selected ? LIGHT : muted;
            cards[i].setSelected(selected);
            cards[i].setBackground(touch(selected ? DesignSystem.mix(page,accent,.18f) : page,selected ? accent : edge,12));
            cards[i].setContentDescription(VinylWidgetProvider.STYLE_NAMES[i] + (selected ? "，已选择。" : "。") + VinylWidgetProvider.STYLE_DETAILS[i]);
            cardLabels[i].setTextColor(ink);
            icons[i].setInk(ink);
        }
        modeValue.setText(MODE_NAMES[colorMode] + (modeList.getVisibility() == View.VISIBLE ? "  ▴" : "  ▾"));
        ((View)modeValue.getParent()).setContentDescription("组件配色，" + MODE_NAMES[colorMode] + (modeList.getVisibility() == View.VISIBLE ? "，收起" : "，展开"));
        for (int i = 0; i < modes.length; i++) {
            modes[i].setSelected(i == colorMode);
            styleButton(modes[i], i == colorMode);
        }
        palette.setVisibility(colorMode == VinylWidgetProvider.COLOR_FIXED ? View.VISIBLE : View.GONE);
        for (int i = 0; i < swatches.length; i++) {
            boolean active = color == ThemeStore.COLORS[i];
            swatches[i].setSelected(active);
            swatches[i].setBackground(touch(ThemeStore.COLORS[i],active ? LIGHT : edge,14));
            swatches[i].setTextColor(Color.luminance(ThemeStore.COLORS[i]) > .18f ? Color.BLACK : Color.WHITE);
            swatches[i].setText(active ? "✓" : "");
            swatches[i].setContentDescription(ThemeStore.COLOR_NAMES[i] + (active ? "，已选择" : ""));
        }
    }

    private void refreshPreview() {
        if (isDestroyed()) return;
        long generation = ++previewGeneration;
        if (previewTask != null) previewTask.cancel(true);
        VinylWidgetProvider.Options options = choice();
        Track track = PlayerService.widgetTrack(this);
        boolean playing = PlayerService.widgetPlaying();
        android.content.Context app = getApplicationContext();
        ExternalMusicBridge.State link = WidgetLinkControl.current(app);
        previewTask = previewsIO.submit(() -> {
            VinylWidgetProvider.Snapshot snapshot = VinylWidgetProvider.snapshot(app, track, playing, true, link);
            if (Thread.currentThread().isInterrupted()) return;
            Bitmap image = VinylWidgetProvider.render(app, snapshot, options, 320, 160);
            main.post(() -> {
                if (generation != previewGeneration || isDestroyed()) return;
                int pigment = snapshot.artworkPalette != null ? snapshot.artworkPalette.record : DesignSystem.currentTint(this);
                applyPalette(pigment);
                preview.setImageBitmap(image);
                preview.setContentDescription(VinylWidgetProvider.STYLE_NAMES[style] + "预览，" + snapshot.title + "，" + snapshot.artist);
            });
        });
    }

    private void save(boolean addToHome) {
        if (saving) return;
        saving = true; save.setEnabled(false); save.setAlpha(.55f); if (pin != null) pin.setEnabled(false);
        VinylWidgetProvider.saveAndUpdate(this, widgetId, choice(), success -> {
            if (isDestroyed()) return;
            saving = false; save.setEnabled(true); save.setAlpha(1f); if (pin != null) pin.setEnabled(true);
            if (!success) { Toast.makeText(this, "暂时无法保存，请重试", Toast.LENGTH_SHORT).show(); return; }
            Intent result = new Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId);
            setResult(RESULT_OK, result);
            if (addToHome) {
                AppWidgetManager manager = AppWidgetManager.getInstance(this);
                boolean requested = manager.isRequestPinAppWidgetSupported() && manager.requestPinAppWidget(new ComponentName(this, VinylWidgetProvider.class), null, null);
                if (!requested) Toast.makeText(this, "请长按桌面，在小组件中添加唱片机", Toast.LENGTH_LONG).show();
                else Toast.makeText(this, "已保存外观，请在桌面确认添加", Toast.LENGTH_SHORT).show();
                return;
            }
            if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) Toast.makeText(this, "新小组件将使用这个外观", Toast.LENGTH_SHORT).show();
            finish();
        });
    }

    @Override protected void onResume() {
        super.onResume();
        VinylWidgetProvider.addPreviewListener(playbackChanged);
        refreshPreview();
    }
    @Override protected void onPause() {
        VinylWidgetProvider.removePreviewListener(this, playbackChanged);
        super.onPause();
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        out.putInt("style", style); out.putInt("colorMode", colorMode); out.putInt("color", color); out.putBoolean("transparent", transparent);
        super.onSaveInstanceState(out);
    }

    @Override protected void onDestroy() {
        previewGeneration++; if (previewTask != null) previewTask.cancel(true); previewsIO.shutdownNow(); super.onDestroy();
    }

    private TextView label(String value, int size, int color) {
        TextView label = new TextView(this); label.setText(value); label.setTextSize(size); label.setTextColor(color); label.setFontFeatureSettings("kern"); return label;
    }

    private TextView action(String value) {
        TextView button = label(value, 14, LIGHT);
        button.setGravity(Gravity.CENTER);
        button.setClickable(true); button.setFocusable(true); button.setMinHeight(dp(48));
        button.setPadding(dp(4),dp(6),dp(4),dp(6));
        return button;
    }

    private RippleDrawable touch(int fill,int border,int radius) {
        return new RippleDrawable(ColorStateList.valueOf(0x28ffffff), rounded(fill,border,1,radius), null);
    }

    private GradientDrawable rounded(int fill, int stroke, int width, int radius) {
        GradientDrawable background = new GradientDrawable(); background.setColor(fill); background.setCornerRadius(dp(radius));
        if (width > 0) background.setStroke(dp(width), stroke); return background;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private static final class Stand extends android.graphics.drawable.Drawable {
        private final int pigment, page;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF arc = new RectF();
        private LinearGradient ground;
        private RadialGradient light;

        Stand(int pigment, int page) { this.pigment = pigment; this.page = page; }

        @Override protected void onBoundsChange(android.graphics.Rect r) {
            if (r.isEmpty()) return;
            ground = new LinearGradient(0,0,r.width(),r.height(),
                    DesignSystem.mix(page,pigment,.74f), page, Shader.TileMode.CLAMP);
            light = new RadialGradient(r.width()*.25f, r.height()*.1f, r.width()*.85f,
                    0x55ffdfb9, 0x00ffdfb9, Shader.TileMode.CLAMP);
        }
        @Override public void draw(Canvas c) {
            android.graphics.Rect r = getBounds();
            paint.setColor(Color.WHITE);
            paint.setStyle(Paint.Style.FILL);
            paint.setShader(ground); c.drawRect(r,paint);
            paint.setShader(light); c.drawRect(r,paint); paint.setShader(null);
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(Math.max(1f,r.width()*.002f)); paint.setColor(0x25ffffff);
            arc.set(-r.width()*.19f, r.height()*.75f, r.width()*1.55f, r.height()*2.8f);
            int n=c.save(); c.rotate(-10,r.centerX(),r.centerY());
            c.drawOval(arc,paint); arc.offset(0,r.height()*.1f); c.drawOval(arc,paint);
            c.restoreToCount(n);
        }
        @Override public void setAlpha(int alpha) { }
        @Override public void setColorFilter(android.graphics.ColorFilter filter) { }
        @Override public int getOpacity() { return android.graphics.PixelFormat.OPAQUE; }
    }
}
