package com.recordroom.vinyl;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Album and playlist tracks use Android's row recycler, including very long playlists. */
final class AlbumSongAdapter extends BaseAdapter {
    interface Actions{void play(int at);void menu(Track track);}
    private final Context context;
    private final ArrayList<Track> tracks;
    private final Actions actions;
    private String current="";
    AlbumSongAdapter(Context context,List<Track> tracks,Actions actions){this.context=context;this.tracks=new ArrayList<>(tracks);this.actions=actions;}
    void setCurrent(Track track){String key=track==null?"":LibraryStore.identity(track);if(!key.equals(current)){current=key;notifyDataSetChanged();}}
    @Override public int getCount(){return tracks.size();}
    @Override public Track getItem(int at){return tracks.get(at);}
    @Override public long getItemId(int at){return at;}
    private final class Holder {
        final LinearLayout line;final TextView title,duration;final PlayerControls.Icon more;int at;
        Holder(){
            line=new LinearLayout(context);line.setGravity(Gravity.CENTER_VERTICAL);line.setPadding(dp(16),0,dp(8),0);line.setMinimumHeight(dp(64));
            title=new TextView(context);title.setTextSize(15);title.setTextColor(DesignSystem.INK);title.setGravity(Gravity.CENTER_VERTICAL);title.setPadding(dp(8),dp(14),dp(8),dp(14));title.setMaxLines(2);title.setEllipsize(TextUtils.TruncateAt.END);title.setMinHeight(dp(56));
            duration=new TextView(context);duration.setTextSize(12);duration.setTextColor(DesignSystem.MUTED);duration.setGravity(Gravity.END);
            line.addView(title,new LinearLayout.LayoutParams(0,-2,1));line.addView(duration,new LinearLayout.LayoutParams(dp(42),-2));
            more=new PlayerControls.Icon(context,"more");line.addView(more,new LinearLayout.LayoutParams(dp(48),dp(48)));more.setOnClickListener(v->actions.menu(getItem(at)));
            line.setOnClickListener(v->actions.play(at));line.setOnLongClickListener(v->{actions.menu(getItem(at));return true;});line.setTag(this);
        }
    }
    @Override public View getView(int at,View reused,ViewGroup parent){
        Holder h=reused==null?new Holder():(Holder)reused.getTag();h.at=at;Track t=getItem(at);boolean selected=LibraryStore.identity(t).equals(current);
        h.title.setText((selected?"•   ":String.format(Locale.ROOT,"%02d   ",at+1))+t.title);h.title.setTypeface(null,selected?Typeface.BOLD:Typeface.NORMAL);
        GradientDrawable bg=new GradientDrawable();bg.setColor(selected?0xffe6deed:Color.TRANSPARENT);bg.setCornerRadius(dp(8));h.line.setBackground(bg);h.title.setTextColor(selected?DesignSystem.ACCENT:DesignSystem.INK);
        long seconds=t.durationMs/1000;String duration=String.format(Locale.ROOT,"%d:%02d",seconds/60,seconds%60);h.duration.setText(duration);
        h.more.setContentDescription("歌曲操作："+t.title);h.line.setContentDescription(t.title+"，"+duration);return h.line;
    }
    private int dp(float n){return Math.round(n*context.getResources().getDisplayMetrics().density);}
}
