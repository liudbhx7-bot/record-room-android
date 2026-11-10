package com.recordroom.vinyl;

import android.content.ClipData;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.DragEvent;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

/** Recycled queue rows with a generation-scoped, local drag operation. */
public final class QueueListView extends ListView {
    public interface Actions { void play(int index); void menu(int index); void move(int from, int to); }
    private final ArrayList<Track> tracks=new ArrayList<>();
    private final QueueAdapter rows=new QueueAdapter();
    private final Handler main=new Handler(Looper.getMainLooper());
    private Actions actions;
    private long generation;
    private int current=-1, target=-1, edgeDirection;
    private int ink=DesignSystem.INK;
    private boolean airy=true;
    private float dragX,dragY;
    private boolean scrollPosted;
    private DragToken active;

    private static final class DragToken {
        final QueueListView owner; final long generation; final int from; final String identity;
        boolean consumed;
        DragToken(QueueListView owner,long generation,int from,String identity) {
            this.owner=owner;this.generation=generation;this.from=from;this.identity=identity;
        }
    }
    private final Runnable edgeScroll=new Runnable() {
        @Override public void run() {
            scrollPosted=false;
            if(!valid(active)||edgeDirection==0)return;
            scrollListBy(edgeDirection*dp(9));
            updateTarget(dragX,dragY);
            scheduleScroll();
        }
    };

    public QueueListView(Context context) {
        super(context);
        setDivider(null);setDividerHeight(0);setSelector(new ColorDrawable(Color.TRANSPARENT));
        setClipToPadding(false);setPadding(0,0,0,dp(24));
        setVerticalScrollBarEnabled(false);setBackgroundColor(Color.TRANSPARENT);
        setContentDescription("播放队列，长按歌曲拖动排序");
        setAdapter(rows);
        setOnDragListener((view,event)->handleDrag(event));
    }
    public void setActions(Actions value){actions=value;}
    public void setInkColor(int color){ink=color;rows.notifyDataSetChanged();}
    public void setAiry(boolean value){if(airy!=value){airy=value;rows.notifyDataSetChanged();}}
    /** Optional on first opening; updates never steal the user's scroll position. */
    public void scrollToCurrent(){if(current>=0)setSelectionFromTop(current,dp(airy?104:64));}
    public void submit(List<Track> values,int playing) {
        finishDrag();generation++;tracks.clear();
        if(values!=null)tracks.addAll(values);
        current=playing>=0&&playing<tracks.size()?playing:-1;
        rows.notifyDataSetChanged();
    }
    private boolean valid(DragToken token) {
        return token!=null&&token.owner==this&&!token.consumed&&token.generation==generation
                &&token.from>=0&&token.from<tracks.size()
                &&token.identity.equals(LibraryStore.identity(tracks.get(token.from)));
    }
    private boolean beginDrag(Holder holder) {
        if(!validHolder(holder))return false;
        finishDrag();
        DragToken token=new DragToken(this,generation,holder.position,LibraryStore.identity(holder.track));
        active=token;
        boolean started=holder.row.startDragAndDrop(ClipData.newPlainText("queue",holder.track.title),
                new View.DragShadowBuilder(holder.row),token,0);
        if(!started)finishDrag();
        return started;
    }
    private boolean validHolder(Holder holder) {
        return holder.boundGeneration==generation&&holder.position>=0&&holder.position<tracks.size()
                &&tracks.get(holder.position)==holder.track;
    }
    private boolean handleDrag(DragEvent event) {
        Object state=event.getLocalState();
        DragToken token=state instanceof DragToken?(DragToken)state:null;
        if(event.getAction()==DragEvent.ACTION_DRAG_ENDED){finishDrag();return token!=null&&token.owner==this;}
        if(!valid(token))return false;
        if(event.getAction()==DragEvent.ACTION_DRAG_STARTED){active=token;return true;}
        if(active!=token)return false;
        switch(event.getAction()) {
            case DragEvent.ACTION_DRAG_ENTERED:return true;
            case DragEvent.ACTION_DRAG_LOCATION:
                dragX=event.getX();dragY=event.getY();updateTarget(dragX,dragY);
                edgeDirection=dragY<dp(48)?-1:dragY>getHeight()-dp(48)?1:0;
                if(edgeDirection==0)stopScroll();else scheduleScroll();
                return true;
            case DragEvent.ACTION_DRAG_EXITED:
                stopScroll();setTarget(-1);return true;
            case DragEvent.ACTION_DROP:
                // Resolve the final pointer position, never a stale last MOVE index.
                updateTarget(event.getX(),event.getY());int destination=target;
                token.consumed=true;finishDrag();
                if(destination>=0&&destination<tracks.size()&&destination!=token.from&&actions!=null)
                    actions.move(token.from,destination);
                return true;
            default:return false;
        }
    }
    private void updateTarget(float x,float y) {
        if(x<0||x>=getWidth()||y<0||y>=getHeight()){setTarget(-1);return;}
        int position=pointToPosition((int)x,(int)y);
        if(position==INVALID_POSITION&&getChildCount()>0) {
            if(y<getChildAt(0).getTop())position=getFirstVisiblePosition();
            else if(y>=getChildAt(getChildCount()-1).getBottom())position=getLastVisiblePosition();
        }
        setTarget(position>=0&&position<tracks.size()?position:-1);
    }
    private void setTarget(int position) {
        if(target==position)return;target=position;
        for(int i=0;i<getChildCount();i++) {
            Object tag=getChildAt(i).getTag();if(tag instanceof Holder)style((Holder)tag);
        }
    }
    private void scheduleScroll() {
        if(!scrollPosted&&edgeDirection!=0&&valid(active)) {scrollPosted=true;main.postDelayed(edgeScroll,32);}
    }
    private void stopScroll(){main.removeCallbacks(edgeScroll);scrollPosted=false;edgeDirection=0;}
    private void finishDrag(){stopScroll();if(active!=null)active.consumed=true;active=null;setTarget(-1);}
    @Override protected void onDetachedFromWindow(){finishDrag();super.onDetachedFromWindow();}

