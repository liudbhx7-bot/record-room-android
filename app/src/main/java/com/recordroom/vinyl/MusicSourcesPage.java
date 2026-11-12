package com.recordroom.vinyl;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.LinkedHashMap;

/** Cover catalogue. Account libraries and official-player sessions have independent entry points. */
public final class MusicSourcesPage extends FrameLayout {
    public interface Actions {
        void back(); void local(); void netease(); void authorize(); void direct(String id);
        void select(String id); void open(String id); void importAudio();
    }

    private final Actions actions;
    private final LinearLayout catalogue, library, linked;
    private final ScrollView scroll;
    private final TextView current, localState, access, appleState;
    private final Sleeve sleeve;
    private final Button authorization;
    private final LinkedHashMap<String,TextView> states=new LinkedHashMap<>();
    private final LinkedHashMap<String,Button> choose=new LinkedHashMap<>();
    private String section="catalogue";
    private String directName="", linkedName="";
    private int cataloguePosition;
    private static final int INK=DesignSystem.onAmbient, SECONDARY=DesignSystem.onAmbientMuted;

    public MusicSourcesPage(Context context,Actions actions){
        super(context);this.actions=actions;
        setBackground(DesignSystem.ambient(context));
        scroll=new ScrollView(context);
        scroll.setFillViewport(true);scroll.setVerticalScrollBarEnabled(false);
        addView(scroll,new LayoutParams(-1,-1));
        catalogue=page("音乐来源","关闭音乐来源");
        FrameLayout hero=new FrameLayout(context);
        hero.setBackground(DesignSystem.settingsCard(context,14));hero.setClipToOutline(true);
        // LiveSurface supplies the tint; a rounded outline clips the jacket at the panel edge.
        hero.setOutlineProvider(new android.view.ViewOutlineProvider(){
            public void getOutline(View v,android.graphics.Outline out){out.setRoundRect(0,0,v.getWidth(),v.getHeight(),dp(14));}
        });
        sleeve=new Sleeve(context);hero.addView(sleeve,new LayoutParams(-1,-1));
        LinearLayout caption=column();
        caption.addView(label("当前来源",13,0xffe3deda));
        current=label("唱片机资料库",23,Color.WHITE);current.setTypeface(Typeface.SERIF,Typeface.BOLD);
        caption.addView(current);caption.setPadding(dp(16),0,dp(12),dp(15));
        hero.addView(caption,new LayoutParams(-2,-2,Gravity.BOTTOM|Gravity.START));
        catalogue.addView(hero,new LinearLayout.LayoutParams(-1,dp(138)));
        heading(catalogue,"我的资料库","");
        localState=label("",13,SECONDARY);
        entry(catalogue,"albums","手机音乐","手机音乐",localState,()->showSection("library"));
        heading(catalogue,"账号与曲库","在唱片机内播放");
        entry(catalogue,"cloud","网易云音乐","网易云音乐 · 账号与曲库",null,actions::netease);
        entry(catalogue,"leaf","QQ 音乐","QQ 音乐 · 账号与曲库",null,()->actions.direct("qq"));
        appleState=label("",13,SECONDARY);
        entry(catalogue,"music","Apple Music","Apple Music · 账号与曲库",appleState,()->actions.direct("applemusic"));
        LinearLayout official=row();official.setMinimumHeight(dp(60));
        official.setPadding(dp(12),dp(7),dp(12),dp(7));
        official.setBackground(DesignSystem.ambientButton(context,12));
        official.addView(new Mark("link"),new LinearLayout.LayoutParams(dp(26),dp(30)));
        LinearLayout summary=column();summary.setPadding(dp(9),0,dp(4),0);
        summary.addView(label("官方 App 联动",15,INK));
        summary.addView(label("网易云音乐、QQ 音乐、Apple Music、汽水音乐",13,SECONDARY));
        official.addView(summary,new LinearLayout.LayoutParams(0,-2,1));
        arrow(official);clickable(official,"官方 App 联动",()->showSection("linked"));
        LinearLayout.LayoutParams route=new LinearLayout.LayoutParams(-1,-2);route.topMargin=dp(16);
        catalogue.addView(official,route);

        library=page("唱片机资料库","返回音乐来源");
        library.addView(label("导入设备里的音乐，或查看网易云账号中的歌单。",15,SECONDARY));
        heading(library,"我的资料库","");
        entry(library,"albums","使用唱片机资料库","切回资料库",null,actions::local);
        entry(library,"download","导入本地音乐","导入本地音乐",null,actions::importAudio);
        entry(library,"disc","网易云音乐","网易云音乐账号",null,actions::netease);

        linked=page("官方 App 联动","返回音乐来源");
        linked.addView(label("在官方 App 开始播放，回到唱片机同步歌曲、封面和播放控制。",15,SECONDARY));
        heading(linked,"联动权限","");
        access=label("",14,INK);linked.addView(access);
        authorization=button("开启播放联动",actions::authorize);linked.addView(authorization,fullButton());
        heading(linked,"选择音乐 App","");
        for(ExternalMusicBridge.Provider provider:ExternalMusicBridge.providers()){
            LinearLayout panel=column();panel.setPadding(0,dp(12),0,dp(12));
            TextView name=label(provider.name,18,INK);name.setTypeface(null,Typeface.BOLD);panel.addView(name);
            TextView status=label("",14,SECONDARY);states.put(provider.id,status);panel.addView(status);
            LinearLayout controls=row();
            Button select=button("联动播放",()->actions.select(provider.id));choose.put(provider.id,select);
            controls.addView(select,new LinearLayout.LayoutParams(0,-2,1));
            LinearLayout.LayoutParams open=new LinearLayout.LayoutParams(0,-2,1);open.leftMargin=dp(8);
            controls.addView(button("打开官方 App",()->actions.open(provider.id)),open);
            panel.addView(controls);linked.addView(panel);line(linked);
        }
        scroll.addView(catalogue);
    }

