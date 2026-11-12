package com.recordroom.vinyl;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.net.Uri;
import org.json.JSONArray;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;

/** Owns audio for this app only. Every public player operation runs on the main thread. */
public class PlayerService extends Service {
    public static final String ACTION_TOGGLE = "com.recordroom.vinyl.TOGGLE";
    public static final String ACTION_NEXT = "com.recordroom.vinyl.NEXT";
    public static final String ACTION_PREVIOUS = "com.recordroom.vinyl.PREVIOUS";
    public static final String ACTION_STOP = "com.recordroom.vinyl.STOP";
    private static final String ACTION_START = "com.recordroom.vinyl.START_PLAYBACK";
    public static final int MODE_ORDER = 0, MODE_REPEAT_ALL = 1, MODE_REPEAT_ONE = 2, MODE_SHUFFLE = 3;
    static final String PREFS = "vinyl_player";
    private static final int NOTIFICATION_ID = 42;
    private static final String CHANNEL = "vinyl_playback";
    private static final int ARTWORK_EDGE = 384;
    private static PlayerService instance;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ArrayList<Track> queue = new ArrayList<>();
    private final ArrayList<Runnable> listeners = new ArrayList<>();
    private final ArrayList<Integer> shuffleHistory = new ArrayList<>();
    private final Random random = new Random();
    private final LocalBinder binder = new LocalBinder();
    private final ExecutorService artworkIO = Executors.newSingleThreadExecutor(r -> new Thread(r, "vinyl-session-artwork"));
    private ExoPlayer player;
    private PlaybackAudioPath audioPath;
    String audioQuality() {
        if (applePlayer!=null) return "音源与输出由 Apple MusicKit 管理。SDK 未报告当前曲目的实际输出规格。";
        return audioPath==null?"播放歌曲后可查看音源与输出设备。":audioPath.report(player==null?null:player.getAudioFormat());
    }
    boolean usbFollowsSource(){return getSharedPreferences(PREFS,MODE_PRIVATE).getBoolean("usb_follow_rate",true);}
    void setUsbFollowsSource(boolean enabled){
        getSharedPreferences(PREFS,MODE_PRIVATE).edit().putBoolean("usb_follow_rate",enabled).apply();
        if(player!=null){positionMs=getPositionMs();prepareCurrent();}
    }
    private ApplePlaybackEngine applePlayer;
    private long appleEndEvent;
    private boolean appleWasPlaying;
    private Player.Listener playerListener;
    private final ExecutorService resourceIO = Executors.newSingleThreadExecutor(r -> new Thread(r, "vinyl-playback-resource"));
    private Future<?> resourceTask;
    private PlaybackResource activeResource;
    private boolean urlRefreshUsed, fullDurationVerified;
    private long expectedDurationMs;
    private AudioManager audioManager;
    private AudioFocusRequest focusRequest;
    private MediaSession mediaSession;
    private PlayerStateStore stateStore;
    private PlayerStateStore.QueueSnapshot queueSnapshot;
    private long persistenceSession;
    private SharedPreferences appearancePreferences;
    private Future<?> artworkTask;
    private Bitmap sessionArtwork;
    private String artworkSignature = "";
    private long artworkGeneration;
    private boolean prepared, preparing, desiredPlay, foreground, hasFocus, resumeOnFocus, ducked;
    private boolean ended, destroyed;
    private long generation, positionMs, durationMs, sleepDeadline, lastSaved;
    private int index = -1, playMode;
    private float volume = 1f;
    private String error = "";
    private final java.util.Map<String,List<Track>> aggregateAlternatives=new java.util.HashMap<>();
    private final java.util.Map<String,Long> aggregateEpochs=new java.util.HashMap<>();
    private final java.util.Set<String> aggregateAttempts=new java.util.HashSet<>();


    private final SharedPreferences.OnSharedPreferenceChangeListener artworkPreferenceListener = (prefs, key) -> {
        if (key != null && !key.endsWith(":art")) return;
        handler.post(() -> {
            if (destroyed) return;
            updateSession();
            if (foreground || getCurrent() != null) updateNotification();
        });
    };

    public class LocalBinder extends Binder {
        public PlayerService getService() { return PlayerService.this; }
    }