    private final class Holder {
        final LinearLayout row,labels;final TextView title,artist;final PlayerControls.Icon more;
        int position;long boundGeneration;Track track;
        Holder() {
            row=new LinearLayout(getContext()){
                final Paint line=new Paint(Paint.ANTI_ALIAS_FLAG);
                @Override protected void dispatchDraw(Canvas canvas){super.dispatchDraw(canvas);line.setColor((ink&0x00ffffff)|0x1c000000);line.setStrokeWidth(Math.max(1,dp(.5f)));canvas.drawLine(dp(8),getHeight()-1,getWidth()-dp(10),getHeight()-1,line);}
            };row.setGravity(Gravity.CENTER_VERTICAL);row.setMinimumHeight(dp(112));
            row.setLayoutParams(new android.widget.AbsListView.LayoutParams(-1,-2));
            labels=new LinearLayout(getContext());labels.setOrientation(LinearLayout.VERTICAL);
            labels.setPadding(dp(8),dp(26),0,dp(26));
            title=new TextView(getContext());title.setTextSize(15);title.setMaxLines(2);title.setEllipsize(TextUtils.TruncateAt.END);
            artist=new TextView(getContext());artist.setTextSize(13);artist.setSingleLine(true);artist.setEllipsize(TextUtils.TruncateAt.END);artist.setPadding(0,dp(4),0,0);
            labels.addView(title);labels.addView(artist);row.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
            more=new PlayerControls.Icon(getContext(),"more");row.addView(more,new LinearLayout.LayoutParams(dp(48),dp(48)));
            View.OnClickListener play=v->{if(validHolder(this)&&actions!=null)actions.play(position);};
            View.OnLongClickListener drag=v->beginDrag(this);
            row.setOnClickListener(play);labels.setOnClickListener(play);
            row.setOnLongClickListener(drag);labels.setOnLongClickListener(drag);
            // Text views receive the same gesture if accessibility or a host targets them directly.
            title.setOnClickListener(play);artist.setOnClickListener(play);
            title.setOnLongClickListener(drag);artist.setOnLongClickListener(drag);
            more.setOnClickListener(v->{if(validHolder(this)&&actions!=null)actions.menu(position);});
            row.setTag(this);
        }
    }
    private void style(Holder holder) {
        boolean playing=holder.position==current,hover=holder.position==target;
        GradientDrawable background=new GradientDrawable();background.setCornerRadius(dp(8));
        background.setColor(hover?((ink&0x00ffffff)|0x18000000):Color.TRANSPARENT);
        if(hover)background.setStroke(dp(1),(ink&0x00ffffff)|0x88000000);
        holder.row.setBackground(background);holder.row.setMinimumHeight(dp(airy?112:68));
        holder.labels.setPadding(dp(8),dp(airy?26:14),0,dp(airy?26:14));
        holder.title.setTextColor(ink);holder.title.setTypeface(Typeface.create(playing?"sans-serif-medium":"sans-serif",Typeface.NORMAL));
        holder.artist.setTextColor((ink&0x00ffffff)|0xcc000000);holder.more.setInk((ink&0x00ffffff)|0xb3000000);
        holder.row.setActivated(hover);holder.row.setSelected(playing);
        if(android.os.Build.VERSION.SDK_INT>=30)holder.row.setStateDescription(playing?"正在播放":null);

    }
    private final class QueueAdapter extends BaseAdapter {
        @Override public int getCount(){return tracks.size();}
        @Override public Track getItem(int position){return tracks.get(position);}
        @Override public long getItemId(int position){return position;}
        @Override public View getView(int position,View recycled,ViewGroup parent) {
            Holder holder=recycled==null?new Holder():(Holder)recycled.getTag();
            holder.position=position;holder.boundGeneration=generation;holder.track=getItem(position);
            holder.title.setText(holder.track.title);holder.artist.setText(holder.track.artist);
            holder.labels.setContentDescription("播放 "+holder.track.title+"，长按拖动排序");
            holder.more.setContentDescription("调整队列中的 "+holder.track.title);
            style(holder);return holder.row;
        }
    }
    private int dp(float value){return Math.round(value*getResources().getDisplayMetrics().density);}
}