    public void update(ExternalMusicBridge.State state){
        boolean internal=state.selectedProvider==null;
        linkedName=internal?"":state.selectedProvider.name+"联动";
        updateSourceLabel();
        access.setText(state.accessGranted?"播放联动权限已开启":"先开启播放联动权限");
        authorization.setText(state.accessGranted?"管理联动权限":"开启播放联动");
        for(ExternalMusicBridge.ProviderState item:state.providers){
            boolean selected=item.provider.id.equals(state.selectedProviderId);
            states.get(item.provider.id).setText(selected?state.statusMessage:item.statusMessage);
            Button select=choose.get(item.provider.id);
            select.setText(selected?"正在使用":"联动播放");
            select.setContentDescription(item.provider.name+" · "+select.getText());
            select.setEnabled(item.installed);select.setAlpha(item.installed?1:.45f);
        }
    }
    void setPlayback(Bitmap bitmap,String providerName){
        sleeve.artwork=bitmap;sleeve.invalidate();directName=providerName;updateSourceLabel();
    }
    private void updateSourceLabel(){
        String name=!linkedName.isEmpty()?linkedName:directName;
        current.setText(name.isEmpty()?"唱片机资料库":name);
        localState.setText(name.isEmpty()?"使用中":"");
    }
    void appleNeedsConfiguration(boolean needed){appleState.setText(needed?"待配置":"");}
    String section(){return section;}
    void goBack(){if("catalogue".equals(section))actions.back();else showSection("catalogue");}
    void showSection(String next){
        if(!"library".equals(next)&&!"linked".equals(next))next="catalogue";
        if(section.equals(next))return;
        if("catalogue".equals(section))cataloguePosition=scroll.getScrollY();
        section=next;scroll.removeAllViews();
        scroll.addView("library".equals(next)?library:"linked".equals(next)?linked:catalogue);
        final int offset="catalogue".equals(next)?cataloguePosition:0;
        scroll.scrollTo(0,offset);
        String destination=section;
        scroll.post(()->{if(section.equals(destination)&&scroll.getScrollY()!=offset)scroll.scrollTo(0,offset);});
    }
    private LinearLayout page(String name,String backLabel){
        LinearLayout body=column();body.setPadding(dp(22),dp(7),dp(22),dp(30));
        LinearLayout top=row();PlayerControls.Icon back=new PlayerControls.Icon(getContext(),"back");
        back.setInk(INK);back.setContentDescription(backLabel);back.setOnClickListener(v->goBack());
        top.addView(back,new LinearLayout.LayoutParams(dp(48),dp(48)));
        TextView context=label("播放设置",13,SECONDARY);context.setGravity(Gravity.END|Gravity.CENTER_VERTICAL);
        top.addView(context,new LinearLayout.LayoutParams(0,-2,1));body.addView(top);
        TextView title=label(name,34,INK);title.setTypeface(Typeface.SERIF,Typeface.BOLD);
        title.setPadding(0,dp(5),0,dp(14));body.addView(title);return body;
    }
    private void heading(LinearLayout parent,String title,String hint){
        LinearLayout header=row();header.setPadding(0,dp(19),0,dp(4));TextView h=label(title,15,INK);
        h.setTypeface(null,Typeface.BOLD);header.addView(h,new LinearLayout.LayoutParams(0,-2,1));
        if(!hint.isEmpty())header.addView(label(hint,13,SECONDARY));parent.addView(header);
    }
    private void entry(LinearLayout list,String icon,String title,String description,TextView status,Runnable action){
        LinearLayout item=row();item.setMinimumHeight(dp(51));
        item.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22ffffff),null,null));
        Mark badge=new Mark(icon);badge.setBackground(DesignSystem.settingsCard(getContext(),9));
        item.addView(badge,new LinearLayout.LayoutParams(dp(32),dp(32)));
        TextView name=label(title,16,INK);name.setPadding(dp(12),dp(4),dp(5),dp(4));
        item.addView(name,new LinearLayout.LayoutParams(0,-2,1));
        if(status!=null)item.addView(status);arrow(item);
        clickable(item,description,action);
        list.addView(item);line(list);
    }
    private void clickable(View v,String description,Runnable action){
        v.setContentDescription(description);v.setFocusable(true);v.setOnClickListener(w->action.run());
    }
    private void arrow(LinearLayout row){row.addView(new Mark("chevron"),new LinearLayout.LayoutParams(dp(20),dp(24)));}
    private void line(LinearLayout list){View divider=new View(getContext());divider.setBackgroundColor(0x22ffffff);list.addView(divider,new LinearLayout.LayoutParams(-1,dp(1)));}
    private LinearLayout.LayoutParams fullButton(){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(8);return p;}
    private int dp(float value){return DesignSystem.dp(getContext(),value);}
    private LinearLayout column(){LinearLayout v=new LinearLayout(getContext());v.setOrientation(LinearLayout.VERTICAL);return v;}
    private LinearLayout row(){LinearLayout v=new LinearLayout(getContext());v.setGravity(Gravity.CENTER_VERTICAL);return v;}
    private TextView label(String text,int size,int color){TextView v=new TextView(getContext());v.setText(text);v.setTextSize(size);v.setTextColor(color);v.setGravity(Gravity.CENTER_VERTICAL);v.setIncludeFontPadding(false);v.setPadding(0,dp(3),0,dp(3));return v;}
    private Button button(String text,Runnable action){Button b=new Button(getContext());b.setAllCaps(false);b.setText(text);b.setTextSize(14);b.setPadding(dp(6),dp(5),dp(6),dp(5));b.setMinimumHeight(dp(48));b.setMinHeight(dp(48));b.setTextColor(INK);b.setBackground(DesignSystem.ambientButton(getContext(),12));b.setOnClickListener(v->action.run());return b;}

    private final class Mark extends View {
        final String symbol;final Paint pen=new Paint(Paint.ANTI_ALIAS_FLAG);
        Mark(String symbol){super(MusicSourcesPage.this.getContext());this.symbol=symbol;setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
        @Override protected void onDraw(Canvas c){
            int ink=DesignSystem.mix(DesignSystem.currentTint(getContext()),INK,.64f);
            float size=dp("chevron".equals(symbol)?13:18);
            if("link".equals(symbol)){
                int save=c.save();c.translate(getWidth()/2f,getHeight()/2f);c.rotate(-42);
                pen.setColor(ink);pen.setStyle(Paint.Style.STROKE);pen.setStrokeWidth(dp(1.5f));
                c.drawRoundRect(-size*.46f,-size*.17f,size*.07f,size*.17f,size*.16f,size*.16f,pen);
                c.drawRoundRect(-size*.07f,-size*.17f,size*.46f,size*.17f,size*.16f,size*.16f,pen);c.restoreToCount(save);
            }else if("leaf".equals(symbol)||"cloud".equals(symbol)){
                int save=c.save();c.translate((getWidth()-size)/2,(getHeight()-size)/2);c.scale(size/24f,size/24f);
                pen.setColor(ink);pen.setStyle(Paint.Style.STROKE);pen.setStrokeWidth(1.65f);pen.setStrokeCap(Paint.Cap.ROUND);
                android.graphics.Path path=new android.graphics.Path();
                if("leaf".equals(symbol)){
                    path.moveTo(5,18);path.cubicTo(1,12,6,5,19,3);
                    path.cubicTo(23,13,14,22,6,17);path.moveTo(4,20);path.lineTo(15,9);
                }else{
                    path.moveTo(13,4);path.cubicTo(18,1,20,7,16,10);
                    path.cubicTo(11,15,5,13,7,9);path.cubicTo(9,5,18,8,19,13);
                    path.cubicTo(21,21,7,24,4,16);path.cubicTo(2,12,3,10,4,9);
                }
                c.drawPath(path,pen);c.restoreToCount(save);
            }else PlayerControls.symbol(c,pen,symbol,(getWidth()-size)/2,(getHeight()-size)/2,size,ink);
        }
    }
    private final class Sleeve extends View {
        Bitmap artwork;
        final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
        final RectF frame=new RectF();
        Shader shade;
        Sleeve(Context c){super(c);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
        @Override protected void onSizeChanged(int w,int h,int ow,int oh){if(w>0)shade=new LinearGradient(0,0,w*.82f,0,0xb512100f,0x0012100f,Shader.TileMode.CLAMP);}
        @Override protected void onDraw(Canvas c){
            float w=getWidth(),h=getHeight(),s=Math.min(h*.75f,w*.34f),x=w-s*.89f,y=h*.51f;
            paint.setShader(null);paint.setStyle(Paint.Style.FILL);paint.setColor(0xff141516);c.drawCircle(x+s*.25f,y,s*.51f,paint);
            paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(.6f));paint.setColor(0xff383739);
            for(float r=s*.16f;r<s*.50f;r+=Math.max(2f,s*.024f))c.drawCircle(x+s*.25f,y,r,paint);
            int save=c.save();c.rotate(-8,x-s*.14f,y);
            frame.set(x-s*.68f,y-s*.47f,x+s*.32f,y+s*.53f);
            paint.setStyle(Paint.Style.FILL);paint.setColor(0x85000000);c.drawRoundRect(frame.left+dp(2),frame.top+dp(4),frame.right+dp(2),frame.bottom+dp(4),dp(1),dp(1),paint);
            paint.setColor(DesignSystem.mix(DesignSystem.currentTint(getContext()),0xff221e20,.5f));c.drawRect(frame,paint);
            if(artwork!=null&&!artwork.isRecycled()){
                float k=Math.min(frame.width()/artwork.getWidth(),frame.height()/artwork.getHeight());
                float dx=(frame.width()-artwork.getWidth()*k)/2,dy=(frame.height()-artwork.getHeight()*k)/2;
                frame.inset(dx,dy);paint.setColor(Color.WHITE);c.drawBitmap(artwork,null,frame,paint);
            }else PlayerControls.symbol(c,paint,"disc",frame.centerX()-s*.1f,frame.centerY()-s*.1f,s*.2f,0xffd6cbc5);
            c.restoreToCount(save);paint.setStyle(Paint.Style.FILL);paint.setColor(Color.WHITE);paint.setShader(shade);
            c.drawRect(0,0,w,h,paint);paint.setShader(null);
        }
    }
}
