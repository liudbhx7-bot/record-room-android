package com.recordroom.vinyl;

import android.content.Context;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.LinearGradient;
import android.graphics.RadialGradient;
import android.graphics.SweepGradient;
import android.graphics.Shader;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.LruCache;
import android.view.KeyEvent;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.LinearInterpolator;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.OverScroller;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/** Finite perspective album shelves: the original vertical stack and an optional upright horizontal rack. */
public final class AlbumStackView extends View {
    public interface OnAlbumClickListener {
        void onAlbumClick(ArrayList<Track> songs);
    }

    /** Real catalog metadata can be browsed before its full track list is requested. */
    public static final class Entry {
        public final String key,title,artist,artworkUri;
        public final List<Track> tracks;
        public Entry(String key,String title,String artist,String artworkUri,List<Track> tracks){
            this.key=key;this.title=title;this.artist=artist;this.artworkUri=artworkUri==null?"":artworkUri;
            this.tracks=Collections.unmodifiableList(new ArrayList<>(tracks==null?Collections.emptyList():tracks));
        }
    }
    public interface OnSelectionChangedListener { void onSelected(int index); }
    public interface OnAlbumIndexClickListener { void onAlbumClick(int index); }
    private static final int MAX_DRAWN = 18;
    private static final int ART_SIZE = 640;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final float[] UNIT_SQUARE = {0, 0, 512, 0, 512, 512, 0, 512};
    private final ArrayList<Album> albums = new ArrayList<>();
    private final HashSet<String> pending = new HashSet<>();
    private final HashSet<String> unavailable = new HashSet<>();
    private final LruCache<String, Cover> covers = new LruCache<String, Cover>(24 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Cover cover) { return cover.bitmap.getAllocationByteCount(); }
    };
    private final DrawnCard[] drawn = new DrawnCard[MAX_DRAWN];
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint caption = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint artistLabel = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint.FontMetrics labelMetrics = new Paint.FontMetrics();
    private final Matrix imageMatrix = new Matrix();
    private final float[] sourcePoints = new float[8];
    private final int[] screenLocation = new int[2];
    private final RectF lastSelectedBounds = new RectF();
    private final Context appContext;
    private final ThemeStore themes;
    private final OverScroller scroller;
    private final int touchSlop, minFling, maxFling;
    private ExecutorService artExecutor;
    private OnAlbumClickListener albumClick;
    private OnAlbumIndexClickListener indexClick;
    private OnSelectionChangedListener selectionChanged;
    private boolean expanded,upright,looping;
    private ValueAnimator sleeveAnimator;
    private Runnable sleeveStateChanged;
    private float sleeveProgress;
    private boolean sleeveTargetOpen,pendingSleeveOpen;
    private int sleeveIndex=-1,queuedSleeveIndex=-1,sleeveSlot;
    private float lastX;
    private final Paint recordPaint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
    private final Path recordLabelClip=new Path();
    private final RectF recordBounds=new RectF();
    private long lastSelectionHaptic=-1;
    private VelocityTracker velocity;
    private volatile boolean attached;
    private volatile int generation;
    private int drawnCount, selectedIndex, pointerId = -1, downCard = -1;
    private int ink = 0xff302a2c;
    private float spacing = 1, firstY, horizon, cardWidth, spineHeight, offset, maxOffset;
    private float pendingScrollPosition = Float.NaN;
    private float downX, downY, lastY;
    private boolean dragging, suppressClick, selectionScroll;
    private float lastSelectedTilt;

    public AlbumStackView(Context context) {
        super(context);
        appContext = context.getApplicationContext();
        themes = new ThemeStore(appContext);
        scroller = new OverScroller(context);
        scroller.setFriction(ViewConfiguration.getScrollFriction() * 1.15f);
        ViewConfiguration configuration = ViewConfiguration.get(context);
        touchSlop = configuration.getScaledTouchSlop();
        minFling = configuration.getScaledMinimumFlingVelocity();
        maxFling = configuration.getScaledMaximumFlingVelocity();
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        label.setTextAlign(Paint.Align.CENTER);
        label.setTextSize(sp(10.5f));
        artistLabel.setTypeface(Typeface.create("sans-serif",Typeface.NORMAL));
        artistLabel.setTextSize(sp(10.5f));
        caption.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        caption.setTextAlign(Paint.Align.CENTER);
        caption.setTextSize(sp(14));
        for (int i = 0; i < drawn.length; i++) drawn[i] = new DrawnCard();
        setClickable(true);
        setFocusable(true);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        updateDescription();
    }

    /** Consolidates repeated groups by album identity without creating sample tracks. */
    public void setAlbums(List<ArrayList<Track>> values) {
        LinkedHashMap<String,ArrayList<Track>> grouped=new LinkedHashMap<>();
        if(values!=null)for(List<Track> group:values)if(group!=null)for(Track track:group)if(track!=null)
            grouped.computeIfAbsent(track.albumKey(),key->new ArrayList<>()).add(track);
        ArrayList<Entry> entries=new ArrayList<>();
        for(java.util.Map.Entry<String,ArrayList<Track>> group:grouped.entrySet()){
            Track first=group.getValue().get(0);String art="";
            for(Track track:group.getValue())if(!track.artworkUri.isEmpty()){art=track.artworkUri;break;}
            entries.add(new Entry(group.getKey(),first.album,first.albumArtist,art,group.getValue()));
        }
        setEntries(entries);
    }

    public void setEntries(List<Entry> entries){
        String previous=albums.isEmpty()?null:albums.get(Math.min(selectedIndex,albums.size()-1)).key;
        float relativePosition=upright&&selectionScroll?0:getScrollPosition()-selectedIndex;
        String sleeveKey=sleeveIndex>=0&&sleeveIndex<albums.size()?albums.get(sleeveIndex).key:null;
        String queuedKey=queuedSleeveIndex>=0&&queuedSleeveIndex<albums.size()?albums.get(queuedSleeveIndex).key:null;
        boolean resumeAnimation=sleeveAnimator!=null;cancelSleeveAnimator();queuedSleeveIndex=-1;
        generation++;pending.clear();unavailable.clear();scroller.abortAnimation();
        albums.clear();drawnCount=0;selectedIndex=0;offset=0;
        LinkedHashMap<String,Entry> unique=new LinkedHashMap<>();
        if(entries!=null)for(Entry entry:entries){
            if(entry==null||entry.key==null||entry.key.isEmpty())continue;
            Entry before=unique.get(entry.key);LinkedHashMap<String,Track> songs=new LinkedHashMap<>();
            if(before!=null)for(Track track:before.tracks)if(track!=null)songs.put(LibraryStore.identity(track),track);
            for(Track track:entry.tracks)if(track!=null)songs.put(LibraryStore.identity(track),track);
            unique.put(entry.key,new Entry(entry.key,entry.title,entry.artist,entry.artworkUri.isEmpty()&&before!=null?before.artworkUri:entry.artworkUri,new ArrayList<>(songs.values())));
        }
        for(Entry entry:unique.values()){
            Album album=new Album(entry,themes);albums.add(album);
            if(album.key.equals(previous))selectedIndex=albums.size()-1;
        }
        if(upright&&sleeveKey!=null){sleeveIndex=-1;for(int i=0;i<albums.size();i++)if(sleeveKey.equals(albums.get(i).key)){sleeveIndex=i;break;}if(sleeveIndex<0)resetSleeve();}
        layoutCards();
        if(!Float.isNaN(pendingScrollPosition))setScrollPosition(pendingScrollPosition);
        else if(previous!=null)setScrollPosition(selectedIndex+relativePosition);
        if(upright&&sleeveIndex>=0)sleeveSlot=Math.round(nearestSlot(sleeveIndex));
        if(upright&&queuedKey!=null&&!albums.isEmpty()&&queuedKey.equals(albums.get(selectedIndex).key)){if(sleeveIndex>=0)queuedSleeveIndex=selectedIndex;else pendingSleeveOpen=true;}
        if(upright&&resumeAnimation&&sleeveIndex>=0)animateSleeve(sleeveTargetOpen);
        if(upright&&pendingSleeveOpen&&!albums.isEmpty())openSelectedSleeve();
        updateDescription();requestArtwork();invalidate();
    }

    /** Fractional album index at the viewport horizon; independent of pixel density. */
    public float getScrollPosition() {
        if (!Float.isNaN(pendingScrollPosition)) return pendingScrollPosition;
        if (albums.isEmpty() || getHeight() <= 0) return 0;
        float position=(offset+horizon-firstY)/Math.max(1,spacing);
        if(isLooping())position=selectedIndex+(position-Math.round(position));
        return clamp(position,0,albums.size()-1);
    }

    /** Restores after layout or defers until both dimensions and album data exist. */
    public void setScrollPosition(float position) {
        if (Float.isNaN(position) || Float.isInfinite(position)) return;
        scroller.abortAnimation(); selectionScroll = false;
        if (albums.isEmpty() || getWidth() <= 0 || getHeight() <= 0) {
            pendingScrollPosition = Math.max(0, position); return;
        }
        pendingScrollPosition = Float.NaN;
        offset = clamp(firstY + clamp(position, 0, albums.size() - 1) * spacing - horizon, 0, maxOffset);
        boolean restoreOpen=pendingSleeveOpen;selectNearest();if(restoreOpen)pendingSleeveOpen=true;if(upright&&sleeveIndex>=0)sleeveSlot=Math.round(nearestSlot(sleeveIndex));requestArtwork();invalidate();
    }

    public void setOnAlbumClickListener(OnAlbumClickListener listener) { albumClick = listener; }
    public void setOnAlbumIndexClickListener(OnAlbumIndexClickListener listener){indexClick=listener;}
    public void setOnSelectionChangedListener(OnSelectionChangedListener listener){selectionChanged=listener;}
    public int getSelectedIndex(){return selectedIndex;}
    public int getAlbumCount(){return albums.size();}
    public boolean isExpanded(){return upright?isSleeveOpeningOrOpen():expanded;}
    public boolean isUpright(){return upright;}
    /** Circular browsing belongs to the upright artist shelf; the main library keeps its old layout. */
    public void setLooping(boolean value){if(looping==value)return;float position=getScrollPosition();looping=value;scroller.abortAnimation();layoutCards();setScrollPosition(position);updateDescription();invalidate();}
    public boolean isLooping(){return upright&&looping&&albums.size()>1;}
    private int albumIndex(int slot){return isLooping()?Math.floorMod(slot,albums.size()):slot;}
    private float nearestSlot(int index){if(!isLooping())return index;float position=(offset+horizon-firstY)/Math.max(1,spacing);return index+Math.round((position-index)/albums.size())*albums.size();}
    private float boundedOffset(float value){return isLooping()?value:clamp(value,0,maxOffset);}
    public void setUpright(boolean value){
        if(upright==value)return;float position=getScrollPosition();resetSleeve();upright=value;scroller.abortAnimation();
        layoutCards();setScrollPosition(position);updateDescription();requestArtwork();invalidate();
    }
    public void setOnSleeveStateChangedListener(Runnable listener){sleeveStateChanged=listener;}
    /** True only when the selected sleeve is fully open; clicks during the animation never navigate. */
    public boolean isSleeveOpen(){return upright&&sleeveTargetOpen&&sleeveIndex==selectedIndex&&sleeveProgress>=.999f;}
    public boolean isSleeveOpeningOrOpen(){return upright&&(pendingSleeveOpen||queuedSleeveIndex==selectedIndex||sleeveTargetOpen&&sleeveIndex==selectedIndex);}
    public float getSleeveOpenProgress(){return sleeveProgress;}
    public void openSelectedSleeve(){
        if(!upright)return;if(albums.isEmpty()||getWidth()<=0||getHeight()<=0){pendingSleeveOpen=true;return;}
        pendingSleeveOpen=false;
        if(sleeveProgress>0&&sleeveIndex!=selectedIndex){queuedSleeveIndex=selectedIndex;notifySleeveState();animateSleeve(false);return;}
        if(sleeveIndex==selectedIndex&&sleeveTargetOpen)return;
        sleeveIndex=selectedIndex;sleeveSlot=Math.round(nearestSlot(selectedIndex));queuedSleeveIndex=-1;
        scroller.abortAnimation();selectionScroll=true;int target=Math.round(firstY+nearestSlot(selectedIndex)*spacing-horizon);
        if(!ValueAnimator.areAnimatorsEnabled())offset=target;else scroller.startScroll(0,Math.round(offset),0,target-Math.round(offset),410);
        animateSleeve(true);requestArtwork();
    }
    public void closeSleeve(){boolean queued=pendingSleeveOpen||queuedSleeveIndex>=0;pendingSleeveOpen=false;queuedSleeveIndex=-1;if(!upright)return;if(queued&&!sleeveTargetOpen)notifySleeveState();animateSleeve(false);}
    private void notifySleeveState(){updateDescription();if(sleeveStateChanged!=null)sleeveStateChanged.run();}
    private void cancelSleeveAnimator(){if(sleeveAnimator!=null){ValueAnimator old=sleeveAnimator;sleeveAnimator=null;old.removeAllListeners();old.removeAllUpdateListeners();old.cancel();}}
    private void stopSleeveForLifecycle(){
        boolean open=isSleeveOpeningOrOpen();cancelSleeveAnimator();queuedSleeveIndex=-1;
        sleeveTargetOpen=open;sleeveIndex=open&&!albums.isEmpty()?selectedIndex:-1;sleeveSlot=Math.round(nearestSlot(selectedIndex));sleeveProgress=sleeveIndex>=0?1:0;pendingSleeveOpen=open&&sleeveIndex<0;
    }
    private void resetSleeve(){cancelSleeveAnimator();sleeveProgress=0;sleeveIndex=-1;queuedSleeveIndex=-1;sleeveTargetOpen=false;pendingSleeveOpen=false;}
    private void animateSleeve(boolean open){
        if(!upright)return;float target=open?1:0;boolean changed=sleeveTargetOpen!=open;sleeveTargetOpen=open;
        if(sleeveAnimator!=null&&!changed)return;cancelSleeveAnimator();
        if(changed)notifySleeveState();
        if(!attached||!ValueAnimator.areAnimatorsEnabled()||Math.abs(target-sleeveProgress)<.001f){sleeveProgress=target;finishSleeveAnimation();return;}
        ValueAnimator animator=ValueAnimator.ofFloat(sleeveProgress,target);sleeveAnimator=animator;
        animator.setDuration(Math.max(1,Math.round((open?760:300)*Math.abs(target-sleeveProgress))));animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(value->{if(sleeveAnimator==value){sleeveProgress=(float)value.getAnimatedValue();invalidate();}});
        animator.addListener(new AnimatorListenerAdapter(){@Override public void onAnimationEnd(Animator value){if(sleeveAnimator!=value)return;sleeveAnimator=null;sleeveProgress=target;finishSleeveAnimation();}});animator.start();invalidate();
    }
    private void finishSleeveAnimation(){
        if(sleeveTargetOpen&&sleeveProgress>=.999f&&sleeveIndex==selectedIndex){scroller.abortAnimation();selectionScroll=false;offset=boundedOffset(firstY+nearestSlot(selectedIndex)*spacing-horizon);}
        if(sleeveProgress<=0){sleeveIndex=-1;int next=queuedSleeveIndex;queuedSleeveIndex=-1;if(next>=0&&next==selectedIndex){openSelectedSleeve();return;}}
        notifySleeveState();invalidate();
    }
    private static float ease(float value){float t=clamp(value,0,1);return t*t*(3-2*t);}
    private float sleeveFront(){return ease(sleeveProgress/.54f);}
    private float sleeveRecord(){return ease((sleeveProgress-.54f)/.46f);}

    public void setExpanded(boolean value){
        if(upright){if(value)openSelectedSleeve();else closeSleeve();return;}
        if(expanded==value)return;float position=getScrollPosition();expanded=value;scroller.abortAnimation();
        layoutCards();setScrollPosition(position);requestArtwork();invalidate();
    }
    public boolean selectAlbum(int direction){return moveSelection(direction);}
    public void setInkColor(int color) { ink = color; invalidate(); }
    public RectF getLastSelectedScreenBounds() { return new RectF(lastSelectedBounds); }
    public float getLastSelectedTiltDegrees() { return lastSelectedTilt; }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow(); attached = true;
        if (artExecutor == null || artExecutor.isShutdown()) artExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "record-room-album-art"); thread.setDaemon(true); return thread;
        });
        requestArtwork();
    }

    @Override protected void onDetachedFromWindow() {
        attached = false; generation++;stopSleeveForLifecycle();
        scroller.abortAnimation(); recycleVelocity();
        pending.clear(); unavailable.clear(); covers.evictAll();
        if (artExecutor != null) { artExecutor.shutdownNow(); artExecutor = null; }
        super.onDetachedFromWindow();
    }

    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (scroller == null) return;
        if (visibility != VISIBLE) {scroller.abortAnimation();if(upright)stopSleeveForLifecycle();}
        else {requestArtwork();if(upright)notifySleeveState();}
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        float center = getScrollPosition();
        super.onSizeChanged(w, h, oldw, oldh);
        scroller.abortAnimation(); layoutCards();
        if (!Float.isNaN(pendingScrollPosition)) setScrollPosition(pendingScrollPosition);
        else if (oldh > 0) setScrollPosition(center);
        else { offset = 0; selectNearest(); }
        if(upright&&pendingSleeveOpen&&!albums.isEmpty())openSelectedSleeve();
        requestArtwork();
    }

    private void layoutCards() {
        if (getWidth() <= 0 || getHeight() <= 0) return;
        float availableHeight=Math.max(dp(96),getHeight()-getPaddingTop()-getPaddingBottom());
        if(upright){
            float availableWidth=Math.max(1,getWidth()-getPaddingLeft()-getPaddingRight());
            cardWidth=Math.max(dp(24),Math.min(availableWidth*.84f,Math.max(1,getHeight()-getPaddingTop()-getPaddingBottom())*.88f));
            horizon=getPaddingLeft()+availableWidth*.5f;firstY=horizon;spacing=Math.max(dp(38),cardWidth*.25f);spineHeight=Math.max(dp(5),cardWidth*.026f);
            maxOffset=Math.max(0,(albums.size()-1)*spacing);offset=clamp(offset,0,maxOffset);return;
        }
        if(expanded){
            cardWidth=Math.min((getWidth()-getPaddingLeft()-getPaddingRight())*.84f,availableHeight*.70f);
            horizon=getPaddingTop()+availableHeight*.5f;spacing=Math.max(dp(64),cardWidth*.70f);firstY=horizon;
            label.getFontMetrics(labelMetrics);spineHeight=Math.max(dp(17),labelMetrics.descent-labelMetrics.ascent+dp(4));
            maxOffset=Math.max(0,(albums.size()-1)*spacing);offset=clamp(offset,0,maxOffset);return;
        }
        cardWidth = Math.min((getWidth()-getPaddingLeft()-getPaddingRight()) * .80f, availableHeight * .94f);
        horizon = getPaddingTop()+availableHeight * .48f;
        spacing = Math.max(dp(58), Math.min(cardWidth * .255f, availableHeight * .16f));
        if (albums.size() <= 4) {
            spacing = Math.min(cardWidth * .45f, getHeight() * .225f);
            firstY = getPaddingTop()+(availableHeight - Math.max(0, albums.size() - 1) * spacing) * .5f;
        } else firstY = getPaddingTop()+Math.max(dp(18), availableHeight * .035f);
        if (albums.size() == 1) firstY = getPaddingTop()+availableHeight * .67f;
        // With only two or three sleeves, put the horizon in a gap so no real
        // album is represented solely by a spine in the initial, non-scrolling view.
        if (albums.size() == 2 || albums.size() == 3) horizon = firstY + spacing * .5f;
        label.getFontMetrics(labelMetrics);
        spineHeight = Math.max(dp(15), labelMetrics.descent - labelMetrics.ascent + dp(3));
        maxOffset = albums.size() <= 3 ? 0 : Math.max(0, firstY + (albums.size() - 1) * spacing - getHeight() * .70f);
        offset = clamp(offset, 0, maxOffset);
    }

    private int circularDrawCount(){return Math.min(MAX_DRAWN,Math.max(3,albums.size()));}
    private boolean isSleeveCard(DrawnCard card){return card.index==sleeveIndex&&(!isLooping()||card.slot==sleeveSlot);}
    private int firstVisible() { if(isLooping())return Math.round((offset+horizon-firstY)/spacing)-circularDrawCount()/2;if(upright)return Math.max(0,(int)Math.floor((offset-horizon-cardWidth)/Math.max(1,spacing)));return Math.max(0, (int)Math.floor((offset - firstY - cardWidth * .42f) / Math.max(1, spacing))); }
    private int lastVisible() { if(isLooping())return firstVisible()+circularDrawCount()-1;if(upright)return Math.min(albums.size()-1,(int)Math.ceil((offset+getWidth()-horizon+cardWidth)/Math.max(1,spacing)));return Math.min(albums.size() - 1, (int)Math.ceil((offset + getHeight() - firstY + cardWidth * .42f) / Math.max(1, spacing))); }

    private int boundedFirstVisible(){
        int first=firstVisible(),last=lastVisible();
        if(upright&&!isLooping()&&last-first+1>MAX_DRAWN)first=Math.max(first,Math.min(selectedIndex-MAX_DRAWN/2,last-MAX_DRAWN+1));
        return first;
    }

    private void requestArtwork() {
        if (!attached || artExecutor == null || getWidth() == 0 || albums.isEmpty()) return;
        int first = isLooping()?boundedFirstVisible()-2:Math.max(0,boundedFirstVisible()-2), last = isLooping()?lastVisible()+2:Math.min(albums.size()-1,lastVisible()+2);
        // The viewport and two neighbours are the only decoding candidates.
        last = Math.min(last, first + MAX_DRAWN + 3);
        for (int i = first; i <= last; i++) {
            if (pending.size() >= MAX_DRAWN + 4) break;
            Album album = albums.get(albumIndex(i));
            if (album.artUri.isEmpty() || covers.get(album.artKey) != null || pending.contains(album.artKey) || unavailable.contains(album.artKey)) continue;
            pending.add(album.artKey);
            try { artExecutor.execute(new ArtTask(this, album.artKey, album.artUri, generation)); }
            catch (RejectedExecutionException ignored) { pending.remove(album.artKey); }
        }
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas); drawnCount = 0;
        if (albums.isEmpty()) {
            caption.setColor(ink); caption.setAlpha(230);
            canvas.drawText("还没有收藏的专辑", getWidth() * .5f, getHeight() * .43f, caption);
            caption.setAlpha(150); caption.setTextSize(sp(12));
            canvas.drawText("导入音乐后，专辑会出现在这里", getWidth() * .5f, getHeight() * .43f + dp(27), caption);
            caption.setTextSize(sp(14)); caption.setAlpha(255); return;
        }
        if (cardWidth <= 0) return;
        int first = boundedFirstVisible(), last = Math.min(lastVisible(), first + MAX_DRAWN - 1);
        for (int i = first; i <= last; i++) {
            DrawnCard card = drawn[drawnCount++];
            geometry(card,i);if(!upright&&!expanded)drawCard(canvas,card,albums.get(card.index));
        }
        if(upright){
            for(int distance=MAX_DRAWN;distance>=0;distance--)for(int i=0;i<drawnCount;i++)if(!isSleeveCard(drawn[i])&&Math.abs(drawn[i].slot-Math.round((offset+horizon-firstY)/spacing))==distance)drawUprightSleeve(canvas,drawn[i],albums.get(drawn[i].index));
            for(int i=0;i<drawnCount;i++)if(isSleeveCard(drawn[i]))drawUprightSleeve(canvas,drawn[i],albums.get(drawn[i].index));
        }else if(expanded)for(int distance=MAX_DRAWN;distance>=0;distance--)for(int i=0;i<drawnCount;i++)
            if(Math.abs(drawn[i].index-selectedIndex)==distance)drawCard(canvas,drawn[i],albums.get(drawn[i].index));
    }

    private void geometry(DrawnCard card, int index) {
        card.slot=index;card.index=albumIndex(index);
        if(upright){uprightGeometry(card,index);return;}
        float y = firstY + index * spacing - offset;
        float relative = y - horizon;
        float factor = Math.min(1, Math.abs(relative) / Math.max(1, getHeight() * .48f));
        if(expanded){
            float width=cardWidth*(1-.10f*factor),height=width*(.94f-.18f*factor);
            float left=(getWidth()-width)*.5f,right=left+width,inset=width*(.015f+.035f*factor);
            float top=y-height*.5f,bottom=y+height*.5f;float[] q=card.quad;
            q[0]=left+inset;q[1]=top;q[2]=right-inset;q[3]=top;q[4]=right;q[5]=bottom;q[6]=left;q[7]=bottom;
            card.spine.set(left,bottom,right,bottom+spineHeight);
            card.surface.reset();card.surface.moveTo(q[0],q[1]);card.surface.lineTo(q[2],q[3]);card.surface.lineTo(q[4],q[5]);card.surface.lineTo(q[6],q[7]);card.surface.close();return;
        }
        float depth = Math.max(dp(3), cardWidth * (albums.size() <= 3 ? .52f : .32f) * factor);
        if (albums.size() == 1) depth = Math.min(cardWidth * .72f, getHeight() * .49f);
        else depth = Math.min(depth, Math.max(dp(3), spacing - spineHeight - dp(5)));
        float left = (getWidth() - cardWidth) * .5f, right = left + cardWidth;
        float inset = cardWidth * (.075f + factor * .045f);
        float[] q = card.quad;
        if (relative < 0) {
            q[0] = left; q[1] = y + spineHeight;
            q[2] = right; q[3] = y + spineHeight;
            q[4] = right - inset; q[5] = y + spineHeight + depth;
            q[6] = left + inset; q[7] = q[5];
        } else {
            q[0] = left + inset; q[1] = y - depth;
            q[2] = right - inset; q[3] = y - depth;
            q[4] = right; q[5] = y;
            q[6] = left; q[7] = y;
        }
        card.spine.set(left, y, right, y + spineHeight);
        card.surface.reset(); card.surface.moveTo(q[0], q[1]);
        card.surface.lineTo(q[2], q[3]); card.surface.lineTo(q[4], q[5]); card.surface.lineTo(q[6], q[7]); card.surface.close();
    }

    private void uprightGeometry(DrawnCard card,int index){
        float position=(offset+horizon-firstY)/Math.max(1,spacing),relative=index-position;
        float front=sleeveIndex>=0?sleeveFront():0,activeRelative=index-sleeveSlot;
        float center=firstY+index*spacing-offset;
        boolean opening=isSleeveCard(card);
        float openWidth=Math.min(cardWidth,(getWidth()-getPaddingLeft()-getPaddingRight())*.66f);
        if(opening)center-=openWidth*.25f*front;
        else if(sleeveIndex>=0)center+=Math.signum(activeRelative)*cardWidth*.60f*front;
        float distance=Math.min(3,Math.abs(relative));
        float projected=cardWidth*(.035f+.25f*Math.min(1,distance)+.055f*Math.min(2,Math.max(0,distance-1)));
        float height=cardWidth*(.94f-.035f*Math.min(3,distance));
        if(opening){projected=projected*(1-front)+openWidth*front;height=height*(1-front)+openWidth*front;}
        float middle=getPaddingTop()+(getHeight()-getPaddingTop()-getPaddingBottom())*.51f;
        float left=center-projected*.5f,right=center+projected*.5f,top=middle-height*.5f,bottom=middle+height*.5f;
        float skew=cardWidth*.13f*(opening?1-front:1);
        boolean rightSide=relative>.05f&&!opening;
        float[] q=card.quad;
        q[0]=left;q[1]=top+(rightSide?skew:0);q[2]=right;q[3]=top+(rightSide?0:skew);
        q[4]=right;q[5]=bottom-(rightSide?0:skew);q[6]=left;q[7]=bottom-(rightSide?skew:0);
        float thickness=spineHeight*(opening?1-.58f*front:1);
        card.spine.set(rightSide?right:left-thickness,top,rightSide?right+thickness:left,bottom);
        card.surface.reset();card.surface.moveTo(q[0],q[1]);card.surface.lineTo(q[2],q[3]);card.surface.lineTo(q[4],q[5]);card.surface.lineTo(q[6],q[7]);card.surface.close();
    }

    private void drawUprightSleeve(Canvas canvas,DrawnCard card,Album album){
        Cover cover=covers.get(album.artKey);int edge=cover==null?album.fallback:cover.color;
        float[] q=card.quad;float left=q[0],right=q[2],top=Math.min(q[1],q[3]),bottom=Math.max(q[5],q[7]);
        float front=isSleeveCard(card)?sleeveFront():0,disc=isSleeveCard(card)?sleeveRecord():0;
        fill.setStyle(Paint.Style.FILL);fill.setShader(null);fill.setColor(0x26000000);
        canvas.drawOval(left-dp(6),bottom+dp(4),right+dp(9)+cardWidth*.60f*disc,bottom+dp(15),fill);
        if(disc>0){
            float size=right-left,cx=(left+right)*.5f+size*.50f*disc,cy=(top+bottom)*.5f,radius=size*.49f;
            drawRecord(canvas,cover,album,cx,cy,radius);
            // The narrow inner sleeve lip stays behind the printed jacket and in front of the record.
            fill.setShader(new LinearGradient(right,0,right+size*.035f,0,0xffd3cbbf,0xfff2eee5,Shader.TileMode.CLAMP));
            canvas.drawRect(right-dp(1),top+size*.018f,right+size*.035f*disc,bottom-size*.018f,fill);fill.setShader(null);
        }
        canvas.save();canvas.translate(dp(2),dp(3));fill.setColor(0x35000000);canvas.drawPath(card.surface,fill);canvas.restore();
        canvas.save();canvas.clipPath(card.surface);
        if(cover!=null&&!cover.bitmap.isRecycled()){
            Bitmap image=cover.bitmap;float side=Math.min(image.getWidth(),image.getHeight());float x=(image.getWidth()-side)*.5f,y=(image.getHeight()-side)*.5f;
            sourcePoints[0]=x;sourcePoints[1]=y;sourcePoints[2]=x+side;sourcePoints[3]=y;sourcePoints[4]=x+side;sourcePoints[5]=y+side;sourcePoints[6]=x;sourcePoints[7]=y+side;
            imageMatrix.setPolyToPoly(sourcePoints,0,q,0,4);canvas.drawBitmap(image,imageMatrix,bitmapPaint);
        }else{
            fill.setColor(album.fallback);canvas.drawRect(left,top,right,bottom,fill);
            if(right-left>cardWidth*.65f){fill.setColor(0x18000000);canvas.drawRect(left+(right-left)*.10f,top+(bottom-top)*.11f,right-(right-left)*.10f,bottom-(bottom-top)*.11f,fill);}
        }
        fill.setShader(new LinearGradient(left,0,right,0,new int[]{0x38000000,0x04ffffff,0x08000000},new float[]{0,.42f,1},Shader.TileMode.CLAMP));canvas.drawPath(card.surface,fill);fill.setShader(null);canvas.restore();
        fill.setStyle(Paint.Style.STROKE);fill.setStrokeWidth(dp(.65f));fill.setColor(0x5cffffff);canvas.drawPath(card.surface,fill);fill.setStyle(Paint.Style.FILL);
        RectF spine=card.spine;
        fill.setShader(new LinearGradient(spine.left,0,spine.right,0,new int[]{darken(edge,.48f),edge,darken(edge,.76f)},new float[]{0,.55f,1},Shader.TileMode.CLAMP));canvas.drawRect(spine,fill);fill.setShader(null);
        fill.setColor(0x88ffffff);canvas.drawRect(spine.left,spine.top,spine.left+dp(.55f),spine.bottom,fill);
        fill.setColor(0x90000000);canvas.drawRect(spine.right-dp(.45f),spine.top,spine.right,spine.bottom,fill);
        if(front<.92f&&spine.height()>dp(42)){
            label.setTextAlign(Paint.Align.LEFT);label.setTextSize(Math.min(sp(11),Math.max(sp(7),spine.width()*.75f)));label.setColor(luminance(edge)>145?0xff222329:0xfff2eee7);
            String title=fitLabel(album.title+"  ·  "+album.artist,label,spine.height()-dp(15));
            canvas.save();canvas.translate(spine.centerX(),spine.top+dp(7));canvas.rotate(90);canvas.drawText(title,0,-(label.ascent()+label.descent())*.5f,label);canvas.restore();
            label.setTextSize(sp(10.5f));label.setTextAlign(Paint.Align.CENTER);
        }
    }

    private void drawRecord(Canvas canvas,Cover cover,Album album,float cx,float cy,float radius){
        recordPaint.setStyle(Paint.Style.FILL);recordPaint.setShader(new RadialGradient(cx,cy,radius,new int[]{0xff17191c,0xff090a0c,0xff24272b},new float[]{0,.89f,1},Shader.TileMode.CLAMP));canvas.drawCircle(cx,cy,radius,recordPaint);
        recordPaint.setShader(new SweepGradient(cx,cy,new int[]{0x00000000,0x005d6570,0x536c7581,0x00000000,0x00000000,0x4c6e7681,0x00000000},new float[]{0,.16f,.23f,.33f,.64f,.73f,1}));canvas.drawCircle(cx,cy,radius-dp(1),recordPaint);recordPaint.setShader(null);
        recordPaint.setStyle(Paint.Style.STROKE);recordPaint.setStrokeWidth(dp(.55f));
        for(float r=radius*.38f;r<radius*.965f;r+=Math.max(dp(1.5f),radius/100)){recordPaint.setColor(((int)((r/radius)*16)+8)<<24|0x9a9da4);canvas.drawCircle(cx,cy,r,recordPaint);}
        recordPaint.setColor(0x8a07080a);recordPaint.setStrokeWidth(dp(1.1f));canvas.drawCircle(cx,cy,radius-dp(.7f),recordPaint);
        recordPaint.setStyle(Paint.Style.FILL);float labelRadius=radius*.29f;recordPaint.setColor(cover==null?album.fallback:cover.color);canvas.drawCircle(cx,cy,labelRadius,recordPaint);
        if(cover!=null&&!cover.bitmap.isRecycled()){
            recordLabelClip.reset();recordLabelClip.addCircle(cx,cy,labelRadius,Path.Direction.CW);canvas.save();canvas.clipPath(recordLabelClip);
            recordBounds.set(cx-labelRadius,cy-labelRadius,cx+labelRadius,cy+labelRadius);canvas.drawBitmap(cover.bitmap,null,recordBounds,bitmapPaint);canvas.restore();
        }
        recordPaint.setStyle(Paint.Style.STROKE);recordPaint.setColor(0x5affffff);recordPaint.setStrokeWidth(dp(.7f));canvas.drawCircle(cx,cy,labelRadius,recordPaint);recordPaint.setStyle(Paint.Style.FILL);
        recordPaint.setColor(0xffc4bfb3);canvas.drawCircle(cx,cy,Math.max(dp(2.8f),radius*.025f),recordPaint);recordPaint.setColor(0xff07080b);canvas.drawCircle(cx,cy,Math.max(dp(1.5f),radius*.013f),recordPaint);
    }
    private static int darken(int color,float amount){return Color.rgb(Math.round(Color.red(color)*amount),Math.round(Color.green(color)*amount),Math.round(Color.blue(color)*amount));}

    private boolean uprightTouch(MotionEvent event){
        int action=event.getActionMasked();
        if(action==MotionEvent.ACTION_DOWN){
            suppressClick=!scroller.isFinished()&&!selectionScroll;scroller.abortAnimation();selectionScroll=false;recycleVelocity();velocity=VelocityTracker.obtain();velocity.addMovement(event);
            pointerId=event.getPointerId(0);downX=lastX=event.getX();downY=event.getY();dragging=false;downCard=hitTest(downX,downY);
            if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(true);return true;
        }
        if(velocity!=null)velocity.addMovement(event);
        if(action==MotionEvent.ACTION_POINTER_DOWN){suppressClick=true;return true;}
        if(action==MotionEvent.ACTION_POINTER_UP){int up=event.getActionIndex();if(event.getPointerId(up)==pointerId){int next=up==0?1:0;if(next<event.getPointerCount()){pointerId=event.getPointerId(next);lastX=downX=event.getX(next);downY=event.getY(next);}}suppressClick=true;return true;}
        int pointer=event.findPointerIndex(pointerId);if(pointer<0){finishTouch();return true;}float x=event.getX(pointer),y=event.getY(pointer);
        if(action==MotionEvent.ACTION_MOVE||action==MotionEvent.ACTION_UP){
            if(!dragging&&Math.abs(x-downX)>touchSlop&&Math.abs(x-downX)>=Math.abs(y-downY)){dragging=true;closeSleeve();}
            if(Math.abs(y-downY)>touchSlop)suppressClick=true;
            if(dragging){offset=boundedOffset(offset+lastX-x);selectNearest(true);requestArtwork();invalidate();}lastX=x;
        }
        if(action==MotionEvent.ACTION_UP){
            if(dragging&&velocity!=null){velocity.computeCurrentVelocity(1000,maxFling);float vx=-velocity.getXVelocity(pointerId);if(Math.abs(vx)>=minFling&&maxOffset>0){scroller.fling(0,Math.round(offset),0,Math.round(vx),0,0,isLooping()?Integer.MIN_VALUE/4:0,isLooping()?Integer.MAX_VALUE/4:Math.round(maxOffset));postInvalidateOnAnimation();}}
            else if(!suppressClick){int hit=hitTest(x,y);if(hit>=0&&hit==downCard){changeSelection(hit,true);performClick();}}
            finishTouch();return true;
        }
        if(action==MotionEvent.ACTION_CANCEL){finishTouch();return true;}return true;
    }

    private void drawCard(Canvas canvas, DrawnCard card, Album album) {
        Cover cover = covers.get(album.artKey);
        int spineColor = cover == null ? album.fallback : cover.color;
        fill.setStyle(Paint.Style.FILL); fill.setShader(null); fill.setColor(0x20000000);
        canvas.save(); canvas.translate(0, dp(5)); canvas.drawPath(card.surface, fill); canvas.drawRect(card.spine, fill); canvas.restore();
        canvas.save(); canvas.clipPath(card.surface);
        if (cover != null && !cover.bitmap.isRecycled()) {
            Bitmap image = cover.bitmap;
            float side = Math.min(image.getWidth(), image.getHeight());
            float left = (image.getWidth() - side) * .5f, top = (image.getHeight() - side) * .5f;
            sourcePoints[0] = left; sourcePoints[1] = top;
            sourcePoints[2] = left + side; sourcePoints[3] = top;
            sourcePoints[4] = left + side; sourcePoints[5] = top + side;
            sourcePoints[6] = left; sourcePoints[7] = top + side;
            imageMatrix.setPolyToPoly(sourcePoints, 0, card.quad, 0, 4);
            canvas.drawBitmap(image, imageMatrix, bitmapPaint);
        } else {
            imageMatrix.setPolyToPoly(UNIT_SQUARE, 0, card.quad, 0, 4);
            canvas.concat(imageMatrix);
            fill.setColor(album.fallback); canvas.drawRect(0, 0, 512, 512, fill);
            fill.setColor(0xff26232a); canvas.drawCircle(256, 256, 179, fill);
            fill.setColor(0xffd9c4b5); canvas.drawCircle(256, 256, 68, fill);
            fill.setColor(0xff29242d); canvas.drawCircle(256, 256, 8, fill);
        }
        canvas.restore();
        fill.setColor(0x08000000); canvas.drawPath(card.surface, fill);
        fill.setColor(spineColor); canvas.drawRect(card.spine, fill);
        fill.setColor(0x32ffffff); fill.setStrokeWidth(dp(.6f));
        canvas.drawLine(card.spine.left, card.spine.top, card.spine.right, card.spine.top, fill);
        fill.setColor(0x20000000);
        canvas.drawLine(card.spine.left, card.spine.bottom, card.spine.right, card.spine.bottom, fill);
        label.setColor(luminance(spineColor) > 150 ? 0xff171719 : Color.WHITE);
        artistLabel.setColor(label.getColor());
        float width = cardWidth - dp(16);
        if (album.labelWidth != width) {
            float gap=label.measureText("  ");
            float titleWidth=label.measureText(album.title),artistWidth=artistLabel.measureText(album.artist);
            float titleBudget=Math.min(titleWidth,width);
            if(titleWidth+gap+artistWidth>width)titleBudget=Math.min(titleWidth,width*.72f);
            album.fittedLabel=fitLabel(album.title,label,titleBudget);
            float remaining=width-label.measureText(album.fittedLabel)-gap;
            album.fittedArtist=remaining>artistLabel.measureText("…")?fitLabel(album.artist,artistLabel,remaining):"";
            album.labelWidth=width;
        }
        float gap=album.fittedArtist.isEmpty()?0:label.measureText("  ");
        float titleWidth=label.measureText(album.fittedLabel),artistWidth=artistLabel.measureText(album.fittedArtist);
        float x=(getWidth()-titleWidth-gap-artistWidth)*.5f;
        float baseline = card.spine.centerY() - (labelMetrics.ascent + labelMetrics.descent) * .5f;
        label.setTextAlign(Paint.Align.LEFT);canvas.drawText(album.fittedLabel,x,baseline,label);
        artistLabel.setTextAlign(Paint.Align.LEFT);canvas.drawText(album.fittedArtist,x+titleWidth+gap,baseline,artistLabel);
        label.setTextAlign(Paint.Align.CENTER);

    }

    @Override public void computeScroll() {
        if (scroller.computeScrollOffset()) {
            offset = boundedOffset(scroller.getCurrY());
            if (!selectionScroll) selectNearest(true);
            requestArtwork(); postInvalidateOnAnimation();
        } else selectionScroll = false;
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled() || albums.isEmpty()) return super.onTouchEvent(event);
        if(upright)return uprightTouch(event);
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            suppressClick = !scroller.isFinished(); scroller.abortAnimation(); selectionScroll = false;
            recycleVelocity(); velocity = VelocityTracker.obtain(); velocity.addMovement(event);
            pointerId = event.getPointerId(0); downX = event.getX(); downY = lastY = event.getY();
            dragging = false; downCard = hitTest(downX, downY);
            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
            return true;
        }
        if (velocity != null) velocity.addMovement(event);
        if (action == MotionEvent.ACTION_POINTER_DOWN) { suppressClick = true; return true; }
        if (action == MotionEvent.ACTION_POINTER_UP) {
            int up = event.getActionIndex();
            if (event.getPointerId(up) == pointerId) {
                int replacement = up == 0 ? 1 : 0;
                if (replacement < event.getPointerCount()) { pointerId = event.getPointerId(replacement); lastY = downY = event.getY(replacement); downX = event.getX(replacement); }
            }
            suppressClick = true; return true;
        }
        int pointer = event.findPointerIndex(pointerId);
        if (pointer < 0) { finishTouch(); return true; }
        float x = event.getX(pointer), y = event.getY(pointer);
        if (action == MotionEvent.ACTION_MOVE) {
            if (!dragging && Math.abs(y - downY) > touchSlop) dragging = true;
            if (Math.abs(x - downX) > touchSlop) suppressClick = true;
            if (dragging) {
                offset = clamp(offset + lastY - y, 0, maxOffset);
                selectNearest(true); requestArtwork(); invalidate();
            }
            lastY = y; return true;
        }
        if (action == MotionEvent.ACTION_UP) {
            if (!dragging && Math.abs(y - downY) > touchSlop) dragging = true;
            if (dragging) {
                offset = clamp(offset + lastY - y, 0, maxOffset);
                selectNearest(true); requestArtwork(); invalidate();
            }
            if (dragging && velocity != null) {
                velocity.computeCurrentVelocity(1000, maxFling);
                float vy = -velocity.getYVelocity(pointerId);
                if (Math.abs(vy) >= minFling && maxOffset > 0) {
                    scroller.fling(0, Math.round(offset), 0, Math.round(vy), 0, 0, 0, Math.round(maxOffset));
                    postInvalidateOnAnimation();
                }
            } else if (!suppressClick) {
                int hit = hitTest(x, y);
                if (hit >= 0 && hit == downCard) { changeSelection(hit,true); performClick(); }
            }
            finishTouch(); return true;
        }
        if (action == MotionEvent.ACTION_CANCEL) { finishTouch(); return true; }
        return true;
    }

    private void finishTouch() {
        dragging = false; pointerId = -1; downCard = -1; recycleVelocity();
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
    }

    private void recycleVelocity() { if (velocity != null) { velocity.recycle(); velocity = null; } }

    /** Uses the same circle as drawRecord, before testing any sleeves behind the exposed vinyl. */
    private boolean hitsExposedRecord(DrawnCard card,float x,float y){
        float extraction=sleeveRecord();if(!isSleeveCard(card)||extraction<=0)return false;
        float[] q=card.quad;float size=q[2]-q[0],cx=(q[0]+q[2])*.5f+size*.50f*extraction;
        float cy=(Math.min(q[1],q[3])+Math.max(q[5],q[7]))*.5f,radius=size*.49f;
        float dx=x-cx,dy=y-cy;return dx*dx+dy*dy<=radius*radius;
    }

    private int hitTest(float x, float y) {
        if(upright&&sleeveIndex>=0)for(int i=0;i<drawnCount;i++)if(isSleeveCard(drawn[i])&&(hitsExposedRecord(drawn[i],x,y)||drawn[i].spine.contains(x,y)||inside(drawn[i].quad,x,y)))return sleeveIndex;
        if(expanded||upright)for(int i=0;i<drawnCount;i++)if(drawn[i].index==selectedIndex&&(drawn[i].spine.contains(x,y)||inside(drawn[i].quad,x,y)))return selectedIndex;
        for (int i = drawnCount - 1; i >= 0; i--) {
            DrawnCard card = drawn[i];
            if (card.spine.contains(x, y) || inside(card.quad, x, y)) return card.index;
        }
        // Slim horizon sleeves retain a 48dp touch target without changing the artwork.
        int nearest=-1;float distance=dp(24);
        for(int i=0;i<drawnCount;i++){
            DrawnCard card=drawn[i];float d=Math.abs(upright?x-card.spine.centerX():y-card.spine.centerY());
            if((upright?y>=card.spine.top&&y<=card.spine.bottom:x>=card.spine.left&&x<=card.spine.right)&&d<distance){distance=d;nearest=card.index;}
        }
        return nearest;
    }

    private static boolean inside(float[] quad, float x, float y) {
        boolean positive = false, negative = false;
        for (int i = 0; i < 4; i++) {
            int a = i * 2, b = ((i + 1) % 4) * 2;
            float cross = (quad[b] - quad[a]) * (y - quad[a + 1]) - (quad[b + 1] - quad[a + 1]) * (x - quad[a]);
            if (cross > .01f) positive = true; else if (cross < -.01f) negative = true;
            if (positive && negative) return false;
        }
        return positive || negative;
    }

    private void selectNearest(){selectNearest(false);}
    private void selectNearest(boolean fromUser) {
        if (albums.isEmpty()) return;
        int slot=Math.round((offset+horizon-firstY)/spacing);
        int next=isLooping()?albumIndex(slot):Math.max(0,Math.min(albums.size()-1,slot));
        changeSelection(next,fromUser);
    }

    private void changeSelection(int next,boolean fromUser){
        if(next==selectedIndex)return;selectedIndex=next;if(upright){if(fromUser)closeSleeve();else{boolean restoringOpen=pendingSleeveOpen;resetSleeve();pendingSleeveOpen=restoringOpen;notifySleeveState();}}updateDescription();
        if(selectionChanged!=null)selectionChanged.onSelected(next);
        long now=SystemClock.uptimeMillis();
        if(fromUser&&isHapticFeedbackEnabled()&&(lastSelectionHaptic<0||now-lastSelectionHaptic>=90)){
            lastSelectionHaptic=now;performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        }
    }

    private boolean moveSelection(int step) {
        if (albums.isEmpty()) return false;
        int next=isLooping()?Math.floorMod(selectedIndex+step,albums.size()):Math.max(0,Math.min(albums.size()-1,selectedIndex+step));
        if(next==selectedIndex)return false;
        float slot=isLooping()?nearestSlot(selectedIndex)+step:next;
        changeSelection(next,true);selectionScroll=true;
        int target=Math.round(boundedOffset(firstY+slot*spacing-horizon));
        if(!ValueAnimator.areAnimatorsEnabled()){scroller.abortAnimation();offset=target;selectionScroll=false;}
        else scroller.startScroll(0,Math.round(offset),0,target-Math.round(offset),280);
        requestArtwork(); postInvalidateOnAnimation(); return true;
    }

    @Override public boolean performClick() {
        super.performClick();
        if(!albums.isEmpty()){
            if(upright&&!isSleeveOpen()){openSelectedSleeve();return true;}
            captureSelectedBounds();int index=Math.min(selectedIndex,albums.size()-1);
            if(indexClick!=null)indexClick.onAlbumClick(index);
            else if(albumClick!=null)albumClick.onAlbumClick(new ArrayList<>(albums.get(index).songs));
        }
        return true;
    }

    private void captureSelectedBounds() {
        // The transition receives a copy in screen coordinates; no Canvas objects escape.
        DrawnCard selected = null;
        for (int i = 0; i < drawnCount; i++) if (drawn[i].index == selectedIndex) { selected = drawn[i]; break; }
        if (selected == null) { lastSelectedBounds.setEmpty(); lastSelectedTilt = 0; return; }
        float[] q = selected.quad;
        float minX = q[0], maxX = q[0], minY = q[1], maxY = q[1];
        for (int i = 2; i < q.length; i += 2) {
            minX = Math.min(minX, q[i]); maxX = Math.max(maxX, q[i]);
            minY = Math.min(minY, q[i + 1]); maxY = Math.max(maxY, q[i + 1]);
        }
        getLocationOnScreen(screenLocation);
        lastSelectedBounds.set(minX + screenLocation[0], minY + screenLocation[1], maxX + screenLocation[0], maxY + screenLocation[1]);
        if(upright){lastSelectedTilt=0;return;}
        float projection = clamp((maxY - minY) / Math.max(1, cardWidth), 0, 1);
        float degrees = (float)Math.toDegrees(Math.acos(projection));
        lastSelectedTilt = selected.spine.centerY() < horizon ? -degrees : degrees;
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if(upright&&keyCode==KeyEvent.KEYCODE_DPAD_RIGHT)return moveSelection(1);
        if(upright&&keyCode==KeyEvent.KEYCODE_DPAD_LEFT)return moveSelection(-1);
        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) return moveSelection(1);
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP) return moveSelection(-1);
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_SPACE) return performClick();
        return super.onKeyDown(keyCode, event);
    }

    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClassName("android.widget.ListView");
        info.setScrollable(albums.size() > 1);
        if (isLooping() || selectedIndex < albums.size() - 1) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
        if (isLooping() || selectedIndex > 0) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
    }

    @Override public boolean performAccessibilityAction(int action, Bundle arguments) {
        if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) return moveSelection(1);
        if (action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) return moveSelection(-1);
        return super.performAccessibilityAction(action, arguments);
    }

    private void updateDescription() {
        if (albums.isEmpty()) { setContentDescription("专辑架为空，导入音乐后可浏览专辑"); return; }
        Album album = albums.get(Math.min(selectedIndex, albums.size() - 1));
        setContentDescription("专辑架，共 " + albums.size() + " 张。当前第 " + (selectedIndex + 1) + " 张：" + album.title + "，" + album.artist + (upright?"。左右滑动选择，"+(isSleeveOpen()?"再次点按进入专辑曲目。":"点按展开封套与唱片。 "):"。上下滑动浏览，点按打开专辑。"));
    }

    private static String fitLabel(String value,Paint paint,float width){
        if(paint.measureText(value)<=width)return value;
        int count=paint.breakText(value,true,Math.max(0,width-paint.measureText("…")),null);
        if(count>0&&count<value.length()&&Character.isHighSurrogate(value.charAt(count-1)))count--;
        return count>0?value.substring(0,count)+"…":"";
    }

    private float dp(float value) { return value * getResources().getDisplayMetrics().density; }
    private float sp(float value) { return value * getResources().getDisplayMetrics().scaledDensity; }
    private static float clamp(float n, float low, float high) { return Math.max(low, Math.min(high, n)); }
    private static float luminance(int color) { return Color.red(color) * .299f + Color.green(color) * .587f + Color.blue(color) * .114f; }

    private static final class Album {
        final ArrayList<Track> songs;
        final String key, title, artist, artUri, artKey;
        final int fallback;
        String fittedLabel = "", fittedArtist = "";
        float labelWidth = -1;
        Album(Entry entry,ThemeStore themes){
            songs=new ArrayList<>(entry.tracks);key=entry.key;title=entry.title;artist=entry.artist;
            String override=themes.artwork(key),uri=override==null||override.isEmpty()?entry.artworkUri:override;
            artUri = uri; artKey = key + "\u001e" + uri;
            int[] palette = {0xffcfaca2, 0xff6e7485, 0xffd8c7ab, 0xff536967, 0xffa992a7};
            fallback = palette[(key.hashCode() & 0x7fffffff) % palette.length];
        }
    }

    private static final class DrawnCard {
        int index,slot;
        final float[] quad = new float[8];
        final RectF spine = new RectF();
        final Path surface = new Path();
    }

    private static final class Cover {
        final Bitmap bitmap;
        final int color;
        Cover(Bitmap bitmap) {
            this.bitmap = bitmap;
            long red = 0, green = 0, blue = 0;
            int y = Math.max(0, Math.min(bitmap.getHeight() - 1, bitmap.getHeight() / 30));
            for (int i = 0; i < 12; i++) {
                int x = Math.min(bitmap.getWidth() - 1, (i * 2 + 1) * bitmap.getWidth() / 24);
                int pixel = bitmap.getPixel(x, y); red += Color.red(pixel); green += Color.green(pixel); blue += Color.blue(pixel);
            }
            color = Color.rgb((int)(red / 12), (int)(green / 12), (int)(blue / 12));
        }
    }

    /** Weak ownership and a generation token reject late work after a page is closed. */
    private static final class ArtTask implements Runnable {
        private final WeakReference<AlbumStackView> owner;
        private final Context context;
        private final String key, uri;
        private final int token;
        ArtTask(AlbumStackView view, String key, String uri, int token) {
            owner = new WeakReference<>(view); context = view.appContext;
            this.key = key; this.uri = uri; this.token = token;
        }
        @Override public void run() {
            AlbumStackView target = owner.get();
            if (target == null || !target.attached || token != target.generation || Thread.currentThread().isInterrupted()) return;
            target = null;
            Bitmap bitmap = LocalMusicImporter.loadArtwork(context, uri, ART_SIZE);
            if (bitmap != null && Math.max(bitmap.getWidth(), bitmap.getHeight()) > ART_SIZE) {
                float scale = ART_SIZE / (float)Math.max(bitmap.getWidth(), bitmap.getHeight());
                Bitmap reduced = Bitmap.createScaledBitmap(bitmap, Math.max(1, Math.round(bitmap.getWidth() * scale)), Math.max(1, Math.round(bitmap.getHeight() * scale)), true);
                if (reduced != bitmap) bitmap.recycle();
                bitmap = reduced;
            }
            Cover result = bitmap == null ? null : new Cover(bitmap);
            MAIN.post(() -> {
                AlbumStackView view = owner.get();
                if (view == null || !view.attached || token != view.generation) return;
                view.pending.remove(key);
                if (result == null) view.unavailable.add(key); else view.covers.put(key, result);
                view.requestArtwork(); view.invalidate();
            });
        }
    }
}
