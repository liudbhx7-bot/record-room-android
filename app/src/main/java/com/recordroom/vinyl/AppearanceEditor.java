package com.recordroom.vinyl;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Switch;
import java.util.ArrayList;
import java.util.Locale;

/** Controls float directly over the live turntable, as in the supplied appearance reference. */
final class AppearanceEditor {
    private final Activity activity;
    private final ThemeStore themes;
    private final String album, name;
    private final Bitmap cover;
    private final Runnable changed, artworkAction, dismissed;
    private final ArrayList<Dialog> children=new ArrayList<>();
    private final ArrayList<DiscSwatchView> discs=new ArrayList<>();
    private final ArrayList<View> colors=new ArrayList<>();
    private Dialog dialog;
    private LinearLayout body;
    private TextView albumTitle,scope,colorModeHint;
    private Switch automaticColors;
    private boolean updatingColorMode;
    private PlayerControls.Icon albumAdd;
    private boolean specific;
    AppearanceEditor(Activity a,ThemeStore t,String album,String name,Bitmap cover,Runnable changed,Runnable artworkAction,Runnable dismissed){activity=a;themes=t;this.album=album;this.name=name;this.cover=cover;this.changed=changed;this.artworkAction=artworkAction;this.dismissed=dismissed;specific=t.hasAlbum(album);}
    Dialog show(){
        dialog=new Dialog(activity);dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        FrameLayout page=new FrameLayout(activity);
        View closeSurface=new View(activity);closeSurface.setOnClickListener(v->dialog.dismiss());page.addView(closeSurface,new FrameLayout.LayoutParams(-1,-1));
        ScrollView scroll=new ScrollView(activity);scroll.setFillViewport(false);scroll.setVerticalScrollBarEnabled(false);scroll.setClipToPadding(false);
        body=column();body.setPadding(dp(22),0,dp(22),dp(30));scroll.addView(body);
        FrameLayout.LayoutParams panel=new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM);page.addView(scroll,panel);

        albumTitle=label("专属于 "+(name.isEmpty()?"当前专辑":name),14,false);
        albumTitle.setSingleLine(true);albumTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        body.addView(albumTitle,new LinearLayout.LayoutParams(-1,dp(22)));
        LinearLayout albumRow=row();albumRow.setGravity(Gravity.CENTER_VERTICAL);
        albumAdd=icon(specific?"check":"add","为当前专辑设置外观",()->{
            if(album.isEmpty())return;
            if(specific){showAlbumActions();return;}
            specific=true;themes.save(album,true,themes.disc(album),themes.colorFor(album,false));update();changed.run();
        },48);
        albumAdd.setBackground(insetRound(0xff272929,19));albumAdd.setInk(Color.WHITE);
        albumAdd.setOnLongClickListener(v->{showAlbumActions();return true;});albumRow.addView(albumAdd);
        LinearLayout.LayoutParams albumRowParams=new LinearLayout.LayoutParams(-1,dp(54));albumRowParams.bottomMargin=dp(12);body.addView(albumRow,albumRowParams);
        scope=label(specific?"专辑专属":"通用",14,false);scope.setContentDescription("当前外观作用范围；点按管理专辑外观");scope.setOnClickListener(v->showAlbumActions());body.addView(scope,new LinearLayout.LayoutParams(-1,dp(22)));

