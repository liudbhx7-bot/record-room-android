package com.recordroom.vinyl;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import java.util.Locale;

/** One live timeline per surface: scrubbing the queue does not detach the deck's controls. */
final class PlaybackTimeline extends LinearLayout {
    interface Seek { void to(long ms); }
    private final TextView start, end;
    final SeekBar slider;
    private final boolean remainingTime;
    private long duration, position=-1, shownStart=Long.MIN_VALUE, shownEnd=Long.MIN_VALUE;
    private boolean dragging;
    private String track="", gestureTrack;

    PlaybackTimeline(Context context, boolean remainingTime, Seek seek) {
        super(context);
        this.remainingTime=remainingTime;
        setGravity(Gravity.CENTER_VERTICAL);
        start=label(context); end=label(context);
        start.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);
        end.setGravity(Gravity.END|Gravity.CENTER_VERTICAL);
        addView(start,new LayoutParams(-2,-2));
        slider=new SeekBar(context);
        slider.setMax(1000);
        slider.setContentDescription("播放进度");
        GradientDrawable knob=new GradientDrawable();
        knob.setShape(GradientDrawable.OVAL); knob.setColor(0xffffffff);
        knob.setSize(dp(10),dp(10)); slider.setThumb(knob);
        slider.setSplitTrack(false); slider.setPadding(dp(8),0,dp(8),0);
        addView(slider,new LayoutParams(0,dp(48),1));
        addView(end,new LayoutParams(-2,-2));
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            @Override public void onStartTrackingTouch(SeekBar bar){dragging=true;gestureTrack=track;}
            @Override public void onProgressChanged(SeekBar bar,int value,boolean user){
                if(!user||!bar.isEnabled()||duration<=0)return;
                long target=duration*value/1000;
                if(dragging&&!track.equals(gestureTrack))return;
                labels(target,duration);
                // Keyboard and accessibility adjustments do not send touch start/stop.
                if(!dragging)seek.to(target);
            }
            @Override public void onStopTrackingTouch(SeekBar bar){
                boolean valid=dragging&&track.equals(gestureTrack)&&bar.isEnabled()&&duration>0;
                dragging=false; gestureTrack=null;
                if(valid)seek.to(duration*bar.getProgress()/1000);
                else update(track,position,duration,bar.isEnabled());
            }
        });
        update("",-1,-1,false);
    }

    void update(String identity,long position,long duration,boolean canSeek) {
        this.track=identity;
        this.duration=duration;
        this.position=position;
        boolean enabled=canSeek&&duration>0;
        if(isEnabled()!=enabled)super.setEnabled(enabled);
        if(slider.isEnabled()!=enabled){slider.setEnabled(enabled);slider.setAlpha(enabled?1f:.65f);}
        if(!dragging||!track.equals(gestureTrack)||!enabled){
            int p=duration>0&&position>0?(int)(Math.min(position,duration)*1000/duration):0;
            if(slider.getProgress()!=p)slider.setProgress(p);
            labels(position,duration);
        }
    }
    void ink(int foreground,int secondary) {
        start.setTextColor(secondary); end.setTextColor(secondary);
        ColorStateList tint=ColorStateList.valueOf(foreground);
        slider.setProgressTintList(tint);slider.setThumbTintList(tint);
        slider.setProgressBackgroundTintList(ColorStateList.valueOf((foreground&0xffffff)|0x44000000));
        slider.setAlpha(slider.isEnabled()?1f:.65f);
    }
    private void labels(long pos,long dur) {
        long first=pos<0?-1:pos/1000,last=dur<=0?-1:(remainingTime?pos<0?-1:Math.max(0,dur-pos)/1000:dur/1000);
        if(first!=shownStart){shownStart=first;start.setText(clock(first));}
        if(last!=shownEnd){shownEnd=last;end.setText((remainingTime&&last>=0?"−":"")+clock(last));}
    }
    private TextView label(Context c){
        TextView v=new TextView(c);v.setTextSize(11);v.setTextColor(0xffc5c3c1);v.setSingleLine();v.setMinWidth(dp(29));
        v.setTypeface(Typeface.create("sans-serif",Typeface.NORMAL));v.setFontFeatureSettings("tnum");return v;
    }
    private static String clock(long seconds){
        return seconds<0?"–:––":String.format(Locale.ROOT,"%d:%02d",seconds/60,seconds%60);
    }
    private int dp(float value){return DesignSystem.dp(getContext(),value);}
}
