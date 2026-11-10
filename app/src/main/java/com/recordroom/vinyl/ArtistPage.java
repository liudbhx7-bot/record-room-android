package com.recordroom.vinyl;

import android.app.Activity;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.view.animation.DecelerateInterpolator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Three native artist pages: introduction, songs, and a browsable catalog of real albums. */
public final class ArtistPage {
    public interface Actions {
        void back();
        void play(ArrayList<Track> songs,int index,boolean shuffle);
        void album(ArrayList<Track> songs);
        void share(String title,String subtitle,ArrayList<Track> songs);
    }
    /** Optional application navigation; existing Actions callers retain useful chapter shortcuts. */
    public interface Navigation extends Actions {
        void library();
        void current();
        void queue();
        void toggle();
        default void playlist(String title,ArrayList<Track> songs){album(songs);}
    }
    private static final String[] TITLES={"","TOP SONGS","ALBUMS"};
    private static final String[] NAMES={"简介与推荐","热门歌曲","全部专辑"};
    private ArtistPage(){}
    private static final class ChangedCatalogException extends Exception {}
    public static FrameLayout create(Activity activity,List<Track> library,Track seed,Actions actions){return create(activity,library,seed,seed==null?null:seed.artist,actions);}
    public static FrameLayout create(Activity activity,List<Track> library,Track seed,String artistName,Actions actions){
        String name=artistName==null||artistName.trim().isEmpty()?(seed==null?"音乐人":seed.artist):artistName.trim();
        return new ArtistRoot(activity,library,seed,name,actions);
    }
    /** Opens a catalog artist by its real provider identity, even when no song was returned by search. */
    public static FrameLayout create(Activity activity,List<Track> library,String providerId,MusicProvider.Artist artist,Actions actions){
        String name=artist==null||artist.name.trim().isEmpty()?"音乐人":artist.name.trim();
        return new ArtistRoot(activity,library,null,name,actions,providerId,artist==null?"":artist.id);
    }
    public static Bundle saveState(FrameLayout page){return page instanceof ArtistRoot?((ArtistRoot)page).snapshot():new Bundle();}
    public static void restoreState(FrameLayout page,Bundle state){if(page instanceof ArtistRoot&&state!=null)((ArtistRoot)page).restore(state);}
    public static void selectChapter(FrameLayout page,int index){if(page instanceof ArtistRoot)((ArtistRoot)page).select(index);}
    public static void bindWindow(FrameLayout page,Window window){if(page instanceof ArtistRoot){((ArtistRoot)page).window=new WeakReference<>(window);((ArtistRoot)page).syncWindowAppearance();}}
    public static void refreshTheme(FrameLayout page){if(page instanceof ArtistRoot)((ArtistRoot)page).refreshTheme();}
    public static int chapter(FrameLayout page){return page instanceof ArtistRoot?((ArtistRoot)page).chapter:0;}
    public static void invalidateProvider(FrameLayout page,String providerId){if(page instanceof ArtistRoot)((ArtistRoot)page).invalidateProvider(providerId);}
    /** Providers validate legacy/library aliases against their official catalog before returning these IDs. */
    static boolean resolvedIdentity(String provider,String requested,String actual,boolean album){
        if(requested==null||actual==null||actual.isEmpty())return false;if(requested.equals(actual))return true;
        if("applemusic".equals(provider)&&!requested.isEmpty()&&!requested.matches("[0-9]+"))return actual.matches("[0-9]+");
        if(album&&"qq".equals(provider)){
            if(requested.matches("[0-9]+"))return actual.matches("[A-Za-z0-9]{14}");
            return requested.matches("[A-Za-z0-9]{14}_[0-9]+")&&requested.substring(0,14).equals(actual);
        }
        return false;
    }
    /** Renders the host's authoritative playback state; this page never owns a second player. */
    public static void updatePlayback(FrameLayout page,boolean playing,Bitmap artwork,boolean canToggle){
        if(page instanceof ArtistRoot)((ArtistRoot)page).updatePlayback(playing,artwork,canToggle);
    }

    private static final class ArtistRoot extends FrameLayout {
        private final Activity activity;
        private final Actions actions;
        private final Track seed;
        private final String requestedName,remoteId,providerId;
        private final ArrayList<Track> library=new ArrayList<>();
        private final CoverLoader covers;
        private final Handler main=new Handler(Looper.getMainLooper());
        private final LinearLayout header;
        private final TextView title;
        private final Button counter,previous,next;
        private final FrameLayout body;
        private final TextView status;
        private final NavIcon back,more;
        private final LinearLayout rail;
        private final PlayerControls.Icon railCover,railPlay;
        private final LinkedHashMap<String,MusicProvider.Album> catalogAlbums=new LinkedHashMap<>();
        private final HashSet<String> pagedAlbumIds=new HashSet<>(),invalidatedProviders=new HashSet<>();
        private int catalogTotal=-1;
        private String albumLoadingProvider="",albumLoadingKey="";
        private int albumRequestGeneration;
        private AlbumBrowser albumBrowser;
        private ScrollView introduction;
        private int introScroll,albumOffset;
        private float albumPosition;
        private String selectedAlbumKey="",catalogError="",albumError="";
        private boolean biographyExpanded,albumsExpanded,albumRestorePending,catalogLoading,catalogComplete,catalogFailed,catalogRestartRequired;
        private ArtistLibraryData.Collection failedAlbum;
        private boolean failedAlbumPlay;
        private final Runnable loggedOut=this::onLogout;
        private ArtistLibraryData data;
        private MusicProvider.Artist remote;
        private ExecutorService worker;
        private int generation,dataVersion,chapter,songPosition,songTop;
        private boolean attached,loading,failed,remoteDisabled,awaitingRestore,albumLoading;
        private ListView list;
        private WeakReference<Window> window=new WeakReference<>(null);
        private int ink=Color.WHITE,stage;
        private ValueAnimator chapterAnimator;
        private FrameLayout chapterOverlay;
        private String error="";