        HorizontalScrollView discScroll=new HorizontalScrollView(activity);discScroll.setHorizontalScrollBarEnabled(false);discScroll.setClipToPadding(false);
        LinearLayout discRow=row();discRow.setGravity(Gravity.CENTER_VERTICAL);
        PlayerControls.Icon random=icon("shuffle","随机唱片材质",()->selectDisc(new java.util.Random().nextInt(ThemeStore.DISC_NAMES.length)),48);
        random.setBackground(insetRound(0xff292b2b,19));random.setInk(Color.WHITE);LinearLayout.LayoutParams randomParams=new LinearLayout.LayoutParams(dp(48),dp(48));randomParams.rightMargin=dp(7);discRow.addView(random,randomParams);
        // Ordering is visual only. Persisted material IDs remain stable for existing users.
        int[] materialOrder={0,1,5,6,4,2,3,7,8};
        for(int style:materialOrder){DiscSwatchView swatch=new DiscSwatchView(activity,style);swatch.setOnClickListener(v->selectDisc(style));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(dp(48),dp(48));lp.rightMargin=dp(7);discRow.addView(swatch,lp);discs.add(swatch);}
        discScroll.addView(discRow);body.addView(discScroll,new LinearLayout.LayoutParams(-1,dp(56)));

        automaticColors=new Switch(activity);automaticColors.setText("背景和唱片随封面配色");automaticColors.setTextSize(14);automaticColors.setContentDescription("背景和唱片随封面自动配色开关");automaticColors.setMinimumHeight(dp(48));automaticColors.setChecked(themes.followCoverColors());
        automaticColors.setOnCheckedChangeListener((button,enabled)->{if(updatingColorMode)return;themes.followCoverColors(enabled);update();changed.run();});body.addView(automaticColors,new LinearLayout.LayoutParams(-1,dp(48)));
        colorModeHint=label("",12,false);colorModeHint.setPadding(0,0,0,dp(6));body.addView(colorModeHint,new LinearLayout.LayoutParams(-1,-2));

        HorizontalScrollView colorScroll=new HorizontalScrollView(activity);colorScroll.setHorizontalScrollBarEnabled(false);colorScroll.setClipToPadding(false);LinearLayout colorRow=row();colorRow.setGravity(Gravity.CENTER_VERTICAL);
        ColorTile auto=new ColorTile("automatic");auto.setContentDescription("随封面自动配色");auto.setOnClickListener(v->{themes.followCoverColors(true);update();changed.run();});addColorTile(colorRow,auto);
        for(int index:new int[]{2,3,4}){int color=ThemeStore.COLORS[index];ColorTile tile=new ColorTile(color);tile.setContentDescription("背景："+ThemeStore.COLOR_NAMES[index]);tile.setOnClickListener(v->selectColor(color));addColorTile(colorRow,tile);}
        ColorTile custom=new ColorTile("custom");custom.setContentDescription("自定义颜色和封面取色");custom.setOnClickListener(v->showColor());addColorTile(colorRow,custom);
        PlayerControls.Icon sun=icon("sun","唱片底灯亮度",this::showLighting,48);sun.setBackground(insetRound(0xff562a25,19));sun.setInk(0xffffecbd);colorRow.addView(sun);
        colorScroll.addView(colorRow);body.addView(colorScroll,new LinearLayout.LayoutParams(-1,dp(56)));

