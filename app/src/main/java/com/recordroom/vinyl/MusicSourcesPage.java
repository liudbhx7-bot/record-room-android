package com.recordroom.vinyl;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.LinkedHashMap;

/** Music services are explicit playback sources, never synthetic local catalogue entries. */
public final class MusicSourcesPage extends FrameLayout {
    public interface Actions {
        void back(); void local(); void netease(); void authorize(); void direct(String id);
        void select(String id); void open(String id);
    }
    private final Actions actions;
    private final TextView access, current;
    private final Button authorization;
    private final LinkedHashMap<String,TextView> states=new LinkedHashMap<>();
    private final LinkedHashMap<String,Button> choose=new LinkedHashMap<>();
    public MusicSourcesPage(Context context,Actions actions){
        super(context);this.actions=actions;setBackground(DesignSystem.ambient(context));
        LinearLayout body=column();body.setPadding(dp(22),dp(12),dp(22),dp(36));
        ScrollView scroll=new ScrollView(context);scroll.setVerticalScrollBarEnabled(false);scroll.setFillViewport(true);scroll.addView(body);addView(scroll,new LayoutParams(-1,-1));
        LinearLayout top=row();PlayerControls.Icon back=new PlayerControls.Icon(context,"back");back.setInk(DesignSystem.onAmbient);back.setGlass(false);back.setContentDescription("关闭音乐来源");back.setOnClickListener(v->actions.back());top.addView(back,new LinearLayout.LayoutParams(dp(48),dp(48)));TextView heading=label("音乐来源",30,DesignSystem.onAmbient);heading.setTypeface(Typeface.create("sans-serif",Typeface.BOLD));top.addView(heading,new LinearLayout.LayoutParams(0,dp(54),1));body.addView(top);
        current=label("选择你的听歌方式",14,DesignSystem.onAmbientMuted);body.addView(current);
        LinearLayout local=card();local.addView(label("唱片机资料库",20,DesignSystem.onAmbient));local.addView(label("导入设备里的音乐，或连接网易云音乐账号与歌单。",13,DesignSystem.onAmbientMuted));LinearLayout buttons=row();buttons.addView(button("切回资料库",actions::local),new LinearLayout.LayoutParams(0,dp(48),1));buttons.addView(button("网易云音乐",actions::netease),new LinearLayout.LayoutParams(0,dp(48),1));local.addView(buttons);body.addView(local);
        TextView directHeading=label("账号与在线曲库",21,DesignSystem.onAmbient);directHeading.setTypeface(Typeface.create("sans-serif",Typeface.BOLD));directHeading.setPadding(0,dp(28),0,dp(8));body.addView(directHeading);
        LinearLayout direct=card();direct.addView(label("在唱片机内浏览与播放",16,DesignSystem.onAmbient));direct.addView(label("使用自己的账号登录。可播放内容由平台及你的账号权益决定。",13,DesignSystem.onAmbientMuted));direct.addView(button("QQ 音乐 · 账号与曲库",()->actions.direct("qq")),stackedButton(52));direct.addView(button("Apple Music · 账号与曲库",()->actions.direct("applemusic")),stackedButton(52));body.addView(direct);
        TextView section=label("可选 · 官方 App 联动",21,DesignSystem.onAmbient);section.setTypeface(Typeface.create("sans-serif",Typeface.BOLD));section.setPadding(0,dp(28),0,dp(8));body.addView(section);
        body.addView(label("在官方 App 登录并开始播放，回到唱片机即可同步歌曲、封面与播放控制。曲库、会员和播放队列仍由官方 App 管理。",14,DesignSystem.onAmbientMuted));
        LinearLayout permission=card();access=label("",14,DesignSystem.onAmbient);permission.addView(access);permission.addView(label("Android 通过“通知使用权”开放播放信息。唱片机仅连接所选音乐 App 的播放会话，不读取或保存通知正文。",12,DesignSystem.onAmbientMuted));authorization=button("开启播放联动",actions::authorize);permission.addView(authorization,stackedButton(48));body.addView(permission);
        for(ExternalMusicBridge.Provider provider:ExternalMusicBridge.providers()){
            LinearLayout panel=card();TextView name=label(provider.name,22,DesignSystem.onAmbient);name.setTypeface(Typeface.create("sans-serif",Typeface.BOLD));panel.addView(name);
            TextView status=label("检查可用状态…",13,DesignSystem.onAmbientMuted);states.put(provider.id,status);panel.addView(status);
            LinearLayout controls=row();Button select=button("联动播放",()->actions.select(provider.id));choose.put(provider.id,select);controls.addView(select,new LinearLayout.LayoutParams(0,dp(48),1));controls.addView(button("打开官方 App",()->actions.open(provider.id)),new LinearLayout.LayoutParams(0,dp(48),1));panel.addView(controls);body.addView(panel);
        }
    }
    public void update(ExternalMusicBridge.State state){
        current.setText(state.selectedProvider==null?"当前来源 · 唱片机资料库":"当前来源 · "+state.selectedProvider.name);
        access.setText(state.accessGranted?"播放联动权限已开启":"先开启播放联动权限");authorization.setText(state.accessGranted?"管理联动权限":"开启播放联动");authorization.setContentDescription(authorization.getText());
        for(ExternalMusicBridge.ProviderState item:state.providers){TextView status=states.get(item.provider.id);Button select=choose.get(item.provider.id);if(status==null)continue;boolean selected=item.provider.id.equals(state.selectedProviderId);status.setText(selected?state.statusMessage:item.statusMessage);select.setText(selected?"正在使用":"联动播放");select.setContentDescription(item.provider.name+" · "+select.getText());select.setEnabled(item.installed);select.setAlpha(item.installed?1:.45f);}
    }
    private LinearLayout.LayoutParams stackedButton(int height){LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,dp(height));params.topMargin=dp(8);return params;}
    private int dp(float value){return DesignSystem.dp(getContext(),value);}
    private LinearLayout column(){LinearLayout v=new LinearLayout(getContext());v.setOrientation(LinearLayout.VERTICAL);return v;}
    private LinearLayout row(){LinearLayout v=new LinearLayout(getContext());v.setOrientation(LinearLayout.HORIZONTAL);v.setGravity(Gravity.CENTER_VERTICAL);android.graphics.drawable.GradientDrawable gap=new android.graphics.drawable.GradientDrawable();gap.setColor(Color.TRANSPARENT);gap.setSize(dp(8),1);v.setDividerDrawable(gap);v.setShowDividers(LinearLayout.SHOW_DIVIDER_MIDDLE);return v;}
    private LinearLayout card(){LinearLayout v=column();v.setPadding(dp(18),dp(15),dp(18),dp(12));v.setBackground(DesignSystem.settingsCard(getContext(),20));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=dp(14);v.setLayoutParams(lp);return v;}
    private TextView label(String text,int size,int color){TextView v=new TextView(getContext());v.setText(text);v.setTextSize(Math.max(14,size));v.setTypeface(Typeface.create("sans-serif",Typeface.NORMAL));v.setTextColor(color);v.setLineSpacing(dp(3),1);v.setPadding(0,dp(5),0,dp(7));v.setGravity(Gravity.CENTER_VERTICAL);return v;}
    private Button button(String text,Runnable action){Button b=new Button(getContext());b.setAllCaps(false);b.setText(text);b.setTextSize(14);b.setTypeface(Typeface.create("sans-serif",Typeface.NORMAL));b.setPadding(dp(10),0,dp(10),0);b.setTextColor(DesignSystem.onAmbient);b.setBackground(DesignSystem.ambientButton(getContext(),16));b.setMinWidth(0);b.setMinimumWidth(0);b.setOnClickListener(v->action.run());b.setContentDescription(text);return b;}
}