        ArtistRoot(Activity activity,List<Track> tracks,Track seed,String name,Actions actions){
            this(activity,tracks,seed,name,actions,null,null);
        }
        ArtistRoot(Activity activity,List<Track> tracks,Track seed,String name,Actions actions,String catalogProvider,String catalogArtistId){
            super(activity);this.activity=activity;this.actions=actions;this.seed=seed;this.requestedName=name;
            if(tracks!=null)for(Track t:tracks)if(t!=null)library.add(t);
            String selectedId="";
            String source=OnlineMusic.providerId(seed);
            if(seed!=null&&ArtistLibraryData.supportsRemote(source)){
                if(ArtistLibraryData.same(name,seed.albumArtist))selectedId=seed.albumArtistId;
                else if(ArtistLibraryData.same(name,seed.artist)&&!name.contains(" / "))selectedId=seed.artistId;
            }
            if(ArtistLibraryData.supportsRemote(catalogProvider)&&catalogArtistId!=null&&!catalogArtistId.isEmpty()){
                source=catalogProvider;selectedId=catalogArtistId;
            }
            remoteId=selectedId==null?"":selectedId;providerId=remoteId.isEmpty()?"":source;
            covers=new CoverLoader(activity.getApplicationContext());
            setId(0x00a47157);setSaveEnabled(true);setFocusable(true);setClipChildren(true);
            header=column(activity);
            LinearLayout chapters=new LinearLayout(activity);chapters.setGravity(Gravity.CENTER);
            previous=button(activity,"‹",Color.WHITE,Color.TRANSPARENT,()->select(chapter-1));previous.setTextSize(24);previous.setContentDescription("上一章节");
            next=button(activity,"›",Color.WHITE,Color.TRANSPARENT,()->select(chapter+1));next.setTextSize(24);next.setContentDescription("下一章节");
            counter=button(activity,"01 | 03",Color.WHITE,Color.TRANSPARENT,this::chapterMenu);counter.setTextSize(14);counter.setTypeface(Typeface.create("sans-serif-condensed",Typeface.NORMAL));
            chapters.addView(previous,new LinearLayout.LayoutParams(dp(activity,48),dp(activity,48)));chapters.addView(counter,new LinearLayout.LayoutParams(dp(activity,100),dp(activity,48)));chapters.addView(next,new LinearLayout.LayoutParams(dp(activity,48),dp(activity,48)));header.addView(chapters,new LinearLayout.LayoutParams(-1,dp(activity,48)));
            title=text(activity,name,46,Color.WHITE);title.setGravity(Gravity.CENTER);title.setTypeface(Typeface.create("sans-serif-condensed",Typeface.BOLD));title.setMaxLines(2);title.setEllipsize(TextUtils.TruncateAt.END);title.setIncludeFontPadding(false);title.setAutoSizeTextTypeUniformWithConfiguration(24,56,1,TypedValue.COMPLEX_UNIT_SP);header.addView(title,new LinearLayout.LayoutParams(-1,dp(activity,86)));addView(header);
            body=new FrameLayout(activity);body.setClipChildren(true);addView(body);
            status=text(activity,"",12,Color.WHITE);status.setGravity(Gravity.CENTER_VERTICAL);status.setMaxLines(2);status.setEllipsize(TextUtils.TruncateAt.END);status.setPadding(dp(activity,8),0,dp(activity,8),0);status.setOnClickListener(v->{if(failed){failed=false;error="";startRemote();}else if(catalogFailed){catalogFailed=false;if(catalogRestartRequired){albumOffset=0;pagedAlbumIds.clear();catalogTotal=-1;catalogRestartRequired=false;}loadNextAlbums();}else if(failedAlbum!=null){ArtistLibraryData.Collection retry=failedAlbum;boolean play=failedAlbumPlay;failedAlbum=null;open(retry,play);}});addView(status);
            back=new NavIcon(activity,"back","返回",actions::back);more=new NavIcon(activity,"more","更多音乐人操作",this::moreMenu);addView(back);addView(more);
            rail=column(activity);rail.setGravity(Gravity.CENTER_HORIZONTAL);rail.setPadding(dp(activity,3),dp(activity,4),dp(activity,3),dp(activity,4));
            NavIcon libraryButton=new NavIcon(activity,"library","音乐资料库",()->{if(actions instanceof Navigation)((Navigation)actions).library();else select(ArtistLibraryData.ALBUMS);});
            rail.addView(libraryButton,new LinearLayout.LayoutParams(dp(activity,48),dp(activity,48)));
            railCover=new PlayerControls.Icon(activity,"disc");railCover.setContentDescription("返回当前播放");railCover.setOnClickListener(v->{if(actions instanceof Navigation)((Navigation)actions).current();else actions.back();});rail.addView(railCover,new LinearLayout.LayoutParams(dp(activity,48),dp(activity,48)));
            railPlay=new PlayerControls.Icon(activity,"play");railPlay.setContentDescription("播放");railPlay.setOnClickListener(v->{if(actions instanceof Navigation)((Navigation)actions).toggle();else playSongs(false);});rail.addView(railPlay,new LinearLayout.LayoutParams(dp(activity,48),dp(activity,48)));
            NavIcon queueButton=new NavIcon(activity,"queue","播放队列",()->{if(actions instanceof Navigation)((Navigation)actions).queue();else select(ArtistLibraryData.SONGS);});rail.addView(queueButton,new LinearLayout.LayoutParams(dp(activity,48),dp(activity,48)));addView(rail);
            rebuild();render();
        }
        private void rebuild(){
            dataVersion++;
            MusicProvider.Artist complete=remote==null?null:new MusicProvider.Artist(remote.id,remote.name,remote.description,remote.avatarUrl,remote.topTracks,new ArrayList<>(catalogAlbums.values()));
            String display=remote!=null&&!remote.name.trim().isEmpty()?remote.name:requestedName;
            data=new ArtistLibraryData(display,providerId,remote==null?remoteId:remote.id,library,invalidatedProviders.contains(OnlineMusic.providerId(seed))?null:seed,complete);
        }
        private void select(int index){
            int target=ArtistLibraryData.clampChapter(index);if(target==chapter)return;
            cancelChapterTransition();rememberList();cancelAlbumOpen();int direction=target>chapter?1:-1;
            View outgoing=body.getChildCount()==0?null:body.getChildAt(0);chapter=target;render();
            animateChapter(outgoing,direction);announceForAccessibility(NAMES[chapter]+"，第"+(chapter+1)+"章，共3章");
        }
        private void cancelChapterTransition(){
            if(chapterAnimator!=null){ValueAnimator old=chapterAnimator;chapterAnimator=null;old.removeAllListeners();old.removeAllUpdateListeners();old.cancel();}
            if(chapterOverlay!=null){removeView(chapterOverlay);chapterOverlay=null;}
            body.setAlpha(1);body.setTranslationX(0);title.setAlpha(1);title.setTranslationX(0);
        }
        private void animateChapter(View outgoing,int direction){
            if(outgoing==null||!attached||!ValueAnimator.areAnimatorsEnabled()||body.getWidth()<=0)return;
            chapterOverlay=new FrameLayout(activity){@Override public boolean onInterceptTouchEvent(MotionEvent event){return true;}};
            chapterOverlay.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            chapterOverlay.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);chapterOverlay.setOnTouchListener((v,event)->true);
            chapterOverlay.addView(outgoing,new FrameLayout.LayoutParams(-1,-1));addView(chapterOverlay,indexOfChild(body)+1);
            place(chapterOverlay,body.getLeft(),body.getTop(),body.getWidth(),body.getHeight());
            body.setAlpha(0);body.setTranslationX(dp(activity,28)*direction);title.setAlpha(0);
            ValueAnimator animator=ValueAnimator.ofFloat(0,1);chapterAnimator=animator;animator.setDuration(280);animator.setInterpolator(new DecelerateInterpolator());
            animator.addUpdateListener(value->{if(chapterAnimator!=value)return;float t=(float)value.getAnimatedValue();body.setAlpha(t);body.setTranslationX(dp(activity,28)*direction*(1-t));title.setAlpha(t);if(chapterOverlay!=null){chapterOverlay.setAlpha(1-t);chapterOverlay.setTranslationX(-dp(activity,20)*direction*t);}});
            animator.addListener(new AnimatorListenerAdapter(){@Override public void onAnimationEnd(Animator value){if(chapterAnimator==value)cancelChapterTransition();}});animator.start();
        }
        private void refreshTheme(){if(stage==DesignSystem.currentStage(activity))return;rememberList();render();}
        private void rememberList(){
            if(list!=null&&list.getCount()>0&&!awaitingRestore){songPosition=list.getFirstVisiblePosition();View first=list.getChildAt(0);songTop=first==null?0:first.getTop()-list.getPaddingTop();}
            if(introduction!=null)introScroll=introduction.getScrollY();
            if(albumBrowser!=null)albumBrowser.rememberSelection();
        }
        private void render(){
            cancelChapterTransition();stage=DesignSystem.currentStage(activity);
            ink=Color.luminance(stage)>.18?0xff17191b:0xfff4f1eb;setBackground(DesignSystem.stage(activity,stage));
            title.setText(chapter==0?data.name:TITLES[chapter]);title.setTextColor(ink);title.setTextScaleX(chapter==0?1f:.82f);
            counter.setText(String.format(Locale.ROOT,"%02d | 03",chapter+1));counter.setTextColor(ink);counter.setContentDescription(NAMES[chapter]+"，第"+(chapter+1)+"章，共3章，选择章节");previous.setTextColor(ink);next.setTextColor(ink);previous.setEnabled(chapter>0);next.setEnabled(chapter<2);previous.setAlpha(chapter>0?1f:.25f);next.setAlpha(chapter<2?1f:.25f);
            int control=blend(stage,ink,.06f);
            back.colors(ink,control);more.colors(ink,control);
            rail.setBackground(round(control,dp(activity,28),withAlpha(ink,38)));for(int i=0;i<rail.getChildCount();i++){View v=rail.getChildAt(i);if(v instanceof NavIcon)((NavIcon)v).colors(ink,Color.TRANSPARENT);else if(v instanceof PlayerControls.Icon)((PlayerControls.Icon)v).setInk(ink);}
            body.removeAllViews();list=null;introduction=null;albumBrowser=null;
            if(chapter==ArtistLibraryData.COVER)renderCover();else if(chapter==ArtistLibraryData.SONGS)renderSongs();else renderCollections();
            syncWindowAppearance();updateStatus();requestLayout();
        }
        private void syncWindowAppearance(){
            Window target=window.get();if(target==null)return;
            boolean light=Color.luminance(stage)>.18;
            int mask=View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            View decor=target.getDecorView();decor.setSystemUiVisibility((decor.getSystemUiVisibility()&~mask)|(light?mask:0));
            if(android.os.Build.VERSION.SDK_INT>=30){android.view.WindowInsetsController controller=target.getInsetsController();if(controller!=null){int appearance=android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;controller.setSystemBarsAppearance(light?appearance:0,appearance);}}
            target.setStatusBarColor(Color.TRANSPARENT);target.setNavigationBarColor(stage);
            if(android.os.Build.VERSION.SDK_INT>=28)target.setNavigationBarDividerColor(stage);
            if(android.os.Build.VERSION.SDK_INT>=29){target.setStatusBarContrastEnforced(false);target.setNavigationBarContrastEnforced(false);}
        }
        private void renderCover(){
            introduction=new ScrollView(activity);introduction.setFillViewport(true);introduction.setVerticalScrollBarEnabled(false);
            LinearLayout page=column(activity);page.setPadding(dp(activity,10),0,dp(activity,10),dp(activity,16));
            HeroView hero=new HeroView(activity,covers,data);hero.setContentDescription(data.portrait.isEmpty()?data.name+"的真实专辑封面构图":data.name+"的音乐平台头像");
            page.addView(hero,new LinearLayout.LayoutParams(-1,dp(activity,190)));
            TextView heading=text(activity,"关于 "+data.name,17,ink);heading.setTypeface(null,Typeface.BOLD);heading.setPadding(0,dp(activity,12),0,dp(activity,8));page.addView(heading);
            TextView biography=text(activity,data.description.isEmpty()?"暂未提供艺人简介。先从下面的音乐开始认识这位艺人。":data.description,15,withAlpha(ink,205));
            biography.setLineSpacing(dp(activity,5),1f);biography.setMaxLines(biographyExpanded?Integer.MAX_VALUE:3);biography.setEllipsize(TextUtils.TruncateAt.END);page.addView(biography,new LinearLayout.LayoutParams(-1,-2));
            if(data.description.length()>100){Button expand=button(activity,biographyExpanded?"收起简介":"展开简介",ink,Color.TRANSPARENT,()->{rememberList();biographyExpanded=!biographyExpanded;render();});page.addView(expand,new LinearLayout.LayoutParams(-1,dp(activity,48)));}
            TextView recommendations=text(activity,"推荐歌曲",18,ink);recommendations.setTypeface(null,Typeface.BOLD);recommendations.setPadding(0,dp(activity,22),0,dp(activity,10));page.addView(recommendations);
            if(data.recommended.isEmpty()){TextView empty=text(activity,"还没有可播放的歌曲",14,withAlpha(ink,175));empty.setPadding(0,dp(activity,8),0,dp(activity,16));page.addView(empty);}
            else{
                LinearLayout songs=column(activity);songs.setPadding(dp(activity,10),0,dp(activity,10),0);songs.setBackground(round(blend(stage,ink,.055f),dp(activity,16),withAlpha(ink,30)));
                for(int i=0;i<data.recommended.size();i++){SongRow row=new SongRow();row.bindTo(data.recommended,i);songs.addView(row,new LinearLayout.LayoutParams(-1,-2));}
                page.addView(songs,new LinearLayout.LayoutParams(-1,-2));
                Button play=button(activity,"播放推荐",stage,ink,()->actions.play(new ArrayList<>(data.recommended),0,false));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(activity,48));lp.topMargin=dp(activity,14);page.addView(play,lp);
            }
            introduction.addView(page,new ScrollView.LayoutParams(-1,-2));body.addView(introduction,new FrameLayout.LayoutParams(-1,-1));
            ScrollView target=introduction;int saved=introScroll;target.post(()->{if(introduction==target)target.scrollTo(0,saved);});
        }
        private void renderSongs(){
            if(data.songs.isEmpty()){empty("还没有这位音乐人的歌曲",loading?"正在读取在线资料":"将该艺人的歌曲加入资料库后，就能在这里播放。");return;}
            list=new ListView(activity);list.setContentDescription("音乐人资料库");list.setDivider(null);list.setDividerHeight(0);list.setVerticalScrollBarEnabled(false);list.setSelector(android.R.color.transparent);list.setBackground(round(blend(stage,ink,.04f),dp(activity,18),withAlpha(ink,25)));list.setPadding(dp(activity,15),dp(activity,10),dp(activity,15),dp(activity,12));list.setClipToPadding(false);
            TextView source=text(activity,data.providerSongs?providerName()+"热门歌曲优先 · "+data.songs.size()+" 首":"资料库歌曲 · "+data.songs.size()+" 首（非热度排名）",12,withAlpha(ink,175));source.setPadding(0,dp(activity,10),0,dp(activity,16));list.addHeaderView(source,null,false);list.setAdapter(new SongsAdapter());body.addView(list,new FrameLayout.LayoutParams(-1,-1));
            ListView target=list;int position=songPosition,top=songTop;target.post(()->{if(list==target)target.setSelectionFromTop(position,top+target.getPaddingTop());});
        }
        private void renderCollections(){
            if(data.albums.isEmpty()){empty("暂无专辑",catalogLoading||loading?"正在读取这位艺人的专辑":"这位艺人的专辑资料尚未提供。");return;}
            albumBrowser=new AlbumBrowser();body.addView(albumBrowser,new FrameLayout.LayoutParams(-1,-1));
        }
        private void empty(String heading,String detail){LinearLayout panel=column(activity);panel.setGravity(Gravity.CENTER);panel.setPadding(dp(activity,20),dp(activity,12),dp(activity,20),dp(activity,12));TextView label=text(activity,heading,22,ink);label.setGravity(Gravity.CENTER);label.setTypeface(null,Typeface.BOLD);panel.addView(label,new LinearLayout.LayoutParams(-1,-2));TextView note=text(activity,detail,14,withAlpha(ink,170));note.setGravity(Gravity.CENTER);note.setPadding(0,dp(activity,14),0,dp(activity,20));panel.addView(note,new LinearLayout.LayoutParams(-1,-2));if(chapter!=2){Button albums=button(activity,"查看专辑",stage,ink,()->select(2));panel.addView(albums,new LinearLayout.LayoutParams(-1,dp(activity,48)));}body.addView(panel,new FrameLayout.LayoutParams(-1,-1));}
        private void playSongs(boolean shuffle){if(!data.songs.isEmpty())actions.play(new ArrayList<>(data.songs),0,shuffle);}
        private void chapterMenu(){PopupMenu menu=new PopupMenu(activity,counter);for(int i=0;i<3;i++)menu.getMenu().add(0,i,i,String.format(Locale.ROOT,"%02d  %s",i+1,NAMES[i]));menu.setOnMenuItemClickListener(item->{select(item.getItemId());return true;});menu.show();}
        private void moreMenu(){PopupMenu menu=new PopupMenu(activity,more);menu.getMenu().add(0,2,1,"随机播放").setEnabled(!data.songs.isEmpty());menu.getMenu().add(0,3,2,"选择章节");menu.setOnMenuItemClickListener(item->{if(item.getItemId()==2)playSongs(true);else chapterMenu();return true;});menu.show();}
        private String providerName(){return "qq".equals(providerId)?"QQ音乐":"applemusic".equals(providerId)?"Apple Music":"网易云音乐";}
        private void updateStatus(){
            String message=albumLoading?"正在读取完整专辑…":loading?"正在读取艺人资料…":failed?error+" · 点按重试":catalogFailed?"已载入 "+data.albums.size()+" 张，后续读取失败 · 点按重试":failedAlbum!=null?albumError+" · 点按重试":catalogLoading?"正在读取专辑 · 已载入 "+data.albums.size()+" 张":remoteDisabled?"已退出"+providerName()+"，保留其他来源资料":catalogComplete?"全部专辑已载入 · "+data.albums.size()+" 张":remote!=null?"音乐资料来自"+providerName():"来自你的音乐资料库";
            status.setText(message);status.setTextColor(withAlpha(ink,175));status.setClickable(failed||catalogFailed||failedAlbum!=null);status.setContentDescription(message);
        }
        private void updatePlayback(boolean playing,Bitmap artwork,boolean canToggle){
            railPlay.setSymbol(playing?"pause":"play");railPlay.setContentDescription(playing?"暂停":"播放");railPlay.setEnabled(canToggle);
            railCover.setArtwork(artwork);
        }
        @Override protected void onMeasure(int widthSpec,int heightSpec){
            int width=MeasureSpec.getSize(widthSpec)-getPaddingLeft()-getPaddingRight(),height=MeasureSpec.getSize(heightSpec)-getPaddingTop()-getPaddingBottom();
            boolean shortScreen=height<dp(activity,540),horizontalRail=height<dp(activity,400);
            int side=dp(activity,width<dp(activity,350)?68:78),headerHeight=dp(activity,shortScreen?112:145),left=dp(activity,12);
            place(header,left,dp(activity,shortScreen?0:8),Math.max(1,width-side-left),headerHeight);
            place(body,left,headerHeight+dp(activity,12),Math.max(1,width-side-left),Math.max(dp(activity,80),height-headerHeight-dp(activity,horizontalRail?84:64)));
            place(status,dp(activity,18),Math.max(0,height-dp(activity,48)),Math.max(1,width-(horizontalRail?dp(activity,236):side+dp(activity,18))),dp(activity,48));
            int right=width-dp(activity,62),buttonSize=dp(activity,48),navTop=shortScreen?dp(activity,12):dp(activity,150);
            place(back,right,navTop,buttonSize,buttonSize);
            rail.setOrientation(horizontalRail?LinearLayout.HORIZONTAL:LinearLayout.VERTICAL);
            int railW=dp(activity,horizontalRail?198:54),railH=dp(activity,horizontalRail?56:200);
            int railTop=horizontalRail?Math.max(0,height-railH-dp(activity,8)):Math.max(navTop+dp(activity,164),height-railH-dp(activity,14));
            if(shortScreen&&!horizontalRail)railTop=Math.max(dp(activity,167),height-railH-dp(activity,8));
            place(rail,horizontalRail?width-railW-dp(activity,12):right-dp(activity,3),railTop,railW,railH);
            place(more,right,horizontalRail?navTop+dp(activity,56):Math.max(navTop+dp(activity,56),railTop-dp(activity,57)),buttonSize,buttonSize);
            if(chapterOverlay!=null){FrameLayout.LayoutParams b=(FrameLayout.LayoutParams)body.getLayoutParams();place(chapterOverlay,b.leftMargin,b.topMargin,b.width,b.height);}header.getChildAt(1).getLayoutParams().height=dp(activity,shortScreen?62:86);super.onMeasure(widthSpec,heightSpec);
        }
        private void place(View view,int x,int y,int w,int h){FrameLayout.LayoutParams lp=view.getLayoutParams() instanceof FrameLayout.LayoutParams?(FrameLayout.LayoutParams)view.getLayoutParams():new FrameLayout.LayoutParams(w,h);lp.width=w;lp.height=h;lp.leftMargin=x;lp.topMargin=y;view.setLayoutParams(lp);}
        @Override public boolean dispatchKeyEvent(KeyEvent event){if(event.getAction()==KeyEvent.ACTION_DOWN){int key=event.getKeyCode();if(chapter==ArtistLibraryData.ALBUMS&&albumBrowser!=null&&albumBrowser.stack.hasFocus()&&(key==KeyEvent.KEYCODE_DPAD_LEFT||key==KeyEvent.KEYCODE_DPAD_RIGHT))return albumBrowser.stack.dispatchKeyEvent(event);if(key==KeyEvent.KEYCODE_PAGE_DOWN||key==KeyEvent.KEYCODE_DPAD_RIGHT){select(chapter+1);return true;}if(key==KeyEvent.KEYCODE_PAGE_UP||key==KeyEvent.KEYCODE_DPAD_LEFT){select(chapter-1);return true;}}return super.dispatchKeyEvent(event);}
        private Bundle snapshot(){rememberList();Bundle b=new Bundle();b.putInt("chapter",chapter);b.putInt("song_position",songPosition);b.putInt("song_top",songTop);b.putFloat("album_position",albumPosition);b.putString("album_key",selectedAlbumKey);b.putBoolean("albums_expanded",albumsExpanded);b.putBoolean("bio_expanded",biographyExpanded);b.putInt("intro_scroll",introScroll);return b;}
        private void restore(Bundle b){
            chapter=ArtistLibraryData.clampChapter(b.getInt("chapter",0));songPosition=Math.max(0,b.getInt("song_position",0));songTop=b.getInt("song_top",0);
            int[] legacy=b.getIntArray("cards");albumPosition=b.containsKey("album_position")?Math.max(0,b.getFloat("album_position",0)):legacy!=null&&legacy.length>2?Math.max(0,legacy[2]):0;
            selectedAlbumKey=b.getString("album_key","");albumsExpanded=b.getBoolean("albums_expanded",false);albumRestorePending=!remoteId.isEmpty()&&!remoteDisabled&&!selectedAlbumKey.isEmpty();biographyExpanded=b.getBoolean("bio_expanded",false);introScroll=Math.max(0,b.getInt("intro_scroll",0));
            awaitingRestore=!remoteId.isEmpty()&&remote==null&&!remoteDisabled;render();
        }
        @Override protected Parcelable onSaveInstanceState(){SavedState state=new SavedState(super.onSaveInstanceState());state.page=snapshot();return state;}
        @Override protected void onRestoreInstanceState(Parcelable state){if(!(state instanceof SavedState)){super.onRestoreInstanceState(state);return;}SavedState saved=(SavedState)state;super.onRestoreInstanceState(saved.getSuperState());restore(saved.page);}
        @Override protected void onAttachedToWindow(){super.onAttachedToWindow();attached=true;covers.start();if(worker==null||worker.isShutdown())worker=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"vinyl-artist-data");t.setDaemon(true);return t;});OnlineMusic.addLogoutListener(loggedOut);startRemote();invalidate();}
        @Override protected void onWindowVisibilityChanged(int visibility){super.onWindowVisibilityChanged(visibility);if(visibility!=View.VISIBLE&&body!=null)cancelChapterTransition();}
        @Override protected void onDetachedFromWindow(){cancelChapterTransition();attached=false;generation++;loading=false;albumLoading=false;catalogLoading=false;if(worker!=null)worker.shutdownNow();worker=null;OnlineMusic.removeLogoutListener(loggedOut);covers.stop();super.onDetachedFromWindow();}
        private void startRemote(){
            if(!attached||remoteDisabled||remoteId.isEmpty()||loading||failed||worker==null)return;
            if(remote!=null){if(!catalogComplete&&!catalogLoading&&!catalogFailed)loadNextAlbums();return;}
            loading=true;updateStatus();int token=++generation;long epoch=OnlineMusic.sessionGeneration(activity,providerId);MusicProvider provider=OnlineMusic.provider(activity,providerId);
            worker.execute(()->{try{
                MusicProvider.Artist found=provider.artist(remoteId);
                if(found==null||!resolvedIdentity(providerId,remoteId,found.id,false))throw new ProviderException(ProviderException.Kind.UNAVAILABLE,"音乐人资料与请求不匹配");
                main.post(()->{if(!valid(token,epoch))return;rememberList();remote=found;catalogAlbums.clear();pagedAlbumIds.clear();catalogTotal=-1;for(MusicProvider.Album album:found.albums)if(album!=null&&!album.id.isEmpty())catalogAlbums.put(album.id,album);albumOffset=0;catalogComplete=false;catalogFailed=false;loading=false;awaitingRestore=false;error="";rebuild();render();loadNextAlbums();});
            }catch(Exception failure){main.post(()->{if(!valid(token,epoch))return;loading=false;failed=true;awaitingRestore=false;error=failure instanceof ProviderException?failure.getMessage():"音乐人资料暂时不可用";updateStatus();});}});
        }
        private void loadNextAlbums(){
            if(!attached||remoteDisabled||remote==null||catalogLoading||catalogComplete||worker==null)return;
            catalogLoading=true;catalogFailed=false;updateStatus();int token=generation,offset=albumOffset,expectedTotal=catalogTotal;HashSet<String> already=new HashSet<>(pagedAlbumIds);String artistId=remote.id;long epoch=OnlineMusic.sessionGeneration(activity,providerId);MusicProvider provider=OnlineMusic.provider(activity,providerId);
            worker.execute(()->{try{
                MusicProvider.Page<MusicProvider.Album> page=provider.artistAlbums(artistId,offset,50);
                if(page==null||page.hasMore&&(page.nextOffset<=offset||page.items.isEmpty()))throw new ProviderException(ProviderException.Kind.UNAVAILABLE,"专辑目录没有返回下一页");
                if(expectedTotal>=0&&page.total>=0&&expectedTotal!=page.total)throw new ChangedCatalogException();
                int total=page.total>=0?page.total:expectedTotal;HashSet<String> received=new HashSet<>(already);
                for(MusicProvider.Album album:page.items)if(album==null||album.id==null||album.id.isEmpty()||!received.add(album.id))throw new ProviderException(ProviderException.Kind.UNAVAILABLE,"专辑目录返回了重复或无效条目");
                if(total>=0&&(received.size()>total||!page.hasMore&&received.size()!=total))throw new ProviderException(ProviderException.Kind.UNAVAILABLE,"专辑目录尚未完整返回");
                main.post(()->{if(!valid(token,epoch))return;rememberList();for(MusicProvider.Album album:page.items)if(album!=null&&!album.id.isEmpty())catalogAlbums.put(album.id,album);if(!page.hasMore)catalogAlbums.keySet().retainAll(received);pagedAlbumIds.clear();pagedAlbumIds.addAll(received);catalogTotal=total;albumOffset=page.nextOffset;catalogLoading=false;catalogComplete=!page.hasMore;catalogError="";rebuild();render();if(page.hasMore)loadNextAlbums();});
            }catch(Exception failure){main.post(()->{if(!valid(token,epoch))return;catalogLoading=false;catalogFailed=true;catalogRestartRequired=failure instanceof ChangedCatalogException;catalogError=catalogRestartRequired?"专辑目录已变化":failure instanceof ProviderException?failure.getMessage():"专辑目录暂时不可用";updateStatus();});}});
        }
        private boolean valid(int token,long epoch){return !remoteDisabled&&validRequest(token,epoch,providerId);}
        private boolean validRequest(int token,long epoch,String source){
            if(!attached||generation!=token)return false;
            if(OnlineMusic.sessionGeneration(activity,source)!=epoch){invalidateProvider(source);return false;}
            return !invalidatedProviders.contains(source);
        }
        private void onLogout(){invalidateProvider("netease");}
        private void invalidateProvider(String source){
            if(source==null||source.isEmpty())return;rememberList();invalidatedProviders.add(source);library.removeIf(track->source.equals(OnlineMusic.providerId(track)));
            boolean own=source.equals(providerId),cancelAlbum=source.equals(albumLoadingProvider)||failedAlbum!=null&&source.equals(failedAlbum.provider);
            if(own||cancelAlbum){
                generation++;loading=false;catalogLoading=false;albumLoading=false;albumLoadingProvider="";failedAlbum=null;
                if(worker!=null){worker.shutdownNow();worker=null;}
                if(attached)worker=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"vinyl-artist-data");t.setDaemon(true);return t;});
            }
            if(own){remoteDisabled=true;remote=null;catalogAlbums.clear();pagedAlbumIds.clear();catalogTotal=-1;albumOffset=0;failed=false;catalogComplete=false;catalogFailed=false;catalogRestartRequired=false;awaitingRestore=false;albumRestorePending=false;albumsExpanded=false;}
            rebuild();render();if(!own&&cancelAlbum)startRemote();
        }
        private void cancelAlbumOpen(){
            albumRequestGeneration++;albumLoading=false;albumLoadingProvider="";albumLoadingKey="";failedAlbum=null;updateStatus();
        }
        private boolean validAlbumRequest(int token,long epoch,String source,int request){return request==albumRequestGeneration&&validRequest(token,epoch,source);}
        private void open(ArtistLibraryData.Collection item,boolean play){
            if(invalidatedProviders.contains(item.provider))return;
            if(item.needsAlbum()){
                if(albumLoading||(remoteDisabled&&item.provider.equals(providerId))||!attached||worker==null)return;albumLoading=true;albumLoadingProvider=item.provider;albumLoadingKey=item.key;failedAlbum=null;updateStatus();int request=++albumRequestGeneration,token=generation;long epoch=OnlineMusic.sessionGeneration(activity,item.provider);MusicProvider provider=OnlineMusic.provider(activity,item.provider);
                worker.execute(()->{try{
                    MusicProvider.Album album=provider.album(item.remoteId);
                    if(album==null||!resolvedIdentity(item.provider,item.remoteId,album.id,true)||album.tracks.isEmpty())throw new ProviderException(ProviderException.Kind.UNAVAILABLE,"该专辑暂时没有可读取的曲目");
                    ArrayList<Track> tracks=new ArrayList<>(album.tracks);
                    main.post(()->{if(!validAlbumRequest(token,epoch,item.provider,request))return;albumLoading=false;albumLoadingProvider="";updateStatus();if(play)actions.play(tracks,0,false);else actions.album(tracks);});
                }catch(Exception failure){main.post(()->{if(!validAlbumRequest(token,epoch,item.provider,request))return;albumLoading=false;albumLoadingProvider="";failedAlbum=item;failedAlbumPlay=play;albumError=failure instanceof ProviderException?failure.getMessage():"专辑曲目暂时不可用";updateStatus();});}});return;
            }
            if(item.tracks.isEmpty())return;ArrayList<Track> tracks=new ArrayList<>(item.tracks);if(play)actions.play(tracks,0,false);else actions.album(tracks);
        }
        private final class SongsAdapter extends BaseAdapter {
            @Override public int getCount(){return data.songs.size();}@Override public Object getItem(int index){return data.songs.get(index);}@Override public long getItemId(int index){return index;}
            @Override public View getView(int index,View old,ViewGroup parent){SongRow row=old instanceof SongRow?(SongRow)old:new SongRow();row.bind(index);return row;}
        }
        private final class SongRow extends LinearLayout {
            final TextView number,label,detail,length;
            SongRow(){super(activity);setOrientation(HORIZONTAL);setGravity(Gravity.CENTER_VERTICAL);setMinimumHeight(dp(activity,66));setPadding(dp(activity,5),dp(activity,9),dp(activity,5),dp(activity,9));setBackground(ripple(Color.TRANSPARENT,dp(activity,4)));setClickable(true);setFocusable(true);number=text(activity,"",13,withAlpha(ink,160));number.setTypeface(Typeface.create("sans-serif-condensed",Typeface.NORMAL));addView(number,new LinearLayout.LayoutParams(dp(activity,29),-2));LinearLayout lines=column(activity);label=text(activity,"",15,ink);label.setTypeface(null,Typeface.BOLD);label.setMaxLines(2);label.setEllipsize(TextUtils.TruncateAt.END);detail=text(activity,"",12,withAlpha(ink,170));detail.setSingleLine(true);detail.setEllipsize(TextUtils.TruncateAt.END);detail.setPadding(0,dp(activity,4),0,0);lines.addView(label);lines.addView(detail);addView(lines,new LinearLayout.LayoutParams(0,-2,1));length=text(activity,"",12,withAlpha(ink,175));length.setGravity(Gravity.END);addView(length,new LinearLayout.LayoutParams(dp(activity,43),-2));}
            void bind(int index){bindTo(data.songs,index);}
            void bindTo(List<Track> tracks,int index){Track track=tracks.get(index);int boundVersion=dataVersion;number.setText(String.format(Locale.ROOT,"%02d",index+1));label.setText(track.title);detail.setText(track.album);length.setText(duration(track.durationMs));setContentDescription("播放 "+track.title);setOnClickListener(v->{if(boundVersion==dataVersion&&index<tracks.size()&&tracks.get(index)==track)actions.play(new ArrayList<>(tracks),index,false);});}
        }
        private final class AlbumBrowser extends FrameLayout {
            final int boundVersion=dataVersion;final AlbumStackView stack;final LinearLayout info;final TextView name,meta;final Button previousAlbum,nextAlbum,openAlbum,playAlbum,expand;
            AlbumBrowser(){super(activity);stack=new AlbumStackView(activity);stack.setInkColor(ink);stack.setUpright(true);stack.setLooping(true);
                ArrayList<AlbumStackView.Entry> entries=new ArrayList<>();for(ArtistLibraryData.Collection item:data.albums)entries.add(item.entry());stack.setEntries(entries);
                int selected=-1;for(int i=0;i<data.albums.size();i++)if(data.albums.get(i).key.equals(selectedAlbumKey)){selected=i;break;}
                if(selected>=0||catalogComplete||remoteDisabled)albumRestorePending=false;
                float restore=selected<0?albumPosition:selected+(albumPosition-Math.round(albumPosition));stack.setScrollPosition(Math.max(0,restore));stack.setExpanded(albumsExpanded&&!albumRestorePending);
                stack.setOnTouchListener((v,event)->{if(event.getActionMasked()==android.view.MotionEvent.ACTION_DOWN)albumRestorePending=false;return false;});
                stack.setOnAlbumIndexClickListener(index->{if(boundVersion==dataVersion&&index<data.albums.size())open(data.albums.get(index),false);});addView(stack);
                info=column(activity);info.setGravity(Gravity.CENTER_HORIZONTAL);name=text(activity,"",22,ink);name.setTypeface(null,Typeface.BOLD);name.setGravity(Gravity.CENTER);name.setMaxLines(2);name.setEllipsize(TextUtils.TruncateAt.END);info.addView(name,new LinearLayout.LayoutParams(-1,-2));
                meta=text(activity,"",12,withAlpha(ink,170));meta.setGravity(Gravity.CENTER);meta.setPadding(0,dp(activity,6),0,dp(activity,10));info.addView(meta,new LinearLayout.LayoutParams(-1,-2));
                LinearLayout buttons=new LinearLayout(activity);buttons.setGravity(Gravity.CENTER);previousAlbum=button(activity,"‹",ink,Color.TRANSPARENT,()->{albumRestorePending=false;stack.selectAlbum(-1);});previousAlbum.setTextSize(24);previousAlbum.setContentDescription("上一张专辑");nextAlbum=button(activity,"›",ink,Color.TRANSPARENT,()->{albumRestorePending=false;stack.selectAlbum(1);});nextAlbum.setTextSize(24);nextAlbum.setContentDescription("下一张专辑");openAlbum=button(activity,"打开专辑",stage,ink,()->{
                    if(boundVersion!=dataVersion||data.albums.isEmpty())return;albumRestorePending=false;
                    if(stack.isSleeveOpeningOrOpen())open(data.albums.get(stack.getSelectedIndex()),false);else stack.openSelectedSleeve();
                });
                playAlbum=button(activity,"播放专辑",ink,withAlpha(ink,22),()->{
                    if(boundVersion==dataVersion&&stack.isSleeveOpeningOrOpen()&&!data.albums.isEmpty())open(data.albums.get(stack.getSelectedIndex()),true);
                });playAlbum.setContentDescription("播放所选专辑");
                for(Button action:new Button[]{openAlbum,playAlbum}){action.setPadding(dp(activity,2),0,dp(activity,2),0);action.setMaxLines(1);action.setAutoSizeTextTypeUniformWithConfiguration(12,14,1,TypedValue.COMPLEX_UNIT_SP);}
                openAlbum.setContentDescription("打开专辑");buttons.addView(previousAlbum,new LinearLayout.LayoutParams(dp(activity,48),dp(activity,48)));buttons.addView(openAlbum,new LinearLayout.LayoutParams(0,dp(activity,48),1));LinearLayout.LayoutParams playParams=new LinearLayout.LayoutParams(0,dp(activity,48),1);playParams.leftMargin=dp(activity,6);buttons.addView(playAlbum,playParams);buttons.addView(nextAlbum,new LinearLayout.LayoutParams(dp(activity,48),dp(activity,48)));info.addView(buttons,new LinearLayout.LayoutParams(-1,dp(activity,48)));addView(info);
                expand=button(activity,"收起",ink,withAlpha(ink,18),()->{albumRestorePending=false;stack.closeSleeve();});expand.setContentDescription("收起唱片");addView(expand);
                stack.setOnSleeveStateChangedListener(()->{if(!stack.isSleeveOpeningOrOpen()&&albumLoading)cancelAlbumOpen();if(!albumRestorePending)albumsExpanded=stack.isSleeveOpeningOrOpen();bind();});
                stack.setOnSelectionChangedListener(index->{if(index<data.albums.size()&&((albumLoading&&!data.albums.get(index).key.equals(albumLoadingKey))||failedAlbum!=null))cancelAlbumOpen();bind();});bind();
            }
            void rememberSelection(){if(boundVersion!=dataVersion||stack.getHeight()<=0||albumRestorePending)return;albumsExpanded=stack.isSleeveOpeningOrOpen();albumPosition=stack.getScrollPosition();if(!data.albums.isEmpty())selectedAlbumKey=data.albums.get(Math.min(stack.getSelectedIndex(),data.albums.size()-1)).key;}
            void bind(){if(data.albums.isEmpty())return;int selected=Math.min(stack.getSelectedIndex(),data.albums.size()-1);ArtistLibraryData.Collection item=data.albums.get(selected);name.setText(item.name);name.setContentDescription(item.name);meta.setText((selected+1)+" / "+data.albums.size()+"  ·  "+(item.year.isEmpty()?"左右滑动选片":item.year+" · 左右滑动选片"));previousAlbum.setEnabled(data.albums.size()>1);nextAlbum.setEnabled(data.albums.size()>1);previousAlbum.setAlpha(data.albums.size()>1?1:.25f);nextAlbum.setAlpha(data.albums.size()>1?1:.25f);boolean open=stack.isSleeveOpeningOrOpen();boolean available=item.needsAlbum()||!item.tracks.isEmpty();openAlbum.setText(open?"查看曲目":"打开专辑");openAlbum.setContentDescription(open?"查看曲目":"打开专辑");openAlbum.setEnabled(available);playAlbum.setVisibility(open?View.VISIBLE:View.GONE);playAlbum.setEnabled(available);playAlbum.setContentDescription("播放专辑："+item.name);expand.setVisibility(open?View.VISIBLE:View.INVISIBLE);rememberSelection();}
            @Override protected void onMeasure(int widthSpec,int heightSpec){
                int width=MeasureSpec.getSize(widthSpec),height=MeasureSpec.getSize(heightSpec);boolean compact=height<dp(activity,260);
                meta.setVisibility(compact&&height<dp(activity,112)?View.GONE:View.VISIBLE);info.setGravity(Gravity.CENTER);
                int infoWidth=compact?Math.max(dp(activity,252),Math.round(width*.44f)):width;
                int stackWidth=compact?Math.max(1,width-infoWidth-dp(activity,8)):width;
                name.setTextSize(compact?17:22);name.setMaxLines(compact?1:2);
                // Two title lines and larger text need their measured height, not a fixed 116dp crop.
                info.measure(MeasureSpec.makeMeasureSpec(infoWidth,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(0,MeasureSpec.UNSPECIFIED));
                int infoHeight=compact?height:Math.min(height,Math.max(dp(activity,116),info.getMeasuredHeight()));
                int stackHeight=compact?height:Math.max(1,height-infoHeight-dp(activity,6));
                place(stack,0,0,stackWidth,stackHeight);
                place(info,compact?stackWidth+dp(activity,8):0,compact?0:height-infoHeight,infoWidth,infoHeight);
                place(expand,Math.max(0,stackWidth-dp(activity,64)),0,dp(activity,64),dp(activity,48));
                super.onMeasure(widthSpec,heightSpec);
            }
        }

    }

    private static final class SavedState extends View.BaseSavedState {
        Bundle page;SavedState(Parcelable parent){super(parent);}SavedState(Parcel source){super(source);page=source.readBundle(ArtistPage.class.getClassLoader());}
        @Override public void writeToParcel(Parcel out,int flags){super.writeToParcel(out,flags);out.writeBundle(page);}
        public static final Parcelable.Creator<SavedState> CREATOR=new Parcelable.Creator<SavedState>(){public SavedState createFromParcel(Parcel source){return new SavedState(source);}public SavedState[] newArray(int size){return new SavedState[size];}};
    }
    private static final class HeroView extends View {
        final CoverLoader loader;final ArtistLibraryData data;final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
        HeroView(Context context,CoverLoader loader,ArtistLibraryData data){super(context);this.loader=loader;this.data=data;setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);}
        @Override protected void onDraw(Canvas canvas){float w=getWidth(),h=getHeight();if(!data.portrait.isEmpty()){float s=Math.min(w*.94f,h*.94f);canvas.save();canvas.rotate(-3,w/2,h/2);RectF bounds=new RectF((w-s)/2,(h-s)/2,(w+s)/2,(h+s)/2);paint.setColor(0xffd7d0c3);canvas.drawRect(bounds.left-4,bounds.top-4,bounds.right+4,bounds.bottom+7,paint);drawCover(canvas,loader.get(data.portrait,768,this),bounds,paint);canvas.restore();return;}if(!data.albums.isEmpty()){float s=Math.min(w*.74f,h*.85f);for(int i=Math.min(2,data.albums.size()-1);i>=0;i--){canvas.save();canvas.rotate((i-1)*6,w*.49f,h*.5f);float x=(w-s)/2+i*dp(getContext(),8);RectF rect=new RectF(x,(h-s)/2,x+s,(h+s)/2);paint.setColor(0xffd7d0c3);canvas.drawRect(rect.left-3,rect.top-3,rect.right+3,rect.bottom+5,paint);drawCover(canvas,loader.get(data.albums.get(i).artwork,640,this),rect,paint);canvas.restore();}return;}paint.setColor(0xff423a30);canvas.drawCircle(w/2,h/2,Math.min(w,h)*.34f,paint);paint.setColor(0xff9b8b71);canvas.drawCircle(w/2,h/2,Math.min(w,h)*.09f,paint);paint.setColor(0xff22201d);canvas.drawCircle(w/2,h/2,dp(getContext(),3),paint);}
    }
    private static final class CoverView extends View {
        final CoverLoader loader;final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);String uri="";
        CoverView(Context context,CoverLoader loader){super(context);this.loader=loader;setElevation(dp(context,5));}
        void bind(String uri){this.uri=uri==null?"":uri;invalidate();}
        @Override protected void onDraw(Canvas canvas){RectF bounds=new RectF(0,0,getWidth(),getHeight());Path clip=new Path();clip.addRoundRect(bounds,dp(getContext(),3),dp(getContext(),3),Path.Direction.CW);canvas.save();canvas.clipPath(clip);drawCover(canvas,loader.get(uri,Math.max(128,Math.min(768,Math.max(getWidth(),getHeight()))),this),bounds,paint);canvas.restore();}
    }
    private static final class NavIcon extends View {
        final String icon;final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);int color=Color.WHITE;
        NavIcon(Context context,String icon,String description,Runnable action){super(context);this.icon=icon;setContentDescription(description);setClickable(true);setFocusable(true);setMinimumWidth(dp(context,48));setMinimumHeight(dp(context,48));setOnClickListener(v->action.run());}
        void colors(int ink,int fill){color=ink;setBackground(ripple(fill,dp(getContext(),24)));invalidate();}
        @Override protected void onDraw(Canvas canvas){float scale=Math.min(getWidth(),getHeight())/48f;canvas.save();canvas.translate((getWidth()-48*scale)/2,(getHeight()-48*scale)/2);canvas.scale(scale,scale);paint.setColor(color);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(2.2f);paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);Path p=new Path();switch(icon){case "back":p.moveTo(28,14);p.lineTo(18,24);p.lineTo(28,34);canvas.drawPath(p,paint);break;case "share":p.moveTo(21,14);p.lineTo(34,14);p.lineTo(34,27);canvas.drawPath(p,paint);canvas.drawLine(34,14,22,26,paint);canvas.drawArc(new RectF(14,14,35,35),45,270,false,paint);break;case "more":paint.setStyle(Paint.Style.FILL);for(int i=0;i<3;i++)canvas.drawCircle(16+i*8,24,2,paint);break;case "play":paint.setStyle(Paint.Style.FILL);p.moveTo(18,14);p.lineTo(34,24);p.lineTo(18,34);p.close();canvas.drawPath(p,paint);break;case "library":canvas.drawLine(14,13,14,35,paint);canvas.drawLine(20,13,20,35,paint);p.moveTo(26,13);p.lineTo(35,17);p.lineTo(35,31);p.lineTo(26,35);p.close();canvas.drawPath(p,paint);break;default:canvas.drawLine(12,14,34,14,paint);canvas.drawLine(12,21,27,21,paint);canvas.drawLine(12,28,21,28,paint);canvas.drawLine(12,35,21,35,paint);canvas.drawLine(31,23,31,34,paint);canvas.drawLine(31,23,36,26,paint);canvas.drawCircle(28,34,3,paint);}canvas.restore();}
    }
    /** Application context and weak subscribers; bounded cache and cancelled generations avoid Activity retention. */
    private static final class CoverLoader {
        final Context context;final Handler main=new Handler(Looper.getMainLooper());
        final LruCache<String,Bitmap> cache=new LruCache<String,Bitmap>(12*1024*1024){@Override protected int sizeOf(String key,Bitmap value){return value.getAllocationByteCount();}};
        final HashMap<String,ArrayList<WeakReference<View>>> waiters=new HashMap<>();final HashSet<String> missing=new HashSet<>();ExecutorService worker;int generation;
        CoverLoader(Context context){this.context=context;}
        void start(){if(worker==null||worker.isShutdown()){generation++;worker=Executors.newFixedThreadPool(2,r->{Thread t=new Thread(r,"vinyl-artist-covers");t.setDaemon(true);return t;});}}
        void stop(){generation++;if(worker!=null)worker.shutdownNow();worker=null;waiters.clear();missing.clear();cache.evictAll();}
        Bitmap get(String uri,int size,View view){if(uri==null||uri.isEmpty())return null;String key=size+":"+uri;Bitmap bitmap=cache.get(key);if(bitmap!=null)return bitmap;if(worker==null||worker.isShutdown()||missing.contains(key))return null;ArrayList<WeakReference<View>> pending=waiters.get(key);if(pending!=null){for(WeakReference<View> ref:pending)if(ref.get()==view)return null;pending.add(new WeakReference<>(view));return null;}pending=new ArrayList<>();pending.add(new WeakReference<>(view));waiters.put(key,pending);int token=generation;worker.execute(()->{Bitmap image=LocalMusicImporter.loadArtwork(context,uri,size);main.post(()->{if(token!=generation)return;ArrayList<WeakReference<View>> targets=waiters.remove(key);if(image==null)missing.add(key);else cache.put(key,image);if(targets!=null)for(WeakReference<View> ref:targets){View target=ref.get();if(target!=null)target.invalidate();}});});return null;}
    }
    private static void drawCover(Canvas canvas,Bitmap bitmap,RectF bounds,Paint paint){paint.setStyle(Paint.Style.FILL);paint.setAlpha(255);if(bitmap==null){paint.setColor(0xff7d8179);canvas.drawRect(bounds,paint);paint.setColor(0xffd2c9b9);canvas.drawCircle(bounds.centerX(),bounds.centerY(),bounds.width()*.27f,paint);paint.setColor(0xff706b60);canvas.drawCircle(bounds.centerX(),bounds.centerY(),bounds.width()*.075f,paint);return;}int edge=Math.min(bitmap.getWidth(),bitmap.getHeight()),x=(bitmap.getWidth()-edge)/2,y=(bitmap.getHeight()-edge)/2;canvas.drawBitmap(bitmap,new Rect(x,y,x+edge,y+edge),bounds,paint);}
    private static String coverUri(Context context,Track track){String custom=new ThemeStore(context).artwork(track.albumKey());return custom.isEmpty()?track.artworkUri:custom;}
    private static int blend(int a,int b,float mix){return Color.rgb(Math.round(Color.red(a)*(1-mix)+Color.red(b)*mix),Math.round(Color.green(a)*(1-mix)+Color.green(b)*mix),Math.round(Color.blue(a)*(1-mix)+Color.blue(b)*mix));}
    private static int withAlpha(int color,int alpha){return (color&0x00ffffff)|(alpha<<24);}
    private static String duration(long ms){return ms<=0?"—:—":String.format(Locale.ROOT,"%d:%02d",ms/60000,ms/1000%60);}
    private static LinearLayout column(Context context){LinearLayout result=new LinearLayout(context);result.setOrientation(LinearLayout.VERTICAL);return result;}
    private static TextView text(Context context,String value,int size,int color){TextView result=new TextView(context);result.setText(value);result.setTextSize(size);result.setTextColor(color);return result;}
    private static Button button(Context context,String value,int ink,int fill,Runnable action){Button button=new Button(context);button.setText(value);button.setTextSize(14);button.setAllCaps(false);button.setTextColor(ink);button.setMinWidth(0);button.setMinimumWidth(0);button.setMinHeight(0);button.setMinimumHeight(0);button.setPadding(dp(context,8),0,dp(context,8),0);button.setBackground(ripple(fill,dp(context,24)));button.setOnClickListener(v->action.run());return button;}
    private static GradientDrawable round(int color,int radius,int stroke){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(radius);if(stroke!=0)d.setStroke(1,stroke);return d;}
    private static RippleDrawable ripple(int color,int radius){return new RippleDrawable(ColorStateList.valueOf(0x28000000),round(color,radius,0),round(Color.WHITE,radius,0));}
    private static int dp(Context context,float value){return Math.round(value*context.getResources().getDisplayMetrics().density);}
}