    private final BroadcastReceiver noisyReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction())) pause(true);
        }
    };

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (destroyed) return;
            if(applePlayer!=null)applePlayer.refresh();
            if (isPlaying()) {
                positionMs = getPositionMs();
                if (SystemClock.elapsedRealtime() - lastSaved > 5000) persist();
                notifyListeners();
                handler.postDelayed(this, 500);
            }
        }
    };

    private final Runnable sleepTask = new Runnable() {
        @Override public void run() {
            if (sleepDeadline == 0) return;
            long remaining = sleepDeadline - SystemClock.elapsedRealtime();
            if (remaining > 0) { handler.postDelayed(this, remaining); return; }
            sleepDeadline = 0;
            pause(true);
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        instance = this;
        stateStore = PlayerStateStore.get(this);
        persistenceSession = stateStore.openSession();
        appearancePreferences = getSharedPreferences("vinyl_appearance", MODE_PRIVATE);
        appearancePreferences.registerOnSharedPreferenceChangeListener(artworkPreferenceListener);
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(audioAttributes())
                .setAcceptsDelayedFocusGain(false)
                .setOnAudioFocusChangeListener(this::onFocusChanged, handler).build();
        NotificationChannel channel = new NotificationChannel(CHANNEL, "唱片播放", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("播放控制与当前曲目");
        channel.setShowBadge(false);
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(channel);
        mediaSession = new MediaSession(this, "RecordRoom");
        mediaSession.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { play(); }
            @Override public void onPause() { pause(true); }
            @Override public void onSkipToNext() { next(); }
            @Override public void onSkipToPrevious() { previous(); }
            @Override public void onSeekTo(long pos) { seekTo(pos); }
            @Override public void onStop() { stopPlayback(); }
            @Override public boolean onMediaButtonEvent(Intent intent) { return super.onMediaButtonEvent(intent); }
        }, handler);
        mediaSession.setSessionActivity(openIntent());
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(noisyReceiver,
                new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(noisyReceiver, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
        restore();
        updateSession();
        VinylWidgetProvider.updateAll(this);
    }

    private AudioAttributes audioAttributes() {
        return new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build();
    }

    @Override public IBinder onBind(Intent intent) { return binder; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        // Widget transport intents use getForegroundService: fulfil its foreground contract first.
        if (ACTION_TOGGLE.equals(action) || ACTION_NEXT.equals(action) || ACTION_PREVIOUS.equals(action)
                || ACTION_START.equals(action)) {
            if (!ensureForeground()) return START_NOT_STICKY;
        }
        if (ACTION_TOGGLE.equals(action)) toggle();
        else if (ACTION_NEXT.equals(action)) next();
        else if (ACTION_PREVIOUS.equals(action)) previous();
        else if (ACTION_STOP.equals(action)) stopPlayback();
        else if (ACTION_START.equals(action) && !desiredPlay && !isPlaying() && !resumeOnFocus) leaveForeground();
        if (!desiredPlay && !isPlaying() && !resumeOnFocus) leaveForeground();
        return START_NOT_STICKY;
    }

    public List<Track> getQueue() { return queueSnapshot == null ? Collections.emptyList() : queueSnapshot.tracks; }
    public int getQueueSize() { return queue.size(); }
    public long getQueueRevision() { return queueSnapshot == null ? 0 : queueSnapshot.revision; }
    private void queueChanged() { queueSnapshot = stateStore.copyQueue(queue); }
    public Track getCurrent() { return index >= 0 && index < queue.size() ? queue.get(index) : null; }
    public int getIndex() { return index; }
    public boolean isPlaying() {
        if(applePlayer!=null)return applePlayer.state().playing;
        if (!prepared || player == null) return false;
        try { return player.isPlaying(); } catch (IllegalStateException e) { return false; }
    }
    public boolean isPreparing() { return preparing; }
    public boolean canSeek(){return applePlayer==null||applePlayer.state().canSeek;}
    public boolean isFullDurationVerified() { return fullDurationVerified; }
    public long getExpectedDurationMs() { return expectedDurationMs; }
    public String getError() { return error; }
    public long getPositionMs() {
        if(applePlayer!=null&&applePlayer.state().ready)return applePlayer.state().positionMs;
        if (player != null) {
            try { return Math.max(0, player.getCurrentPosition()); } catch (IllegalStateException ignored) { }
        }
        return positionMs;
    }
    String currentEmbeddedLyrics() {
        return player == null ? "" : TrackLyrics.embedded(player.getCurrentTracks());
    }

    public long getDurationMs() {
        if (durationMs > 0) return durationMs;
        Track current = getCurrent();
        return current == null ? 0 : current.durationMs;
    }
    public int getPlayMode() { return playMode; }
    public float getVolume() { return volume; }
    public long getSleepRemainingMs() {
        return sleepDeadline == 0 ? 0 : Math.max(0, sleepDeadline - SystemClock.elapsedRealtime());
    }
    public void addListener(Runnable listener) {
        if (listener != null && !listeners.contains(listener)) listeners.add(listener);
    }
    public void removeListener(Runnable listener) { listeners.remove(listener); }

    public void setQueue(List<Track> tracks, int startIndex, boolean autoplay) {
        pause(false);
        releasePlayer();
        queue.clear();aggregateAlternatives.clear();aggregateEpochs.clear();aggregateAttempts.clear();
        if (tracks != null) for (Track track : tracks) if (track != null && !track.uri.isEmpty()) queue.add(track);
        queueChanged();
        index = queue.isEmpty() ? -1 : Math.max(0, Math.min(startIndex, queue.size() - 1));
        positionMs = durationMs = 0;
        ended = false;
        error = "";
        shuffleHistory.clear();
        changed(true);
        if (autoplay) play();
    }

    public void setAggregateQueue(List<AggregateSearch.Result> results,int startIndex){
        ArrayList<Track> primary=new ArrayList<>();for(AggregateSearch.Result r:results)primary.add(r.displayTrack);
        setQueue(primary,startIndex,false);
        for(AggregateSearch.Result r:results)for(Track t:r.alternatives){aggregateAlternatives.put(LibraryStore.identity(t),r.alternatives);aggregateEpochs.put(LibraryStore.identity(t),OnlineMusic.sessionGeneration(this,OnlineMusic.providerId(t)));}
        play();
    }
    private boolean tryAggregateAlternative(){
        if(!desiredPlay||!preparing||prepared)return false;Track current=getCurrent();if(current==null)return false;
        List<Track> candidates=aggregateAlternatives.get(LibraryStore.identity(current));if(candidates==null)return false;
        for(Track candidate:candidates){String key=LibraryStore.identity(candidate);if(aggregateAttempts.contains(key))continue;
            Long epoch=aggregateEpochs.get(key);if(epoch==null||epoch!=OnlineMusic.sessionGeneration(this,OnlineMusic.providerId(candidate)))continue;
            long resumePosition=getPositionMs();queue.set(index,candidate);queueChanged();positionMs=resumePosition;durationMs=0;error="";prepareCurrent();return true;
        }return false;
    }
    public void toggle() {
        if (isPlaying() || desiredPlay) pause(true);
        else play();
    }
    public void startPlayback() { play(); }
    public void pausePlayback() { pause(true); }
    public void playAt(int at) { if(at>=0&&at<queue.size())selectIndex(at,true); }
    public void moveQueueItem(int from,int to) {
        if(from<0||to<0||from>=queue.size()||to>=queue.size()||from==to)return;
        Track item=queue.remove(from);queue.add(to,item);
        if(index==from)index=to;else if(from<index&&to>=index)index--;else if(from>index&&to<=index)index++;
        shuffleHistory.clear();queueChanged();changed(false);
    }
    public void removeQueueItem(int at) {
        if(at<0||at>=queue.size())return;
        boolean resume=desiredPlay||isPlaying();
        if(at==index){pause(false);releasePlayer();queue.remove(at);queueChanged();index=queue.isEmpty()?-1:Math.min(at,queue.size()-1);positionMs=durationMs=0;ended=false;error="";shuffleHistory.clear();changed(true);if(resume&&index>=0)play();}
        else {queue.remove(at);queueChanged();if(at<index)index--;shuffleHistory.clear();changed(false);}
    }
    public void enqueue(List<Track> tracks, boolean next) {
        int before=queue.size();int at=next?Math.max(0,index+1):queue.size();if(tracks!=null)for(Track track:tracks)if(track!=null&&!track.uri.isEmpty())queue.add(at++,track);
        if(queue.size()==before)return;queueChanged();
        if(index<0&&!queue.isEmpty())index=0;shuffleHistory.clear();changed(false);
    }
    public void refreshMetadata(List<Track> tracks) {
        java.util.HashMap<String,Track> latest=new java.util.HashMap<>();for(Track t:tracks)latest.put(LibraryStore.identity(t),t);boolean updated=false;
        for(int i=0;i<queue.size();i++){Track old=queue.get(i),fresh=latest.get(LibraryStore.identity(old));if(fresh!=null&&!old.toJson().toString().equals(fresh.toJson().toString())){queue.set(i,fresh);updated=true;}}
        if(updated){queueChanged();changed(false);}
    }

    /** Runs on the main thread after credential removal, even if the login Activity is gone. */
    static void removeProviderAfterLogout(Context context,String providerId){
        if(instance!=null&&!instance.destroyed){instance.removeProviderTracks(providerId);return;}
        PlayerStateStore store=PlayerStateStore.get(context);PlayerStateStore.State previous=store.restore();
        ArrayList<Track> retained=new ArrayList<>();int selected=-1,next=0;
        for(int i=0;i<previous.queue.tracks.size();i++){
            Track track=previous.queue.tracks.get(i);boolean removed=providerId.equals(track.provider)||track.uri.startsWith(providerId+"://");
            if(i<previous.index&&!removed)next++;
            if(i==previous.index&&!removed)selected=retained.size();
            if(!removed)retained.add(track);
        }
        if(retained.size()==previous.queue.tracks.size())return;
        boolean same=selected>=0;if(!same)selected=retained.isEmpty()?-1:Math.min(next,retained.size()-1);
        long owner=store.openSession();store.save(owner,store.copyQueue(retained),selected,same?previous.position:0,same&&previous.ended,previous.mode,previous.volume);
    }

    /** Logout removes that provider in one queue mutation and invalidates an in-flight remote source. */
    public void removeProviderTracks(String providerId) {
        if(providerId==null||providerId.isEmpty())return;
        ArrayList<Track> retained=new ArrayList<>();int selected=-1,nextIndex=0;boolean currentRemoved=false;
        for(int i=0;i<queue.size();i++){
            Track track=queue.get(i);boolean removed=providerId.equals(track.provider)||track.uri.startsWith(providerId+"://");
            if(i<index&&!removed)nextIndex++;
            if(i==index){currentRemoved=removed;if(!removed)selected=retained.size();}
            if(!removed)retained.add(track);
        }
        if(retained.size()==queue.size())return;
        if(currentRemoved){
            desiredPlay=false;resumeOnFocus=false;releasePlayer();abandonFocus();leaveForeground();
            positionMs=durationMs=0;ended=false;error="";
            selected=retained.isEmpty()?-1:Math.min(nextIndex,retained.size()-1);
        }
        queue.clear();queue.addAll(retained);index=selected;shuffleHistory.clear();queueChanged();changed(currentRemoved);
    }

    private void play() {
        if(!error.isEmpty())aggregateAttempts.clear();
        if (destroyed) return;
        if (getCurrent() == null) { error = "请先选择一首歌曲"; changed(false); leaveForeground(); return; }
        error = "";
        desiredPlay = true;
        resumeOnFocus = false;
        if (!startOwnedService()) return;
        if(OnlineMusic.isApple(getCurrent())){if(hasFocus)abandonFocus();}
        else if(!acquireFocus())return;
        if (ended) { ended = false; positionMs = 0; if (prepared) seekTo(0); }
        if (prepared && player != null && activeResource != null && activeResource.expiresAtMs > 0
                && activeResource.expiresAtMs <= System.currentTimeMillis()+2000) {
            positionMs=getPositionMs();urlRefreshUsed=true;prepareCurrent(true);
        }
        else if (prepared && (player != null||applePlayer!=null)) startPrepared();
        else if (!preparing) prepareCurrent();
        else changed(false);
    }

    /** A bound-only service must also become started before it can outlive its Activity. */
    private boolean startOwnedService() {
        try {
            startForegroundService(new Intent(this, PlayerService.class).setAction(ACTION_START));
            return ensureForeground();
        } catch (RuntimeException e) {
            desiredPlay = false;
            error = "系统暂不允许后台播放，请打开应用重试";
            changed(false);
            leaveForeground();
            return false;
        }
    }

    private boolean acquireFocus() {
        if (hasFocus) return true;
        int result;
        try { result = audioManager.requestAudioFocus(focusRequest); }
        catch (RuntimeException e) { result = AudioManager.AUDIOFOCUS_REQUEST_FAILED; }
        hasFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        if (!hasFocus) {
            desiredPlay = false;
            error = "未获得音频焦点，请稍后重试";
            changed(false);
            leaveForeground();
        }
        return hasFocus;
    }

    private void prepareCurrent() { prepareCurrent(false); }

    private void prepareCurrent(boolean refreshing) {
        Track track=getCurrent();if(track==null)return;
        aggregateAttempts.add(LibraryStore.identity(track));
        releasePlayer();preparing=true;fullDurationVerified=false;
        if(!refreshing)urlRefreshUsed=false;
        final long token=generation;
        if(OnlineMusic.isApple(track)){abandonFocus();openApple(track,token);return;}
        if(OnlineMusic.isRemote(track)) {
            expectedDurationMs=track.durationMs;
            Context app=getApplicationContext();
            resourceTask=resourceIO.submit(()->{
                PlaybackResource resource=null;String failure=null;
                try{resource=OnlineMusic.resolve(app,track);}
                catch(ProviderException exception){failure=exception.getMessage();}
                catch(RuntimeException exception){failure="无法获取播放资源，请稍后重试";}
                final PlaybackResource resolved=resource;final String message=failure;
                handler.post(()->{
                    if(destroyed||generation!=token)return;resourceTask=null;
                    if(resolved==null){fail(message==null?"没有可用的完整歌曲资源":message);return;}
                    if(resolved.expiresAtMs>0&&resolved.expiresAtMs<=System.currentTimeMillis()+1000){fail("播放地址已过期，请重试");return;}
                    if(resolved.url==null||!"https".equalsIgnoreCase(Uri.parse(resolved.url).getScheme())){fail("播放资源没有可用的安全连接");return;}
                    expectedDurationMs=Math.max(track.durationMs,resolved.durationMs);
                    if(expectedDurationMs<=0){fail("歌曲时长信息不完整，暂时无法确认整曲播放");return;}
                    openMedia3(track,resolved,token);
                });
            });
            changed(false);
        } else {expectedDurationMs=0;openMedia3(track,null,token);}
    }

    /** MusicKit owns Apple DRM and audio focus; the app retains its queue, notification and timer. */
    protected ApplePlaybackEngine createApplePlayback(AppleMusicPlayback.Listener listener){
        return new AppleMusicPlayback(this,OnlineMusic.appleSession(this),listener);
    }
    private void openApple(Track track,long token){
        appleEndEvent=0;appleWasPlaying=false;
        try{
            applePlayer=createApplePlayback(state->{
                if(destroyed||generation!=token||applePlayer==null)return;
                if(state.error){fail(state.statusMessage);return;}
                durationMs=state.durationMs>0?state.durationMs:track.durationMs;
                boolean firstReady=state.ready&&!prepared;
                preparing=state.preparing||state.buffering;
                if(state.ready)prepared=true;
                if(firstReady){
                    long resumeAt=positionMs;positionMs=state.positionMs;
                    if(resumeAt>0&&state.canSeek)applePlayer.seek(resumeAt);
                    if(generation!=token||applePlayer==null)return;
                    if(desiredPlay)startPrepared();
                }
                if(generation!=token||applePlayer==null)return;
                if(state.ended&&state.endEventId>appleEndEvent){appleEndEvent=state.endEventId;completed();return;}
                AppleMusicPlayback.State actual=applePlayer.state();
                boolean playing=actual.playing;
                // MusicKit owns audio focus: a confirmed SDK pause must also clear our stale play intent.
                // Use the latest snapshot after the initial ready/seek/resume callbacks above.
                if(playing)desiredPlay=true;
                else if(appleWasPlaying&&actual.ready&&!actual.buffering&&!actual.preparing)desiredPlay=false;
                if(playing&&!appleWasPlaying){handler.removeCallbacks(tick);handler.postDelayed(tick,500);}
                if(!playing)handler.removeCallbacks(tick);
                appleWasPlaying=playing;changed(false);
            });
            applePlayer.prepare(Collections.singletonList(track),0,false);
            if(generation==token)changed(false);
        }catch(RuntimeException|LinkageError failure){fail("当前设备无法初始化 Apple Music 播放组件");}
    }

    private void openMedia3(Track track,PlaybackResource resource,long token) {
        if(destroyed||token!=generation)return;
        try {
            Uri uri=Uri.parse(resource==null?track.uri:resource.url);String scheme=uri.getScheme();
            if("asset".equals(scheme)) {
                String path=uri.getPath();if(path==null||!path.startsWith("/demo/")||path.contains(".."))throw new IllegalArgumentException("Unsupported asset");
            } else if(!"content".equals(scheme)&&!"file".equals(scheme)&&!"https".equalsIgnoreCase(scheme)&&!"http".equalsIgnoreCase(scheme))throw new IllegalArgumentException("Unsupported audio URI");
            PlaybackAudioPath path=new PlaybackAudioPath(this,usbFollowsSource(),() -> {
                if (!destroyed && generation==token && player!=null && !ended) {
                    positionMs=getPositionMs();
                    prepareCurrent();
                }
            });
            audioPath=path;
            DataSource.Factory usual=createDataSourceFactory(resource);
            DataSource.Factory inputs=resource==null?()->new LocalFormatDataSource(this,usual.createDataSource(),path::source):usual;
            ExoPlayer candidate=new ExoPlayer.Builder(this,path.renderers(this)).setLooper(Looper.getMainLooper())
                    .setMediaSourceFactory(new DefaultMediaSourceFactory(inputs))
                    .setAudioAttributes(new androidx.media3.common.AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),false)
                    .setHandleAudioBecomingNoisy(false).setWakeMode(C.WAKE_MODE_LOCAL).setUsePlatformDiagnostics(false).build();
            player=candidate;activeResource=resource;
            playerListener=new Player.Listener(){
                private boolean current(){return !destroyed&&player==candidate&&token==generation;}
                @Override public void onMediaItemTransition(MediaItem next,int reason) {
                    if (!current() || reason != Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || next == null) return;
                    Track upcoming = upcomingLocal();
                    if (upcoming == null || !next.mediaId.equals(LibraryStore.identity(upcoming))) return;
                    index++;
                    path.selectSource(Uri.parse(upcoming.uri));
                    positionMs = candidate.getCurrentPosition();
                    long length = candidate.getDuration();
                    durationMs = length == C.TIME_UNSET ? upcoming.durationMs : length;
                    ended = false;
                    error = "";
                    if (desiredPlay) new LibraryStore(PlayerService.this).remember(upcoming);
                    changed(true);
                }
                @Override public void onPlaybackStateChanged(int state){
                    if(!current())return;
                    if(state==Player.STATE_READY){
                        long actual=candidate.getDuration();durationMs=actual==C.TIME_UNSET?0:Math.max(0,actual);
                        if(resource!=null&&!completeDuration(durationMs,expectedDurationMs)){fail("音频时长与整曲不符，已停止播放，未使用试听片段");return;}
                        fullDurationVerified=resource!=null;preparing=false;prepared=true;applyVolume();
                        if(desiredPlay)startPrepared();else changed(false);
                    } else if(state==Player.STATE_BUFFERING){preparing=true;changed(false);}
                    else if(state==Player.STATE_ENDED){
                        if(resource!=null&&!completeDuration(candidate.getCurrentPosition(),expectedDurationMs)){fail("音频提前结束，未完成整曲播放");return;}
                        completed();
                    }
                }
                @Override public void onIsPlayingChanged(boolean playing){
                    if(!current())return;handler.removeCallbacks(tick);if(playing)handler.post(tick);changed(false);
                }
                @Override public void onPlayerError(PlaybackException failure){if(current())handlePlaybackError(failure);}
            };
            candidate.addListener(playerListener);
            path.selectSource(uri);
            candidate.setMediaItem(LocalCueImport.mediaItem(track, uri),positionMs);
            syncUpcomingLocal();
            candidate.prepare();changed(false);
        } catch(RuntimeException exception){fail("无法打开音频：请检查文件、网络或播放权限");}
    }

    /** Keeps the engine and its network boundary independently verifiable. */
    protected DataSource.Factory createDataSourceFactory(PlaybackResource resource) {
        DefaultHttpDataSource.Factory http=new DefaultHttpDataSource.Factory().setUserAgent("RecordRoom/0.6 Android")
                .setConnectTimeoutMs(15000).setReadTimeoutMs(20000).setAllowCrossProtocolRedirects(false);
        if(resource!=null)http.setDefaultRequestProperties(resource.headers);
        return new DefaultDataSource.Factory(this,http);
    }

    static boolean completeDuration(long actual,long expected){return actual>0&&expected>0&&Math.abs(actual-expected)<=Math.max(500,Math.min(5000,expected/20));}

    private void handlePlaybackError(PlaybackException failure) {
        boolean expired=activeResource!=null&&activeResource.expiresAtMs>0&&activeResource.expiresAtMs<=System.currentTimeMillis();
        for(Throwable cause=failure;cause!=null;cause=cause.getCause())if(cause instanceof HttpDataSource.InvalidResponseCodeException){int code=((HttpDataSource.InvalidResponseCodeException)cause).responseCode;expired|=code==401||code==403||code==410;}
        if(OnlineMusic.isRemote(getCurrent())&&expired&&!urlRefreshUsed){positionMs=getPositionMs();urlRefreshUsed=true;prepareCurrent(true);return;}
        if(!OnlineMusic.isRemote(getCurrent())){
            fail("本地音频读取或解码失败，请检查文件完整性和访问权限");return;
        }
        fail(expired?"播放地址失效，刷新后仍不可用，请重试":"播放失败：请检查网络、文件或歌曲播放权限");
    }


    private void startPrepared() {
        if (!desiredPlay || !prepared) return;
        if(applePlayer!=null){
            if(applePlayer.resume()){new LibraryStore(this).remember(getCurrent());error="";ended=false;}
            changed(false);return;
        }
        if(player==null)return;
        if (!hasFocus && !acquireFocus()) return;
        try {
            player.play();
            new LibraryStore(this).remember(getCurrent());
            error = "";
            ended = false;
            handler.removeCallbacks(tick);
            handler.post(tick);
            changed(false);
        } catch (RuntimeException e) { fail("无法开始播放此音频"); }
    }

    private void pause(boolean publish) {
        desiredPlay = false;
        resumeOnFocus = false;
        if(applePlayer!=null){positionMs=getPositionMs();applePlayer.pause();}
        if (player != null) {
            positionMs = getPositionMs();
            // STATE_ENDED and buffering are not isPlaying(), but can retain playWhenReady.
            // Clear that intent so a later seek never resumes audio without a new play request.
            try { player.pause(); } catch (IllegalStateException ignored) { }
        }
        if (preparing) releasePlayer();
        handler.removeCallbacks(tick);
        abandonFocus();
        leaveForeground();
        persist();
        if (publish) changed(false);
    }

    private void stopPlayback() {
        pause(false);
        releasePlayer();
        positionMs = 0;
        ended = false;
        error = "";
        changed(false);
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).cancel(NOTIFICATION_ID);
        stopSelf();
    }

    public void next() { advance(false); }

    private void advance(boolean completion) {
        if (queue.isEmpty()) { leaveForeground(); return; }
        boolean autoplay = completion || desiredPlay || isPlaying();
        if (completion && playMode == MODE_REPEAT_ONE) {
            positionMs = 0;
            ended = false;
            desiredPlay = true;
            try { if(applePlayer!=null){prepareCurrent();return;}player.seekTo(0); startPrepared(); } catch (RuntimeException e) { fail("无法重新播放此音频"); }
            return;
        }
        int next = index + 1;
        if (playMode == MODE_SHUFFLE && queue.size() > 1) {
            shuffleHistory.add(index);
            next = random.nextInt(queue.size() - 1);
            if (next >= index) next++;
        } else if (next >= queue.size()) {
            if (playMode == MODE_ORDER) {
                pause(false);
                ended = completion;
                if (completion) positionMs = getDurationMs();
                changed(false);
                return;
            }
            next = 0;
        }
        selectIndex(next, autoplay);
    }

    private void completed() {
        handler.removeCallbacks(tick);
        positionMs = getDurationMs();
        advance(true);
    }

    public void previous() {
        if (getCurrent() == null) { leaveForeground(); return; }
        if (getPositionMs() > 3000) { seekTo(0); return; }
        boolean autoplay = desiredPlay || isPlaying();
        int previous = index - 1;
        if (playMode == MODE_SHUFFLE && !shuffleHistory.isEmpty())
            previous = shuffleHistory.remove(shuffleHistory.size() - 1);
        if (previous < 0) previous = playMode == MODE_ORDER ? 0 : queue.size() - 1;
        selectIndex(previous, autoplay);
    }

    private void selectIndex(int nextIndex, boolean autoplay) {
        aggregateAttempts.clear();
        desiredPlay = false;
        resumeOnFocus = false;
        releasePlayer();
        index = nextIndex;
        positionMs = durationMs = 0;
        ended = false;
        error = "";
        changed(true);
        if (autoplay) play();
        else { abandonFocus(); leaveForeground(); }
    }

    public void seekTo(long position) {
        long duration = getDurationMs();
        positionMs = Math.max(0, duration > 0 ? Math.min(position, duration) : position);
        ended = false;
        if(applePlayer!=null&&applePlayer.state().canSeek)applePlayer.seek(positionMs);
        if (player != null) {
            try { player.seekTo(positionMs); }
            catch (RuntimeException e) { fail("无法跳转到此播放位置"); return; }
        }
        persist();
        updateSession();
        notifyListeners();
    }

    public void setPlayMode(int mode) {
        if (mode < MODE_ORDER || mode > MODE_SHUFFLE) return;
        playMode = mode;
        syncUpcomingLocal();
        shuffleHistory.clear();
        persist();
        notifyListeners();
    }

    public void setVolume(float value) {
        if (Float.isNaN(value) || Float.isInfinite(value)) return;
        volume = Math.max(0f, Math.min(1f, value));
        applyVolume();
        persist();
        notifyListeners();
    }

    private void applyVolume() {
        if (player != null) {
            float effective = volume * (ducked ? .2f : 1f);
            try { player.setVolume(effective); } catch (IllegalStateException ignored) { }
        }
    }

    public void setSleepTimerMinutes(int minutes) {
        handler.removeCallbacks(sleepTask);
        sleepDeadline = minutes <= 0 ? 0 : SystemClock.elapsedRealtime() + Math.min(minutes, 1440) * 60000L;
        if (sleepDeadline > 0) handler.postDelayed(sleepTask, getSleepRemainingMs());
        notifyListeners();
    }

    private void onFocusChanged(int change) {
        if (destroyed||OnlineMusic.isApple(getCurrent())) return;
        if (change == AudioManager.AUDIOFOCUS_GAIN) {
            hasFocus = true;
            ducked = false;
            applyVolume();
            if (resumeOnFocus) {
                resumeOnFocus = false;
                desiredPlay = true;
                if (startOwnedService()) {
                    if (prepared) startPrepared();
                    else if (!preparing) prepareCurrent();
                }
            }
        } else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
            ducked = true;
            applyVolume();
        } else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            boolean resume = desiredPlay || isPlaying();
            desiredPlay = false;
            hasFocus = false;
            positionMs = getPositionMs();
            if (prepared && player != null) try { player.pause(); } catch (IllegalStateException ignored) { }
            handler.removeCallbacks(tick);
            resumeOnFocus = resume;
            // Keep the already-running media foreground service while waiting for focus to return.
            changed(false);
        } else if (change == AudioManager.AUDIOFOCUS_LOSS) {
            hasFocus = false;
            pause(true);
        }
    }

    private void abandonFocus() {
        if (audioManager != null && focusRequest != null) audioManager.abandonAudioFocusRequest(focusRequest);
        hasFocus = false;
        ducked = false;
        applyVolume();
    }

    private void fail(String message) {
        if(tryAggregateAlternative())return;
        // Scope the final reason to its own attempt; other versions may have failed differently.
        if(desiredPlay&&preparing&&!prepared){
            Track failed=getCurrent();List<Track> candidates=failed==null?null:aggregateAlternatives.get(LibraryStore.identity(failed));
            java.util.Set<String> tried=new java.util.HashSet<>();
            if(candidates!=null)for(Track candidate:candidates){String key=LibraryStore.identity(candidate);if(aggregateAttempts.contains(key))tried.add(key);}
            if(tried.size()>1)message="已尝试 "+tried.size()+" 个版本，当前仍无法播放。最后一次："+message;
        }
        positionMs = getPositionMs();
        desiredPlay = false;
        resumeOnFocus = false;
        error = message;
        releasePlayer();
        abandonFocus();
        leaveForeground();
        changed(false);
    }

    private void releasePlayer() {
        generation++;
        prepared = false;
        preparing = false;
        handler.removeCallbacks(tick);
        if(resourceTask!=null){resourceTask.cancel(true);resourceTask=null;}
        ApplePlaybackEngine oldApple=applePlayer;applePlayer=null;appleWasPlaying=false;
        if(oldApple!=null)oldApple.close();
        ExoPlayer old = player;
        player = null;
        activeResource=null;fullDurationVerified=false;expectedDurationMs=0;
        if (old != null) {
            if(playerListener!=null)old.removeListener(playerListener);
            old.release();
        }
        playerListener=null;
        if(audioPath!=null){audioPath.close();audioPath=null;}
    }

    /** Media3 can buffer the next local item on the current audio renderer. Provider URLs,
     * shuffle and repeat keep their own resolution and completion paths. */
    private Track upcomingLocal() {
        if (playMode != MODE_ORDER || index < 0 || index+1 >= queue.size()) return null;
        Track current = queue.get(index), next = queue.get(index+1);
        return isQueueableLocal(current) && isQueueableLocal(next) ? next : null;
    }
    private static boolean isQueueableLocal(Track t) {
        Uri uri = Uri.parse(t.uri);
        String scheme = uri.getScheme();
        if (OnlineMusic.isRemote(t)) return false;
        if ("asset".equals(scheme)) return uri.getPath() != null && uri.getPath().startsWith("/demo/") && !uri.getPath().contains("..");
        return "content".equals(scheme) || "file".equals(scheme);
    }
    private void syncUpcomingLocal() {
        if (player == null || activeResource != null || player.getMediaItemCount() == 0) return;
        // Only the current item and one successor are retained in the native timeline.
        int current = player.getCurrentMediaItemIndex();
        if (current > 0) player.removeMediaItems(0,current);
        Track upcoming = upcomingLocal();
        if (upcoming == null) {
            if (player.getMediaItemCount() > 1) player.removeMediaItem(1);
            return;
        }
        MediaItem next = LocalCueImport.mediaItem(upcoming,Uri.parse(upcoming.uri));
        if (player.getMediaItemCount() == 1) player.addMediaItem(next);
        else if (!next.equals(player.getMediaItemAt(1))) player.replaceMediaItem(1,next);
    }

    private void changed(boolean trackChanged) {
        if (player != null && getCurrent() != null && player.getCurrentMediaItem() != null
                && player.getCurrentMediaItem().mediaId.equals(LibraryStore.identity(getCurrent()))) syncUpcomingLocal();
        persist();
        updateSession();
        if (getCurrent() == null) {
            leaveForeground();
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).cancel(NOTIFICATION_ID);
        } else updateNotification();
        VinylWidgetProvider.updateAll(this);
        notifyListeners();
    }

    private void notifyListeners() {
        for (Runnable listener : new ArrayList<>(listeners)) {
            try { listener.run(); } catch (RuntimeException ignored) { }
        }
    }

    private void updateSession() {
        if (mediaSession == null || destroyed) return;
        requestArtwork();
        Track track = getCurrent();
        mediaSession.setActive(track != null);
        if (track != null) {
            MediaMetadata.Builder metadata = new MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_MEDIA_ID, track.id)
                    .putString(MediaMetadata.METADATA_KEY_TITLE, track.title)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, track.artist)
                    .putString(MediaMetadata.METADATA_KEY_ALBUM, track.album)
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, getDurationMs());
            if (sessionArtwork != null) metadata.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, sessionArtwork);
            mediaSession.setMetadata(metadata.build());
        } else mediaSession.setMetadata(null);
        int state = track == null ? PlaybackState.STATE_NONE : preparing ? PlaybackState.STATE_BUFFERING
                : !error.isEmpty() ? PlaybackState.STATE_ERROR : isPlaying() ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED;
        PlaybackState.Builder builder = new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                        | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS
                        | (canSeek()?PlaybackState.ACTION_SEEK_TO:0) | PlaybackState.ACTION_STOP)
                .setState(state, getPositionMs(), isPlaying() ? 1f : 0f, SystemClock.elapsedRealtime());
        if (!error.isEmpty()) builder.setErrorMessage(error);
        mediaSession.setPlaybackState(builder.build());
    }

    /** Only a changed album/cover starts IO; playback ticks never decode artwork. */
    private void requestArtwork() {
        Track track = getCurrent();
        String uri = "";
        if (track != null) {
            String custom = new ThemeStore(this).artwork(track.albumKey());
            uri = custom.isEmpty() ? track.artworkUri : custom;
        }
        String signature = track == null ? "empty" : track.albumKey() + "\u001f" + uri;
        if (signature.equals(artworkSignature)) return;
        artworkSignature = signature;
        long token = ++artworkGeneration;
        if (artworkTask != null) { artworkTask.cancel(true); artworkTask = null; }
        // Drop our reference; published bitmaps may still be held by MediaSession/Notification.
        sessionArtwork = null;
        if (track == null || uri.isEmpty()) return;
        final String source = uri;
        final Context app = getApplicationContext();
        artworkTask = artworkIO.submit(() -> {
            Bitmap decoded = null;
            try {
                decoded = LocalMusicImporter.loadArtwork(app, source, ARTWORK_EDGE);
                if (decoded != null) {
                    int edge = Math.max(decoded.getWidth(), decoded.getHeight());
                    if (edge > ARTWORK_EDGE) {
                        Bitmap smaller = Bitmap.createScaledBitmap(decoded,
                                Math.max(1, decoded.getWidth() * ARTWORK_EDGE / edge),
                                Math.max(1, decoded.getHeight() * ARTWORK_EDGE / edge), true);
                        if (smaller != decoded) decoded.recycle();
                        decoded = smaller;
                    }
                }
            } catch (RuntimeException ignored) { }
            if (Thread.currentThread().isInterrupted()) {
                if (decoded != null) decoded.recycle();
                return;
            }
            final Bitmap result = decoded;
            handler.post(() -> completeArtwork(token, signature, result));
        });
    }

    private void completeArtwork(long token, String signature, Bitmap bitmap) {
        if (destroyed || token != artworkGeneration || !signature.equals(artworkSignature)) {
            if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
            return;
        }
        artworkTask = null;
        sessionArtwork = bitmap;
        updateSession();
        if (foreground || getCurrent() != null) updateNotification();
    }

    private PendingIntent openIntent() {
        Intent intent = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(this, 100, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static PendingIntent transportIntent(Context context, String action, int requestCode) {
        Intent intent = new Intent(context, PlayerService.class).setAction(action);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        return ACTION_STOP.equals(action) ? PendingIntent.getService(context, requestCode, intent, flags)
                : PendingIntent.getForegroundService(context, requestCode, intent, flags);
    }

    private Notification notification() {
        Track track = getCurrent();
        boolean playing = isPlaying() || desiredPlay;
        Notification.Builder builder = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(track == null ? "唱片室" : track.title)
                .setContentText(!error.isEmpty() ? error : preparing ? "正在载入音频…" : track == null ? "选择歌曲开始播放" : track.artist)
                .setContentIntent(openIntent()).setOnlyAlertOnce(true).setShowWhen(false)
                .setVisibility(Notification.VISIBILITY_PUBLIC).setCategory(Notification.CATEGORY_TRANSPORT)
                .setOngoing(playing || preparing)
                .addAction(new Notification.Action.Builder(android.R.drawable.ic_media_previous, "上一首", transportIntent(this, ACTION_PREVIOUS, 101)).build())
                .addAction(new Notification.Action.Builder(playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                        playing ? "暂停" : "播放", transportIntent(this, ACTION_TOGGLE, 102)).build())
                .addAction(new Notification.Action.Builder(android.R.drawable.ic_media_next, "下一首", transportIntent(this, ACTION_NEXT, 103)).build())
                .addAction(new Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "停止", transportIntent(this, ACTION_STOP, 104)).build())
                .setStyle(new Notification.MediaStyle().setMediaSession(mediaSession.getSessionToken()).setShowActionsInCompactView(0, 1, 2));
        if (sessionArtwork != null) builder.setLargeIcon(sessionArtwork);
        return builder.build();
    }

    private boolean ensureForeground() {
        if (foreground) return true;
        try {
            startForeground(NOTIFICATION_ID, notification());
            foreground = true;
            return true;
        } catch (RuntimeException e) {
            desiredPlay = false;
            error = "无法启动播放服务，请打开应用重试";
            notifyListeners();
            return false;
        }
    }

    private void updateNotification() {
        try { ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(NOTIFICATION_ID, notification()); }
        catch (RuntimeException ignored) { }
    }

    private void leaveForeground() {
        if (foreground) { stopForeground(STOP_FOREGROUND_DETACH); foreground = false; }
    }

    private void persist() {
        if (stateStore == null || queueSnapshot == null) return;
        stateStore.save(persistenceSession, queueSnapshot, index, positionMs, ended, playMode, volume);
        lastSaved = SystemClock.elapsedRealtime();
    }

    private void restore() {
        PlayerStateStore.State saved = stateStore.restore();
        queueSnapshot = saved.queue; queue.clear();int restored=-1;boolean removed=false;
        for(int i=0;i<saved.queue.tracks.size();i++){Track track=saved.queue.tracks.get(i);if("unsupported".equals(OnlineMusic.providerId(track))){removed=true;continue;}if(i==saved.index)restored=queue.size();queue.add(track);}
        index=restored>=0?restored:queue.isEmpty()?-1:0;positionMs=restored>=0?saved.position:0;ended=restored>=0&&saved.ended;
        if(removed)queueChanged();
        playMode = saved.mode; volume = saved.volume;
        desiredPlay = false;
    }

    static Track widgetTrack(Context context) {
        if (instance != null && !instance.destroyed) return instance.getCurrent();
        PlayerStateStore.State saved = PlayerStateStore.get(context).restore();
        return saved.index >= 0 && saved.index < saved.queue.tracks.size() ? saved.queue.tracks.get(saved.index) : null;
    }
    static boolean widgetPlaying() {
        return instance != null && !instance.destroyed && (instance.isPlaying() || instance.desiredPlay);
    }

    @Override public void onTaskRemoved(Intent rootIntent) {
        positionMs = getPositionMs();
        persist();
        super.onTaskRemoved(rootIntent);
    }

    @Override public void onDestroy() {
        positionMs = getPositionMs();
        persist();
        destroyed = true;
        artworkGeneration++;
        if (appearancePreferences != null) appearancePreferences.unregisterOnSharedPreferenceChangeListener(artworkPreferenceListener);
        if (artworkTask != null) { artworkTask.cancel(true); artworkTask = null; }
        artworkIO.shutdownNow();
        resourceIO.shutdownNow();
        sessionArtwork = null;
        desiredPlay = false;
        resumeOnFocus = false;
        handler.removeCallbacksAndMessages(null);
        releasePlayer();
        abandonFocus();
        try { unregisterReceiver(noisyReceiver); } catch (IllegalArgumentException ignored) { }
        if (mediaSession != null) { mediaSession.setActive(false); mediaSession.release(); }
        if (foreground) stopForeground(STOP_FOREGROUND_REMOVE);
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).cancel(NOTIFICATION_ID);
        foreground = false;
        listeners.clear();
        if (instance == this) instance = null;
        VinylWidgetProvider.updateAll(this);
        super.onDestroy();
    }
}
