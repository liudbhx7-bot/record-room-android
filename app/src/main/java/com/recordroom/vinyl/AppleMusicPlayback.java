package com.recordroom.vinyl;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import com.apple.android.music.playback.controller.MediaPlayerController;
import com.apple.android.music.playback.controller.MediaPlayerControllerFactory;
import com.apple.android.music.playback.configuration.PlaybackStoreConfigurationProxy;
import com.apple.android.music.playback.model.MediaItemType;
import com.apple.android.music.playback.model.MediaPlayerException;
import com.apple.android.music.playback.model.PlaybackRepeatMode;
import com.apple.android.music.playback.model.PlaybackShuffleMode;
import com.apple.android.music.playback.model.PlaybackState;
import com.apple.android.music.playback.model.PlayerMediaItem;
import com.apple.android.music.playback.model.PlayerQueueItem;
import com.apple.android.music.playback.player.MediaPlayerContextFactory;
import com.apple.android.music.playback.player.MediaPlayerContext;
import com.apple.android.music.playback.queue.CatalogPlaybackQueueItemProvider;
import com.apple.android.music.playback.queue.LibraryPlaybackQueueItemProvider;
import com.apple.android.music.playback.queue.PlaybackModeContainer;
import com.apple.android.music.playback.queue.PlaybackQueueItemProvider;
import com.apple.android.music.playback.queue.PlaybackQueueItemsAdded;
import com.apple.android.sdk.authentication.TokenProvider;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Native MusicKit 1.1.4 playback. Control methods and callbacks run on the main thread. */
public final class AppleMusicPlayback implements ApplePlaybackEngine {
    public enum Error { NONE, CONFIGURATION, USER_AUTH_REQUIRED, USER_AUTH_INVALID, SUBSCRIPTION,
        SESSION_CHANGED, DRM, QUEUE, LEASE, NETWORK, SDK_UNAVAILABLE, UNKNOWN }
    public interface Listener { void onStateChanged(State state); }
    public static final class State {
        public final Track currentTrack;
        /** The explicitly requested queue; never an invented expansion of SDK containers. */
        public final List<Track> queue;
        public final int queueIndex,sdkQueueCount;
        public final long positionMs,durationMs,endEventId;
        public final boolean initialized,playing,buffering,preparing,ready,canPlay,canPause,canSeek,canNext,canPrevious,ended,error;
        public final Error errorCode;
        public final String statusMessage;
        State(Track current,List<Track> queue,int index,int count,long position,long duration,
                boolean initialized,boolean playing,boolean buffering,boolean preparing,boolean ready,
                boolean canPlay,boolean canPause,boolean canSeek,boolean canNext,boolean canPrevious,
                boolean ended,long endEventId,Error error,String message){
            currentTrack=current;this.queue=Collections.unmodifiableList(new ArrayList<>(queue));queueIndex=index;sdkQueueCount=count;
            positionMs=position;durationMs=duration;this.initialized=initialized;this.playing=playing;this.buffering=buffering;
            this.preparing=preparing;this.ready=ready;this.canPlay=canPlay;this.canPause=canPause;this.canSeek=canSeek;
            this.canNext=canNext;this.canPrevious=canPrevious;this.ended=ended;this.endEventId=endEventId;
            errorCode=error;this.error=error!=Error.NONE;statusMessage=message;
        }
    }
    /** A test seam around the actual SDK controller, not a replacement playback engine. */
    interface Factory {
        MediaPlayerController create(Context context,Handler handler,TokenProvider tokens);
        void clearSession(Context context,MediaPlayerController controller);
    }
    private static final TokenProvider CLEARED_TOKENS=new TokenProvider(){
        public String getDeveloperToken(){return null;}public String getUserToken(){return null;}
        public String getAppleMusicStoreFrontId(){return null;}
    };
    private static final class SdkFactory implements Factory {
        private static boolean loaded;
        private static volatile boolean engineCreatedInProcess,cleanupRequestedInProcess;
        private static synchronized void load(){
            if(loaded)return;
            // Values used by Apple's supplied SDK sample to disable JavaCPP's spurious OOM guard.
            System.setProperty("org.bytedeco.javacpp.maxphysicalbytes","0");
            System.setProperty("org.bytedeco.javacpp.maxbytes","0");
            System.loadLibrary("c++_shared");System.loadLibrary("appleMusicSDK");loaded=true;
        }
        public MediaPlayerController create(Context context,Handler handler,TokenProvider tokens){
            synchronized(SdkFactory.class){
                SharedPreferences state=runtime(context);
                checkCleanupBoundary(state.getBoolean("cleanup_pending",false)||cleanupRequestedInProcess,engineCreatedInProcess);
                // A cold process has no old SDK handler which could write a queue after deletion.
                if(!engineCreatedInProcess&&(state.getBoolean("used",false)||state.getBoolean("cleanup_pending",false)))clearPersistedNow(context);
                load();
                if(!state.edit().putBoolean("used",true).commit())throw new IllegalStateException("SDK lifecycle storage unavailable");
                engineCreatedInProcess=true;
                return MediaPlayerControllerFactory.createLocalController(context,handler,tokens);
            }
        }
        public void clearSession(Context context,MediaPlayerController controller){
            // The app may retain its developer configuration when the user signs out. The SDK must not.
            synchronized(SdkFactory.class){
                cleanupRequestedInProcess=true;
                runtime(context).edit().putBoolean("cleanup_pending",true).commit();
                controller.getPlaybackStoreConfiguration().notifyTokenChanged(context,CLEARED_TOKENS);
                // stop/release only enqueue SDK-thread messages. Keep the marker until a cold process.
            }
        }
    }
    static final class SessionCleanupPending extends IllegalStateException {}
    static void checkCleanupBoundary(boolean pending,boolean engineWasCreated){if(pending&&engineWasCreated)throw new SessionCleanupPending();}
    private static SharedPreferences runtime(Context context){return context.getSharedPreferences("applemusic_sdk_runtime",Context.MODE_PRIVATE);}
    private static void clearMarker(Context context){
        if(SdkFactory.engineCreatedInProcess)throw new SessionCleanupPending();
        if(!runtime(context).edit().remove("used").remove("cleanup_pending").commit())throw new IllegalStateException("SDK lifecycle storage unavailable");
        SdkFactory.cleanupRequestedInProcess=false;
    }
    private static void clearPersistedNow(Context context){
        if(SdkFactory.engineCreatedInProcess)throw new SessionCleanupPending();
        SdkFactory.load();MediaPlayerContext player=MediaPlayerContextFactory.createPlayerContext(context);
        try{new PlaybackStoreConfigurationProxy(player).notifyTokenChanged(context,CLEARED_TOKENS);}
        finally{player.clearData();}
        clearMarker(context);
    }
    /** Call after advancing the credential generation, including logout while another source is playing.
     * No credentials are read or HTTP requests made here. If this process ever owned an SDK engine,
     * queue cleanup waits for a cold process because SDK release has no completion callback. The app's
     * authorization remains cleared; new playback is blocked until a safe cleanup succeeds. */
    public static void clearStoredSession(Context context){
        Context app=context.getApplicationContext();SharedPreferences state=runtime(app);
        if(!state.getBoolean("used",false)&&!state.getBoolean("cleanup_pending",false)&&!SdkFactory.engineCreatedInProcess)return;
        SdkFactory.cleanupRequestedInProcess=true;
        state.edit().putBoolean("cleanup_pending",true).commit();
        new Handler(Looper.getMainLooper()).post(()->{
            synchronized(SdkFactory.class){
                if(SdkFactory.engineCreatedInProcess||!runtime(app).getBoolean("cleanup_pending",false))return;
                try{clearPersistedNow(app);}catch(RuntimeException|LinkageError ignored){/* Retain the marker; never use the old SDK cache again. */}
            }
        });
    }
    private final Context context;
    private final AppleMusicApi.Tokens credentials;
    private final TokenProvider tokenProvider;
    private final Listener listener;
    private final Factory factory;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Runnable sessionChanged=this::tokensChanged;
    private AppleMusicSessionStore observedSession;
    private MediaPlayerController controller;
    private List<Track> requested=Collections.emptyList();
    private long generation,endEventId,lastEndedQueueId=Long.MIN_VALUE,previousQueueId=Long.MIN_VALUE;
    private int requestedIndex;
    private boolean closed,preparing,queueObserved;
    private Error lastError=Error.NONE;
    private String message="等待选择 Apple Music 歌曲";
    private volatile State snapshot;

