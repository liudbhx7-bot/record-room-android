package com.recordroom.vinyl;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.GridLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.TextUtils;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** A small native player. The service is the single source of playback state. */
public final class MainActivity extends Activity {
    private static final int PICK_AUDIO = 101, PICK_ART = 102, NETEASE_WEB_LOGIN = 103, QQ_WEB_LOGIN=104, QISHUI_LOGIN=105, APPLE_LOGIN=106, PICK_LYRICS=107;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ArrayList<Track> library = new ArrayList<>();
    private final ArrayList<Track> local = new ArrayList<>();
    private ThemeStore themes;
    private LibraryStore collections;
    private NeedleSound needleSound;
    private ShareStudio shareStudio;
    private PlaylistDraftDialog activeDraft;
    private Dialog artistDialog, settingsDialog;
    private FrameLayout artistPage;
    private ExternalMusicBridge externalMusic;
    private Dialog sourcesDialog;
    private MusicSourcesPage sourcesPage;
    private TextView externalQueueTitle,externalQueueArtist;
    private ImageView externalQueueArtwork;
    private NeteasePage neteasePage;
    private final LinkedHashMap<String,ProviderLibraryPage> providerPages=new LinkedHashMap<>();
    private final LinkedHashMap<String,String> providerAccounts=new LinkedHashMap<>();
    private Dialog neteaseDialog;
    private final LinkedHashMap<Dialog,NeteasePage> neteasePages=new LinkedHashMap<>();
    private String neteaseAccountId="";
    private boolean refreshNeteaseAfterRestore;
    private final Runnable onlineLogoutListener=this::onOnlineLoggedOut;
    private PlayerControls.Icon likeButton, queueMode;
    private QueueListView queueRows;
    private TextView queueCount;
    private CoverView queueCover;
    private String queueSignature="";
    private final ArrayList<java.lang.ref.WeakReference<PlayerControls.Icon>> pagePlayButtons=new ArrayList<>();
    private boolean shelfGrid;
    private int shelfSort;
    private PlayerService player;
    private SettingsPage settingsPage;
    private AggregateSearchPage aggregatePage;
    private boolean bindingRegistered, libraryReady, foreground, destroyed;
    private FrameLayout root;
    private LinearLayout details, hardwareRow;
    private Button toolEntry;
    private PlayerToolDrawer toolDrawer;
    private Dialog lyricsDialog;
    private String lyricsTrack = "", pendingLyricsTrack = "";
    private final ArrayList<Dialog> dialogs = new ArrayList<>();
    private final LinkedHashMap<Dialog, Runnable> pageRestorers = new LinkedHashMap<>();
    private final LinkedHashMap<Dialog, java.util.function.Supplier<Bundle>> pageSnapshots = new LinkedHashMap<>();
    private ArrayList<Bundle> pendingPages;
    private int searchLimit = 80;
    private String searchQuery = "";
    private TurntableView turntable;
    private TextView title, artist, status, subtitle;
    private PlayerControls.Hardware play, previous, next;
    private PlaybackTimeline seek, queueTimeline;
    private int ink = Color.WHITE, mutedInk = 0xffe0dce5;
    private Bitmap externalArtworkSeen;
    private int externalArtworkGeneration;
    private long externalArtworkHash;
    private String externalArtworkUriSignature="";
    private boolean externalArtworkLoading;
    private long externalArtworkRetryAt;
    private String visibleSleeveKey = "";
    private String shownId = "", shownAlbumKey = "", artSignature = "", artAlbum = "";
    private String albumHighlightedId;
    private int artToken;
    private Bitmap currentArtwork;
    private android.graphics.drawable.Drawable playerSurface;
    private int playerSurfaceColor;
    private Dialog shelfDialog, appearanceDialog, albumDialog, queueDialog;
    private LinearLayout shelfRows;
    private EditText shelfSearch;
    private AlbumStackView albumStack;
    private ScrollView shelfSongScroll;
    private LibraryBrowserList shelfResults;
    private final Runnable delayedShelfSearch=()->{if(shelfDialog!=null&&shelfDialog.isShowing()){renderShelf();}};
    private boolean shelfSongs, wasPlaying;
    private int fullPages;
    private AlbumSongAdapter albumAdapter;
    private final ArrayList<TextView> albumRows = new ArrayList<>();
    private final ArrayList<Track> albumSongs = new ArrayList<>();
    private PlayerControls.Icon albumDockPlay, albumDockCover;
    private final LinkedHashMap<Dialog,AlbumPageState> albumPages=new LinkedHashMap<>();
    private static final class AlbumPageState {
        final ArrayList<Track> songs; final AlbumSongAdapter adapter;
        final PlayerControls.Icon play,cover;
        AlbumPageState(ArrayList<Track> songs,AlbumSongAdapter adapter,PlayerControls.Icon play,PlayerControls.Icon cover){this.songs=songs;this.adapter=adapter;this.play=play;this.cover=cover;}
    }
    private final Runnable hideChrome = () -> { if (toolDrawer != null && isPlaybackPlaying()) toolDrawer.dismiss(); };
    private final Runnable listener = this::refresh;
    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            if (destroyed) return;
            player = ((PlayerService.LocalBinder) binder).getService();
            if(foreground)player.addListener(listener);if(usingExternal())player.pausePlayback();
            initializeQueue();
            refresh(); restorePendingPages();
        }
        @Override public void onServiceDisconnected(ComponentName name) { player = null; refresh(); }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        themes = new ThemeStore(this);
        externalMusic=new ExternalMusicBridge(this,sourceState->{if(sourcesPage!=null)sourcesPage.update(sourceState);refresh();});
        String selected=getPreferences(MODE_PRIVATE).getString("external_music_source","");
        if(ExternalMusicBridge.providerForId(selected)!=null)externalMusic.selectProvider(selected);
        collections = new LibraryStore(this);
        OnlineMusic.addLogoutListener(onlineLogoutListener);
        needleSound = new NeedleSound(this);
        if(state!=null&&state.getBundle("share_session")!=null)shareStudio=ShareStudio.restore(this,state.getBundle("share_session"));
        if(state!=null&&state.getBundle("draft_session")!=null)activeDraft=PlaylistDraftDialog.restore(this,collections,library,state.getBundle("draft_session"),this::playlistSaved);
        if(state!=null){pendingLyricsTrack=state.getString("pending_lyrics_track","");artAlbum=state.getString("pending_art_album","");pendingPages=state.getParcelableArrayList("navigation_pages");shelfSongs=state.getBoolean("shelf_songs");}
        shelfGrid = getPreferences(MODE_PRIVATE).getBoolean("shelf_grid", false);
        shelfSort = getPreferences(MODE_PRIVATE).getInt("shelf_sort", 0);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        buildMain();
        bindingRegistered = bindService(new Intent(this, PlayerService.class), connection, Context.BIND_AUTO_CREATE);
        io.execute(() -> {
            List<Track> demos = DemoCatalog.load(this);
            List<Track> imports = LocalMusicImporter.loadLibrary(this);
            List<Track> online = OnlineCatalog.load(this);
            String onlineAccount = OnlineCatalog.accountId(this);
            List<Track> qqTracks=OnlineCatalog.load(this,"qq"),appleTracks=OnlineCatalog.load(this,"applemusic");
            String qqAccount=OnlineCatalog.accountId(this,"qq"),appleAccount=OnlineCatalog.accountId(this,"applemusic");
            handler.post(() -> {
                if (destroyed) return;
                library.clear(); library.addAll(demos); local.clear(); local.addAll(imports); library.addAll(imports);
                library.addAll(online);library.addAll(qqTracks);library.addAll(appleTracks); neteaseAccountId=onlineAccount;providerAccounts.put("qq",qqAccount);providerAccounts.put("applemusic",appleAccount);
                libraryReady = true; initializeQueue(); refresh(); restorePendingPages(); if (shelfDialog != null && shelfDialog.isShowing()) renderShelf();
            });
        });
    }
    @Override protected void onSaveInstanceState(Bundle out){
        if(shareStudio!=null&&(shareStudio.isVisible()||shareStudio.hasPendingSave()))out.putBundle("share_session",shareStudio.snapshot());
        if(activeDraft!=null)out.putBundle("draft_session",activeDraft.snapshot());
        out.putString("pending_lyrics_track",pendingLyricsTrack);out.putString("pending_art_album",artAlbum);out.putBoolean("shelf_songs",shelfSongs);
        ArrayList<Bundle> pages=new ArrayList<>();
        for(Dialog d:new ArrayList<>(dialogs)){java.util.function.Supplier<Bundle> snapshot=pageSnapshots.get(d);if(d.isShowing()&&snapshot!=null){Bundle b=snapshot.get();b.putBundle("scroll",ViewScrollState.capture(d.getWindow().getDecorView()));pages.add(b);}}
        out.putParcelableArrayList("navigation_pages",pendingPages!=null?pendingPages:pages);super.onSaveInstanceState(out);
    }
    private Bundle pageState(String type){Bundle b=new Bundle();b.putString("type",type);return b;}
    private ArrayList<String> trackIds(List<Track> tracks){ArrayList<String> ids=new ArrayList<>();for(Track t:tracks)ids.add(LibraryStore.identity(t));return ids;}
    private ArrayList<Track> resolveTracks(ArrayList<String> ids){
        LinkedHashMap<String,Track> index=new LinkedHashMap<>();for(Track t:library)index.put(LibraryStore.identity(t),t);
        for(LibraryStore.Playlist p:collections.playlists())for(Track t:p.tracks)index.put(LibraryStore.identity(t),t);
        if(player!=null)for(Track t:player.getQueue())index.put(LibraryStore.identity(t),t);
        ArrayList<Track> result=new ArrayList<>();if(ids!=null)for(String id:ids){Track t=index.get(id);if(t!=null)result.add(t);}return latestLocalTracks(result);
    }
    private void restorePendingPages(){if(!libraryReady||player==null||pendingPages==null)return;ArrayList<Bundle> pages=pendingPages;pendingPages=null;for(Bundle b:pages)restorePage(b);}
    private void restorePage(Bundle b){
        Dialog restored=null;
        switch(b.getString("type","")){
            case "player_tools":showPlayerTools();break;
            case "track_lyrics":if (b.getString("track","").equals(lyricsIdentity())) showLyrics();break;
            case "aggregate_search":showAggregateSearch(b.getBundle("search"));break;
            case "shelf":shelfSongs=b.getBoolean("songs");showShelf();shelfSearch.setVisibility(b.getInt("search_visibility",View.GONE));shelfSearch.setText(b.getString("query",""));searchLimit=b.getInt("limit",80);renderShelf();restored=shelfDialog;break;
            case "album":
                ArrayList<String> requestedIds=b.getStringArrayList("track_ids");
                if(requestedIds==null||requestedIds.isEmpty())break;
                ArrayList<Track> tracks=resolveTracks(requestedIds);
                if(tracks.size()!=requestedIds.size()){
                    boolean remote=b.getBoolean("remote");for(String id:requestedIds)if(id.startsWith("网易云音乐\u001f"))remote=true;
                    toast(remote?"在线曲目缓存不完整，请从对应音乐来源重新打开歌单或专辑":"部分曲目暂时无法恢复，请从资料库重新打开");break;
                }
                showAlbum(tracks,b.getString("collection"),null,0,findPlaylist(b.getString("playlist_id","")));restored=albumDialog;break;
            case "queue":showQueue();restored=queueDialog;break;
            case "share":if(shareStudio==null)shareStudio=ShareStudio.restore(this,b.getBundle("share"));if(shareStudio!=null)restored=registerShare(shareStudio);break;
            case "playlist_draft":if(activeDraft==null)activeDraft=PlaylistDraftDialog.restore(this,collections,library,b.getBundle("draft"),this::playlistSaved);restored=registerDraft(activeDraft);break;
            case "appearance":showAppearance();restored=appearanceDialog;break;
            case "settings":showSettings();restored=settingsDialog;break;
            case "sources":showMusicSources();restored=sourcesDialog;break;
            case "provider":showProvider(b.getString("provider_id","qq"),b.getBundle("panel"));break;
            case "netease":showNetease(b.getBundle("panel"),null,null);restored=neteaseDialog;break;
            case "artist":try{
                if(b.containsKey("catalog_artist_id"))showCatalogArtist(b.getString("catalog_provider",""),new MusicProvider.Artist(b.getString("catalog_artist_id",""),b.getString("artist_name",""),b.getString("artist_description",""),b.getString("artist_avatar",""),Collections.emptyList(),Collections.emptyList()));
                else{Track seed=Track.fromJson(new org.json.JSONObject(b.getString("seed","{}")));showArtist(seed,b.getString("artist_name",seed.artist));}
                restored=artistDialog;ArtistPage.restoreState(artistPage,b.getBundle("chapters"));
            }catch(Exception ignored){}break;
        }
        if(restored!=null&&restored.getWindow()!=null)ViewScrollState.restore(restored.getWindow().getDecorView(),b.getBundle("scroll"));
    }
    @Override public void onResume() { super.onResume(); foreground = true;if(player!=null)player.addListener(listener);externalMusic.start(); if (turntable != null) turntable.setActive(fullPages == 0); refresh();scheduleChromeHide(); }
    @Override public void onPause() { foreground = false;if(player!=null)player.removeListener(listener);handler.removeCallbacks(hideChrome);externalMusic.stop(); if (turntable != null) turntable.setActive(false); super.onPause(); }
    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        ArrayList<Runnable> restore = new ArrayList<>();
        for (Dialog dialog : new ArrayList<>(dialogs)) {
            if (!dialog.isShowing()) continue;
            Runnable reopen = pageRestorers.get(dialog);
            java.util.function.Supplier<Bundle> snapshot=pageSnapshots.get(dialog);
            if(snapshot!=null){Bundle saved=snapshot.get();saved.putBundle("scroll",ViewScrollState.capture(dialog.getWindow().getDecorView()));reopen=()->restorePage(saved);}
            if (snapshot==null && dialog == shelfDialog) {
                String query = shelfSearch.getText().toString();
                int visibility = shelfSearch.getVisibility();
                int scrollY = shelfSongScroll.getScrollY();
                int loaded = searchLimit;
                float stackPosition=albumStack==null?0:albumStack.getScrollPosition();
                reopen = () -> {
                    showShelf();
                    shelfSearch.setVisibility(visibility);
                    shelfSearch.setText(query);
                    searchLimit = loaded; renderShelf();
                    albumStack.setScrollPosition(stackPosition);
                    ScrollView restoredScroll = shelfSongScroll;
                    restoredScroll.post(() -> restoredScroll.scrollTo(0, scrollY));
                };
            }
            if (reopen != null) restore.add(reopen);
        }
        TurntableView previousTurntable=turntable;
        boolean rebuildingShare=shareStudio!=null&&shareStudio.isVisible();
        if (turntable != null) turntable.setActive(false);
        dismissDialogs();
        if(rebuildingShare)shareStudio=null;
        activeDraft=null;
        buildMain(); turntable.restorePlaybackPoseFrom(previousTurntable); applyAppearance(); refresh();
        for (Runnable reopen : restore) reopen.run();
    }
    @Override public void onDestroy() {
        destroyed = true;if(externalMusic!=null)externalMusic.close(); artToken++;OnlineMusic.removeLogoutListener(onlineLogoutListener);
        if (turntable != null) turntable.setActive(false);
        dismissDialogs();
        if (needleSound != null) needleSound.close();
        if (shareStudio != null) shareStudio.close();
        if (player != null) player.removeListener(listener);
        if (bindingRegistered) { unbindService(connection); bindingRegistered = false; }
        io.shutdown(); handler.removeCallbacksAndMessages(null); super.onDestroy();
    }
    private void dismissDialogs() {
        for (Dialog dialog : new ArrayList<>(dialogs)) dialog.dismiss();
        neteasePages.clear();providerPages.clear();albumPages.clear();sourcesDialog=null;sourcesPage=null;externalQueueTitle=null;externalQueueArtist=null;externalQueueArtwork=null;
        dialogs.clear(); pageRestorers.clear(); pageSnapshots.clear(); shelfDialog = null; appearanceDialog = null; albumDialog = null; queueDialog = null; settingsDialog = null; artistDialog = null; neteaseDialog=null;neteasePage=null; shelfRows = null; shelfSearch = null; albumStack = null; shelfSongScroll = null; shelfResults=null;handler.removeCallbacks(delayedShelfSearch);fullPages = 0;
        queueTimeline = null; queueRows = null; queueCover = null; queueCount = null; queueMode = null; pagePlayButtons.clear();
        albumAdapter=null; albumRows.clear(); albumSongs.clear(); albumDockPlay = null; albumDockCover = null;
    }
    private void trackDialog(Dialog dialog) {
        dialogs.add(dialog);
        dialog.setOnDismissListener(d -> {dialogs.remove(dialog);pageSnapshots.remove(dialog);pageRestorers.remove(dialog);});
    }
    private void initializeQueue() {
        if(player!=null)player.removeProviderTracks("qishui");
        if (player != null && libraryReady && player.getQueueSize()==0 && !library.isEmpty()) player.setQueue(new ArrayList<>(library), 0, false);
        else if(player!=null&&libraryReady)player.refreshMetadata(library);
    }
    private void buildMain() {
        playerSurface=null;
        root = new FrameLayout(this); root.setPadding(0, dp(12), 0, dp(12));
        root.setBackgroundColor(DesignSystem.deckColor(themes,albumKey()));
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(insets.getSystemWindowInsetLeft(), insets.getStableInsetTop(), insets.getSystemWindowInsetRight(), Math.max(dp(8), insets.getStableInsetBottom()));
            return insets;
        });
        setContentView(root);
        LinearLayout content = column(); content.setPadding(dp(24), dp(14), dp(24), dp(12)); content.setClipChildren(false); content.setClipToPadding(false);
        root.addView(content, new FrameLayout.LayoutParams(-1, -1));

        turntable = new TurntableView(this);turntable.updateArtworkInPlace(currentArtwork); turntable.setContentDescription("唱机，点按打开播放工具；拖动唱臂定位，落针播放，抬针暂停");
        turntable.setOnClickListener(v -> showPlayerTools());
        turntable.setNeedleListener(new TurntableView.NeedleListener() {
            public void onNeedleSeek(float progress) { seekPlayback((long)(playbackDuration()*progress)); }
            public void onNeedlePlay() { if(usingExternal()){if(!externalMusic.play())toast(externalMusic.getState().statusMessage);refresh();}else if(player!=null){playNeedle();player.startPlayback();} }
            public void onNeedlePause() { if(usingExternal()){externalMusic.pause();refresh();}else if(player!=null)player.pausePlayback(); }
        });
        details = column();
        title = text("唱片机", 24, ink); title.setTypeface(null, Typeface.BOLD); title.setMaxLines(2); title.setMinHeight(dp(38));title.setGravity(Gravity.START|Gravity.CENTER_VERTICAL); title.setEllipsize(TextUtils.TruncateAt.END); title.setPadding(0, 0, 0, 0);
        artist = text("正在准备唱片…", 14, mutedInk); artist.setMinHeight(dp(32));artist.setGravity(Gravity.START|Gravity.CENTER_VERTICAL); artist.setMaxLines(1); artist.setEllipsize(TextUtils.TruncateAt.END); artist.setPadding(0, 0, 0, 0);
        artist.setOnClickListener(v -> { if(usingExternal())openOfficial(externalMusic.getState().selectedProviderId);else if(player!=null&&player.getCurrent()!=null)showArtist(player.getCurrent()); });
        title.setOnClickListener(v -> showCurrentAlbum());
        LinearLayout captionRow = row();
        captionRow.setGravity(Gravity.CENTER_VERTICAL);
        captionRow.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        toolEntry = quietButton("工具  ⌃","打开播放工具",this::showPlayerTools);
        toolEntry.setTextSize(14);
        toolEntry.setMinHeight(dp(48));
        toolEntry.setBackground(PlayerControls.glassCapsule(this));
        captionRow.addView(toolEntry, new LinearLayout.LayoutParams(-2,-2));
        details.addView(captionRow);
        LinearLayout.LayoutParams artistParams = new LinearLayout.LayoutParams(-1, -2); artistParams.topMargin = dp(2); details.addView(artist, artistParams);
        subtitle = text("", 10, mutedInk); subtitle.setLetterSpacing(.12f); subtitle.setGravity(Gravity.START);
        status = text("", 12, ink); status.setVisibility(View.GONE); details.addView(status);
        seek = new PlaybackTimeline(this,false,this::seekPlayback);
        details.addView(seek,new LinearLayout.LayoutParams(-1,-2));
        hardwareRow = row(); hardwareRow.setGravity(Gravity.CENTER_VERTICAL); hardwareRow.setPadding(0, dp(6), 0, 0);
        play = hardwareButton("play", "播放", this::togglePlayback);
        previous = hardwareButton("previous", "上一首", () -> { previousTrack(); });
        next = hardwareButton("next", "下一首", () -> { nextTrack(); });
        hardwareRow.addView(play, new LinearLayout.LayoutParams(dp(58), dp(66))); hardwareRow.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        hardwareRow.addView(previous, new LinearLayout.LayoutParams(dp(58), dp(66))); LinearLayout.LayoutParams nextParams = new LinearLayout.LayoutParams(dp(58), dp(66)); nextParams.leftMargin = dp(14); hardwareRow.addView(next, nextParams); details.addView(hardwareRow);
        Configuration config = getResources().getConfiguration();
        boolean wide = (config.orientation == Configuration.ORIENTATION_LANDSCAPE && config.screenWidthDp >= 480) || config.screenWidthDp >= 600;
        if (wide) {
            hardwareRow.setPadding(0,dp(8),0,0);
            content.setOrientation(LinearLayout.HORIZONTAL); content.setGravity(Gravity.CENTER_VERTICAL); content.addView(turntable, new LinearLayout.LayoutParams(0, -1, config.screenWidthDp < 600 ? 1 : 1.2f));
            details.setPadding(dp(20), dp(16), 0, dp(12));
            ScrollView detailScroll = new ScrollView(this); detailScroll.setFillViewport(false); detailScroll.addView(details, new ScrollView.LayoutParams(-1, -2));
            content.addView(detailScroll, new LinearLayout.LayoutParams(0, -2, 1));
        } else {
            root.removeView(content);
            turntable.setReferenceLayout(true);
            root.addView(turntable, new FrameLayout.LayoutParams(-1,-1));
            details.setPadding(0,0,0,0);
            FrameLayout.LayoutParams caption=new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM);
            int bottom=dp(Math.max(12,Math.min(42,config.screenHeightDp*.043f)));
            caption.setMargins(dp(26),0,dp(26),bottom);root.addView(details,caption);
            // Include font scaling, multi-line errors and system insets in the reserved deck space.
            details.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->turntable.setCaptionInsetPx(b-t+bottom));
        }
        root.requestApplyInsets();
    }
    private PlayerControls.Icon iconButton(String name, String description, Runnable action) {
        PlayerControls.Icon v = new PlayerControls.Icon(this, name); v.setContentDescription(description); v.setLayoutParams(new LinearLayout.LayoutParams(dp(48), dp(48))); v.setOnClickListener(w -> action.run()); return v;
    }
    private PlayerControls.Hardware hardwareButton(String name, String description, Runnable action) {
        PlayerControls.Hardware v = new PlayerControls.Hardware(this, name); v.setContentDescription(description); v.setOnClickListener(w -> action.run()); return v;
    }
    private void showPlayerTools() {
        if (toolDrawer != null && toolDrawer.isShowing()) return;
        PlayerToolDrawer drawer = new PlayerToolDrawer(this, this::showAudioQuality, this::showTrackMenu, this::scheduleChromeHide);
        drawer.addTool("search","搜索",() -> showCatalogSearch(""));
        drawer.addTool("albums","资料库",this::showShelf);
        drawer.addTool("queue","队列",this::showQueue);
        drawer.addTool("lyrics","歌词",this::showLyrics);
        drawer.addTool("disc","外观",this::showAppearance);
        drawer.addTool("settings","设置",this::showSettings);
        drawer.tint(playerSurfaceColor,ink,themes.deckTint(albumKey()));
        toolDrawer = drawer;
        dialogs.add(drawer);
        pageSnapshots.put(drawer,() -> pageState("player_tools"));
        drawer.setOnDismissListener(d -> {
            dialogs.remove(drawer);
            pageSnapshots.remove(drawer);
            if (toolDrawer == drawer) {
                toolDrawer = null;
                handler.removeCallbacks(hideChrome);
            }
        });
        drawer.show();
        scheduleChromeHide();
    }

    private void scheduleChromeHide() {
        handler.removeCallbacks(hideChrome);
        if (toolDrawer == null || !toolDrawer.isShowing() || !foreground || !themes.autoHide() || !isPlaybackPlaying()) return;
        android.view.accessibility.AccessibilityManager accessibility =
            (android.view.accessibility.AccessibilityManager)getSystemService(ACCESSIBILITY_SERVICE);
        if (accessibility != null && accessibility.isTouchExplorationEnabled()) return;
        int timeout = 6000;
        if (android.os.Build.VERSION.SDK_INT >= 29 && accessibility != null) {
            timeout = accessibility.getRecommendedTimeoutMillis(timeout,android.view.accessibility.AccessibilityManager.FLAG_CONTENT_CONTROLS);
        }
        handler.postDelayed(hideChrome, timeout);
    }
    private String lyricsIdentity() {
        if (usingExternal()) {
            ExternalMusicBridge.State state = externalMusic.getState();
            return state.connected && !state.title.isEmpty() ? externalTrackKey(state) : "";
        }
        Track current = player == null ? null : player.getCurrent();
        return current == null ? "" : LibraryStore.identity(current);
    }

    private void showLyrics() {
        String id = lyricsIdentity();
        if (id.isEmpty()) { toast("先选择一首歌曲"); return; }
        if (lyricsDialog != null && lyricsDialog.isShowing()) lyricsDialog.dismiss();
        LinearLayout body = column();
        String song = usingExternal() ? externalMusic.getState().title : player.getCurrent().title;
        TextView lines = text("正在读取歌词…",17,DesignSystem.INK);
        lines.setTextIsSelectable(true);
        lines.setLineSpacing(dp(5),1.12f);
        lines.setPadding(dp(4),dp(8),dp(4),dp(16));
        body.addView(lines);
        Dialog dialog = sheet(song+" · 歌词",body,false);
        lyricsDialog = dialog;
        lyricsTrack = id;
        pageSnapshots.put(dialog,() -> { Bundle b=pageState("track_lyrics"); b.putString("track",id); return b; });
        body.addView(actionButton("选择本地歌词文件",() -> {
            Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE);
            pick.setType("*/*");
            pendingLyricsTrack = id;
            startActivityForResult(pick,PICK_LYRICS);
        }));
        String embedded = usingExternal() || player==null ? "" : player.currentEmbeddedLyrics();
        io.execute(() -> {
            String saved = TrackLyrics.load(this,id);
            String lyrics = saved.isEmpty() ? embedded : saved;
            handler.post(() -> {
                if (destroyed || !dialog.isShowing()) return;
                lines.setText(lyrics.isEmpty() ? "这首歌暂无可显示的歌词，可选择 LRC 或 TXT 文件。" : lyrics);
                ScrollView scroll = (ScrollView)body.getParent();
                scroll.getLayoutParams().height = Math.min((int)(root.getHeight()*.58f),
                    measureBody(body,Math.max(dp(80),root.getWidth()-dp(44))));
                scroll.requestLayout();
            });
        });
    }
    private void showCurrentAlbum() { if(usingExternal()){openOfficial(externalMusic.getState().selectedProviderId);return;} Track current = player == null ? null : player.getCurrent(); if (current == null) { showShelf(); return; } if(OnlineMusic.isRemote(current)&&!current.albumId.isEmpty()){if(OnlineMusic.isNetease(current))showNetease(null,current.albumId,null);else openProviderAlbum(current);return;} ArrayList<Track> songs = new ArrayList<>(); for (Track t : library) if (t.albumKey().equals(current.albumKey())) songs.add(t); if (songs.isEmpty()) songs.add(current); sortAlbum(songs); showAlbum(songs); }
    private void refresh() {
        if (destroyed || title == null) return;
        if (lyricsDialog != null && lyricsDialog.isShowing() && !lyricsTrack.equals(lyricsIdentity())) lyricsDialog.dismiss();
        if(usingExternal()){refreshExternal();return;}
        turntable.setNeedleSeekingEnabled(player!=null&&player.canSeek()&&playbackDuration()>0);play.setEnabled(true);previous.setEnabled(true);next.setEnabled(true);
        Track track = player == null ? null : player.getCurrent();
        if (track != null) {
            title.setText(track.title); artist.setText(track.artist); subtitle.setText(track.album);
            if (!track.id.equals(shownId)) { shownId = track.id;shownAlbumKey=track.albumKey();applyAppearance(); }
            loadArtwork(track);
        } else {
            // Clearing the queue also invalidates any cover decode still in flight.
            // Otherwise its callback can restore an album after the player is empty.
            artToken++; artSignature = ""; shownId = ""; shownAlbumKey = "";
            currentArtwork = null; visibleSleeveKey = ""; turntable.setArtwork(null);
            if (albumDockCover != null) albumDockCover.setArtwork(null);
            title.setText("唱片机"); artist.setText("选择一张唱片开始播放"); subtitle.setText("");
            updateTimelines("",-1,-1,false);
            turntable.setProgress(0);
            if (player == null) { status.setText(""); status.setVisibility(View.GONE); }
            applyAppearance();
        }
        boolean playing = player != null && player.isPlaying();ArtistPage.updatePlayback(artistPage,playing,currentArtwork,player!=null);
        for(java.lang.ref.WeakReference<PlayerControls.Icon> ref:pagePlayButtons){PlayerControls.Icon icon=ref.get();if(icon!=null){icon.setSymbol(playing?"pause":"play");icon.setEnabled(true);}}
        if(queueDialog!=null&&queueDialog.isShowing()&&player!=null){String signature=(track==null?"":track.id)+"|"+player.getIndex()+"|"+player.getQueueRevision()+"|"+player.getPlayMode();if(!signature.equals(queueSignature)){queueSignature=signature;renderQueue();}}
        if(likeButton!=null)likeButton.setSymbol(collections.liked(track)?"heartfilled":"heart");
        turntable.setPlaying(playing); turntable.setActive(foreground && fullPages == 0);
        play.setSymbol(playing?"pause":"play");play.setContentDescription(playing ? "暂停" : "播放"); if (albumDockPlay != null) albumDockPlay.setSymbol(playing ? "pause" : "play");
        if (playing && !wasPlaying) scheduleChromeHide(); wasPlaying = playing;
        String currentId = track == null ? "" : track.id;
        for(AlbumPageState page:albumPages.values()){page.adapter.setCurrent(track);page.play.setSymbol(playing?"pause":"play");}
        if (player != null) {
            long duration = player.getDurationMs(), position = player.getPositionMs();
            if(track!=null)updateTimelines(currentId,position,duration,player.canSeek());
            turntable.setProgress(duration > 0 ? Math.min(1f, position / (float) duration) : 0);
            String error = player.getError();
            String message = error != null && !error.isEmpty() ? error : player.isPreparing() ? "唱针就位中…" : "";
            status.setText(message); status.setVisibility(message.isEmpty() ? View.GONE : View.VISIBLE);
        }
    }
    private String albumKey() { if(usingExternal()){ExternalMusicBridge.State state=externalMusic.getState();return externalTrackKey(state);} Track t = player == null ? null : player.getCurrent(); return t == null ? "" : t.albumKey(); }
    private void applyAppearance() {applyAppearance(false);}
    private void applyAppearance(boolean animateColor) {
        int color = DesignSystem.deckColor(themes,albumKey());
        DesignSystem.setAlbumTheme(this,albumKey(),stageColor(),themes.recordTint(albumKey()));
        ArtistPage.refreshTheme(artistPage);
        if(aggregatePage!=null)aggregatePage.refreshTheme();
        styleAmbientWindow(settingsDialog);styleAmbientWindow(sourcesDialog);
        for(ProviderLibraryPage page:providerPages.values())page.refreshTheme();
        for(NeteasePage page:neteasePages.values())page.refreshTheme();
        if(settingsPage!=null){Track current=player==null?null:player.getCurrent();settingsPage.update(currentArtwork,usingExternal()?externalMusic.getState().title:current==null?"":current.title,usingExternal()?externalMusic.getState().artist:current==null?"":current.artist);}
        if(playerSurface==null||playerSurfaceColor!=color){
            android.graphics.drawable.Drawable nextSurface=DesignSystem.surface(this,color);
            if(animateColor&&themes.motion()&&playerSurface!=null&&android.animation.ValueAnimator.areAnimatorsEnabled()){
                android.graphics.drawable.TransitionDrawable transition=new android.graphics.drawable.TransitionDrawable(new android.graphics.drawable.Drawable[]{playerSurface,nextSurface});transition.setCrossFadeEnabled(true);root.setBackground(transition);transition.startTransition(420);FrameLayout target=root;handler.postDelayed(()->{if(!destroyed&&target==root&&target.getBackground()==transition)target.setBackground(nextSurface);},440);
            }else root.setBackground(nextSurface);
            playerSurface=nextSurface;playerSurfaceColor=color;
        }
        turntable.setStyle(themes.disc(albumKey()));turntable.setRecordTint(themes.deckTint(albumKey()));
        turntable.setMotionEnabled(themes.motion()); turntable.setLighting(themes.lighting());
        if(themes.keepAwake())getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        ink = readableInk(color); mutedInk = blend(ink,color,.20f);
        title.setTextColor(ink); artist.setTextColor(mutedInk); subtitle.setTextColor(mutedInk); status.setTextColor(ink);
        toolEntry.setTextColor(ink);
        if(toolDrawer!=null && toolDrawer.isShowing()) toolDrawer.tint(color,ink,themes.deckTint(albumKey()));
        play.setInk(ink); previous.setInk(ink); next.setInk(ink);
        if(queueDialog!=null&&queueDialog.isShowing()){retintQueue(queueDialog.getWindow().getDecorView());if(queueRows!=null)queueRows.setInkColor(shelfInk());}
        styleProgressControls();
        getWindow().setStatusBarColor(color);getWindow().setNavigationBarColor(color);
        getWindow().getDecorView().setSystemUiVisibility(ink != Color.WHITE ? View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR : 0);
    }
    private void retintQueue(View view){if(view instanceof TextView)((TextView)view).setTextColor(shelfInk());if(view instanceof PlayerControls.Icon)((PlayerControls.Icon)view).setInk(shelfInk());if(view instanceof android.view.ViewGroup)for(int i=0;i<((android.view.ViewGroup)view).getChildCount();i++)retintQueue(((android.view.ViewGroup)view).getChildAt(i));}
    private void styleProgressControls() {
        seek.ink(ink,mutedInk);
        if(queueTimeline!=null){
            int foreground=usingExternal()?DesignSystem.INK:shelfInk();
            queueTimeline.ink(foreground,foreground);
        }
    }
    private void updateTimelines(String identity,long position,long duration,boolean canSeek) {
        seek.update(identity,position,duration,canSeek);
        if(queueDialog!=null&&queueDialog.isShowing()&&queueTimeline!=null)
            queueTimeline.update(identity,position,duration,canSeek);
    }
    private void loadArtwork(Track t) {
        String override = themes.artwork(t.albumKey()); String uri = override != null && !override.isEmpty() ? override : t.artworkUri;
        String signature = t.albumKey() + "|" + uri;
        if (signature.equals(artSignature)) return;
        artSignature = signature; int token = ++artToken;

        // Keep the old sleeve visible until the new cover is decoded for a smooth record change.
        io.execute(() -> { Bitmap image = LocalMusicImporter.loadArtwork(this, uri, 640);AlbumPalette palette=AlbumPalette.from(image); handler.post(() -> { if (!destroyed && token == artToken) { currentArtwork = image;ArtistPage.updatePlayback(artistPage,isPlaybackPlaying(),image,player!=null);themes.coverPalette(t.albumKey(),palette);showSleeve(image,"local:"+t.albumKey());applyAppearance(true);for(AlbumPageState page:albumPages.values())page.cover.setArtwork(image);if(shelfDialog!=null&&shelfDialog.isShowing()){shelfDialog.getWindow().getDecorView().invalidate();renderShelf();} } }); });
    }

    private void pickAudio() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT); intent.addCategory(Intent.CATEGORY_OPENABLE); intent.setType("*/*"); intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        toast("整轨专辑请同时选中 CUE 和对应音频文件");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION); startActivityForResult(intent, PICK_AUDIO);
    }
    private void showAudioQuality() {
        LinearLayout body=column();
        TextView report=text("",17,Color.WHITE);
        report.setLineSpacing(dp(2),1);report.setPadding(0,dp(12),0,dp(16));body.addView(report);
        if(!usingExternal() && player!=null && android.os.Build.VERSION.SDK_INT>=34){
            android.widget.Switch follow=new android.widget.Switch(this);
            follow.setText("USB 优先使用当前采样率");follow.setMinHeight(dp(48));
            follow.setTextColor(Color.WHITE);follow.setChecked(player.usbFollowsSource());body.addView(follow);
            follow.setOnCheckedChangeListener((v,enabled)->{if(player!=null)player.setUsbFollowsSource(enabled);});
        }
        Dialog dialog=sheet("音质与输出",body,false,true);
        Runnable refreshQuality=new Runnable(){public void run(){
            if(!dialog.isShowing()||destroyed)return;
            String value=usingExternal()?"官方 App 正在控制音频输出。媒体会话未报告文件规格、采样率或位深。":
                player==null?"播放歌曲后可查看音源与输出设备。":player.audioQuality();
            if(!value.contentEquals(report.getText())){
                android.text.SpannableString styled=new android.text.SpannableString(value);
                java.util.regex.Matcher labels=java.util.regex.Pattern.compile("(?m)^([^\n]+)\n(?=[^\n])").matcher(value);
                while(labels.find()){
                    int a=labels.start(1),b=labels.end(1);
                    styled.setSpan(new android.text.style.RelativeSizeSpan(.76f),a,b,0);
                    styled.setSpan(new android.text.style.ForegroundColorSpan(0xffd2caca),a,b,0);
                }
                report.setText(styled);
            }
            report.postDelayed(this,750);
        }};
        refreshQuality.run();
    }
    private void pickArtwork() {
        if(usingExternal()){toast("联动时封面由官方音乐 App 提供");return;}
        if (player == null || player.getCurrent() == null) { toast("先选择一张专辑"); return; }
        artAlbum = albumKey(); Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT); intent.addCategory(Intent.CATEGORY_OPENABLE); intent.setType("image/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION); startActivityForResult(intent, PICK_ART);
    }
    private void keepAccess(Uri uri, Intent data) {
        try { getContentResolver().takePersistableUriPermission(uri, data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (SecurityException ignored) { }
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == PICK_LYRICS) {
            String id = pendingLyricsTrack;
            pendingLyricsTrack = "";
            if (result == RESULT_OK && !id.isEmpty() && data != null && data.getData() != null) {
                Uri uri = data.getData();
                io.execute(() -> {
                    try {
                        TrackLyrics.importFile(this,id,uri);
                        handler.post(() -> {
                            if (destroyed) return;
                            if (id.equals(lyricsIdentity())) showLyrics();
                            else toast("歌词已保存到原曲目");
                        });
                    } catch (Exception ex) {
                        handler.post(() -> { if (!destroyed) toast("歌词读取失败，请选择 256 KB 以内的 LRC 或 TXT 文本"); });
                    }
                });
            }
            return;
        }
        if(aggregatePage!=null&&(request==QQ_WEB_LOGIN||request==APPLE_LOGIN||request==NETEASE_WEB_LOGIN))aggregatePage.refresh();
        if(request==QQ_WEB_LOGIN||request==QISHUI_LOGIN||request==APPLE_LOGIN){String providerId=request==QQ_WEB_LOGIN?"qq":request==QISHUI_LOGIN?"qishui":"applemusic";ProviderLibraryPage page=providerPages.get(providerId);if(page!=null)page.refreshAccount();return;}
        if(request==NETEASE_WEB_LOGIN){ProviderLibraryPage songsPage=providerPages.get("netease");if(songsPage!=null)songsPage.refreshAccount();if(neteasePage!=null)neteasePage.refreshAccount();else refreshNeteaseAfterRestore=true;return;}
        if(activeDraft!=null&&activeDraft.onActivityResult(request,result,data)){
            if(pendingPages!=null)for(Bundle page:pendingPages)if("playlist_draft".equals(page.getString("type")))page.putBundle("draft",activeDraft.snapshot());
            return;
        }
        if(shareStudio!=null&&shareStudio.onActivityResult(request,result,data)){
            // The picker can return before the asynchronously restored navigation stack exists.
            // Replace its saved session now so a consumed request cannot be resurrected later.
            if(pendingPages!=null)for(Bundle page:pendingPages)if("share".equals(page.getString("type")))page.putBundle("share",shareStudio.snapshot());
            if(!shareStudio.isVisible()&&!shareStudio.hasPendingSave()){shareStudio.close();shareStudio=null;}return;
        } if (result != RESULT_OK || data == null) {if(request==PICK_ART)artAlbum="";return;}
        if (request == PICK_ART && data.getData() != null) { if(artAlbum.isEmpty()){toast("没有找到要更换封面的专辑，请重新选择");return;} Uri uri = data.getData(); keepAccess(uri, data); themes.artwork(artAlbum, uri.toString()); artAlbum=""; artSignature = ""; refresh(); toast("专辑封面已保存"); return; }
        if (request != PICK_AUDIO) return;
        ArrayList<Uri> uris = new ArrayList<>(); ClipData clips = data.getClipData();
        if (clips != null) for (int i = 0; i < clips.getItemCount(); i++) uris.add(clips.getItemAt(i).getUri()); else if (data.getData() != null) uris.add(data.getData());
        for (Uri uri : uris) keepAccess(uri, data);
        toast("正在读取 " + uris.size() + " 个文件…");
        io.execute(() -> {
            LocalCueImport.Result batch = LocalCueImport.read(this, uris);
            ArrayList<Track> all;
            synchronized (LocalMusicImporter.class) {
                all = batch.merge(LocalMusicImporter.loadLibrary(this));
                LocalMusicImporter.saveLibrary(this, all);
            }
            handler.post(() -> {
                if (destroyed) return;
                ArrayList<Track> online=new ArrayList<>();for(Track t:library)if(OnlineMusic.isRemote(t))online.add(t);
                local.clear(); local.addAll(all); library.clear(); library.addAll(DemoCatalog.load(this)); library.addAll(all);library.addAll(online); libraryReady = true; initializeQueue();
                toast(batch.summary());
                if (shelfDialog != null && shelfDialog.isShowing()) renderShelf(); else showShelf();

            });
        });
    }
    private ArrayList<Track> latestLocalTracks(List<Track> tracks){
        LinkedHashMap<String,Track> latest=new LinkedHashMap<>();for(Track t:local)latest.put(LibraryStore.identity(t),t);
        ArrayList<Track> result=new ArrayList<>();for(Track old:tracks){Track fresh=latest.get(LibraryStore.identity(old));result.add(fresh!=null&&fresh.uri.equals(old.uri)?fresh:old);}return result;
    }
    private void openShelfAlbum(ArrayList<Track> songs,android.graphics.RectF origin,float tilt){
        if(songs.isEmpty())return;Track seed=songs.get(0);
        if(OnlineMusic.isRemote(seed)){
            if(seed.albumId.isEmpty()){toast("这首歌尚未返回专辑标识，请从在线曲库重新搜索后打开专辑");return;}
            openProviderAlbum(seed);
        }else showAlbum(songs,null,origin,tilt);
    }
    private void showCatalogSearch(String query){Bundle saved=new Bundle();saved.putString("query",query);showAggregateSearch(saved);}
    private void showAggregateSearch(Bundle saved){
        if(aggregatePage!=null)aggregatePage.close();
        AggregateSearchPage page=AggregateSearchPage.restore(this,new AggregateSearch(this),new AggregateSearchPage.Actions(){
            public void play(List<AggregateSearch.Result> results,int index){if(player==null||results.isEmpty())return;for(AggregateSearch.Result result:results)for(Track track:result.alternatives)rememberProvider(OnlineMusic.providerId(track),Collections.singletonList(track));playNeedle();player.setAggregateQueue(results,index);returnToPlayer();}
            public void manageAccounts(){showMusicSources();}
            public void artist(AggregateSearch.ArtistResult result){showCatalogArtist(result.providerId,result.artist);}
            public List<Track> libraryTracks(){return new ArrayList<>(library);}
            public List<Track> recentTracks(){return collections.recent();}
            public void libraryArtist(Track track){showArtist(track);}
            public void catalogAlbum(AggregateSearch.AlbumResult value){openProviderAlbum(value.providerId,value.album.id,value.album.name);}
            public void album(Track seed){
                if(OnlineMusic.isRemote(seed)){openProviderAlbum(seed);return;}
                ArrayList<Track> tracks=new ArrayList<>();
                for(Track track:library)if(track.albumKey().equals(seed.albumKey()))tracks.add(track);
                if(tracks.isEmpty())tracks.add(seed);
                sortAlbum(tracks);showAlbum(tracks);
            }
            public void libraryPlay(List<Track> tracks,int index){
                if(player==null||tracks.isEmpty())return;
                playNeedle();player.setQueue(latestLocalTracks(tracks),index,true);returnToPlayer();
            }
        },saved);aggregatePage=page;Dialog dialog=page.show();dialogs.add(dialog);fullPages++;turntable.setActive(false);handler.removeCallbacks(hideChrome);
        pageSnapshots.put(dialog,()->{Bundle state=pageState("aggregate_search");state.putBundle("search",page.snapshot());return state;});
        page.setOnClosed(()->{if(dialogs.remove(dialog))fullPages=Math.max(0,fullPages-1);pageSnapshots.remove(dialog);if(aggregatePage==page)aggregatePage=null;if(turntable!=null)turntable.setActive(foreground&&fullPages==0);});
    }
    private void showShelf() {
        if(shelfDialog!=null&&shelfDialog.isShowing())return;
        FrameLayout page=new FrameLayout(this);int background=stageColor();page.setBackground(DesignSystem.liveStage(this));
        LinearLayout layout=column();
        LinearLayout bar=row();bar.setPadding(dp(10),dp(10),dp(10),dp(4));bar.setGravity(Gravity.CENTER_VERTICAL);
        PlayerControls.Icon back=iconButton("back","回到唱机",()->shelfDialog.dismiss());back.setGlass(true);bar.addView(back);
        LinearLayout segments=row();segments.setBackground(PlayerControls.glassCapsule(this));Button albumsTab=quietButton("专辑","浏览专辑",()->{}),listsTab=quietButton("歌单","浏览歌单",()->{});albumsTab.setTextColor(0xff302b30);listsTab.setTextColor(0xff302b30);segments.addView(albumsTab,new LinearLayout.LayoutParams(dp(48),dp(48)));segments.addView(listsTab,new LinearLayout.LayoutParams(dp(48),dp(48)));LinearLayout.LayoutParams seg=new LinearLayout.LayoutParams(-2,dp(48));seg.leftMargin=dp(6);bar.addView(segments,seg);bar.addView(new View(this),new LinearLayout.LayoutParams(0,1,1));
        PlayerControls.Icon sort=iconButton("sort","排序专辑",this::showShelfSort);sort.setGlass(true);bar.addView(sort);
        PlayerControls.Icon grid=iconButton(shelfGrid?"albums":"grid","切换网格与立体专辑架",()->{});grid.setOnClickListener(v->{shelfGrid=!shelfGrid;getPreferences(MODE_PRIVATE).edit().putBoolean("shelf_grid",shelfGrid).apply();grid.setSymbol(shelfGrid?"albums":"grid");renderShelf();});grid.setGlass(true);bar.addView(grid);
        PlayerControls.Icon search=iconButton("search","搜索专辑、歌曲和音乐人",()->{boolean open=shelfSearch.getVisibility()!=View.VISIBLE;shelfSearch.setVisibility(open?View.VISIBLE:View.GONE);if(open){shelfSearch.requestFocus();((android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).showSoftInput(shelfSearch,android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);}else{shelfSearch.setText("");((android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(shelfSearch.getWindowToken(),0);}renderShelf();});search.setGlass(true);bar.addView(search);layout.addView(bar);
        shelfSearch=new EditText(this);shelfSearch.setSingleLine(true);shelfSearch.setTextSize(15);shelfSearch.setTextColor(0xff302b30);shelfSearch.setHintTextColor(0xff665559);shelfSearch.setHint("专辑、歌曲、音乐人");shelfSearch.setBackground(rounded(0xc0ffffff,20));shelfSearch.setPadding(dp(16),0,dp(16),0);shelfSearch.setVisibility(View.GONE);LinearLayout.LayoutParams searchParams=new LinearLayout.LayoutParams(-1,dp(48));searchParams.setMargins(dp(18),dp(8),dp(18),dp(8));layout.addView(shelfSearch,searchParams);
        Button onlineSearch=quietButton("搜索在线曲库 →","搜索未收藏的歌曲",()->showCatalogSearch(shelfSearch.getText().toString().trim()));onlineSearch.setTextColor(shelfInk());LinearLayout.LayoutParams onlineParams=new LinearLayout.LayoutParams(-1,dp(42));onlineParams.setMargins(dp(18),0,dp(18),dp(2));layout.addView(onlineSearch,onlineParams);
        FrameLayout collection=new FrameLayout(this);page.addView(collection,new FrameLayout.LayoutParams(-1,-1));albumStack=new AlbumStackView(this);albumStack.setPadding(0,dp(126),0,dp(64));albumStack.setInkColor(shelfInk());albumStack.setOnAlbumClickListener(songs->openShelfAlbum(songs,albumStack.getLastSelectedScreenBounds(),albumStack.getLastSelectedTiltDegrees()));collection.addView(albumStack,new FrameLayout.LayoutParams(-1,-1));
        shelfSongScroll=new ScrollView(this);shelfRows=column();shelfRows.setPadding(dp(22),dp(126),dp(22),dp(115));shelfSongScroll.addView(shelfRows);collection.addView(shelfSongScroll,new FrameLayout.LayoutParams(-1,-1));
        shelfResults=new LibraryBrowserList(this,themes);shelfResults.setInkColor(shelfInk());shelfResults.setActions(new LibraryBrowserList.Actions(){
            public void album(ArrayList<Track> songs,View origin){int[] p=new int[2];origin.getLocationOnScreen(p);openShelfAlbum(songs,new android.graphics.RectF(p[0],p[1],p[0]+origin.getWidth(),p[1]+origin.getHeight()),0);}
            public void albumMenu(ArrayList<Track> songs){showAlbumActions(songs);}
            public void play(ArrayList<Track> songs,int at){if(player!=null){playNeedle();player.setQueue(latestLocalTracks(songs),at,true);}returnToPlayer();}
            public void songMenu(Track t){showSongMenu(t);}public void artist(Track t){showArtist(t);}
        });collection.addView(shelfResults,new FrameLayout.LayoutParams(-1,-1));
        Runnable tabs=()->{albumsTab.setBackground(rounded(!shelfSongs?0xe8ffffff:Color.TRANSPARENT,22));listsTab.setBackground(rounded(shelfSongs?0xe8ffffff:Color.TRANSPARENT,22));renderShelf();};albumsTab.setOnClickListener(v->{shelfSongs=false;tabs.run();});listsTab.setOnClickListener(v->{shelfSongs=true;tabs.run();});
        shelfSearch.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int c,int a){}public void onTextChanged(CharSequence s,int st,int before,int count){handler.removeCallbacks(delayedShelfSearch);handler.postDelayed(delayedShelfSearch,220);}public void afterTextChanged(Editable e){}});
        page.addView(layout,new FrameLayout.LayoutParams(-1,-2,Gravity.TOP));
        addPageDock(page);shelfDialog=fullPage(page,false);pageSnapshots.put(shelfDialog,()->{Bundle b=pageState("shelf");b.putString("query",shelfSearch.getText().toString());b.putInt("search_visibility",shelfSearch.getVisibility());b.putInt("limit",searchLimit);b.putBoolean("songs",shelfSongs);return b;});tabs.run();
    }
    private int stageColor(){return DesignSystem.stageColor(themes,albumKey());}
    private int shelfInk(){return readableInk(stageColor());}
    static int readableInk(int background) {
        double luminance=luminance(background), dark=luminance(DesignSystem.INK);
        return (luminance+.05)/(dark+.05) >= 1.05/(luminance+.05) ? DesignSystem.INK : Color.WHITE;
    }
    private static double luminance(int color) {
        return .2126*linear(Color.red(color))+.7152*linear(Color.green(color))+.0722*linear(Color.blue(color));
    }
    private static double linear(int channel) {
        double value=channel/255.0;return value<=.04045?value/12.92:Math.pow((value+.055)/1.055,2.4);
    }
    private void showShelfSort(){String[] labels={"添加顺序","专辑名称","音乐人"};AlertDialog d=new AlertDialog.Builder(this).setTitle("专辑排序").setSingleChoiceItems(labels,shelfSort,(a,which)->{shelfSort=which;getPreferences(MODE_PRIVATE).edit().putInt("shelf_sort",which).apply();renderShelf();a.dismiss();}).create();trackDialog(d);d.show();}
    private void renderShelf() {
        handler.removeCallbacks(delayedShelfSearch);
        if(shelfRows==null||albumStack==null)return;shelfRows.removeAllViews();String query=shelfSearch==null?"":shelfSearch.getText().toString().trim().toLowerCase(Locale.ROOT);
        if(!query.equals(searchQuery)){searchQuery=query;searchLimit=80;}
        LinkedHashMap<String,ArrayList<Track>> albums=new LinkedHashMap<>();for(Track t:library){if(!albums.containsKey(t.albumKey()))albums.put(t.albumKey(),new ArrayList<>());albums.get(t.albumKey()).add(t);}
        ArrayList<ArrayList<Track>> visible=new ArrayList<>();for(ArrayList<Track> songs:albums.values()){boolean matches=query.isEmpty();for(Track t:songs)if((t.title+" "+t.artist+" "+t.album).toLowerCase(Locale.ROOT).contains(query))matches=true;if(matches){sortAlbum(songs);visible.add(songs);}}
        if(shelfSort==1)visible.sort((a,b)->a.get(0).album.compareToIgnoreCase(b.get(0).album));else if(shelfSort==2)visible.sort((a,b)->a.get(0).albumArtist.compareToIgnoreCase(b.get(0).albumArtist));
        boolean useStack=!shelfSongs&&!shelfGrid&&query.isEmpty();boolean useResults=!query.isEmpty()||(!shelfSongs&&!useStack);
        albumStack.setVisibility(useStack?View.VISIBLE:View.GONE);shelfSongScroll.setVisibility(useStack||useResults?View.GONE:View.VISIBLE);shelfResults.setVisibility(useResults?View.VISIBLE:View.GONE);
        if(useStack)albumStack.setAlbums(visible);
        if(useResults){
            ArrayList<Track> matched=new ArrayList<>();LinkedHashMap<String,Track> artists=new LinkedHashMap<>();
            if(!query.isEmpty())for(Track t:library){if((t.title+" "+t.artist).toLowerCase(Locale.ROOT).contains(query))matched.add(t);if(t.artist.toLowerCase(Locale.ROOT).contains(query))artists.put(t.artist,t);}
            shelfResults.submit(visible,matched,new ArrayList<>(artists.values()),!query.isEmpty());shelfResults.setPadding(0,query.isEmpty()?dp(42):dp(170),0,dp(108));return;
        }
        if(shelfSongs){
            Button netease=quietButton("网易云音乐 · 账号与歌单","打开网易云音乐",this::showNetease);netease.setTextColor(shelfInk());netease.setGravity(Gravity.START);shelfRows.addView(netease);
            shelfRows.addView(shelfNote("我的歌单",26));Button add=quietButton("＋ 新建歌单","新建歌单",()->createPlaylist(new ArrayList<>()));add.setTextColor(shelfInk());add.setGravity(Gravity.START);shelfRows.addView(add);
            shelfRows.addView(collectionButton("喜欢的歌曲",collections.favorites()));shelfRows.addView(collectionButton("最近播放",collections.recent()));
            for(LibraryStore.Playlist p:collections.playlists()){View card=collectionButton(p.name,p.tracks,p.description,p.coverUri);card.setOnClickListener(v->{if(p.tracks.isEmpty())editPlaylist(p);else showAlbum(p.tracks,p.name,null,0,p);});card.setOnLongClickListener(v->{playlistMenu(p);return true;});shelfRows.addView(card);}
            shelfRows.addView(shelfNote("音乐资料库",18));shelfRows.addView(collectionButton("全部音乐",new ArrayList<>(library)));shelfRows.addView(collectionButton("本地导入",new ArrayList<>(local)));
            if(player!=null)shelfRows.addView(collectionButton("当前队列",new ArrayList<>(player.getQueue())));
        }
        if(library.isEmpty()){shelfRows.addView(shelfNote("收藏你的第一张唱片",24));shelfRows.addView(collectionButton("导入音乐",new ArrayList<>()));}
    }
    private TextView shelfNote(String value,int size){TextView t=text(value,size,shelfInk());if(size>=18)t.setTypeface(null,Typeface.BOLD);t.setPadding(0,dp(12),0,dp(14));return t;}
    private View collectionButton(String name,ArrayList<Track> songs){return collectionButton(name,songs,"","");}
    private View collectionButton(String name,ArrayList<Track> songs,String description,String coverUri){
        LinearLayout card=row();card.setGravity(Gravity.CENTER_VERTICAL);card.setPadding(dp(12),dp(12),dp(12),dp(12));card.setBackground(rounded(0x55ffffff,18));
        CoverView image=new CoverView(this);if(!coverUri.isEmpty())image.setSource(coverUri,180);else if(!songs.isEmpty())image.setTrack(songs.get(0),themes,180);card.addView(image,new LinearLayout.LayoutParams(dp(66),dp(66)));
        LinearLayout labels=column();labels.setPadding(dp(16),0,dp(40),0);TextView title=text(name,17,shelfInk());title.setMaxLines(2);title.setEllipsize(TextUtils.TruncateAt.END);labels.addView(title);
        TextView detail=text(songs.size()+" 首歌曲"+(description.isEmpty()?"":" · "+description),13,shelfInk());detail.setMaxLines(2);detail.setEllipsize(TextUtils.TruncateAt.END);detail.setPadding(0,dp(6),0,0);labels.addView(detail);card.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.bottomMargin=dp(12);card.setLayoutParams(lp);card.setFocusable(true);card.setContentDescription(name+"，"+songs.size()+" 首歌曲");card.setOnClickListener(v->{if(songs.isEmpty()){showEmptyCollection(name);return;}showAlbum(songs,name);});return card;
    }
    private LibraryStore.Playlist findPlaylist(String id){for(LibraryStore.Playlist p:collections.playlists())if(p.id.equals(id))return p;return null;}
    private void showEmptyCollection(String name){LinearLayout body=column();body.addView(note("“"+name+"”还没有歌曲。可以长按歌曲加入歌单，或导入本地音乐。"));Dialog d=sheet(name,body,false);body.addView(actionButton("导入音乐",()->{d.dismiss();pickAudio();}));}
    private static void sortAlbum(List<Track> tracks) { Collections.sort(tracks, (a, b) -> { int aa = a.trackNumber > 0 ? a.trackNumber : Integer.MAX_VALUE, bb = b.trackNumber > 0 ? b.trackNumber : Integer.MAX_VALUE; return Integer.compare(aa, bb); }); }
    private void showAlbum(ArrayList<Track> songs) {
        showAlbum(songs, null);
    }
    private void showAlbum(ArrayList<Track> songs, String collectionName) {
        showAlbum(songs, collectionName, null, 0);
    }
    private void showAlbum(ArrayList<Track> songs, String collectionName, android.graphics.RectF origin, float sourceTilt) {
        showAlbum(songs,collectionName,origin,sourceTilt,null);
    }
    private void showAlbum(ArrayList<Track> songs, String collectionName, android.graphics.RectF origin, float sourceTilt,LibraryStore.Playlist playlist) {
        if (songs.isEmpty()) return;
        ArrayList<Track> selected = latestLocalTracks(songs); Track first = selected.get(0); String heading = collectionName == null ? first.album : collectionName;
        FrameLayout page = new FrameLayout(this); page.setBackground(DesignSystem.paper(this));
        android.widget.ListView scroll = new android.widget.ListView(this);scroll.setDivider(null);scroll.setSelector(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));scroll.setClipToPadding(false);page.addView(scroll,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout body = column(); body.setPadding(dp(28), dp(100), dp(28), 0); body.setGravity(Gravity.CENTER_HORIZONTAL);scroll.addHeaderView(body,null,false);
        ImageView cover = new ImageView(this); cover.setScaleType(ImageView.ScaleType.CENTER_CROP); cover.setBackground(rounded(0xffded8df, 4)); cover.setElevation(dp(13)); cover.setContentDescription(heading + " 专辑封面");
        int imageSize = Math.min(dp(310), (int)(getResources().getDisplayMetrics().widthPixels * .62f)); body.addView(cover, new LinearLayout.LayoutParams(imageSize, imageSize));
        TextView headingView = text(heading, 24, DesignSystem.INK); headingView.setTypeface(null, Typeface.BOLD); headingView.setGravity(Gravity.CENTER); headingView.setPadding(0, dp(24), 0, dp(4)); body.addView(headingView, new LinearLayout.LayoutParams(-1, -2));
        TextView musician = text(collectionName == null ? first.albumArtist : "音乐收藏", 14, DesignSystem.MUTED); musician.setMinHeight(dp(48));musician.setGravity(Gravity.CENTER); if(collectionName==null)musician.setOnClickListener(v -> showArtist(first,first.albumArtist)); body.addView(musician);
        if(playlist!=null&&!playlist.description.isEmpty()){TextView description=text(playlist.description,14,DesignSystem.MUTED);description.setGravity(Gravity.CENTER);description.setLineSpacing(dp(4),1);description.setPadding(0,dp(8),0,dp(8));body.addView(description,new LinearLayout.LayoutParams(-1,-2));}
        String metadata = first.genre.isEmpty() ? first.year : first.year.isEmpty() ? first.genre : first.genre + " · " + first.year;
        if (collectionName == null && !metadata.isEmpty()) { TextView meta = text(metadata, 12, 0xff777179); meta.setPadding(0, dp(9), 0, 0); body.addView(meta); }
        LinearLayout actions = row(); Button ordered = outlineButton("播放", () -> playAlbum(selected, false)); Button shuffle = outlineButton("随机播放", () -> playAlbum(selected, true)); LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(0, dp(48), 1); actionParams.rightMargin = dp(12); actions.addView(ordered, actionParams); actions.addView(shuffle, new LinearLayout.LayoutParams(0, dp(48), 1)); LinearLayout.LayoutParams actionsParams = new LinearLayout.LayoutParams(-1, -2); actionsParams.topMargin = dp(28); actionsParams.bottomMargin = dp(25); body.addView(actions, actionsParams);
        albumRows.clear();albumSongs.clear();albumSongs.addAll(selected);albumHighlightedId=null;long total=0;for(Track t:selected)total+=t.durationMs;
        AlbumSongAdapter adapter=new AlbumSongAdapter(this,selected,new AlbumSongAdapter.Actions(){public void play(int at){if(player!=null){playNeedle();player.setQueue(latestLocalTracks(selected),at,true);}refresh();}public void menu(Track track){showSongMenu(track);}});albumAdapter=adapter;
        TextView totals=text(selected.size()+" 首歌曲 · "+time(total),12,0xff827a83);totals.setPadding(dp(28),dp(20),dp(28),dp(100));scroll.addFooterView(totals,null,false);scroll.setAdapter(adapter);adapter.setCurrent(player==null?null:player.getCurrent());
        LinearLayout bar = row(); bar.setGravity(Gravity.CENTER_VERTICAL); bar.setPadding(dp(12), dp(3), dp(12), 0); bar.addView(iconButton("back", "返回收藏", () -> { if (albumDialog != null) albumDialog.onBackPressed(); })); bar.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1)); bar.addView(iconButton("share", "分享专辑", () -> showShare(heading,first.albumArtist,selected))); bar.addView(iconButton("more", "专辑操作", () -> showAlbumActions(selected))); page.addView(bar, new FrameLayout.LayoutParams(-1, dp(50), Gravity.TOP));
        LinearLayout capsule = column(); capsule.setBackground(PlayerControls.glassCapsule(this)); capsule.addView(iconButton("albums", "专辑架", () -> { if (albumDialog != null) albumDialog.dismiss(); showShelf(); })); albumDockCover = iconButton("disc", "返回唱机", this::returnToPlayer); albumDockCover.setArtwork(currentArtwork); capsule.addView(albumDockCover); albumDockPlay = iconButton("play", "播放或暂停", this::togglePlayback); capsule.addView(albumDockPlay); capsule.addView(iconButton("queue", "播放队列", this::showQueue)); for(int i=0;i<capsule.getChildCount();i++)if(capsule.getChildAt(i) instanceof PlayerControls.Icon)((PlayerControls.Icon)capsule.getChildAt(i)).setInk(DesignSystem.INK); FrameLayout.LayoutParams dockParams = new FrameLayout.LayoutParams(dp(48), -2, Gravity.END | Gravity.BOTTOM); dockParams.setMargins(0, 0, dp(12), dp(15)); page.addView(capsule, dockParams);
        Dialog dialog = fullPage(page, true); albumDialog = dialog;
        albumPages.put(dialog,new AlbumPageState(selected,adapter,albumDockPlay,albumDockCover));
        ((PageDialog)dialog).setReturnAction(new AlbumReturnTransition(dialog,page,cover,origin,sourceTilt));
        pageRestorers.put(dialog, () -> showAlbum(selected, collectionName));
        pageSnapshots.put(dialog,()->{Bundle b=pageState("album");b.putStringArrayList("track_ids",trackIds(selected));b.putBoolean("remote",selected.stream().anyMatch(OnlineMusic::isRemote));b.putString("collection",collectionName);if(playlist!=null)b.putString("playlist_id",playlist.id);return b;});
        String override = themes.artwork(first.albumKey()); String artUri = playlist!=null&&!playlist.coverUri.isEmpty()?playlist.coverUri:override.isEmpty() ? first.artworkUri : override;
        io.execute(() -> { Bitmap bitmap = LocalMusicImporter.loadArtwork(this, artUri, 640); handler.post(() -> { if (!destroyed && dialog.isShowing()) cover.setImageBitmap(bitmap); }); });
        if (android.animation.ValueAnimator.areAnimatorsEnabled()) {
            cover.setAlpha(0f); cover.post(() -> { if (!dialog.isShowing()) return;
                if (origin != null && !origin.isEmpty() && cover.getWidth() > 0) { int[] location = new int[2]; cover.getLocationOnScreen(location); float tilt = Math.max(-72, Math.min(72, sourceTilt)); cover.setCameraDistance(dp(1400)); cover.setRotationX(tilt); cover.setScaleX(Math.max(.2f, Math.min(2f, origin.width()/cover.getWidth()))); cover.setScaleY(Math.max(.2f, Math.min(2f, origin.height()/(cover.getHeight()*(float)Math.cos(Math.toRadians(tilt)))))); cover.setTranslationX(origin.centerX()-location[0]-cover.getWidth()/2f); cover.setTranslationY(origin.centerY()-location[1]-cover.getHeight()/2f); }
                else { cover.setRotationX(48); cover.setScaleX(.88f); cover.setScaleY(.88f); }
                cover.setAlpha(.7f); cover.animate().translationX(0).translationY(0).rotationX(0).scaleX(1).scaleY(1).alpha(1).setDuration(360).setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
            });
        }
        refresh();
    }
    private void playAlbum(ArrayList<Track> songs, boolean random) { if (player == null || songs.isEmpty()) return; playNeedle(); player.setPlayMode(random ? PlayerService.MODE_SHUFFLE : PlayerService.MODE_ORDER); int start = random ? new java.util.Random().nextInt(songs.size()) : 0; player.setQueue(latestLocalTracks(songs), start, true); refresh(); }
    private Button outlineButton(String label, Runnable action) { Button b = quietButton(label, label, action); b.setTextColor(0xff29242d); GradientDrawable d = rounded(0x00ffffff, 24); d.setStroke(dp(1), 0xff958d96); b.setBackground(d); return b; }
    private void returnToPlayer() { dismissDialogs(); refresh(); }
    private void restoreTopAlbum(){
        albumDialog=null;albumAdapter=null;albumSongs.clear();albumDockPlay=null;albumDockCover=null;
        for(java.util.Map.Entry<Dialog,AlbumPageState> entry:albumPages.entrySet())if(entry.getKey().isShowing()){
            AlbumPageState saved=entry.getValue();albumDialog=entry.getKey();albumAdapter=saved.adapter;albumSongs.clear();albumSongs.addAll(saved.songs);albumDockPlay=saved.play;albumDockCover=saved.cover;
        }
        if(albumAdapter!=null)albumAdapter.setCurrent(player==null?null:player.getCurrent());
    }
    private void styleAmbientWindow(Dialog dialog){
        if(dialog==null||!dialog.isShowing()||dialog.getWindow()==null)return;
        Window window=dialog.getWindow();window.setStatusBarColor(DesignSystem.ambientColor(this));
        window.setNavigationBarColor(DesignSystem.ambientColor(this));window.getDecorView().setSystemUiVisibility(0);
    }
    private Dialog fullPage(FrameLayout page, boolean light) {
        Dialog dialog = new PageDialog(this); dialog.setContentView(page);
        page.setOnApplyWindowInsetsListener((v, insets) -> { v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(), insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom()); return insets; });
        dialogs.add(dialog); fullPages++; if (turntable != null) turntable.setActive(false);
        dialog.setOnDismissListener(d -> { if (!dialogs.remove(dialog)) return; pageRestorers.remove(dialog); pageSnapshots.remove(dialog); fullPages = Math.max(0, fullPages - 1); boolean album=albumPages.remove(dialog)!=null;if(album||dialog==albumDialog)restoreTopAlbum();if(dialog==queueDialog){queueTimeline=null;queueDialog=null;queueRows=null;queueCover=null;queueCount=null;queueMode=null;styleProgressControls();} if (foreground && turntable != null) turntable.setActive(fullPages == 0); if (fullPages == 0) scheduleChromeHide(); });
        dialog.show(); Window window = dialog.getWindow(); if (window != null) { window.setLayout(-1, -1); window.setStatusBarColor(Color.TRANSPARENT); window.setNavigationBarColor(light ? DesignSystem.pageColor(this) : stageColor()); window.getDecorView().setSystemUiVisibility(light || shelfInk()!=Color.WHITE ? View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR : 0); }
        page.requestApplyInsets(); return dialog;
    }
    private void showQueue() {
        if(usingExternal()){showExternalQueue();return;}
        if(queueDialog!=null&&queueDialog.isShowing())return;
        FrameLayout page=new FrameLayout(this);int tint=stageColor();page.setBackground(DesignSystem.liveStage(this));
        LinearLayout layout=column();layout.setPadding(dp(20),dp(6),dp(20),dp(20));page.addView(layout,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout bar=row();bar.setGravity(Gravity.CENTER_VERTICAL);bar.addView(iconButton("back","返回上层",()->queueDialog.dismiss()));TextView heading=text("队列",22,shelfInk());heading.setTypeface(null,Typeface.BOLD);bar.addView(heading,new LinearLayout.LayoutParams(0,-2,1));queueMode=iconButton("repeat","播放模式",this::showPlayModes);queueMode.setGlass(true);bar.addView(queueMode);layout.addView(bar,new LinearLayout.LayoutParams(-1,dp(62)));
        queueCount=text("",12,shelfInk());queueCount.setPadding(dp(8),dp(5),0,dp(22));layout.addView(queueCount);
        LinearLayout middle=row();middle.setGravity(Gravity.TOP);layout.addView(middle,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout sleeve=column();sleeve.setPadding(0,dp(30),dp(15),0);int sleeveWidth=Math.max(dp(84),Math.min(dp(200),(int)(getResources().getDisplayMetrics().widthPixels*.40f)));queueCover=new CoverView(this);queueCover.setRotationY(-22);queueCover.setRotation(-7);queueCover.setCameraDistance(dp(1200));queueCover.setElevation(dp(8));sleeve.addView(queueCover,new LinearLayout.LayoutParams(sleeveWidth-dp(16),(int)(sleeveWidth*1.25f)));TextView hint=text("UP NEXT",10,shelfInk());hint.setLetterSpacing(.18f);hint.setPadding(dp(4),dp(24),0,0);sleeve.addView(hint);middle.addView(sleeve,new LinearLayout.LayoutParams(sleeveWidth,-1));
        queueRows=new QueueListView(this);queueRows.setInkColor(shelfInk());queueRows.setAiry(true);queueRows.setActions(new QueueListView.Actions(){public void play(int at){if(player!=null){playNeedle();player.playAt(at);}}public void menu(int at){queueItemMenu(at);}public void move(int from,int to){if(player!=null){player.moveQueueItem(from,to);renderQueue();}}});middle.addView(queueRows,new LinearLayout.LayoutParams(0,-1,1));
        queueTimeline=new PlaybackTimeline(this,true,this::seekPlayback);layout.addView(queueTimeline,new LinearLayout.LayoutParams(-1,-2));
        LinearLayout controls=row();controls.setGravity(Gravity.CENTER);controls.addView(iconButton("previous","上一首",()->{previousTrack();}));PlayerControls.Icon toggle=iconButton("play","播放或暂停",this::togglePlayback);pagePlayButtons.add(new java.lang.ref.WeakReference<>(toggle));LinearLayout.LayoutParams playParams=new LinearLayout.LayoutParams(dp(68),dp(56));playParams.setMargins(dp(18),0,dp(18),0);controls.addView(toggle,playParams);controls.addView(iconButton("next","下一首",()->{nextTrack();}));layout.addView(controls);
        tintQueueIcons(page);queueDialog=fullPage(page,false);pageRestorers.put(queueDialog,this::showQueue);pageSnapshots.put(queueDialog,()->pageState("queue"));queueSignature="";styleProgressControls();renderQueue();queueRows.scrollToCurrent();refresh();
    }
    private void tintQueueIcons(View view){if(view instanceof PlayerControls.Icon)((PlayerControls.Icon)view).setInk(shelfInk());if(view instanceof android.view.ViewGroup){android.view.ViewGroup group=(android.view.ViewGroup)view;for(int i=0;i<group.getChildCount();i++)tintQueueIcons(group.getChildAt(i));}}
    private void renderQueue(){if(queueRows==null||player==null)return;List<Track> queue=player.getQueue();queueCount.setText(queue.isEmpty()?"队列为空":queue.size()+" 首 · 第 "+(player.getIndex()+1)+" 首");String[] symbols={"queue","repeat","repeatone","shuffle"};queueMode.setSymbol(symbols[player.getPlayMode()]);queueMode.setContentDescription("播放模式："+new String[]{"顺序播放","列表循环","单曲循环","随机播放"}[player.getPlayMode()]);Track current=player.getCurrent();if(current!=null)queueCover.setTrack(current,themes,640);else queueCover.clear();queueRows.submit(queue,player.getIndex());}
    private void queueItemMenu(int at){if(player==null||at>=player.getQueueSize())return;Track track=player.getQueue().get(at);String[] actions={"现在播放","上移一首","下移一首","从队列移除","加入歌单"};AlertDialog d=new AlertDialog.Builder(this).setTitle(track.title).setItems(actions,(a,which)->{if(which==0){useInternalSource();player.playAt(at);}if(which==1)player.moveQueueItem(at,at-1);if(which==2)player.moveQueueItem(at,at+1);if(which==3)player.removeQueueItem(at);if(which==4)addToPlaylist(single(track));renderQueue();}).create();trackDialog(d);d.show();}
    private void showPlayModes(){if(usingExternal()){openOfficial(externalMusic.getState().selectedProviderId);return;}if(player==null)return;String[] modes={"顺序播放","列表循环","单曲循环","随机播放"};AlertDialog d=new AlertDialog.Builder(this).setTitle("播放模式").setSingleChoiceItems(modes,player.getPlayMode(),(a,which)->{player.setPlayMode(which);renderQueue();a.dismiss();}).create();trackDialog(d);d.show();}
    private void showAppearance() {
        if(appearanceDialog!=null&&appearanceDialog.isShowing())return;
        handler.removeCallbacks(hideChrome);details.setVisibility(View.INVISIBLE);
        Track t=player==null?null:player.getCurrent();
        final Dialog[] owned = new Dialog[1];
        AppearanceEditor editor=new AppearanceEditor(this,themes,albumKey(),usingExternal()?externalMusic.getState().album:t==null?"":t.album,currentArtwork,()->{applyAppearance();VinylWidgetProvider.updateAll(this);},this::pickArtwork,()->{
            if(appearanceDialog!=owned[0])return;
            if(appearanceDialog!=null){dialogs.remove(appearanceDialog);pageRestorers.remove(appearanceDialog);pageSnapshots.remove(appearanceDialog);}appearanceDialog=null;details.setVisibility(View.VISIBLE);
        });appearanceDialog=editor.show();owned[0]=appearanceDialog;dialogs.add(appearanceDialog);pageRestorers.put(appearanceDialog,this::showAppearance);pageSnapshots.put(appearanceDialog,()->pageState("appearance"));
    }
    private boolean isPlaybackPlaying(){return usingExternal()?externalMusic.getState().playing:player!=null&&player.isPlaying();}
    private boolean usingExternal(){return externalMusic!=null&&!externalMusic.getState().selectedProviderId.isEmpty();}
    private long playbackDuration(){return usingExternal()?Math.max(0,externalMusic.getState().durationMs):player==null?0:player.getDurationMs();}
    private void seekPlayback(long position){if(usingExternal()){if(externalMusic.getState().canSeek)externalMusic.seekTo(position);refresh();}else if(player!=null&&player.canSeek())player.seekTo(position);}
    private void previousTrack(){if(usingExternal()){if(!externalMusic.previous())toast("当前来源暂未开放上一首");}else if(player!=null)player.previous();}
    private void nextTrack(){if(usingExternal()){if(!externalMusic.next())toast("当前来源暂未开放下一首");}else if(player!=null)player.next();}
    private void useInternalSource(){
        if(!usingExternal())return;
        if(externalMusic.getState().playing&&!externalMusic.pause())toast("原音乐 App 未开放暂停，请在官方 App 停止播放");externalMusic.selectProvider(null);getPreferences(MODE_PRIVATE).edit().remove("external_music_source").apply();
        artToken++;artSignature="";shownId="";externalArtworkHash=0;externalArtworkSeen=null;externalArtworkUriSignature="";externalArtworkLoading=false;externalArtworkRetryAt=0;wasPlaying=false;refresh();
    }
    private void selectExternalSource(String id){
        if(ExternalMusicBridge.providerForId(id)==null)return;
        if(usingExternal()&&!id.equals(externalMusic.getState().selectedProviderId)&&externalMusic.getState().playing&&!externalMusic.pause())toast("原音乐 App 未开放暂停，请在官方 App 停止播放");
        if(queueDialog!=null&&queueDialog.isShowing())queueDialog.dismiss();
        if(player!=null)player.pausePlayback();
        artToken++;artSignature="";shownId="";externalArtworkHash=0;externalArtworkSeen=null;externalArtworkUriSignature="";externalArtworkLoading=false;externalArtworkRetryAt=0;wasPlaying=false;externalMusic.selectProvider(id);
        getPreferences(MODE_PRIVATE).edit().putString("external_music_source",id).apply();
        refresh();
    }
    private void refreshExternal(){
        ExternalMusicBridge.State state=externalMusic.getState();
        String name=state.selectedProvider==null?"音乐联动":state.selectedProvider.name;
        title.setText(state.title.isEmpty()?name:state.title);artist.setText(state.artist.isEmpty()?"在官方 App 开始播放":state.artist);subtitle.setText(state.album);
        String signature=externalTrackKey(state);boolean songChanged=!signature.equals(artSignature);
        if(songChanged){artToken++;artSignature=signature;shownId=signature;}
        String uriSignature=state.connected&&state.artwork==null&&!state.artworkUri.isEmpty()?signature+"|"+state.artworkUri:"";
        if(!uriSignature.equals(externalArtworkUriSignature)){
            artToken++;externalArtworkUriSignature=uriSignature;externalArtworkLoading=false;externalArtworkRetryAt=0;
        }
        if(state.artwork==null)externalArtworkSeen=null;
        if(!state.connected){externalArtworkSeen=null;externalArtworkHash=0;if(currentArtwork!=null)setExternalArtwork(null,false,signature);}
        else if(state.artwork!=null){
            int generation=state.artwork.isRecycled()?0:state.artwork.getGenerationId();
            if(state.artwork!=externalArtworkSeen||generation!=externalArtworkGeneration||songChanged){
                long fingerprint=ArtworkFingerprint.of(state.artwork);
                externalArtworkSeen=state.artwork;externalArtworkGeneration=generation;
                if(fingerprint!=0&&(fingerprint!=externalArtworkHash||currentArtwork==null)){
                    artToken++;externalArtworkHash=fingerprint;setExternalArtwork(state.artwork,songChanged,signature);
                }else if(songChanged){themes.coverPalette(signature,AlbumPalette.from(currentArtwork));applyAppearance(true);}
            }
        }else if(!uriSignature.isEmpty()){
            if(songChanged&&!externalSleeveKey(state).equals(visibleSleeveKey)){externalArtworkHash=0;setExternalArtwork(null,false,signature);}
            if(!externalArtworkLoading&&android.os.SystemClock.uptimeMillis()>=externalArtworkRetryAt){
                externalArtworkLoading=true;final int token=++artToken;final String key=signature,uri=state.artworkUri;
                io.execute(()->{Bitmap bitmap=LocalMusicImporter.loadArtwork(this,uri,640);handler.post(()->{
                    if(destroyed||token!=artToken||!usingExternal())return;
                    ExternalMusicBridge.State now=externalMusic.getState();
                    if(!now.connected||!key.equals(externalTrackKey(now))||now.artwork!=null||!uri.equals(now.artworkUri))return;
                    externalArtworkLoading=false;
                    if(bitmap==null){externalArtworkRetryAt=android.os.SystemClock.uptimeMillis()+5000;return;}
                    externalArtworkRetryAt=Long.MAX_VALUE;externalArtworkHash=ArtworkFingerprint.of(bitmap);setExternalArtwork(bitmap,false,key);
                });});
            }
        }else if(songChanged&&!externalSleeveKey(state).equals(visibleSleeveKey)){externalArtworkHash=0;setExternalArtwork(null,false,signature);}
        if(sourcesPage!=null)sourcesPage.update(state);
        turntable.setNeedleSeekingEnabled(state.canSeek&&state.durationMs>0);play.setEnabled(state.playing?state.canPause:state.canPlay);previous.setEnabled(state.canPrevious);next.setEnabled(state.canNext);
        play.setSymbol(state.playing?"pause":"play");play.setContentDescription(state.playing?"暂停":"播放");
        for(java.lang.ref.WeakReference<PlayerControls.Icon> ref:pagePlayButtons){PlayerControls.Icon icon=ref.get();if(icon!=null){icon.setSymbol(state.playing?"pause":"play");icon.setEnabled(state.playing?state.canPause:state.canPlay);}}
        long duration=Math.max(0,state.durationMs),position=Math.max(0,state.positionMs);
        updateTimelines(signature,state.positionMs,state.durationMs,state.canSeek);
        boolean needleEngaged=state.playing||(state.connected&&wasPlaying&&(state.playbackState==android.media.session.PlaybackState.STATE_BUFFERING||state.playbackState==android.media.session.PlaybackState.STATE_CONNECTING));
        turntable.setProgress(duration>0?Math.min(1f,position/(float)duration):0);turntable.setPlaying(needleEngaged);turntable.setActive(foreground&&fullPages==0);
        status.setText(state.statusMessage);status.setVisibility(View.VISIBLE);ArtistPage.updatePlayback(artistPage,state.playing,currentArtwork,state.playing?state.canPause:state.canPlay);
        if(state.playing&&!wasPlaying)scheduleChromeHide();wasPlaying=needleEngaged;
        if(externalQueueTitle!=null){externalQueueTitle.setText(state.title.isEmpty()?name:state.title);externalQueueArtist.setText(state.artist.isEmpty()?state.statusMessage:state.artist);externalQueueArtwork.setImageBitmap(state.artwork);}
    }
    private static String externalTrackKey(ExternalMusicBridge.State state){
        return "external|"+state.selectedProviderId+"|"+(!state.mediaId.isEmpty()?state.mediaId:state.title+"|"+state.artist+"|"+state.album);
    }
    private void showSleeve(Bitmap image,String sleeveKey){
        if(sleeveKey.equals(visibleSleeveKey))turntable.updateArtworkInPlace(image);
        else turntable.setArtwork(image);
        visibleSleeveKey=sleeveKey;
    }
    private static String externalSleeveKey(ExternalMusicBridge.State state) {
        return state.album.isEmpty()?externalTrackKey(state):"album:"+state.selectedProviderId+"|"+state.album;
    }
    private void setExternalArtwork(Bitmap artwork,boolean newSong,String paletteKey){
        currentArtwork=artwork;
        String sleeveKey=externalSleeveKey(externalMusic.getState());
        if(newSong)showSleeve(artwork,sleeveKey);
        else { turntable.updateArtworkInPlace(artwork); visibleSleeveKey=sleeveKey; }
        themes.coverPalette(paletteKey,AlbumPalette.from(artwork));
        applyAppearance(true);
    }
    private void showMusicSources(){
        if(sourcesDialog!=null&&sourcesDialog.isShowing()){sourcesPage.update(externalMusic.getState());return;}
        final Dialog[] owned=new Dialog[1];
        MusicSourcesPage page=new MusicSourcesPage(this,new MusicSourcesPage.Actions(){
            public void back(){if(owned[0]!=null)owned[0].dismiss();}
            public void local(){useInternalSource();if(owned[0]!=null)owned[0].dismiss();returnToPlayer();}
            public void netease(){showNetease();}
            public void direct(String id){showProvider(id,null);}
            public void authorize(){try{startActivity(ExternalMusicBridge.notificationAccessSettingsIntent());}catch(android.content.ActivityNotFoundException unavailable){toast("请在系统设置中打开唱片机的通知使用权");}}
            public void select(String id){selectExternalSource(id);}
            public void open(String id){openOfficial(id);}
        });
        sourcesPage=page;page.update(externalMusic.getState());sourcesDialog=fullPage(page,false);styleAmbientWindow(sourcesDialog);owned[0]=sourcesDialog;pageSnapshots.put(sourcesDialog,()->pageState("sources"));
    }
    private void openOfficial(String id){
        Intent launch=externalMusic.getLaunchIntent(id);
        if(launch!=null){try{startActivity(launch);return;}catch(android.content.ActivityNotFoundException|SecurityException unavailable){}}
        String website="apple_music".equals(id)?"https://www.apple.com/apple-music/":"qishui_music".equals(id)?"https://www.qishui.com/":"https://y.qq.com/";
        try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(website)));}catch(android.content.ActivityNotFoundException unavailable){toast("请先安装对应的官方音乐 App");}
    }
    private void showExternalQueue(){
        if(queueDialog!=null&&queueDialog.isShowing())return;
        LinearLayout body=column();externalQueueArtwork=new ImageView(this);externalQueueArtwork.setScaleType(ImageView.ScaleType.CENTER_CROP);LinearLayout.LayoutParams art=new LinearLayout.LayoutParams(dp(160),dp(160));art.gravity=Gravity.CENTER_HORIZONTAL;body.addView(externalQueueArtwork,art);
        externalQueueTitle=text("",23,DesignSystem.INK);externalQueueTitle.setTypeface(null,Typeface.BOLD);externalQueueTitle.setPadding(0,dp(20),0,dp(5));body.addView(externalQueueTitle);externalQueueArtist=note("");body.addView(externalQueueArtist);
        queueTimeline=new PlaybackTimeline(this,true,this::seekPlayback);body.addView(queueTimeline,new LinearLayout.LayoutParams(-1,-2));
        LinearLayout controls=row();controls.setGravity(Gravity.CENTER);PlayerControls.Icon prev=iconButton("previous","上一首",this::previousTrack),toggle=iconButton("play","播放或暂停",this::togglePlayback),next=iconButton("next","下一首",this::nextTrack);controls.addView(prev);controls.addView(toggle);controls.addView(next);pagePlayButtons.add(new java.lang.ref.WeakReference<>(toggle));body.addView(controls);
        body.addView(note("播放队列由官方 App 管理。"));body.addView(actionButton("在官方 App 查看队列",()->openOfficial(externalMusic.getState().selectedProviderId)));body.addView(actionButton("切换音乐来源",this::showMusicSources));
        queueDialog=sheet("正在联动",body,false);pageSnapshots.put(queueDialog,()->pageState("queue"));refreshExternal();
        styleProgressControls();
    }
    private void showSettings(){
        if(settingsDialog!=null&&settingsDialog.isShowing())return;
        SettingsPage page=new SettingsPage(this,themes,new SettingsPage.Actions(){
            public void open(String destination){switch(destination){
                case "back":settingsDialog.dismiss();break;
                case "import":pickAudio();break;
                case "accounts":showMusicSources();break;
                case "search":showCatalogSearch("");break;
                case "appearance":settingsDialog.dismiss();showAppearance();break;
                case "widgets":startActivity(new Intent(MainActivity.this,WidgetAppearanceActivity.class));break;
                case "sleep":showSleepTimer();break;
                case "mode":showPlayModes();break;
                case "volume":showVolumeSettings();break;
                case "help":showHelp();break;
                case "licenses":showLicenses();break;
            }}
            public void changed(){applyAppearance(true);scheduleChromeHide();if(shelfDialog!=null&&shelfDialog.isShowing())renderShelf();}
        });settingsPage=page;Track current=player==null?null:player.getCurrent();page.update(currentArtwork,current==null?"":current.title,current==null?"":current.artist);
        settingsDialog=fullPage(page,false);styleAmbientWindow(settingsDialog);pageRestorers.put(settingsDialog,this::showSettings);pageSnapshots.put(settingsDialog,()->pageState("settings"));
    }
    private void showVolumeSettings(){
        LinearLayout body=column();boolean system=usingExternal()||(player!=null&&OnlineMusic.isApple(player.getCurrent()));
        body.addView(note(system?"当前播放音量请使用手机音量键调节":"唱片机内置播放音量"));
        SeekBar volume=new SeekBar(this);volume.setMax(100);volume.setProgress(player==null?100:(int)(player.getVolume()*100));volume.setContentDescription("播放器音量");volume.setEnabled(!system);
        volume.setProgressTintList(android.content.res.ColorStateList.valueOf(DesignSystem.pageAccent(this)));volume.setThumbTintList(android.content.res.ColorStateList.valueOf(DesignSystem.pageAccent(this)));body.addView(volume,new LinearLayout.LayoutParams(-1,dp(56)));
        volume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar b,int value,boolean user){if(user&&player!=null)player.setVolume(value/100f);}public void onStartTrackingTouch(SeekBar b){}public void onStopTrackingTouch(SeekBar b){}});sheet("播放音量",body,false);
    }
    private Switch settingSwitch(String name,boolean checked,java.util.function.Consumer<Boolean> changed){Switch view=new Switch(this);view.setText(name);view.setTextSize(14);view.setTextColor(0xff3a313f);view.setPadding(dp(12),dp(6),dp(12),dp(6));view.setChecked(checked);view.setLayoutParams(new LinearLayout.LayoutParams(-1,dp(56)));view.setOnCheckedChangeListener((b,value)->changed.accept(value));return view;}
    private void showSleepTimer(){if(usingExternal()){toast("请在官方音乐 App 设置睡眠定时");return;}LinearLayout body=column();Dialog d=sheet("睡眠定时",body,false);long remaining=player==null?0:player.getSleepRemainingMs();TextView label=note(remaining>0?"剩余 "+time(remaining):"在选定时间后轻轻停下");body.addView(label);for(int minutes:new int[]{15,30,45,60,90,0})body.addView(actionButton(minutes==0?"关闭定时":minutes+" 分钟",()->{if(player!=null)player.setSleepTimerMinutes(minutes);d.dismiss();toast(minutes==0?"定时已关闭":minutes+" 分钟后暂停");}));}
    private void showLicenses(){io.execute(()->{StringBuilder value=new StringBuilder();for(String name:new String[]{"record-formats.txt","FFmpeg-LGPL-2.1.txt","dependencies.txt","apple-musickit.txt","netease-protocol.txt","mineradio-notice.txt","mineradio-GPL-3.0.txt"})try(java.io.InputStream in=getAssets().open("licenses/"+name)){java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();byte[] bytes=new byte[8192];int count;while((count=in.read(bytes))!=-1)out.write(bytes,0,count);value.append(new String(out.toByteArray(),java.nio.charset.StandardCharsets.UTF_8)).append("\n\n");}catch(java.io.IOException ignored){}handler.post(()->{if(destroyed)return;LinearLayout body=column();TextView text=note(value.length()==0?"许可文件暂时无法读取":value.toString());text.setTextIsSelectable(true);body.addView(text);sheet("开源许可",body,true);});});}
    private void showHelp(){LinearLayout body=column();body.addView(note("唱机\n拖动唱臂落针开始播放；沿唱片向内移动可以调整进度，移回右侧暂停。也可使用 PLAY、上一首与下一首。\n\n收藏\n在专辑架右上角切换网格与立体唱片架。点击专辑看曲目，点击音乐人进入作品页。长按歌曲可以喜欢、加入队列或歌单。\n\n外观\n点曲名旁的工具，再点外观，实时选择盘面与背景；添加专辑专属外观后，仅对这张专辑生效。自定义颜色支持在封面上点按取色。\n\n队列与分享\n队列右上角选择播放模式；长按曲目拖动排序，更多菜单可移除。分享卡有三种布局，可保存图片或打开系统分享。"));sheet("操作指南",body,false);}
    private void togglePlayback(){if(usingExternal()){if(!externalMusic.togglePlayPause())toast("当前来源暂未开放此播放操作");return;}if(player==null)return;if(!player.isPlaying()&&!player.isPreparing())playNeedle();player.toggle();}
    private void playNeedle(){useInternalSource();if(themes.needleSound()&&needleSound!=null)needleSound.play(player==null?1:player.getVolume());}
    private void addPageDock(FrameLayout page){LinearLayout capsule=column();capsule.setBackground(PlayerControls.glassCapsule(this));capsule.addView(iconButton("albums","专辑架",()->{if(shelfDialog==null||!shelfDialog.isShowing())showShelf();}));PlayerControls.Icon current=iconButton("disc","回到唱机",this::returnToPlayer);current.setArtwork(currentArtwork);capsule.addView(current);PlayerControls.Icon toggle=iconButton("play","播放或暂停",this::togglePlayback);toggle.setSymbol(player!=null&&player.isPlaying()?"pause":"play");pagePlayButtons.add(new java.lang.ref.WeakReference<>(toggle));capsule.addView(toggle);capsule.addView(iconButton("queue","打开播放队列",this::showQueue));FrameLayout.LayoutParams dock=new FrameLayout.LayoutParams(dp(48),-2,Gravity.END|Gravity.BOTTOM);dock.setMargins(0,0,dp(12),dp(18));page.addView(capsule,dock);}
    private View trackRow(Track t,Runnable action,int color,boolean art){LinearLayout row=row();row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(dp(3),dp(8),dp(44),dp(8));if(art){CoverView image=new CoverView(this);image.setTrack(t,themes,120);row.addView(image,new LinearLayout.LayoutParams(dp(46),dp(46)));}LinearLayout labels=column();labels.setPadding(dp(12),0,0,0);TextView name=text(t.title,14,color);name.setMaxLines(2);name.setEllipsize(TextUtils.TruncateAt.END);labels.addView(name);TextView artist=text(t.artist,11,color);artist.setAlpha(.65f);artist.setSingleLine(true);labels.addView(artist);row.addView(labels,new LinearLayout.LayoutParams(0,-2,1));row.setContentDescription(t.title+"，"+t.artist);row.setFocusable(true);row.setOnClickListener(v->action.run());row.setOnLongClickListener(v->{showSongMenu(t);return true;});return row;}
    private void showArtist(Track seed){showArtist(seed,seed==null?"":seed.artist);}
    private String providerName(String id){return "qq".equals(id)?"QQ 音乐":"qishui".equals(id)?"汽水音乐":"applemusic".equals(id)?"Apple Music":"网易云音乐";}
    private void showProvider(String id,Bundle saved){
        if("qishui".equals(id)){showMusicSources();return;}
        if(!"qq".equals(id)&&!"netease".equals(id)&&!"applemusic".equals(id))return;
        ProviderLibraryPage old=providerPages.get(id);if(old!=null){old.close();}
        MusicProvider provider=OnlineMusic.provider(this,id);
        ProviderLibraryPage.Search search=provider::searchTracks;
        ProviderLibraryPage page=ProviderLibraryPage.restore(this,provider,providerName(id),search,new ProviderLibraryPage.Actions(){
            public void openTracks(List<Track> tracks,String heading){rememberProvider(id,tracks);showAlbum(new ArrayList<>(tracks),heading);}
            public void play(List<Track> tracks,int index){if(player==null||tracks.isEmpty())return;rememberProvider(id,tracks);playNeedle();player.setQueue(new ArrayList<>(tracks),index,true);returnToPlayer();}
            public void officialLogin(){if(!provider.supportsAccount())return;startActivityForResult(new Intent(MainActivity.this,"qq".equals(id)?QQMusicLoginActivity.class:"netease".equals(id)?NeteaseWebLoginActivity.class:AppleMusicLoginActivity.class),"qq".equals(id)?QQ_WEB_LOGIN:"netease".equals(id)?NETEASE_WEB_LOGIN:APPLE_LOGIN);}
            public void onAccount(MusicProvider.Account account){if(!provider.supportsAccount())return;String next=account.loggedIn?account.id:"";if("netease".equals(id)){if(!next.equals(neteaseAccountId)){clearOnlineAccount(true);neteaseAccountId=next;OnlineCatalog.stage(MainActivity.this,next,Collections.emptyList());saveOnlineCatalog();}return;}if(!next.equals(providerAccounts.getOrDefault(id,""))){clearProviderMetadata(id);providerAccounts.put(id,next);OnlineCatalog.stage(MainActivity.this,id,next,Collections.emptyList());persistProvider(id);}}
            public void onLogout(){clearProviderMetadata(id);}
        },saved);
        providerPages.put(id,page);Dialog dialog=page.show();dialogs.add(dialog);fullPages++;turntable.setActive(false);handler.removeCallbacks(hideChrome);
        pageSnapshots.put(dialog,()->{Bundle state=pageState("provider");state.putString("provider_id",id);state.putBundle("panel",page.snapshot());return state;});
        page.setOnClosed(()->{if(dialogs.remove(dialog))fullPages=Math.max(0,fullPages-1);pageSnapshots.remove(dialog);if(providerPages.get(id)==page)providerPages.remove(id);if(turntable!=null)turntable.setActive(foreground&&fullPages==0);});
    }
    private void rememberProvider(String id,List<Track> tracks){
        if("netease".equals(id)){rememberOnline(tracks);return;}
        LinkedHashMap<String,Track> merged=new LinkedHashMap<>();for(Track track:library)merged.put(LibraryStore.identity(track),track);
        for(Track track:tracks)if(id.equals(OnlineMusic.providerId(track)))merged.put(LibraryStore.identity(track),track);
        library.clear();library.addAll(merged.values());OnlineCatalog.stage(this,id,providerAccounts.getOrDefault(id,""),tracks);persistProvider(id);if(shelfDialog!=null&&shelfDialog.isShowing())renderShelf();
    }
    private void persistProvider(String id){io.execute(()->{try{OnlineCatalog.persist(getApplicationContext(),id);}catch(ProviderException failure){handler.post(()->{if(!destroyed)toast(failure.getMessage());});}});}
    private void clearProviderMetadata(String id){
        if("netease".equals(id)){onOnlineLoggedOut();return;}
        ArtistPage.invalidateProvider(artistPage,id);
        if(aggregatePage!=null)aggregatePage.refresh();
        providerAccounts.put(id,"");library.removeIf(track->id.equals(OnlineMusic.providerId(track)));OnlineCatalog.resetMemory(this,id);persistProvider(id);
        if(player!=null)player.removeProviderTracks(id);
        for(java.util.Map.Entry<Dialog,AlbumPageState> entry:new ArrayList<>(albumPages.entrySet()))if(entry.getValue().songs.stream().anyMatch(track->id.equals(OnlineMusic.providerId(track))))entry.getKey().dismiss();
        if(shelfDialog!=null&&shelfDialog.isShowing())renderShelf();refresh();
    }
    private void openProviderAlbum(Track track){
        openProviderAlbum(OnlineMusic.providerId(track),track.albumId,track.album);
    }
    private void openProviderAlbum(String id,String albumId,String albumName){
        if(id.isEmpty()||albumId.isEmpty())return;
        if("qishui".equals(id)){showMusicSources();return;}
        MusicProvider provider=OnlineMusic.provider(this,id);long epoch=OnlineMusic.sessionGeneration(this,id);
        LinearLayout body=column();body.addView(note("正在读取“"+albumName+"”的完整曲目…"));Dialog loading=sheet("完整专辑",body,false);
        io.execute(()->{try{MusicProvider.Album album=provider.album(albumId);handler.post(()->{
            if(destroyed||!loading.isShowing()||OnlineMusic.sessionGeneration(this,id)!=epoch)return;
            loading.dismiss();rememberProvider(id,album.tracks);
            if(album.tracks.isEmpty())toast("该专辑暂时没有可读取的曲目");else showAlbum(new ArrayList<>(album.tracks));
        });}catch(ProviderException failure){handler.post(()->{if(!destroyed&&loading.isShowing()){body.removeAllViews();body.addView(note(failure.getMessage()));body.addView(actionButton("重试",()->{loading.dismiss();openProviderAlbum(id,albumId,albumName);}));}});}});
    }

    private void showNetease(){showNetease(null,null,null);}
    private void showNetease(Bundle state,String albumId,String artistId){
        // Keep the originating search/album page alive until Back, including its list position.
        NeteasePage.Actions actions=new NeteasePage.Actions(){
            public void openTracks(List<Track> tracks,String heading){rememberOnline(tracks);showAlbum(new ArrayList<>(tracks),heading);}
            public void play(List<Track> tracks,int index){if(player==null||tracks.isEmpty())return;rememberOnline(tracks);playNeedle();player.setQueue(new ArrayList<>(tracks),index,true);returnToPlayer();}
            public void onLogout(){onOnlineLoggedOut();}
            public void onAccount(MusicProvider.Account account){String id=account.loggedIn?account.id:"";if(!id.equals(neteaseAccountId)){clearOnlineAccount();neteaseAccountId=id;OnlineCatalog.stage(MainActivity.this,id,Collections.emptyList());saveOnlineCatalog();}}
            public void officialLogin(){startActivityForResult(new Intent(MainActivity.this,NeteaseWebLoginActivity.class),NETEASE_WEB_LOGIN);}
        };
        NeteasePage page=NeteasePage.restore(this,OnlineMusic.netease(this),actions,state);
        if(albumId!=null)page.showAlbum(albumId);if(artistId!=null)page.showArtist(artistId);
        neteasePage=page;Dialog dialog=page.show();neteaseDialog=dialog;dialogs.add(dialog);fullPages++;
        neteasePages.put(dialog,page);
        if(turntable!=null)turntable.setActive(false);handler.removeCallbacks(hideChrome);
        pageSnapshots.put(dialog,()->{Bundle snapshot=pageState("netease");snapshot.putBundle("panel",page.snapshot());return snapshot;});
        page.setOnClosed(()->{if(dialogs.remove(dialog))fullPages=Math.max(0,fullPages-1);pageSnapshots.remove(dialog);pageRestorers.remove(dialog);neteasePages.remove(dialog);if(neteasePage==page){neteasePage=null;neteaseDialog=null;for(java.util.Map.Entry<Dialog,NeteasePage> entry:neteasePages.entrySet())if(entry.getKey().isShowing()){neteaseDialog=entry.getKey();neteasePage=entry.getValue();}}if(turntable!=null)turntable.setActive(foreground&&fullPages==0);});
        if(refreshNeteaseAfterRestore){refreshNeteaseAfterRestore=false;page.refreshAccount();}
    }
    private void rememberOnline(List<Track> tracks){
        LinkedHashMap<String,Track> merged=new LinkedHashMap<>();for(Track t:library)merged.put(LibraryStore.identity(t),t);
        for(Track t:tracks)if(OnlineMusic.isNetease(t))merged.put(LibraryStore.identity(t),t);
        library.clear();library.addAll(merged.values());OnlineCatalog.stage(this,neteaseAccountId,tracks);saveOnlineCatalog();
        if(shelfDialog!=null&&shelfDialog.isShowing())renderShelf();
    }
    private void saveOnlineCatalog(){io.execute(()->{try{OnlineCatalog.persist(getApplicationContext());}catch(ProviderException failure){handler.post(()->{if(!destroyed)toast(failure.getMessage());});}});}
    private void onOnlineLoggedOut(){
        if(aggregatePage!=null)aggregatePage.refresh();
        if(destroyed)return;neteaseAccountId="";library.removeIf(OnlineMusic::isNetease);
        ArtistPage.invalidateProvider(artistPage,"netease");ProviderLibraryPage catalog=providerPages.get("netease");if(catalog!=null)catalog.close();
        closePreviousOnlinePages();if(neteasePage!=null)neteasePage.refreshAccount();
        if(shelfDialog!=null&&shelfDialog.isShowing())renderShelf();refresh();
    }
    private void closePreviousOnlinePages(){
        for(java.util.Map.Entry<Dialog,AlbumPageState> entry:new ArrayList<>(albumPages.entrySet()))if(!entry.getValue().songs.isEmpty()&&OnlineMusic.isNetease(entry.getValue().songs.get(0)))entry.getKey().dismiss();
        for(NeteasePage page:new ArrayList<>(neteasePages.values()))if(page!=neteasePage)page.close();
    }
    private void clearOnlineAccount(){clearOnlineAccount(false);}
    private void clearOnlineAccount(boolean keepCatalogPage){
        ArtistPage.invalidateProvider(artistPage,"netease");if(!keepCatalogPage){ProviderLibraryPage catalog=providerPages.get("netease");if(catalog!=null)catalog.close();}
        neteaseAccountId="";library.removeIf(OnlineMusic::isNetease);OnlineCatalog.resetMemory(this);saveOnlineCatalog();
        closePreviousOnlinePages();
        if(player!=null)player.removeProviderTracks("netease");if(shelfDialog!=null&&shelfDialog.isShowing())renderShelf();refresh();
    }
    private void showArtist(Track seed,String requestedName){
        if(seed!=null)showArtistPage(seed,requestedName,"",null);
    }
    private void showCatalogArtist(String provider,MusicProvider.Artist identity){
        if(identity==null||identity.id.isEmpty()||identity.name.isEmpty())return;
        if(!"qq".equals(provider)&&!"netease".equals(provider)&&!"applemusic".equals(provider))return;
        showArtistPage(null,identity.name,provider,identity);
    }
    private void showArtistPage(Track seed,String requestedName,String provider,MusicProvider.Artist identity){
        if(artistDialog!=null)artistDialog.dismiss();
        final Dialog[] owner=new Dialog[1];
        ArtistPage.Navigation navigation=new ArtistPage.Navigation(){
            public void back(){if(owner[0]!=null)owner[0].dismiss();}
            public void play(ArrayList<Track> songs,int index,boolean shuffle){if(player==null||songs.isEmpty())return;playNeedle();player.setPlayMode(shuffle?PlayerService.MODE_SHUFFLE:PlayerService.MODE_ORDER);player.setQueue(latestLocalTracks(songs),shuffle?new java.util.Random().nextInt(songs.size()):index,true);}
            public void album(ArrayList<Track> songs){showAlbum(songs);}
            public void playlist(String name,ArrayList<Track> songs){showAlbum(songs,name);}
            public void share(String title,String subtitle,ArrayList<Track> songs){showShare(title,subtitle,songs);}
            public void library(){showShelf();}public void current(){returnToPlayer();}public void queue(){showQueue();}public void toggle(){togglePlayback();}
        };
        FrameLayout page=identity==null?ArtistPage.create(this,new ArrayList<>(library),seed,requestedName,navigation):ArtistPage.create(this,new ArrayList<>(library),provider,identity,navigation);
        artistPage=page;ArtistPage.updatePlayback(page,isPlaybackPlaying(),currentArtwork,usingExternal()?(externalMusic.getState().playing?externalMusic.getState().canPause:externalMusic.getState().canPlay):player!=null);artistDialog=fullPage(page,true);owner[0]=artistDialog;ArtistPage.bindWindow(page,artistDialog.getWindow());
        pageRestorers.put(artistDialog,()->showArtistPage(seed,requestedName,provider,identity));
        pageSnapshots.put(artistDialog,()->{Bundle b=pageState("artist");if(identity==null)b.putString("seed",seed.toJson().toString());else{b.putString("catalog_provider",provider);b.putString("catalog_artist_id",identity.id);b.putString("artist_description",identity.description);b.putString("artist_avatar",identity.avatarUrl);}b.putString("artist_name",requestedName);b.putBundle("chapters",ArtistPage.saveState(page));return b;});
    }
    private void showShare(String title,String subtitle,List<Track> songs){if(shareStudio!=null)shareStudio.close();registerShare(new ShareStudio(this,title,subtitle,songs,themes.background(albumKey()),themes.disc(albumKey())));}
    private Dialog registerShare(ShareStudio studio){shareStudio=studio;Dialog d=studio.show();dialogs.add(d);pageSnapshots.put(d,()->{Bundle b=pageState("share");b.putBundle("share",studio.snapshot());return b;});studio.setOnClosed(()->{dialogs.remove(d);pageSnapshots.remove(d);pageRestorers.remove(d);if(shareStudio==studio&&!studio.hasPendingSave())shareStudio=null;});return d;}
    private void showTrackMenu(){if(usingExternal()){showMusicSources();return;}Track t=player==null?null:player.getCurrent();if(t==null){showShelf();return;}LinearLayout body=column();Dialog d=sheet(t.title,body,false);body.addView(note(t.artist+" · "+t.album));LinearLayout actions=row();likeButton=iconButton(collections.liked(t)?"heartfilled":"heart",collections.liked(t)?"取消喜欢":"喜欢歌曲",()->{boolean liked=collections.toggleLike(t);likeButton.setSymbol(liked?"heartfilled":"heart");toast(liked?"已加入喜欢的歌曲":"已取消喜欢");});actions.addView(likeButton);actions.addView(iconButton("artist","音乐人",()->{d.dismiss();showArtist(t);}));actions.addView(iconButton("share","分享歌曲",()->{d.dismiss();showShare(t.title,t.artist,single(t));}));actions.addView(iconButton("add","加入歌单",()->{d.dismiss();addToPlaylist(single(t));}));body.addView(actions);body.addView(actionButton("查看专辑",()->{d.dismiss();showCurrentAlbum();}));body.addView(actionButton("播放队列",()->{d.dismiss();showQueue();}));body.addView(actionButton("睡眠定时",()->{d.dismiss();showSleepTimer();}));}
    private void showSongMenu(Track track){String[] options={collections.liked(track)?"取消喜欢":"喜欢歌曲","下一首播放","加入播放队列","加入歌单","查看音乐人","分享歌曲"};AlertDialog d=new AlertDialog.Builder(this).setTitle(track.title).setItems(options,(a,index)->{if(index==0){collections.toggleLike(track);if(shelfDialog!=null&&shelfDialog.isShowing())renderShelf();}if(index==1&&player!=null)player.enqueue(latestLocalTracks(single(track)),true);if(index==2&&player!=null)player.enqueue(latestLocalTracks(single(track)),false);if(index==3)addToPlaylist(single(track));if(index==4)showArtist(track);if(index==5)showShare(track.title,track.artist,single(track));}).create();trackDialog(d);d.show();}
    private void showAlbumActions(ArrayList<Track> songs){if(songs.isEmpty())return;Track first=songs.get(0);AlertDialog d=new AlertDialog.Builder(this).setTitle(first.album).setItems(new String[]{"加入歌单","接下来播放","加入播放队列","分享专辑"},(a,index)->{if(index==0)addToPlaylist(songs);if(index==1&&player!=null)player.enqueue(latestLocalTracks(songs),true);if(index==2&&player!=null)player.enqueue(latestLocalTracks(songs),false);if(index==3)showShare(first.album,first.albumArtist,songs);}).create();trackDialog(d);d.show();}
    private ArrayList<Track> single(Track track){ArrayList<Track> result=new ArrayList<>();result.add(track);return result;}
    private void addToPlaylist(ArrayList<Track> songs){ArrayList<LibraryStore.Playlist> lists=collections.playlists();String[] names=new String[lists.size()+1];names[0]="＋ 新建歌单";for(int i=0;i<lists.size();i++)names[i+1]=lists.get(i).name;AlertDialog d=new AlertDialog.Builder(this).setTitle("加入歌单").setItems(names,(a,which)->{if(which==0)createPlaylist(songs);else{collections.add(lists.get(which-1).id,songs);toast("已加入 "+lists.get(which-1).name);if(shelfDialog!=null&&shelfDialog.isShowing())renderShelf();}}).create();trackDialog(d);d.show();}
    private void createPlaylist(ArrayList<Track> initial){registerDraft(PlaylistDraftDialog.create(this,collections,library,initial,this::playlistSaved));}
    private void playlistSaved(){if(shelfDialog!=null&&shelfDialog.isShowing())renderShelf();toast("歌单已保存");}
    private Dialog registerDraft(PlaylistDraftDialog draft){if(draft==null)return null;activeDraft=draft;Dialog d=draft.show();dialogs.add(d);pageSnapshots.put(d,()->{Bundle b=pageState("playlist_draft");b.putBundle("draft",draft.snapshot());return b;});d.setOnDismissListener(ignored->{dialogs.remove(d);pageSnapshots.remove(d);pageRestorers.remove(d);if(activeDraft==draft&&!draft.hasPendingCover())activeDraft=null;});return d;}
    private void playlistMenu(LibraryStore.Playlist playlist){AlertDialog d=new AlertDialog.Builder(this).setTitle(playlist.name).setItems(new String[]{"播放","编辑歌曲","重命名","删除歌单"},(a,index)->{if(index==0)playAlbum(playlist.tracks,false);if(index==1)editPlaylist(playlist);if(index==2)registerDraft(PlaylistDraftDialog.rename(this,collections,library,playlist,this::playlistSaved));if(index==3){AlertDialog confirm=new AlertDialog.Builder(this).setTitle("删除“"+playlist.name+"”？").setMessage("只删除这个歌单，原始音乐文件会保留。").setNegativeButton("取消",null).setPositiveButton("删除",(b,n)->{collections.delete(playlist.id);renderShelf();}).create();trackDialog(confirm);confirm.show();}}).create();trackDialog(d);d.show();}
    private void editPlaylist(LibraryStore.Playlist playlist){registerDraft(PlaylistDraftDialog.edit(this,collections,library,playlist,this::playlistSaved));}

    private int blend(int a,int b,float amount){return Color.rgb((int)(Color.red(a)*(1-amount)+Color.red(b)*amount),(int)(Color.green(a)*(1-amount)+Color.green(b)*amount),(int)(Color.blue(a)*(1-amount)+Color.blue(b)*amount));}
    private Dialog sheet(String heading, LinearLayout body, boolean retainStage) {
        return sheet(heading,body,retainStage,false);
    }
    private Dialog sheet(String heading, LinearLayout body, boolean retainStage, boolean albumSurface) {
        Dialog dialog = new Dialog(this); dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel = column(); panel.setPadding(dp(22), dp(10), dp(22), dp(20)); panel.setBackground(albumSurface ? DesignSystem.settingsCard(this,24) : DesignSystem.pageCard(this,24));
        TextView handle = text("━━━━", 16, 0xffb0a5b6); handle.setGravity(Gravity.CENTER); panel.addView(handle, new LinearLayout.LayoutParams(-1, dp(24)));
        LinearLayout top = row(); top.setGravity(Gravity.CENTER_VERTICAL); TextView h = text(heading, 21, albumSurface ? Color.WHITE : 0xff302737); h.setTypeface(null, Typeface.BOLD); top.addView(h, new LinearLayout.LayoutParams(0, dp(54), 1)); Button close = quietButton("完成", "关闭面板", dialog::dismiss); if(albumSurface){close.setTextColor(Color.WHITE);close.setBackground(DesignSystem.ambientButton(this,24));} top.addView(close); panel.addView(top);
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(false); scroll.setClipToPadding(false); scroll.addView(body, new ScrollView.LayoutParams(-1, -2)); panel.addView(scroll, new LinearLayout.LayoutParams(-1, -2));
        dialog.setContentView(panel); Window window = dialog.getWindow(); if (window != null) { window.setBackgroundDrawableResource(android.R.color.transparent); window.setGravity(Gravity.BOTTOM); window.setDimAmount(retainStage ? 0 : .22f); window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND); window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE); }
        trackDialog(dialog); dialog.show(); if (window != null) { window.setLayout(-1, -2); scroll.post(() -> { if (!dialog.isShowing() || destroyed) return; int hPx = getResources().getDisplayMetrics().heightPixels; int max = (int) (hPx * (retainStage ? .49f : .78f)); scroll.getLayoutParams().height = Math.min(Math.max(dp(80), max - dp(115)), measureBody(body, getResources().getDisplayMetrics().widthPixels - dp(44))); scroll.requestLayout(); }); }
        return dialog;
    }
    private int measureBody(View v, int width) { v.measure(View.MeasureSpec.makeMeasureSpec(Math.max(1, width), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)); return Math.max(dp(80), v.getMeasuredHeight()); }
    private Button quietButton(String label, String description, Runnable action) { Button b = new Button(this); b.setText(label); b.setContentDescription(description); b.setTextSize(12); b.setAllCaps(false); b.setTextColor(DesignSystem.INK); b.setMinWidth(0); b.setMinimumWidth(0); b.setPadding(dp(12), 0, dp(12), 0); b.setBackground(rounded(Color.TRANSPARENT, 16)); b.setOnClickListener(v -> action.run()); b.setLayoutParams(new LinearLayout.LayoutParams(-2, dp(48))); return b; }
    private Button physicalButton(String label, String description, Runnable action) { Button b = quietButton(label, description, action); b.setTextSize(23); b.setTextColor(0xffe2dce7); b.setTypeface(null, Typeface.BOLD); GradientDrawable surface = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xff55515d, 0xff29262e, 0xff35313c}); surface.setCornerRadius(dp(32)); surface.setStroke(dp(1), 0xff69616f); b.setBackground(surface); b.setElevation(dp(5)); return b; }
    private Button actionButton(String label, Runnable action) { Button b = quietButton(label, label, action); b.setTextSize(14); b.setBackground(DesignSystem.pageButton(this,16)); LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(50)); lp.topMargin = dp(10); lp.bottomMargin = dp(8); b.setLayoutParams(lp); return b; }
    private Button itemButton(String label, Runnable action) { Button b = actionButton(label, action); b.setGravity(Gravity.START | Gravity.CENTER_VERTICAL); b.setPadding(dp(14), dp(10), dp(14), dp(10)); b.setTextSize(14); b.setMinHeight(dp(68)); b.setMinimumHeight(dp(68)); b.setBackground(rounded(0x80ffffff, 16)); return b; }
    private Button chip(String label, boolean selected) { Button b = actionButton(label, () -> {}); b.setTextSize(12); b.setPadding(dp(3), 0, dp(3), 0); b.setBackground(rounded(selected ? 0xffc7b5d2 : 0xffe8e0ed, 13)); return b; }
    private TextView sectionLabel(String value) { TextView t = text(value, 13, DesignSystem.MUTED); t.setPadding(0, dp(20), 0, dp(10)); t.setLetterSpacing(.05f); return t; }
    private TextView note(String value) { TextView t = text(value, 14, DesignSystem.MUTED); t.setLineSpacing(dp(4), 1); t.setPadding(0, dp(10), 0, dp(10)); return t; }
    private TextView text(String value, int size, int color) { TextView t = new TextView(this); t.setText(value); t.setTextSize(size); t.setTextColor(color); t.setFontFeatureSettings("kern"); return t; }
    private LinearLayout column() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }
    private LinearLayout row() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.HORIZONTAL); return l; }
    private LinearLayout.LayoutParams rowParams() { LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.topMargin = dp(5); lp.bottomMargin = dp(5); return lp; }
    private GradientDrawable rounded(int color, int radius) { GradientDrawable g = new GradientDrawable(); g.setColor(color); g.setCornerRadius(dp(radius)); return g; }
    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private String time(long milliseconds) { long seconds = Math.max(0, milliseconds) / 1000; return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60); }
    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_SHORT).show(); }
}
