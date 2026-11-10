package com.recordroom.vinyl;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;

/** One process-level writer. Playback events replace a pending snapshot instead of queuing jobs. */
final class PlayerStateStore {
    private static WeakReference<Context> owner = new WeakReference<>(null);
    private static PlayerStateStore instance;
    static synchronized PlayerStateStore get(Context context) {
        Context app = context.getApplicationContext();
        if (instance == null || owner.get() != app) {
            owner = new WeakReference<>(app);
            instance = new PlayerStateStore(app.getSharedPreferences(PlayerService.PREFS, Context.MODE_PRIVATE));
        }
        return instance;
    }

    static final class QueueSnapshot {
        final long revision;
        final List<Track> tracks;
        private String json; // Accessed only by the single writer.
        QueueSnapshot(long revision, List<Track> source) {
            this.revision = revision;
            tracks = Collections.unmodifiableList(new ArrayList<>(source));
        }
    }
    static final class State {
        final QueueSnapshot queue;
        final int index, mode;
        final long position, sequence;
        final boolean ended;
        final float volume;
        State(QueueSnapshot queue, int index, long position, boolean ended, int mode, float volume, long sequence) {
            this.queue = queue; this.index = queue.tracks.isEmpty() ? -1 : Math.max(0, Math.min(index, queue.tracks.size()-1));
            this.position = Math.max(0, position); this.ended = ended;
            this.mode = Math.max(PlayerService.MODE_ORDER, Math.min(PlayerService.MODE_SHUFFLE, mode));
            this.volume = Float.isNaN(volume) || Float.isInfinite(volume) ? 1f : Math.max(0, Math.min(1, volume));
            this.sequence = sequence;
        }
    }

    private final SharedPreferences preferences;
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "vinyl-player-state"); thread.setDaemon(true); return thread;
    });
    private State latest, pending;
    private boolean writing;
    private long queueRevision, sequence, session;
    private volatile long queueEncodeCount;

    private PlayerStateStore(SharedPreferences preferences) { this.preferences = preferences; }
    synchronized long openSession() { return ++session; }
    synchronized QueueSnapshot copyQueue(List<Track> tracks) { return new QueueSnapshot(++queueRevision, tracks); }

    synchronized State restore() {
        if (latest != null) return latest;
        ArrayList<Track> tracks = new ArrayList<>();
        int originalIndex = preferences.getInt("index", 0), selected = -1, next = -1;
        try {
            JSONArray values = new JSONArray(preferences.getString("queue", "[]"));
            for (int i=0; i<values.length(); i++) {
                org.json.JSONObject value = values.optJSONObject(i);
                if (value == null) continue;
                Track track = Track.fromJson(value);
                if (track.uri.isEmpty()) continue;
                // The saved index addresses the original array, not this filtered queue.
                int filteredIndex = tracks.size(); tracks.add(track);
                if (i == originalIndex) selected = filteredIndex;
                if (i > originalIndex && next < 0) next = filteredIndex;
            }
        } catch (Exception ignored) { tracks.clear(); selected = next = -1; }
        boolean sameTrack = selected >= 0;
        // Missing selection: prefer the next valid item, then the previous/last item. Never reuse another song's progress.
        int restoredIndex = sameTrack ? selected : next >= 0 ? next : tracks.size() - 1;
        latest = new State(copyQueue(tracks), restoredIndex, sameTrack ? preferences.getLong("position", 0) : 0,
                sameTrack && preferences.getBoolean("ended", false), preferences.getInt("mode", PlayerService.MODE_ORDER),
                preferences.getFloat("volume", 1f), ++sequence);
        return latest;
    }

    synchronized void save(long ownerSession, QueueSnapshot queue, int index, long position, boolean ended, int mode, float volume) {
        // A delayed callback or destroy from an older Service cannot overwrite its replacement.
        if (ownerSession != session) return;
        latest = new State(queue, index, position, ended, mode, volume, ++sequence);
        pending = latest;
        if (!writing) { writing = true; writer.execute(this::drain); }
    }

    private void drain() {
        while (true) {
            State state;
            synchronized (this) {
                state = pending; pending = null;
                if (state == null) { writing = false; return; }
            }
            try {
                QueueSnapshot queue = state.queue;
                if (queue.json == null) {
                    JSONArray values = new JSONArray();
                    for (Track track : queue.tracks) values.put(track.toJson());
                    queue.json = values.toString(); queueEncodeCount++;
                }
                // Keep the established format and write matching queue/index/scalars in one transaction.
                boolean saved = preferences.edit().putString("queue", queue.json).putInt("index", state.index)
                        .putLong("position", state.position).putBoolean("ended", state.ended)
                        .putInt("mode", state.mode).putFloat("volume", state.volume).commit();
                if (!saved) Log.w("PlayerStateStore", "Could not persist playback state");
            } catch (RuntimeException failure) {
                Log.w("PlayerStateStore", "Could not persist playback state", failure);
            }
        }
    }
}
