package com.recordroom.vinyl;

import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.view.KeyEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Controls an explicitly selected, authorized Android MediaSession. Main-thread only.
 * This class never reads notifications, resolves song URLs, owns audio, or touches PlayerService.
 * Command success means dispatched, not acknowledged playback: the session callback is authoritative.
 */
public final class ExternalMusicBridge implements AutoCloseable {
    public enum Status { STOPPED, NO_SOURCE, PERMISSION_REQUIRED, CONNECTING, NOT_INSTALLED,
        NO_ACTIVE_SESSION, CONNECTED, SESSION_UNAVAILABLE }

    public static final class Provider {
        public final String id, name, packageName;
        private Provider(String id, String name, String packageName) {
            this.id = id; this.name = name; this.packageName = packageName;
        }
    }

    private static final List<Provider> PROVIDERS = Collections.unmodifiableList(Arrays.asList(
            new Provider("qq_music", "QQ音乐", "com.tencent.qqmusic"),
            new Provider("qishui_music", "汽水音乐", "com.luna.music"),
            new Provider("apple_music", "Apple Music", "com.apple.android.music"),
            new Provider("netease_music", "网易云音乐", "com.netease.cloudmusic")));

    public static List<Provider> providers() { return PROVIDERS; }
    public static Provider providerForId(String id) {
        for (Provider provider : PROVIDERS) if (provider.id.equals(id)) return provider;
        return null;
    }
    private static Provider providerForPackage(String packageName) {
        for (Provider provider : PROVIDERS) if (provider.packageName.equals(packageName)) return provider;
        return null;
    }

    public static final class ProviderState {
        public final Provider provider;
        public final boolean installed, active;
        public final Status status;
        public final String statusMessage;
        private ProviderState(Provider provider, boolean installed, boolean active, Status status) {
            this.provider = provider; this.installed = installed; this.active = active;
            this.status = status; this.statusMessage = message(status, provider);
        }
    }

    /** Missing position/duration are -1, missing text is empty, missing artwork is null. */
    public static final class State {
        public final String selectedProviderId;
        public final Provider selectedProvider;
        public final Status status;
        public final String statusMessage;
        public final boolean accessGranted, connected;
        public final String title, artist, album, mediaId, artworkUri;
        /** Borrowed read-only bitmap owned by the remote metadata. Do not recycle or mutate it. */
        public final Bitmap artwork;
        public final long positionMs, durationMs, actions;
        public final int playbackState;
        public final boolean playing, canPlay, canPause, canPrevious, canNext, canSeek;
        public final List<ProviderState> providers;

