package com.recordroom.vinyl;

import android.app.Activity;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ComponentName;
import android.content.Intent;
import android.content.res.ColorStateList;
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
    private static final int BACKGROUND = DesignSystem.PAPER, INK = DesignSystem.INK, MUTED = DesignSystem.MUTED;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService previewsIO = Executors.newSingleThreadExecutor(r -> new Thread(r, "vinyl-widget-preview"));
    private final ImageView[] previews = new ImageView[4];
    private final LinearLayout[] cards = new LinearLayout[4];
    private final TextView[] checks = new TextView[4];
    private final TextView[] modes = new TextView[3];
    private final TextView[] swatches = new TextView[ThemeStore.COLORS.length];
    private int widgetId = AppWidgetManager.INVALID_APPWIDGET_ID;
    private int style, colorMode, color;
    private boolean transparent, saving;
    private long previewGeneration;
    private Future<?> previewTask;
    private TextView save, pin, colorExplanation;
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
        getWindow().setStatusBarColor(BACKGROUND);
        getWindow().setNavigationBarColor(BACKGROUND);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        build();
    }

    private void build() {
        LinearLayout screen = new LinearLayout(this);
        screen.setOrientation(LinearLayout.VERTICAL); screen.setBackground(DesignSystem.paper(this));
        screen.setOnApplyWindowInsetsListener((view, insets) -> {
            int top, bottom, left, right;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                top = bars.top; bottom = bars.bottom; left = bars.left; right = bars.right;
            } else {
                top = insets.getSystemWindowInsetTop(); bottom = insets.getSystemWindowInsetBottom();
                left = insets.getSystemWindowInsetLeft(); right = insets.getSystemWindowInsetRight();
            }
            view.setPadding(left, top, right, bottom);
            return insets;
        });
        setContentView(screen);
        LinearLayout header = new LinearLayout(this); header.setGravity(Gravity.CENTER_VERTICAL); header.setPadding(dp(12), 0, dp(18), 0);
        TextView back = label("‹", 32, INK); back.setGravity(Gravity.CENTER); back.setContentDescription("返回"); back.setClickable(true); back.setFocusable(true); back.setOnClickListener(v -> finish());
        header.addView(back, new LinearLayout.LayoutParams(dp(48), dp(56)));
        TextView title = label("桌面小组件", 21, INK); title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, -2, 1); titleParams.leftMargin = dp(8); header.addView(title, titleParams);
        screen.addView(header, new LinearLayout.LayoutParams(-1, dp(64)));
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(false); scroll.setClipToPadding(false);
        screen.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL); body.setPadding(dp(20), dp(2), dp(20), dp(20)); scroll.addView(body);
        TextView introduction = label(widgetId == AppWidgetManager.INVALID_APPWIDGET_ID
                ? "选择喜欢的样式，放在桌面上继续听。" : "为这个小组件选择外观。", 14, MUTED);
        introduction.setPadding(0, 0, 0, dp(17)); body.addView(introduction, new LinearLayout.LayoutParams(-1, -2));
        for (int i = 0; i < 4; i++) {
            final int selectedStyle = i;
            LinearLayout card = new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL); card.setPadding(dp(12), dp(12), dp(12), dp(12));
            card.setClickable(true); card.setFocusable(true); card.setOnClickListener(v -> { style = selectedStyle; updateSelection(); });
            cards[i] = card;
            LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(-1, -2); cardParams.bottomMargin = dp(13); body.addView(card, cardParams);
            LinearLayout cardTitle = new LinearLayout(this); cardTitle.setGravity(Gravity.CENTER_VERTICAL);
            TextView name = label(VinylWidgetProvider.STYLE_NAMES[i], 16, INK); name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            name.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            cardTitle.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
            checks[i] = label("", 12, MUTED); checks[i].setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            cardTitle.addView(checks[i]); card.addView(cardTitle, new LinearLayout.LayoutParams(-1, dp(28)));
            TextView detail = label(VinylWidgetProvider.STYLE_DETAILS[i], 12, MUTED); detail.setPadding(0, dp(1), 0, dp(9));
            detail.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO); card.addView(detail);
            FrameLayout previewBox = new FrameLayout(this); previewBox.setBackground(new CheckerDrawable());
            previewBox.setClipToOutline(true); previewBox.setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override public void getOutline(View view, android.graphics.Outline outline) { outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(18)); }
            });
            ImageView image = new ImageView(this); image.setAdjustViewBounds(true); image.setScaleType(ImageView.ScaleType.FIT_CENTER); image.setMinimumHeight(dp(150));
            image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO); previews[i] = image;
            previewBox.addView(image, new FrameLayout.LayoutParams(-1, -2)); card.addView(previewBox, new LinearLayout.LayoutParams(-1, -2));
        }
        TextView previewNote = label("预览使用当前专辑；尚未选歌时显示演示唱片。", 11, MUTED);
        previewNote.setPadding(2, 0, 0, dp(22)); body.addView(previewNote);
        TextView colorTitle = label("背景颜色", 17, INK); colorTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); body.addView(colorTitle);
        LinearLayout colorModes = new LinearLayout(this); colorModes.setPadding(0, dp(10), 0, dp(8));
        String[] names = {"随唱机", "随封面", "自选"};
        for (int i = 0; i < 3; i++) {
            final int mode = i;
            TextView choice = action(names[i], false); modes[i] = choice;
            choice.setOnClickListener(v -> { colorMode = mode; updateColorControls(); refreshPreviews(); });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(48), 1); if (i > 0) params.leftMargin = dp(7); colorModes.addView(choice, params);
        }
        body.addView(colorModes, new LinearLayout.LayoutParams(-1, -2));
        colorExplanation = label("", 12, MUTED); colorExplanation.setPadding(0, 0, 0, dp(10)); body.addView(colorExplanation);
        palette = new HorizontalScrollView(this); palette.setHorizontalScrollBarEnabled(false);
        LinearLayout paletteRow = new LinearLayout(this); paletteRow.setPadding(0, 0, 0, dp(10)); palette.addView(paletteRow);
        for (int i = 0; i < ThemeStore.COLORS.length; i++) {
            final int index = i;
            TextView swatch = label("", 20, Color.WHITE); swatch.setGravity(Gravity.CENTER); swatch.setClickable(true); swatch.setFocusable(true);
            swatch.setContentDescription(ThemeStore.COLOR_NAMES[i]); swatches[i] = swatch;
            swatch.setOnClickListener(v -> { color = ThemeStore.COLORS[index]; updateColorControls(); refreshPreviews(); });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(48), dp(48)); if (i > 0) params.leftMargin = dp(8); paletteRow.addView(swatch, params);
        }
        body.addView(palette, new LinearLayout.LayoutParams(-1, -2));
        transparency = new Switch(this); transparency.setText("透明背景"); transparency.setTextColor(INK); transparency.setTextSize(16); transparency.setMinHeight(dp(52));
        transparency.setThumbTintList(new ColorStateList(new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}}, new int[]{0xff655b6f, 0xffe0dcd6}));
        transparency.setTrackTintList(new ColorStateList(new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}}, new int[]{0xffc3b8cd, 0xffccc7bf}));
        transparency.setChecked(transparent);
        transparency.setOnCheckedChangeListener((button, checked) -> { transparent = checked; refreshPreviews(); });
        body.addView(transparency, new LinearLayout.LayoutParams(-1, -2));
        body.addView(label("桌面壁纸会透过背景显示。", 12, MUTED));
        LinearLayout footer = new LinearLayout(this); footer.setOrientation(LinearLayout.VERTICAL); footer.setPadding(dp(20), dp(10), dp(20), dp(12)); footer.setBackgroundColor(BACKGROUND);
        save = action(widgetId == AppWidgetManager.INVALID_APPWIDGET_ID ? "保存默认外观" : VinylWidgetProvider.configured(this, widgetId) ? "保存外观" : "添加小组件", true);
        save.setOnClickListener(v -> save(false)); footer.addView(save, new LinearLayout.LayoutParams(-1, dp(48)));
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID && AppWidgetManager.getInstance(this).isRequestPinAppWidgetSupported()) {
            pin = action("添加到桌面", false); pin.setOnClickListener(v -> save(true));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(48)); params.topMargin = dp(7); footer.addView(pin, params);
        }
        screen.addView(footer, new LinearLayout.LayoutParams(-1, -2));
        updateSelection(); updateColorControls(); screen.requestApplyInsets();
    }

    private VinylWidgetProvider.Options choice() { return new VinylWidgetProvider.Options(style, colorMode, color, transparent); }

    private void updateSelection() {
        for (int i = 0; i < cards.length; i++) {
            boolean selected = i == style;
            cards[i].setSelected(selected); cards[i].setBackground(rounded(0x88ffffff, selected ? DesignSystem.ACCENT : 0xffe4e0da, selected ? 2 : 1, 16));
            cards[i].setContentDescription(VinylWidgetProvider.STYLE_NAMES[i] + (selected ? "，已选择" : "，点按选择") + "。" + VinylWidgetProvider.STYLE_DETAILS[i]);
            checks[i].setText(selected ? "✓ 已选择" : "选择"); checks[i].setTextColor(selected ? DesignSystem.ACCENT : MUTED);
        }
    }

    private void updateColorControls() {
        for (int i = 0; i < modes.length; i++) {
            boolean selected = i == colorMode; modes[i].setSelected(selected); modes[i].setTextColor(selected ? Color.WHITE : INK);
            modes[i].setBackground(rounded(selected ? DesignSystem.ACCENT : 0xffe8e1ed, Color.TRANSPARENT, 0, 12));
        }
        colorExplanation.setText(colorMode == VinylWidgetProvider.COLOR_THEME ? "沿用唱机设置；自动配色时，背景与唱片随封面变化。"
                : colorMode == VinylWidgetProvider.COLOR_ARTWORK ? "从当前封面搭配背景与唱片颜色。" : "为小组件保留你选定的背景色与唱片材质。");
        palette.setVisibility(colorMode == VinylWidgetProvider.COLOR_FIXED ? View.VISIBLE : View.GONE);
        for (int i = 0; i < swatches.length; i++) {
            boolean selected = color == ThemeStore.COLORS[i]; swatches[i].setSelected(selected);
            swatches[i].setBackground(rounded(ThemeStore.COLORS[i], selected ? INK : 0x22000000, selected ? 3 : 1, 13));
            swatches[i].setText(selected ? "✓" : ""); swatches[i].setTextColor(i == 4 || i == 6 ? INK : Color.WHITE);
            swatches[i].setContentDescription(ThemeStore.COLOR_NAMES[i] + (selected ? "，已选择" : ""));
        }
    }

    private void refreshPreviews() {
        if (previews[0] == null || isDestroyed()) return;
        final long serial = ++previewGeneration;
        if (previewTask != null) previewTask.cancel(true);
        final VinylWidgetProvider.Options options = choice();
        final Track track = PlayerService.widgetTrack(this);
        final boolean playing = PlayerService.widgetPlaying();
        final android.content.Context app = getApplicationContext();
        previewTask = previewsIO.submit(() -> {
            VinylWidgetProvider.Snapshot state = VinylWidgetProvider.snapshot(app, track, playing, true);
            Bitmap[] images = new Bitmap[4];
            for (int i = 0; i < images.length; i++) {
                if (Thread.currentThread().isInterrupted()) return;
                images[i] = VinylWidgetProvider.render(app, state, options.withStyle(i), 320, 190);
            }
            main.post(() -> {
                if (isDestroyed() || serial != previewGeneration) return;
                for (int i = 0; i < images.length; i++) previews[i].setImageBitmap(images[i]);
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
                boolean requested = AppWidgetManager.getInstance(this).requestPinAppWidget(new ComponentName(this, VinylWidgetProvider.class), null, null);
                if (!requested) Toast.makeText(this, "请长按桌面，在小组件中添加唱片机", Toast.LENGTH_LONG).show();
                else Toast.makeText(this, "已保存外观，请在桌面确认添加", Toast.LENGTH_SHORT).show();
                return;
            }
            if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) Toast.makeText(this, "新小组件将使用这个外观", Toast.LENGTH_SHORT).show();
            finish();
        });
    }

    @Override protected void onResume() { super.onResume(); if (previews[0] != null) refreshPreviews(); }

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

    private TextView action(String value, boolean primary) {
        TextView button = label(value, 15, primary ? Color.WHITE : INK); button.setGravity(Gravity.CENTER); button.setClickable(true); button.setFocusable(true);
        button.setMinHeight(dp(48));button.setBackground(rounded(primary ? DesignSystem.ACCENT : 0xffe8e1ed, Color.TRANSPARENT, 0, 12)); return button;
    }

    private GradientDrawable rounded(int fill, int stroke, int width, int radius) {
        GradientDrawable background = new GradientDrawable(); background.setColor(fill); background.setCornerRadius(dp(radius));
        if (width > 0) background.setStroke(dp(width), stroke); return background;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private static final class CheckerDrawable extends android.graphics.drawable.Drawable {
        private final Paint paint = new Paint();
        @Override public void draw(Canvas canvas) {
            android.graphics.Rect bounds = getBounds(); float cell = 16;
            canvas.drawColor(0xffb7bbb6);
            paint.setColor(0xffc2c7c1);
            for (float y = bounds.top; y < bounds.bottom; y += cell)
                for (float x = bounds.left; x < bounds.right; x += cell)
                    if ((((int)((x - bounds.left) / cell)) + ((int)((y - bounds.top) / cell))) % 2 == 0)
                        canvas.drawRect(x, y, Math.min(x + cell, bounds.right), Math.min(y + cell, bounds.bottom), paint);
        }
        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
        @Override public void setColorFilter(android.graphics.ColorFilter filter) { paint.setColorFilter(filter); }
        @Override public int getOpacity() { return android.graphics.PixelFormat.OPAQUE; }
    }
}
