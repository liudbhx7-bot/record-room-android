package com.recordroom.vinyl;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.widget.RemoteViews;
import java.util.Objects;

/** Shares the selected official media session between installed widgets and visible setup pages. Main thread only. */
public final class WidgetLinkControl extends BroadcastReceiver {
    private static SharedPreferences sources;
    private static ExternalMusicBridge bridge;
    private static ExternalMusicBridge.State last;
    private static int artGeneration;
    private static Context app;
    private static final SharedPreferences.OnSharedPreferenceChangeListener selection = (preferences, key) -> {
        if (key == null || "external_music_source".equals(key)) {
            select();
            VinylWidgetProvider.updateAll(app);
        }
    };

    static ExternalMusicBridge.State current(Context context) {
        if (sources == null) {
            app = context.getApplicationContext();
            sources = app.getSharedPreferences("MainActivity", Context.MODE_PRIVATE);
            sources.registerOnSharedPreferenceChangeListener(selection);
            select();
        }
        return bridge == null ? null : bridge.getState();
    }
    private static void select() {
        String id = sources.getString("external_music_source","");
        if (bridge != null && id.equals(bridge.getSelectedProviderId())) return;
        closeBridge();
        if (ExternalMusicBridge.providerForId(id) == null) return;
        bridge = new ExternalMusicBridge(app,state -> {
            int generation = state.artwork == null || state.artwork.isRecycled() ? 0 : state.artwork.getGenerationId();
            boolean changed = last == null || last.playing != state.playing || last.status != state.status
                    || last.actions != state.actions || !last.mediaId.equals(state.mediaId) || !last.title.equals(state.title)
                    || !last.artist.equals(state.artist) || !last.album.equals(state.album)
                    || !last.artworkUri.equals(state.artworkUri) || last.artwork != state.artwork || artGeneration != generation;
            artGeneration = generation;
            last = state;
            if (changed) VinylWidgetProvider.updateAll(app);
        });
        bridge.disableTimelineTicks();
        bridge.selectProvider(id);
        bridge.start();
    }
    private static void closeBridge() {
        if (bridge != null) { ExternalMusicBridge old=bridge; bridge=null; old.close(); }
        last = null;
    }
    static void stop() {
        if (sources != null) sources.unregisterOnSharedPreferenceChangeListener(selection);
        closeBridge();
        sources = null;
        app = null;
    }

    static void bind(Context context, RemoteViews views, ExternalMusicBridge.State state, PendingIntent open) {
        button(context,views,R.id.widget_previous,"previous","上一首",state.canPrevious,state,open);
        button(context,views,R.id.widget_play,"toggle",state.playing?"暂停":"播放",state.playing?state.canPause:state.canPlay,state,open);
        button(context,views,R.id.widget_next,"next","下一首",state.canNext,state,open);
    }
    private static void button(Context c, RemoteViews v, int viewId, String action, String label,
                               boolean enabled, ExternalMusicBridge.State state, PendingIntent open) {
        Intent i = new Intent(c, WidgetLinkControl.class).setAction(action)
                .setData(new android.net.Uri.Builder().scheme("recordroom").authority("linked-player").appendPath(state.selectedProviderId).build());
        PendingIntent send = enabled ? PendingIntent.getBroadcast(c,viewId,i,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT) : open;
        v.setOnClickPendingIntent(viewId,send);
        v.setContentDescription(viewId,enabled ? state.selectedProvider.name + "，" + label : "打开唱片机查看联动状态");
    }
    @Override public void onReceive(Context context, Intent intent) {
        ExternalMusicBridge.State state=current(context);
        if (state == null || !state.connected || intent.getData() == null || !Objects.equals(intent.getData().getLastPathSegment(),state.selectedProviderId)) return;
        if ("previous".equals(intent.getAction())) bridge.previous();
        else if ("next".equals(intent.getAction())) bridge.next();
        else if ("toggle".equals(intent.getAction())) bridge.togglePlayPause();
    }
}
