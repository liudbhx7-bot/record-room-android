package com.recordroom.vinyl;

import android.app.Activity;
import android.app.Dialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Thumb-reachable controls. The underlying turntable stays visible and keeps playing. */
final class PlayerToolDrawer extends Dialog {
    private final Activity host;
    private final LinearLayout panel, grid;
    private final TextView title;
    private final View handle;
    private final Button quality;
    private final PlayerControls.Icon more, close;
    private final Runnable onInteraction;
    private LinearLayout activeRow;

    PlayerToolDrawer(Activity activity, Runnable audioQuality, Runnable trackActions, Runnable onInteraction) {
        super(activity);
        host = activity;
        this.onInteraction = onInteraction;
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setTitle("播放工具");
        setCanceledOnTouchOutside(true);

        ScrollView scroll = new ScrollView(host);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setPadding(dp(10), dp(6), dp(10), dp(10));
        panel = new LinearLayout(host);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(13), dp(9), dp(13), dp(14));
        handle = new View(host);
        LinearLayout.LayoutParams grip = new LinearLayout.LayoutParams(dp(30), dp(3));
        grip.gravity = Gravity.CENTER_HORIZONTAL;
        panel.addView(handle, grip);

        LinearLayout top = new LinearLayout(host);
        top.setGravity(Gravity.CENTER_VERTICAL);
        title = new TextView(host);
        title.setText("播放工具");
        title.setTextSize(17);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        top.addView(title, new LinearLayout.LayoutParams(0,-2,1));
        quality = new Button(host);
        quality.setText("音质");
        quality.setTextSize(14);
        quality.setContentDescription("查看音质与输出");
        quality.setMinWidth(0);
        quality.setMinimumWidth(dp(48));
        quality.setPadding(dp(6),0,dp(6),0);
        quality.setOnClickListener(v -> open(audioQuality));
        top.addView(quality,new LinearLayout.LayoutParams(-2,-2));
        more = topButton(top, "more", "更多歌曲操作", () -> open(trackActions));
        close = topButton(top, "down", "收起工具", this::dismiss);
        panel.addView(top);
        grid = new LinearLayout(host);
        grid.setOrientation(LinearLayout.VERTICAL);
        panel.addView(grid);
        scroll.addView(panel);
        setContentView(scroll);
        Window w = getWindow();
        w.setGravity(Gravity.BOTTOM);
        w.setDimAmount(.08f);
        w.setWindowAnimations(android.R.style.Animation_InputMethod);
        w.setBackgroundDrawableResource(android.R.color.transparent);
    }

    void addTool(String symbol, String label, Runnable action) {
        if (activeRow == null || activeRow.getChildCount() == 3) {
            activeRow = new LinearLayout(host);
            grid.addView(activeRow);
        }
        LinearLayout cell = new LinearLayout(host);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER);
        cell.setPadding(dp(2), dp(8), dp(2), dp(8));
        cell.setMinimumHeight(dp(56));
        cell.setFocusable(true);
        cell.setContentDescription(label);
        cell.setOnClickListener(v -> open(action));
        PlayerControls.Icon icon = new PlayerControls.Icon(host,symbol);
        icon.setClickable(false);
        icon.setFocusable(false);
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        cell.addView(icon,new LinearLayout.LayoutParams(dp(22), dp(22)));
        TextView name = new TextView(host);
        name.setText(label);
        name.setTextSize(13);
        name.setGravity(Gravity.CENTER);
        name.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        cell.addView(name);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,-2,1);
        lp.setMargins(dp(2),dp(2),dp(2),dp(2));
        activeRow.addView(cell,lp);
    }

    void tint(int base, int ink, int accent) {
        int body = DesignSystem.mix(base,ink,.07f);
        GradientDrawable skin = DesignSystem.rounded(host,body,20);
        skin.setStroke(dp(1),DesignSystem.mix(base,ink,.22f));
        panel.setBackground(skin);
        title.setTextColor(ink);
        more.setInk(ink);
        close.setInk(ink);
        handle.setBackground(DesignSystem.rounded(host,DesignSystem.mix(body,ink,.52f),2));
        quality.setTextColor(ink);
        quality.setBackground(ripple(body));
        for (int r=0; r<grid.getChildCount(); r++) {
            LinearLayout row = (LinearLayout)grid.getChildAt(r);
            for (int c=0; c<row.getChildCount(); c++) {
                LinearLayout cell = (LinearLayout)row.getChildAt(c);
                int surface = r==0 && c==0 ? DesignSystem.mix(accent,ink,.46f) : DesignSystem.mix(body,ink,.07f);
                int foreground = Color.luminance(surface)>.179f ? Color.BLACK : Color.WHITE;
                cell.setBackground(ripple(surface));
                ((PlayerControls.Icon)cell.getChildAt(0)).setInk(foreground);
                ((TextView)cell.getChildAt(1)).setTextColor(foreground);
            }
        }
    }

    @Override protected void onStart() {
        super.onStart();
        int available = host.findViewById(android.R.id.content).getWidth();
        if (available <= 0) available = host.getResources().getDisplayMetrics().widthPixels;
        getWindow().setLayout(Math.min(dp(540),available),-2);
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_UP) onInteraction.run();
        return super.dispatchTouchEvent(event);
    }

    private RippleDrawable ripple(int color) {
        return new RippleDrawable(ColorStateList.valueOf(0x36888888),DesignSystem.rounded(host,color,12),null);
    }

    private PlayerControls.Icon topButton(LinearLayout row, String symbol, String label, Runnable action) {
        PlayerControls.Icon view = new PlayerControls.Icon(host,symbol);
        view.setContentDescription(label);
        view.setOnClickListener(v -> action.run());
        row.addView(view,new LinearLayout.LayoutParams(dp(48),dp(48)));
        return view;
    }

    private void open(Runnable action) { dismiss(); action.run(); }
    private int dp(int value) { return DesignSystem.dp(host,value); }
}