        LinearLayout bottom=row();bottom.setGravity(Gravity.CENTER_VERTICAL);
        PlayerControls.Icon collapse=icon("chevronDown","收起外观编辑",dialog::dismiss,48);collapse.setBackground(insetRound(0x55232f36,8));collapse.setInk(0xffaeb6ba);bottom.addView(collapse);
        // Existing custom-cover/reset operations remain available from the album control.
        LinearLayout.LayoutParams bottomParams=new LinearLayout.LayoutParams(-1,dp(48));bottomParams.topMargin=dp(20);body.addView(bottom,bottomParams);
        dialog.setContentView(page);Window w=dialog.getWindow();if(w!=null){w.setBackgroundDrawableResource(android.R.color.transparent);w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);w.setGravity(Gravity.BOTTOM);w.setNavigationBarColor(DesignSystem.stageColor(themes,album));}
        dialog.setOnDismissListener(d->{for(Dialog child:new ArrayList<>(children))child.dismiss();children.clear();dismissed.run();});dialog.show();if(w!=null)w.setLayout(-1,-1);
        page.setOnApplyWindowInsetsListener((v,insets)->{v.setPadding(insets.getSystemWindowInsetLeft(),insets.getStableInsetTop(),insets.getSystemWindowInsetRight(),insets.getStableInsetBottom());return insets;});page.requestApplyInsets();
        scroll.post(()->{if(!dialog.isShowing())return;int available=Math.max(dp(140),page.getHeight()-page.getPaddingTop()-page.getPaddingBottom()-dp(80));scroll.getLayoutParams().height=Math.min(dp(386),available);scroll.requestLayout();});update();return dialog;
    }
    private void addColorTile(LinearLayout row,ColorTile tile){LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(dp(48),dp(48));lp.rightMargin=dp(7);row.addView(tile,lp);colors.add(tile);}
    private android.graphics.drawable.Drawable insetRound(int color,int radius){GradientDrawable g=round(color,radius);g.setStroke(dp(1),0x337f8587);return new android.graphics.drawable.InsetDrawable(g,dp(5));}
    private void showAlbumActions(){
        boolean saved=themes.hasAlbum(album);
        String[] options=album.isEmpty()?new String[]{"更换封面"}:new String[]{saved?"使用通用外观":"为当前专辑设置外观","更换封面"};
        AlertDialog d=new AlertDialog.Builder(activity).setTitle(name.isEmpty()?"当前专辑":name).setItems(options,(which,index)->{
            if(album.isEmpty()||index==1){dialog.dismiss();artworkAction.run();return;}
            if(saved){themes.clearAlbum(album);specific=false;}else{themes.save(album,true,themes.disc(album),themes.colorFor(album,false));specific=true;}
            update();changed.run();
        }).setNegativeButton("取消",null).create();children.add(d);d.setOnDismissListener(v->children.remove(d));d.show();
    }
    private void selectDisc(int style){themes.save(album,specific,style,themes.colorFor(album,specific));update();changed.run();}
    private void selectColor(int color){themes.followCoverColors(false);themes.save(album,specific,themes.discFor(album,specific),color);update();changed.run();}
    private void update(){
        if(body==null)return;
        int current=themes.discFor(album,specific),background=themes.followCoverColors()?DesignSystem.stageColor(themes,album):themes.colorFor(album,specific);
        int tint=themes.recordTint(album);
        updatingColorMode=true;automaticColors.setChecked(themes.followCoverColors());updatingColorMode=false;
        colorModeHint.setText(themes.followCoverColors()?"自动模式：封面决定背景与盘色，材质纹理保持。":"手动模式：使用下方固定颜色与原材质；打开开关恢复自动。");
        for(DiscSwatchView swatch:discs){swatch.setSelected(swatch.getStyle()==current);swatch.setRecordTint(tint);}
        boolean preset=false;for(View tile:colors)if(tile.getTag() instanceof Integer&&((Integer)tile.getTag())==background)preset=true;
        for(View view:colors){ColorTile tile=(ColorTile)view;Object tag=tile.getTag();boolean auto="automatic".equals(tag),custom="custom".equals(tag);
            boolean selected=themes.followCoverColors()?auto:custom?!preset:tag instanceof Integer&&((Integer)tag)==background;
            tile.configure(background,selected);}
        scope.setText(specific?"专辑专属":"通用");albumAdd.setSymbol(specific?"check":"add");
        albumAdd.setContentDescription(specific?"管理当前专辑外观与封面":"为当前专辑设置外观；长按更换封面");
        int ink=MainActivity.readableInk(background);recolor(body,ink);
        body.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,new int[]{0x00080808,0x18080808,0x34080808}));
    }
    private final class ColorTile extends View {
        private final android.graphics.Paint paint=new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.RectF rect=new android.graphics.RectF();
        private int background;
        ColorTile(Object value){super(activity);setTag(value);setClickable(true);setFocusable(true);setMinimumWidth(dp(48));setMinimumHeight(dp(48));}
        void configure(int background,boolean selected){this.background=background;setSelected(selected);invalidate();}
        @Override protected void onDraw(android.graphics.Canvas c){
            super.onDraw(c);float inset=dp(5);rect.set(inset,inset,getWidth()-inset,getHeight()-inset);
            paint.setStyle(android.graphics.Paint.Style.FILL);paint.setShader(null);Object tag=getTag();
            if("custom".equals(tag))paint.setShader(new android.graphics.LinearGradient(rect.left,rect.top,rect.right,rect.bottom,new int[]{0xff586ad8,0xffed775c,0xff7bc1af},null,android.graphics.Shader.TileMode.CLAMP));
            else if("automatic".equals(tag))paint.setShader(new android.graphics.LinearGradient(rect.left,rect.top,rect.right,rect.bottom,new int[]{background,Color.rgb(Math.min(255,Color.red(background)+28),Math.min(255,Color.green(background)+22),Math.min(255,Color.blue(background)+14))},null,android.graphics.Shader.TileMode.CLAMP));
            else paint.setColor((Integer)tag);
            c.drawRoundRect(rect,dp(9),dp(9),paint);paint.setShader(null);paint.setStyle(android.graphics.Paint.Style.STROKE);paint.setStrokeWidth(dp(isSelected()?2:1));paint.setColor(isSelected()?Color.WHITE:0x557f8385);c.drawRoundRect(rect,dp(9),dp(9),paint);
        }
        @Override public void onInitializeAccessibilityNodeInfo(android.view.accessibility.AccessibilityNodeInfo info){super.onInitializeAccessibilityNodeInfo(info);info.setClassName(android.widget.RadioButton.class.getName());info.setCheckable(true);info.setChecked(isSelected());}
    }
    private void recolor(LinearLayout layout,int ink){for(int i=0;i<layout.getChildCount();i++){View child=layout.getChildAt(i);if(child instanceof TextView)((TextView)child).setTextColor(ink);else if(child instanceof LinearLayout)recolor((LinearLayout)child,ink);}}
    private void showLighting(){LinearLayout panel=column();panel.setPadding(dp(24),dp(10),dp(24),dp(8));SeekBar slider=new SeekBar(activity);slider.setMax(100);slider.setProgress((int)(themes.lighting()*100));slider.setContentDescription("底灯亮度");panel.addView(slider,new LinearLayout.LayoutParams(-1,dp(56)));slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar b,int value,boolean user){if(user){themes.lighting(value/100f);changed.run();}}public void onStartTrackingTouch(SeekBar b){}public void onStopTrackingTouch(SeekBar b){}});AlertDialog d=new AlertDialog.Builder(activity).setTitle("唱片底灯 · 随封面配色").setView(panel).setPositiveButton("完成",null).create();children.add(d);d.show();}
    private void showColor(){
        final int before=themes.colorFor(album,specific);final boolean beforeAutomatic=themes.followCoverColors();LinearLayout panel=column();panel.setPadding(dp(20),dp(4),dp(20),dp(4));ColorPickerView picker=new ColorPickerView(activity,before);panel.addView(picker,new LinearLayout.LayoutParams(-1,dp(215)));
        EditText hex=new EditText(activity);hex.setSingleLine(true);hex.setText(String.format(Locale.ROOT,"#%06X",before&0xffffff));hex.setTextSize(16);hex.setContentDescription("十六进制颜色");panel.addView(hex,new LinearLayout.LayoutParams(-1,dp(48)));
        TextView caption=label("在封面上点按取色",12,false);caption.setTextColor(0xff635b63);panel.addView(caption);
        if(cover!=null){ImageView art=new ImageView(activity);art.setImageBitmap(cover);art.setScaleType(ImageView.ScaleType.CENTER_CROP);panel.addView(art,new LinearLayout.LayoutParams(-1,dp(102)));art.setContentDescription("专辑封面取色区");art.setOnTouchListener((v,e)->{int action=e.getActionMasked();if(action==MotionEvent.ACTION_DOWN)v.getParent().requestDisallowInterceptTouchEvent(true);if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL)v.getParent().requestDisallowInterceptTouchEvent(false);if(e.getActionMasked()==MotionEvent.ACTION_DOWN||e.getActionMasked()==MotionEvent.ACTION_MOVE||e.getActionMasked()==MotionEvent.ACTION_UP){Matrix inverse=new Matrix();art.getImageMatrix().invert(inverse);float[] point={e.getX()-art.getPaddingLeft(),e.getY()-art.getPaddingTop()};inverse.mapPoints(point);int x=Math.max(0,Math.min(cover.getWidth()-1,Math.round(point[0]))),y=Math.max(0,Math.min(cover.getHeight()-1,Math.round(point[1])));picker.setColor(cover.getPixel(x,y));if(e.getActionMasked()==MotionEvent.ACTION_UP)art.performClick();return true;}return false;});}
        else caption.setText("拖动色板选择颜色，也可输入 HEX 色值");
        picker.setListener(color->{hex.setText(String.format(Locale.ROOT,"#%06X",color&0xffffff));selectColor(color);});
        // The custom content must remain reachable on a short landscape window and
        // when the IME reduces its height. Keep the dialog actions outside this scroll.
        ScrollView scroll=new ScrollView(activity);
        scroll.setFillViewport(false);
        scroll.setClipToPadding(false);
        scroll.addView(panel,new ScrollView.LayoutParams(-1,-2));
        final boolean[] saved={false};
        AlertDialog d=new AlertDialog.Builder(activity).setTitle("自定义颜色")
                .setView(scroll).setNegativeButton("取消",null).setPositiveButton("保存",null).create();
        children.add(d);
        // Back, outside taps, parent closure, and configuration teardown all dismiss
        // this dialog. Only the explicit Save action commits the previewed color.
        d.setOnDismissListener(a->{children.remove(d);if(!saved[0]){selectColor(before);themes.followCoverColors(beforeAutomatic);update();changed.run();}});
        d.show();
        if(d.getWindow()!=null)d.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            String value=hex.getText().toString().trim();
            if(!value.matches("#[0-9a-fA-F]{6}")){hex.setError("请输入 # 加六位颜色");return;}
            selectColor(Color.parseColor(value));saved[0]=true;d.dismiss();
        });
    }
    private PlayerControls.Icon icon(String symbol,String description,Runnable action,int size){PlayerControls.Icon v=new PlayerControls.Icon(activity,symbol);v.setContentDescription(description);v.setOnClickListener(w->action.run());v.setLayoutParams(new LinearLayout.LayoutParams(dp(Math.max(48,size)),dp(Math.max(48,size))));return v;}
    private TextView label(String value,int size,boolean bold){TextView v=new TextView(activity);v.setText(value);v.setTextSize(size);v.setTextColor(Color.WHITE);if(bold)v.setTypeface(null,android.graphics.Typeface.BOLD);return v;}
    private Button button(String value,Runnable action){Button b=new Button(activity);b.setText(value);b.setTextSize(12);b.setAllCaps(false);b.setTextColor(Color.WHITE);b.setBackground(round(Color.TRANSPARENT,12));b.setOnClickListener(v->action.run());return b;}
    private LinearLayout column(){LinearLayout l=new LinearLayout(activity);l.setOrientation(1);return l;}
    private LinearLayout row(){LinearLayout l=new LinearLayout(activity);l.setOrientation(0);return l;}
    private GradientDrawable round(int color,int radius){GradientDrawable g=new GradientDrawable();g.setColor(color);g.setCornerRadius(dp(radius));return g;}
    private double luminance(int c){return Color.red(c)*.299+Color.green(c)*.587+Color.blue(c)*.114;}
    private int dp(float value){return Math.round(value*activity.getResources().getDisplayMetrics().density);}
}
