package com.recordroom.vinyl;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Shader;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import java.lang.ref.WeakReference;

/** A real material preview with a separate, accessible selection ring. */
public final class DiscSwatchView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Matrix matrix = new Matrix();
    private int style, requestedSize = -1, generation;
    private Bitmap bitmap;
    private BitmapShader shader;
    private DiscMaterials.Request request;
    private float cx, cy, radius;
    private int tint;

    public DiscSwatchView(Context context, int style) {
        super(context);
        this.style = DiscMaterials.normalize(style);
        setClickable(true);
        setFocusable(true);
        setMinimumWidth(dp(48));
        setMinimumHeight(dp(48));
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        updateDescription();
    }

    /** Uses the same album-derived material color as the live turntable. */
    public void setRecordTint(int color) { if (tint != color) { tint = color; invalidate(); } }
    public int getStyle() { return style; }
    public void setStyle(int value) {
        int normalized = DiscMaterials.normalize(value);
        if (style == normalized) return;
        generation++;
        if (request != null) request.cancel();
        request = null;
        style = normalized;
        bitmap = null; shader = null; requestedSize = -1;
        updateDescription(); requestMaterial(); invalidate();
    }
    @Override public void setSelected(boolean value) {
        super.setSelected(value);
        updateDescription(); invalidate();
    }
    private void updateDescription() { setContentDescription(DiscMaterials.NAMES[style] + (isSelected() ? "，已选中" : "")); }
    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); requestMaterial(); }
    @Override protected void onDetachedFromWindow() {
        generation++;
        if (request != null) request.cancel();
        request = null; requestedSize = -1;
        super.onDetachedFromWindow();
    }
    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        cx = width / 2f; cy = height / 2f;
        radius = Math.max(1, Math.min(width, height) / 2f - dp(5));
        place(); requestMaterial();
    }
    private void place() {
        if (bitmap != null && shader != null) DiscMaterials.place(bitmap, shader, matrix, cx, cy, radius);
    }
    private void requestMaterial() {
        if (getWidth() <= 0 || getHeight() <= 0) return;
        int diameter = Math.max(1, (int) Math.ceil(radius * 2));
        int size = DiscMaterials.sizeForDiameter(diameter);
        if (requestedSize == size && (request != null || bitmap != null)) return;
        requestedSize = size;
        final int token = ++generation;
        if (request != null) request.cancel();
        WeakReference<DiscSwatchView> reference = new WeakReference<>(this);
        request = DiscMaterials.load(getContext(), style, diameter, value -> {
            DiscSwatchView view = reference.get();
            if (view == null || token != view.generation) return;
            view.request = null; view.bitmap = value;
            view.shader = value == null ? null : new BitmapShader(value, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            view.place(); view.invalidate();
        });
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (radius <= 0) return;
        paint.setShader(null); paint.setStyle(Paint.Style.FILL); paint.setColor(0x23000000);
        canvas.drawCircle(cx, cy + dp(1), radius + dp(1), paint);
        canvas.save();
        if (isPressed()) canvas.scale(.96f, .96f, cx, cy);
        paint.setColorFilter(tint == 0 ? null : DiscMaterials.tintFilter(tint));
        if (shader != null) {
            paint.setShader(shader); paint.setAlpha(isEnabled() ? DiscMaterials.alpha(style) : 90);
            canvas.drawCircle(cx, cy, radius, paint); paint.setShader(null);
        } else {
            paint.setColor(DiscMaterials.color(style));
            paint.setAlpha(isEnabled() ? DiscMaterials.alpha(style) : 90);
            canvas.drawCircle(cx, cy, radius, paint);
        }
        paint.setColorFilter(null);
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(Math.max(.6f, dp(1) * .6f));
        paint.setColor(style == 0 ? 0x99373941 : 0x80f3e7f4);
        canvas.drawCircle(cx, cy, radius, paint);
        paint.setStyle(Paint.Style.FILL); paint.setColor(tint != 0 ? tint : style == 5 ? 0xff197fb1 : style == 6 ? 0xff334425 : 0xff28244d);
        canvas.drawCircle(cx, cy, radius * .16f, paint);
        paint.setColor(0xff11141d); canvas.drawCircle(cx, cy, radius * .045f, paint);
        canvas.restore();
        if (isSelected()) {
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(1.5f * getResources().getDisplayMetrics().density); paint.setColor(0xffffffff);
            canvas.drawCircle(cx, cy, radius + dp(1), paint);
        }
        paint.setStyle(Paint.Style.FILL); paint.setAlpha(255);
    }
    @Override protected void drawableStateChanged() { super.drawableStateChanged(); invalidate(); }
    @Override public CharSequence getAccessibilityClassName() { return android.widget.RadioButton.class.getName(); }
    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setCheckable(true); info.setChecked(isSelected());
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