        private State(Provider provider, Status status, boolean access, List<ProviderState> providers,
                      MediaMetadata metadata, PlaybackState playback, long now) {
            selectedProvider = provider; selectedProviderId = provider == null ? "" : provider.id;
            this.status = status; accessGranted = access; connected = status == Status.CONNECTED;
            this.providers = Collections.unmodifiableList(new ArrayList<>(providers));
            title = firstText(metadata, MediaMetadata.METADATA_KEY_TITLE, MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
            artist = firstText(metadata, MediaMetadata.METADATA_KEY_ARTIST,
                    MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
            album = firstText(metadata, MediaMetadata.METADATA_KEY_ALBUM);
            mediaId = firstText(metadata, MediaMetadata.METADATA_KEY_MEDIA_ID);
            artwork = firstBitmap(metadata);
            artworkUri = firstText(metadata, MediaMetadata.METADATA_KEY_ALBUM_ART_URI,
                    MediaMetadata.METADATA_KEY_ART_URI, MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI);
            long duration = metadata != null && metadata.containsKey(MediaMetadata.METADATA_KEY_DURATION)
                    ? metadata.getLong(MediaMetadata.METADATA_KEY_DURATION) : -1;
            durationMs = duration > 0 ? duration : -1;
            playbackState = playback == null ? PlaybackState.STATE_NONE : playback.getState();
            playing = connected && playbackState == PlaybackState.STATE_PLAYING;
            actions = connected && playback != null ? playback.getActions() : 0;
            positionMs = estimatePosition(playback, durationMs, now);
            boolean toggle = has(actions, PlaybackState.ACTION_PLAY_PAUSE);
            boolean idle = playbackState == PlaybackState.STATE_PAUSED || playbackState == PlaybackState.STATE_STOPPED;
            canPlay = has(actions, PlaybackState.ACTION_PLAY) || (toggle && idle);
            canPause = has(actions, PlaybackState.ACTION_PAUSE) || (toggle && playing);
            canPrevious = has(actions, PlaybackState.ACTION_SKIP_TO_PREVIOUS);
            canNext = has(actions, PlaybackState.ACTION_SKIP_TO_NEXT);
            // A turntable seek needs a reported finite timeline, never a fabricated duration.
            canSeek = has(actions, PlaybackState.ACTION_SEEK_TO) && durationMs > 0 && positionMs >= 0;
            if (connected && playbackState == PlaybackState.STATE_ERROR)
                statusMessage = "已连接" + provider.name + "，对方报告播放错误";
            else if (connected && title.isEmpty())
                statusMessage = "已连接" + provider.name + "，对方尚未提供曲目信息";
            else statusMessage = message(status, provider);
        }
    }

    public interface Listener { void onStateChanged(State state); }

    // The seam models official platform objects; it cannot resolve streams or arbitrary providers.
    interface Session {
        String packageName(); Object token();
        MediaMetadata metadata(); PlaybackState playback();
        void subscribe(Runnable changed, Runnable destroyed);
        void unsubscribe();
        void play(); void pause(); void previous(); void next(); void seekTo(long position);
        boolean toggle();
    }
    interface Access {
        boolean hasAccess(); boolean isConnected(); boolean isInstalled(String packageName);
        void start(Runnable changed); void stop(); void stopSessionWatch();
        List<Session> activeSessions();
        Intent launchIntent(String packageName);
    }

    private final Access access;
    private final Handler handler;
    private Listener listener;
    private Provider selectedProvider;
    private List<Session> sessions = Collections.emptyList();
    private final Set<Object> destroyedTokens = new HashSet<>();
    private Session selectedSession;
    private MediaMetadata metadata;
    private PlaybackState playback;
    private State state;
    private boolean started, closed, sessionFailure;
    private boolean timelineTicks = true;
    private long bindingGeneration;
    private final Runnable accessChanged = () -> { if (started && !closed) refresh(); };
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!started || closed || selectedSession == null) return;
            if (!access.hasAccess() || !access.isConnected()) { refresh(); return; }
            emit();
            if (timelineTicks && started && !closed && selectedSession != null) handler.postDelayed(this, 500);
        }
    };

    public ExternalMusicBridge(Context context, Listener listener) {
        this(new AndroidAccess(context.getApplicationContext()), new Handler(Looper.getMainLooper()), listener);
    }
    ExternalMusicBridge(Access access, Handler handler, Listener listener) {
        this.access = access; this.handler = handler; this.listener = listener;
        state = snapshot();
    }

    /** Static widgets only need real media callbacks, never playback-position ticks. */
    void disableTimelineTicks() { timelineTicks = false; handler.removeCallbacks(tick); }

    public State getState() { checkThread(); return state; }
    public String getSelectedProviderId() { return selectedProvider == null ? "" : selectedProvider.id; }

    /** Invalid IDs are rejected, without changing the existing source. Null/empty selects no external source. */
    public boolean selectProvider(String id) {
        checkThread();
        if (closed) return false;
        Provider provider = id == null || id.isEmpty() ? null : providerForId(id);
        if (provider == null && id != null && !id.isEmpty()) return false;
        if (provider != selectedProvider) {
            detach(); selectedProvider = provider; sessionFailure = false;
        }
        if (started) refresh(); else emit();
        return true;
    }

    public void start() {
        checkThread();
        if (closed || started) return;
        started = true;
        access.start(accessChanged);
        refresh();
    }
    public void stop() {
        checkThread();
        if (!started) return;
        started = false;
        detach(); access.stop(); sessions = Collections.emptyList(); destroyedTokens.clear();
        emit();
    }
    @Override public void close() {
        checkThread();
        if (closed) return;
        stop(); closed = true; listener = null;
    }

    public void refresh() {
        checkThread();
        if (!started || closed) return;
        sessionFailure = false;
        if (!access.hasAccess() || !access.isConnected()) {
            access.stopSessionWatch(); sessions = Collections.emptyList(); detach(); emit(); return;
        }
        try {
            ArrayList<Session> allowed = new ArrayList<>();
            Set<Object> existingTokens = new HashSet<>();
            for (Session session : access.activeSessions()) {
                if (session != null && providerForPackage(session.packageName()) != null) {
                    existingTokens.add(session.token());
                    if (!destroyedTokens.contains(session.token())) allowed.add(session);
                }
            }
            destroyedTokens.retainAll(existingTokens);
            sessions = allowed;
            Session match = null;
            if (selectedProvider != null) {
                for (Session session : sessions) {
                    if (!selectedProvider.packageName.equals(session.packageName())) continue;
                    if (match == null) match = session;
                    if (selectedSession != null && Objects.equals(selectedSession.token(), session.token())) {
                        match = selectedSession; break;
                    }
                }
            }
            bind(match);
        } catch (SecurityException denied) {
            access.stopSessionWatch(); sessions = Collections.emptyList(); detach();
            sessionFailure = access.hasAccess();
        } catch (RuntimeException unavailable) {
            sessions = Collections.emptyList(); detach(); sessionFailure = true;
        }
        emit();
    }

    private void bind(Session session) {
        if (session != selectedSession) {
            detach(); selectedSession = session;
            if (session != null) {
                final long generation = ++bindingGeneration;
                session.subscribe(() -> {
                    if (!currentBinding(session, generation)) return;
                    if (!access.hasAccess() || !access.isConnected()) { refresh(); return; }
                    try { readSession(); emit(); }
                    catch (RuntimeException unavailable) { failSession(); }
                }, () -> {
                    if (!currentBinding(session, generation)) return;
                    destroyedTokens.add(session.token()); detach(); refresh();
                });
            }
        }
        if (session != null) {
            readSession(); handler.removeCallbacks(tick); if (timelineTicks) handler.postDelayed(tick, 500);
        }
    }
    private boolean currentBinding(Session session, long generation) {
        return started && !closed && selectedSession == session && bindingGeneration == generation;
    }
    private void readSession() {
        metadata = selectedSession.metadata(); playback = selectedSession.playback();
    }
    private void detach() {
        ++bindingGeneration; handler.removeCallbacks(tick);
        Session previous = selectedSession; selectedSession = null; metadata = null; playback = null;
        if (previous != null) {
            try { previous.unsubscribe(); } catch (RuntimeException ignored) { /* Remote process may have died. */ }
        }
    }
    private void failSession() {
        detach(); sessions = Collections.emptyList(); sessionFailure = true;
        if (!access.hasAccess()) access.stopSessionWatch();
        emit();
    }

    public boolean play() { return command(PlaybackState.ACTION_PLAY, 0); }
    public boolean pause() { return command(PlaybackState.ACTION_PAUSE, 0); }
    public boolean previous() { return command(PlaybackState.ACTION_SKIP_TO_PREVIOUS, 0); }
    public boolean next() { return command(PlaybackState.ACTION_SKIP_TO_NEXT, 0); }
    public boolean seekTo(long positionMs) { return command(PlaybackState.ACTION_SEEK_TO, positionMs); }
    public boolean togglePlayPause() {
        checkThread();
        if (!readyForCommand()) return false;
        return state.playing ? pause() : play();
    }
    private boolean readyForCommand() {
        if (closed || !started || selectedSession == null || selectedProvider == null) return false;
        if (!access.hasAccess() || !access.isConnected()) { refresh(); return false; }
        try { readSession(); state = snapshot(); return state.connected; }
        catch (RuntimeException unavailable) { failSession(); return false; }
    }
    private boolean command(long action, long position) {
        checkThread();
        if (!readyForCommand()) return false;
        try {
            if (action == PlaybackState.ACTION_PLAY && state.canPlay) {
                if (has(state.actions, action)) selectedSession.play(); else return selectedSession.toggle();
            } else if (action == PlaybackState.ACTION_PAUSE && state.canPause) {
                if (has(state.actions, action)) selectedSession.pause(); else return selectedSession.toggle();
            } else if (action == PlaybackState.ACTION_SKIP_TO_PREVIOUS && state.canPrevious) selectedSession.previous();
            else if (action == PlaybackState.ACTION_SKIP_TO_NEXT && state.canNext) selectedSession.next();
            else if (action == PlaybackState.ACTION_SEEK_TO && state.canSeek)
                selectedSession.seekTo(Math.min(state.durationMs, Math.max(0, position)));
            else return false;
            return true;
        } catch (RuntimeException unavailable) { failSession(); return false; }
    }

    public static Intent notificationAccessSettingsIntent() {
        return new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
    }
    /** Returns only an installed allowlisted app's normal launcher intent; never starts it automatically. */
    public Intent getLaunchIntent(String providerId) {
        checkThread();
        Provider provider = providerForId(providerId);
        if (provider == null) return null;
        try { return access.launchIntent(provider.packageName); }
        catch (RuntimeException unavailable) { return null; }
    }

    private State snapshot() {
        boolean permission = access.hasAccess();
        boolean serviceConnected = permission && access.isConnected();
        ArrayList<ProviderState> providers = new ArrayList<>();
        Status selectedStatus = started ? Status.NO_SOURCE : Status.STOPPED;
        for (Provider provider : PROVIDERS) {
            boolean active = false;
            for (Session session : sessions) if (provider.packageName.equals(session.packageName())) { active = true; break; }
            boolean installed = active || access.isInstalled(provider.packageName);
            Status status = !started ? Status.STOPPED : !installed ? Status.NOT_INSTALLED
                    : !permission ? Status.PERMISSION_REQUIRED : !serviceConnected ? Status.CONNECTING
                    : sessionFailure ? Status.SESSION_UNAVAILABLE : active ? Status.CONNECTED : Status.NO_ACTIVE_SESSION;
            providers.add(new ProviderState(provider, installed, active && permission && serviceConnected, status));
            if (provider == selectedProvider) selectedStatus = status;
        }
        if (selectedProvider != null && selectedStatus == Status.CONNECTED && selectedSession == null)
            selectedStatus = Status.NO_ACTIVE_SESSION;
        boolean connected = selectedStatus == Status.CONNECTED;
        return new State(selectedProvider, selectedStatus, permission, providers,
                connected ? metadata : null, connected ? playback : null, SystemClock.elapsedRealtime());
    }
    private void emit() { state = snapshot(); if (listener != null && !closed) listener.onStateChanged(state); }
    private static String message(Status status, Provider provider) {
        String name = provider == null ? "音乐 App" : provider.name;
        switch (status) {
            case STOPPED: return "联动已暂停";
            case NO_SOURCE: return "正在使用内建播放";
            case NOT_INSTALLED: return "未检测到" + name + "，请先安装官方 App";
            case PERMISSION_REQUIRED: return "需要在系统中允许通知使用权以连接音乐会话";
            case CONNECTING: return "已获授权，等待系统连接媒体会话服务";
            case NO_ACTIVE_SESSION: return "请先在" + name + "播放一首歌曲";
            case CONNECTED: return "已连接" + name + "的当前播放会话";
            default: return "暂时无法连接" + name + "，请打开官方 App 后重试";
        }
    }
    private void checkThread() {
        if (Looper.myLooper() != handler.getLooper()) throw new IllegalStateException("ExternalMusicBridge requires the main thread");
    }
    private static boolean has(long actions, long action) { return (actions & action) != 0; }
    private static String firstText(MediaMetadata metadata, String... keys) {
        if (metadata == null) return "";
        for (String key : keys) {
            CharSequence value = metadata.getText(key);
            if (value != null && !value.toString().trim().isEmpty()) return value.toString();
        }
        return "";
    }
    private static Bitmap firstBitmap(MediaMetadata metadata) {
        if (metadata == null) return null;
        for (String key : new String[] { MediaMetadata.METADATA_KEY_ALBUM_ART,
                MediaMetadata.METADATA_KEY_ART, MediaMetadata.METADATA_KEY_DISPLAY_ICON }) {
            Bitmap bitmap = metadata.getBitmap(key);
            if (bitmap != null && !bitmap.isRecycled()) return bitmap;
        }
        return null;
    }
    static long estimatePosition(PlaybackState playback, long duration, long now) {
        if (playback == null || playback.getPosition() < 0) return -1;
        double position = playback.getPosition();
        int state = playback.getState();
        float speed = playback.getPlaybackSpeed();
        long updated = playback.getLastPositionUpdateTime();
        if ((state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_FAST_FORWARDING
                || state == PlaybackState.STATE_REWINDING) && updated > 0 && Float.isFinite(speed))
            position += Math.max(0, now - updated) * (double) speed;
        position = Math.max(0, position);
        if (duration > 0) position = Math.min(duration, position);
        return (long) position;
    }

    private static final class AndroidAccess implements Access {
        private static final String ENABLED_LISTENERS = "enabled_notification_listeners";
        private final Context context;
        private final ComponentName component;
        private final MediaSessionManager manager;
        private final Handler handler = new Handler(Looper.getMainLooper());
        private Runnable changed;
        private boolean watching, observing, sessionAccessReady, reconnectScheduled;
        private long lastRebindAt=-5000,reconnectDelay=1000;
        private final Runnable reconnect=new Runnable(){@Override public void run(){
            reconnectScheduled=false;if(changed==null||!hasAccess())return;
            if(!isConnected())requestReconnect();notifyChanged();
        }};
        private final MediaSessionManager.OnActiveSessionsChangedListener sessionsChanged = controllers -> notifyChanged();
        private final Runnable connectionChanged = () -> {
            sessionAccessReady=false;
            if(!ExternalMusicNotificationListener.isConnected()){stopSessionWatch();requestReconnect();}
            notifyChanged();
        };
        private final ContentObserver permissionObserver = new ContentObserver(handler) {
            @Override public void onChange(boolean selfChange) {
                sessionAccessReady=false;
                if(!hasAccess()){stopSessionWatch();cancelReconnect();}else requestReconnect();
                notifyChanged();
            }
        };
        AndroidAccess(Context context) {
            this.context = context;
            component = new ComponentName(context, ExternalMusicNotificationListener.class);
            manager = (MediaSessionManager) context.getSystemService(Context.MEDIA_SESSION_SERVICE);
        }
        private void notifyChanged() { if (changed != null) changed.run(); }
        @Override public boolean hasAccess() {
            try {
                if (Build.VERSION.SDK_INT >= 27) {
                    NotificationManager notifications = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
                    return notifications != null && notifications.isNotificationListenerAccessGranted(component);
                }
                String granted = Settings.Secure.getString(context.getContentResolver(), ENABLED_LISTENERS);
                if (granted != null) for (String value : granted.split(":"))
                    if (component.equals(ComponentName.unflattenFromString(value))) return true;
            } catch (RuntimeException unavailable) { return false; }
            return false;
        }
        @Override public boolean isConnected() {
            if(changed==null)return false;
            if(!hasAccess()){sessionAccessReady=false;return false;}
            if(sessionAccessReady||ExternalMusicNotificationListener.isConnected())return true;
            // Android authorizes getActiveSessions by enabled listener component, not by our
            // process-local callback flag. After restart/OEM callback delays, prove access directly.
            // A status snapshot can probe successfully just after refresh failed. Keep its
            // recovery task until activeSessions has actually subscribed and returned sessions.
            try{if(manager==null)return false;manager.getActiveSessions(component);sessionAccessReady=true;return true;}
            catch(RuntimeException unavailable){requestReconnect();return false;}
        }
        private void cancelReconnect(){handler.removeCallbacks(reconnect);reconnectScheduled=false;reconnectDelay=1000;}
        private void requestReconnect(){
            if(changed==null||!hasAccess()){cancelReconnect();return;}
            long now=SystemClock.uptimeMillis();
            if(now-lastRebindAt>=2000){lastRebindAt=now;try{NotificationListenerService.requestRebind(component);}catch(RuntimeException ignored){}}
            if(!sessionAccessReady&&!reconnectScheduled){reconnectScheduled=true;handler.postDelayed(reconnect,reconnectDelay);reconnectDelay=Math.min(30000,reconnectDelay*2);}
        }
        @Override public boolean isInstalled(String packageName) {
            try { return context.getPackageManager().getApplicationInfo(packageName, 0).enabled; }
            catch (PackageManager.NameNotFoundException | RuntimeException unavailable) { return false; }
        }
        @Override public void start(Runnable changed) {
            this.changed = changed;
            ExternalMusicNotificationListener.addConnectionListener(connectionChanged);
            if (!observing) {
                try {
                    context.getContentResolver().registerContentObserver(Settings.Secure.getUriFor(ENABLED_LISTENERS),
                            false, permissionObserver);
                    observing = true;
                } catch (RuntimeException ignored) { /* Resume/control checks also revalidate access. */ }
            }
            if(hasAccess()&&!ExternalMusicNotificationListener.isConnected())requestReconnect();
        }
        @Override public void stopSessionWatch() {
            if (watching && manager != null) {
                try { manager.removeOnActiveSessionsChangedListener(sessionsChanged); }
                catch (RuntimeException ignored) { }
            }
            watching = false;
        }
        @Override public void stop() {
            changed = null;cancelReconnect();sessionAccessReady=false;lastRebindAt=-5000;stopSessionWatch();
            ExternalMusicNotificationListener.removeConnectionListener(connectionChanged);
            if (observing) {
                context.getContentResolver().unregisterContentObserver(permissionObserver); observing = false;
            }
        }
        @Override public List<Session> activeSessions() {
            if (manager == null) throw new IllegalStateException("Media sessions unavailable");
            if(!hasAccess())throw new SecurityException("Media access unavailable");
            try{
                List<MediaController> controllers=manager.getActiveSessions(component);sessionAccessReady=true;
                if(!watching){manager.addOnActiveSessionsChangedListener(sessionsChanged,component,handler);watching=true;}
                ArrayList<Session> result=new ArrayList<>();
                for(MediaController controller:controllers){
                    // Exact official package identity; never accept a cloned/lookalike package by prefix.
                    if(controller!=null&&providerForPackage(controller.getPackageName())!=null)result.add(new AndroidSession(controller,handler));
                }
                cancelReconnect();
                return result;
            }catch(RuntimeException unavailable){sessionAccessReady=false;requestReconnect();throw unavailable;}
        }
        @Override public Intent launchIntent(String packageName) {
            return context.getPackageManager().getLaunchIntentForPackage(packageName);
        }
    }

    private static final class AndroidSession implements Session {
        private final MediaController controller;
        private final Handler handler;
        private MediaController.Callback callback;
        AndroidSession(MediaController controller, Handler handler) { this.controller = controller; this.handler = handler; }
        @Override public String packageName() { return controller.getPackageName(); }
        @Override public Object token() { return controller.getSessionToken(); }
        @Override public MediaMetadata metadata() { return controller.getMetadata(); }
        @Override public PlaybackState playback() { return controller.getPlaybackState(); }
        @Override public void subscribe(Runnable changed, Runnable destroyed) {
            callback = new MediaController.Callback() {
                @Override public void onMetadataChanged(MediaMetadata metadata) { changed.run(); }
                @Override public void onPlaybackStateChanged(PlaybackState state) { changed.run(); }
                @Override public void onSessionDestroyed() { destroyed.run(); }
            };
            controller.registerCallback(callback, handler);
        }
        @Override public void unsubscribe() {
            if (callback != null) { controller.unregisterCallback(callback); callback = null; }
        }
        @Override public void play() { controller.getTransportControls().play(); }
        @Override public void pause() { controller.getTransportControls().pause(); }
        @Override public void previous() { controller.getTransportControls().skipToPrevious(); }
        @Override public void next() { controller.getTransportControls().skipToNext(); }
        @Override public void seekTo(long position) { controller.getTransportControls().seekTo(position); }
        @Override public boolean toggle() {
            long now = SystemClock.uptimeMillis();
            boolean down = controller.dispatchMediaButtonEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN,
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 0));
            boolean up = controller.dispatchMediaButtonEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP,
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 0));
            return down && up;
        }
    }
}