    public AppleMusicPlayback(Context context,AppleMusicSessionStore session,Listener listener){
        this(context,session,session,listener,new SdkFactory());observedSession=session;session.addChangeListener(sessionChanged);
    }
    public AppleMusicPlayback(Context context,AppleMusicApi.Tokens credentials,TokenProvider tokens,Listener listener){
        this(context,credentials,tokens,listener,new SdkFactory());
    }
    AppleMusicPlayback(Context context,AppleMusicApi.Tokens credentials,TokenProvider tokens,Listener listener,Factory factory){
        if(context==null||credentials==null||tokens==null||listener==null||factory==null)throw new IllegalArgumentException("MusicKit dependencies required");
        this.context=context.getApplicationContext();this.credentials=credentials;tokenProvider=tokens;this.listener=listener;this.factory=factory;
        generation=credentials.generation();snapshot=empty(Error.NONE,message);requireMain();
    }
    public State state(){return snapshot;}

    /** true means the SDK accepted a command; playing/ready are reported only by SDK state. */
    public boolean play(List<Track> tracks,int index){
        return prepare(tracks,index,true);
    }
    public boolean prepare(List<Track> tracks,int index,boolean autoPlay){
        requireMain();if(closed)return false;
        final QueueSpec spec;
        try{spec=queueSpec(tracks,index);}catch(IllegalArgumentException invalid){fail(Error.QUEUE,"Apple Music 队列必须是同一来源的有效歌曲；目录与个人曲库请分开播放");return false;}
        if(!validSession())return false;
        try{
            if(controller==null){controller=factory.create(context,main,tokenProvider);if(controller==null)throw new IllegalStateException();controller.addListener(callbacks);}
            PlayerQueueItem previous=controller.getCurrentItem();previousQueueId=previous==null?Long.MIN_VALUE:previous.getPlaybackQueueId();queueObserved=false;
            requested=Collections.unmodifiableList(new ArrayList<>(tracks));requestedIndex=index;preparing=true;lastError=Error.NONE;message="正在准备 Apple Music 歌曲";lastEndedQueueId=Long.MIN_VALUE;
            // The app owns queue/repeat/shuffle policy. Never inherit an SDK session's old modes.
            controller.stop();
            if(controller.canSetRepeatMode())controller.setRepeatMode(PlaybackRepeatMode.REPEAT_MODE_OFF);
            if(controller.canSetShuffleMode())controller.setShuffleMode(PlaybackShuffleMode.SHUFFLE_MODE_OFF);
            PlaybackQueueItemProvider provider=spec.library
                    ?new LibraryPlaybackQueueItemProvider.Builder().items(MediaItemType.SONG,spec.ids).startItemIndex(index).shuffleMode(PlaybackShuffleMode.SHUFFLE_MODE_OFF).featureName("record_room_library").build()
                    :new CatalogPlaybackQueueItemProvider.Builder().items(MediaItemType.SONG,spec.ids).startItemIndex(index).shuffleMode(PlaybackShuffleMode.SHUFFLE_MODE_OFF).featureName("record_room_catalog").build();
            controller.prepare(provider,autoPlay);publish(false);return true;
        }catch(SessionCleanupPending failure){fail(Error.SESSION_CHANGED,"Apple Music 授权已变化，请完全关闭并重新打开应用后再播放");return false;}
        catch(LinkageError failure){dispose(false);fail(Error.SDK_UNAVAILABLE,"当前设备无法加载 Apple Music 原生播放组件");return false;}
        catch(RuntimeException failure){fail(Error.QUEUE,"Apple Music 未能准备此队列，请重试");return false;}
    }
    public boolean resume(){return command(0,0);}
    public boolean pause(){return command(1,0);}
    public boolean next(){return command(2,0);}
    public boolean previous(){return command(3,0);}
    public boolean seek(long positionMs){return command(4,positionMs);}
    public boolean stop(){
        requireMain();if(closed||controller==null)return false;
        try{preparing=false;controller.stop();publish(false);return true;}catch(RuntimeException failure){fail(Error.UNKNOWN,"Apple Music 未能停止播放");return false;}
    }
    private boolean command(int kind,long value){
        requireMain();if(closed||controller==null||!validSession())return false;
        try{
            publish(false);
            if(kind==0){if(!snapshot.canPlay)return false;controller.play();}
            else if(kind==1)controller.pause();
            else if(kind==2){if(!snapshot.canNext)return false;controller.skipToNextItem();}
            else if(kind==3){if(!snapshot.canPrevious)return false;controller.skipToPreviousItem();}
            else{if(!snapshot.canSeek||value<0||snapshot.durationMs<=0)return false;controller.seekToPosition(Math.min(value,snapshot.durationMs));}
            publish(false);return true;
        }catch(RuntimeException failure){fail(Error.UNKNOWN,"Apple Music 未能执行播放操作");return false;}
    }
    /** Use after user-token, developer-token or account changes. The session-store constructor observes this automatically. */
    public void tokensChanged(){
        requireMain();if(closed||generation==credentials.generation())return;
        generation=credentials.generation();dispose(true);requested=Collections.emptyList();preparing=false;
        lastError=Error.SESSION_CHANGED;message="Apple Music 授权已变化，请重新选择歌曲";publish(false);
    }
    public void refresh(){requireMain();if(!closed){tokensChanged();publish(false);}}
    private boolean validSession(){
        tokensChanged();
        if(emptyToken(tokenProvider.getDeveloperToken())){fail(Error.CONFIGURATION,"尚未配置有效的 Apple Music 开发者令牌");return false;}
        if(emptyToken(tokenProvider.getUserToken())){fail(Error.USER_AUTH_REQUIRED,"请先授权 Apple Music 账号");return false;}
        return true;
    }
    private static boolean emptyToken(String value){return value==null||value.trim().isEmpty();}
    private boolean current(MediaPlayerController source){
        if(closed||source!=controller)return false;
        if(generation!=credentials.generation()){tokensChanged();return false;}
        return true;
    }
    private void changed(MediaPlayerController source){if(current(source))publish(false);}
    private void observeQueue(MediaPlayerController source,PlayerQueueItem item){
        if(!current(source))return;
        if(!queueObserved&&item!=null&&item.getPlaybackQueueId()!=previousQueueId&&requestedIndex>=0&&requestedIndex<requested.size()){
            PlayerMediaItem media=item.getItem();Track target=requested.get(requestedIndex);
            if(media!=null&&(target.id.equals(media.getCloudLibraryUniversalId())||target.id.equals(media.getSubscriptionStoreId())||target.id.equals(media.getPlaybackStoreId())))queueObserved=true;
        }
        publish(false);
    }
    private final MediaPlayerController.Listener callbacks=new MediaPlayerController.Listener(){
        public void onPlayerStateRestored(MediaPlayerController c){changed(c);}
        public void onPlaybackStateChanged(MediaPlayerController c,int previous,int current){changed(c);}
        public void onPlaybackStateUpdated(MediaPlayerController c){changed(c);}
        public void onBufferingStateChanged(MediaPlayerController c,boolean buffering){changed(c);}
        public void onCurrentItemChanged(MediaPlayerController c,PlayerQueueItem previous,PlayerQueueItem current){observeQueue(c,current);}
        public void onMetadataUpdated(MediaPlayerController c,PlayerQueueItem item){observeQueue(c,item);}
        public void onPlaybackQueueChanged(MediaPlayerController c,List<PlayerQueueItem> queue){if(current(c))observeQueue(c,c.getCurrentItem());}
        public void onPlaybackQueueItemsAdded(MediaPlayerController c,PlaybackQueueItemsAdded added){changed(c);}
        public void onPlaybackModeChanged(MediaPlayerController c,PlaybackModeContainer mode){changed(c);}
        public void onPlaybackRepeatModeChanged(MediaPlayerController c,int mode){changed(c);}
        public void onPlaybackShuffleModeChanged(MediaPlayerController c,int mode){changed(c);}
        public void onItemEnded(MediaPlayerController c,PlayerQueueItem item,long position){
            if(!current(c)||!queueObserved||preparing||item==null||lastEndedQueueId==item.getPlaybackQueueId())return;
            lastEndedQueueId=item.getPlaybackQueueId();endEventId++;preparing=false;publish(true);
        }
        public void onPlaybackError(MediaPlayerController c,MediaPlayerException exception){
            if(!current(c))return;int type=exception==null?MediaPlayerException.TYPE_UNKNOWN:exception.getType();
            switch(type){
                case MediaPlayerException.TYPE_USER_SUBSCRIPTION_INVALID:fail(Error.SUBSCRIPTION,"Apple Music 订阅不可用，请在官方 App 检查订阅");break;
                case MediaPlayerException.TYPE_MUSIC_USER_TOKEN_ERROR:fail(Error.USER_AUTH_INVALID,"Apple Music 账号授权已失效，请重新授权");break;
                case MediaPlayerException.TYPE_DRM:fail(Error.DRM,"Apple Music 未能取得此内容的播放许可");break;
                case MediaPlayerException.TYPE_PLAYBACK_QUEUE:fail(Error.QUEUE,"Apple Music 无法加载此歌曲或队列");break;
                case MediaPlayerException.TYPE_PLAYBACK_LEASE:fail(Error.LEASE,"Apple Music 当前无法取得播放会话，请检查其他设备");break;
                case MediaPlayerException.TYPE_IO:case MediaPlayerException.TYPE_FAILED_CONNECTION:fail(Error.NETWORK,"Apple Music 网络连接失败，请稍后重试");break;
                default:fail(Error.UNKNOWN,"Apple Music 播放失败，请在官方 App 检查内容权限");
            }
        }
    };
    private void fail(Error error,String text){lastError=error;message=text;preparing=false;publish(false);}
    private void publish(boolean ended){
        if(controller==null){snapshot=empty(lastError,message);listener.onStateChanged(snapshot);return;}
        try{
            PlayerQueueItem current=controller.getCurrentItem();PlayerMediaItem media=current==null?null:current.getItem();
            int index=controller.getPlaybackQueueIndex(),playbackState=controller.getPlaybackState();
            long duration=Math.max(0,controller.getDuration()),position=Math.max(0,controller.getCurrentPosition());
            boolean playing=playbackState==PlaybackState.PLAYING,buffering=controller.isBuffering();
            boolean ready=queueObserved&&media!=null&&duration>0&&!buffering&&(playing||playbackState==PlaybackState.PAUSED)&&lastError==Error.NONE;
            if(ready)preparing=false;
            Track track=!queueObserved||media==null?null:track(media,index);
            boolean canPlay=ready&&media.isPlayableContent();
            String text=lastError!=Error.NONE?message:preparing?"正在准备 Apple Music 歌曲":buffering?"Apple Music 正在缓冲":playing?"正在播放":track==null?"等待选择 Apple Music 歌曲":"已暂停";
            snapshot=new State(track,requested,index,Math.max(0,controller.getPlaybackQueueItemCount()),position,duration,true,playing,buffering,preparing,ready,
                    canPlay,playing||buffering,ready&&!controller.isLiveStream()&&controller.canSeek(),ready&&controller.canSkipToNextItem(),ready&&controller.canSkipToPreviousItem(),ended,endEventId,lastError,text);
        }catch(RuntimeException invalid){preparing=false;lastError=Error.UNKNOWN;message="暂时无法读取 Apple Music 播放状态";snapshot=empty(lastError,message);}
        listener.onStateChanged(snapshot);
    }
    private State empty(Error error,String message){return new State(null,requested,-1,0,0,0,false,false,false,preparing,false,false,false,false,false,false,false,endEventId,error,message);}
    private Track track(PlayerMediaItem media,int index){
        String libraryId=text(media.getCloudLibraryUniversalId()),catalogId=text(media.getSubscriptionStoreId());if(catalogId.isEmpty())catalogId=text(media.getPlaybackStoreId());
        Track original=index>=0&&index<requested.size()?requested.get(index):null;
        if(original!=null&&!original.id.equals(libraryId)&&!original.id.equals(catalogId))original=null;
        String id=original!=null?original.id:!libraryId.isEmpty()?libraryId:catalogId;if(id.isEmpty())return null;
        String uri=original!=null?original.uri:"applemusic://"+(!libraryId.isEmpty()?"library-songs/":"songs/")+Uri.encode(id);
        String title=text(media.getTitle()),artist=text(media.getArtistName()),album=text(media.getAlbumTitle()),artwork=text(media.getArtworkUrl(800,800));
        if(original!=null){if(title.isEmpty())title=original.title;if(artist.isEmpty())artist=original.artist;if(album.isEmpty())album=original.album;if(artwork.isEmpty())artwork=original.artworkUri;}
        return new Track(id,title,artist,album,"Apple Music",uri,artwork,Math.max(0,media.getDuration()),Math.max(0,media.getAlbumTrackNumber()),text(media.getAlbumArtistName()),"","","applemusic",text(media.getAlbumSubscriptionStoreId()),text(media.getArtistSubscriptionStoreId()));
    }
    private static String text(String value){return value==null?"":value;}
    static final class QueueSpec {final boolean library;final String[] ids;QueueSpec(boolean library,String[] ids){this.library=library;this.ids=ids;}}
    static QueueSpec queueSpec(List<Track> tracks,int index){
        if(tracks==null||tracks.isEmpty()||tracks.size()>10000||index<0||index>=tracks.size())throw new IllegalArgumentException();
        String type=null;String[] ids=new String[tracks.size()];
        for(int i=0;i<tracks.size();i++){
            Track track=tracks.get(i);if(track==null||!"applemusic".equals(track.provider)||track.id==null||!track.id.matches("[A-Za-z0-9._-]{1,256}"))throw new IllegalArgumentException();
            Uri uri=Uri.parse(track.uri);String host=uri.getHost();
            if(!"applemusic".equals(uri.getScheme())||!("songs".equals(host)||"library-songs".equals(host))||uri.getPort()!=-1||uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null||!uri.getPath().equals("/"+track.id))throw new IllegalArgumentException();
            if(type!=null&&!type.equals(host))throw new IllegalArgumentException();type=host;ids[i]=track.id;
        }
        return new QueueSpec("library-songs".equals(type),ids);
    }
    private void dispose(boolean clearSession){
        MediaPlayerController old=controller;controller=null;if(old==null)return;
        try{old.removeListener(callbacks);}catch(RuntimeException ignored){}
        try{old.pause();old.stop();}catch(RuntimeException ignored){}
        if(clearSession)try{factory.clearSession(context,old);}catch(RuntimeException|LinkageError ignored){}
        try{old.release();}catch(RuntimeException ignored){}
    }
    @Override public void close(){
        requireMain();if(closed)return;closed=true;if(observedSession!=null)observedSession.removeChangeListener(sessionChanged);
        dispose(false);requested=Collections.emptyList();preparing=false;lastError=Error.NONE;message="Apple Music 播放已关闭";snapshot=empty(lastError,message);
    }
    private static void requireMain(){if(Looper.myLooper()!=Looper.getMainLooper())throw new IllegalStateException("MusicKit controls require the main thread");}
}
