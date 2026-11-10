package com.recordroom.vinyl;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Local collections: shared read-your-writes snapshots, with JSON and disk work off the UI thread. */
final class LibraryStore {
    static final class Playlist {
        final String id, name, description, coverUri;
        final ArrayList<Track> tracks;
        Playlist(String id, String name, List<Track> tracks) {
            this(id,name,"","",tracks);
        }
        Playlist(String id,String name,String description,String coverUri,List<Track> tracks){
            this.id=id;this.name=name;this.description=description==null?"":description;this.coverUri=coverUri==null?"":coverUri;this.tracks=new ArrayList<>(tracks);
        }
    }

    // Android returns one SharedPreferences object per preference file within this process.
    // Holding the store for the process lifetime also covers short-lived service/widget callers.
    private static void addSupported(java.util.List<Track> tracks,Track track){if(!"unsupported".equals(OnlineMusic.providerId(track)))tracks.add(track);}
    private static final Map<SharedPreferences, State> STORES = new IdentityHashMap<>();
    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "vinyl-library-store"); thread.setDaemon(true); return thread;
    });
    private final State state;

    LibraryStore(Context context) {
        SharedPreferences preferences = context.getSharedPreferences("record_room_collections", Context.MODE_PRIVATE);
        synchronized (STORES) {
            State found = STORES.get(preferences);
            if (found == null) { found = new State(preferences); STORES.put(preferences, found); }
            state = found;
        }
    }

    static String identity(Track track) { return track.source + "\u001f" + track.id; }

    private static final class Tracks {
        final List<Track> values;
        final Set<String> identities;
        String json; // Only the single writer accesses encoded snapshots after construction.
        Tracks(List<Track> tracks) {
            values = Collections.unmodifiableList(new ArrayList<>(tracks));
            HashSet<String> ids = new HashSet<>();
            for (Track track : values) ids.add(identity(track));
            identities = Collections.unmodifiableSet(ids);
        }
    }

    private static final class StoredPlaylist {
        final String id, name, description, coverUri;
        final Tracks tracks;
        StoredPlaylist(String id,String name,String description,String coverUri,Tracks tracks){this.id=id;this.name=name;this.description=description;this.coverUri=coverUri;this.tracks=tracks;}
        Playlist copy() { return new Playlist(id,name,description,coverUri,tracks.values); }
    }

    private static final class Playlists {
        final List<StoredPlaylist> values;
        String json;
        Playlists(List<StoredPlaylist> playlists) { values = Collections.unmodifiableList(new ArrayList<>(playlists)); }
    }

    private static final class Snapshot {
        final Tracks favorites, recent;
        final Playlists playlists;
        Snapshot(Tracks favorites, Tracks recent, Playlists playlists) {
            this.favorites = favorites; this.recent = recent; this.playlists = playlists;
        }
    }

    private static final class State {
        final SharedPreferences preferences;
        Snapshot latest, pending;
        boolean writing;
        // Diagnostics also let regression tests verify where expensive encoding actually ran.
        volatile long encodeCount;
        volatile String lastEncodeThread;

        State(SharedPreferences preferences) {
            this.preferences = preferences;
            latest = new Snapshot(readTracks(preferences.getString("favorites", "[]")),
                    readTracks(preferences.getString("recent", "[]")), readPlaylists(preferences.getString("playlists", "[]")));
        }

        // The caller owns this State's monitor. At most one queued/running drain and one pending snapshot.
        void changed(Snapshot snapshot) {
            latest = snapshot; pending = snapshot;
            if (!writing) { writing = true; WRITER.execute(this::drain); }
        }

        void encoded() { encodeCount++; lastEncodeThread = Thread.currentThread().getName(); }

        String encodeTracks(Tracks tracks) {
            if (tracks.json == null) {
                JSONArray values = new JSONArray();
                for (Track track : tracks.values) values.put(track.toJson());
                tracks.json = values.toString(); encoded();
            }
            return tracks.json;
        }

        String encodePlaylists(Playlists playlists) {
            if (playlists.json == null) {
                JSONArray values = new JSONArray();
                for (StoredPlaylist playlist : playlists.values) try {
                    JSONObject value = new JSONObject(); value.put("id", playlist.id); value.put("name", playlist.name);
                    if(!playlist.description.isEmpty())value.put("description",playlist.description);if(!playlist.coverUri.isEmpty())value.put("coverUri",playlist.coverUri);
                    JSONArray songs = new JSONArray();
                    for (Track track : playlist.tracks.values) songs.put(track.toJson());
                    value.put("tracks", songs); values.put(value);
                } catch (org.json.JSONException failure) { throw new IllegalStateException(failure); }
                playlists.json = values.toString(); encoded();
            }
            return playlists.json;
        }

        private void drain() {
            while (true) {
                Snapshot snapshot;
                synchronized (this) {
                    snapshot = pending; pending = null;
                    if (snapshot == null) { writing = false; notifyAll(); return; }
                }
                try {
                    String favorites = encodeTracks(snapshot.favorites), recent = encodeTracks(snapshot.recent);
                    String playlists = encodePlaylists(snapshot.playlists);
                    // If edits arrived during encoding, discard this obsolete encoded snapshot before disk I/O.
                    synchronized (this) { if (pending != null) continue; }
                    boolean saved = preferences.edit().putString("favorites", favorites).putString("recent", recent)
                            .putString("playlists", playlists).commit();
                    if (!saved) Log.w("LibraryStore", "Could not persist local collections");
                } catch (RuntimeException failure) {
                    Log.w("LibraryStore", "Could not persist local collections", failure);
                }
            }
        }
    }

    private static Tracks readTracks(String json) {
        ArrayList<Track> values = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) addSupported(values,Track.fromJson(array.getJSONObject(i)));
        } catch (Exception ignored) { }
        return new Tracks(values);
    }

    private static Playlists readPlaylists(String json) {
        ArrayList<StoredPlaylist> values = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                JSONObject value = array.getJSONObject(i); ArrayList<Track> tracks = new ArrayList<>();
                JSONArray songs = value.optJSONArray("tracks");
                if (songs != null) for (int n = 0; n < songs.length(); n++) addSupported(tracks,Track.fromJson(songs.getJSONObject(n)));
                values.add(new StoredPlaylist(value.getString("id"),value.optString("name","未命名歌单"),value.isNull("description")?"":value.optString("description",""),value.isNull("coverUri")?"":value.optString("coverUri",""),new Tracks(tracks)));
            }
        } catch (Exception ignored) { }
        return new Playlists(values);
    }

    ArrayList<Track> favorites() { synchronized (state) { return new ArrayList<>(state.latest.favorites.values); } }
    boolean liked(Track track) { synchronized (state) { return track != null && state.latest.favorites.identities.contains(identity(track)); } }

    boolean toggleLike(Track track) {
        if (track == null) return false;
        synchronized (state) {
            Snapshot current = state.latest; ArrayList<Track> tracks = new ArrayList<>(current.favorites.values);
            boolean removed = tracks.removeIf(item -> identity(item).equals(identity(track)));
            if (!removed) tracks.add(0, track);
            state.changed(new Snapshot(new Tracks(tracks), current.recent, current.playlists)); return !removed;
        }
    }

    ArrayList<Track> recent() { synchronized (state) { return new ArrayList<>(state.latest.recent.values); } }

    void remember(Track track) {
        if (track == null) return;
        synchronized (state) {
            Snapshot current = state.latest; List<Track> old = current.recent.values;
            if (!old.isEmpty() && identity(old.get(0)).equals(identity(track))) return;
            ArrayList<Track> tracks = new ArrayList<>(old); tracks.removeIf(item -> identity(item).equals(identity(track)));
            tracks.add(0, track); while (tracks.size() > 50) tracks.remove(tracks.size() - 1);
            state.changed(new Snapshot(current.favorites, new Tracks(tracks), current.playlists));
        }
    }

    ArrayList<Playlist> playlists() {
        synchronized (state) {
            ArrayList<Playlist> values = new ArrayList<>();
            for (StoredPlaylist playlist : state.latest.playlists.values) values.add(playlist.copy());
            return values;
        }
    }

    private void save(List<StoredPlaylist> playlists) {
        Snapshot current = state.latest;
        state.changed(new Snapshot(current.favorites, current.recent, new Playlists(playlists)));
    }

    Playlist create(String name, List<Track> initial) {
        return create(name,"","",initial);
    }
    Playlist create(String name,String description,String coverUri,List<Track> initial){
        synchronized (state) {
            StoredPlaylist playlist = new StoredPlaylist(UUID.randomUUID().toString(),clean(name),detail(description),coverUri==null?"":coverUri,new Tracks(initial));
            ArrayList<StoredPlaylist> all = new ArrayList<>(state.latest.playlists.values); all.add(playlist); save(all);
            return playlist.copy();
        }
    }

    void rename(String id, String name) {
        synchronized (state) {
            ArrayList<StoredPlaylist> all = new ArrayList<>(state.latest.playlists.values);
            for (int i = 0; i < all.size(); i++) {
                StoredPlaylist playlist = all.get(i);
                if (playlist.id.equals(id)) all.set(i,new StoredPlaylist(playlist.id,clean(name),playlist.description,playlist.coverUri,playlist.tracks));
            }
            save(all);
        }
    }

    void delete(String id) {
        synchronized (state) {
            ArrayList<StoredPlaylist> all = new ArrayList<>(state.latest.playlists.values);
            all.removeIf(playlist -> playlist.id.equals(id)); save(all);
        }
    }

    /** One snapshot publishes the whole confirmed draft, never partially saved metadata. */
    void update(String id,String name,String description,String coverUri,List<Track> songs){
        synchronized(state){
            ArrayList<StoredPlaylist> all=new ArrayList<>(state.latest.playlists.values);
            for(int i=0;i<all.size();i++){StoredPlaylist playlist=all.get(i);if(playlist.id.equals(id))all.set(i,new StoredPlaylist(id,clean(name),detail(description),coverUri==null?"":coverUri,new Tracks(songs)));}
            save(all);
        }
    }

    void replaceTracks(String id, List<Track> songs) {
        synchronized (state) {
            ArrayList<StoredPlaylist> all = new ArrayList<>(state.latest.playlists.values);
            for (int i = 0; i < all.size(); i++) {
                StoredPlaylist playlist = all.get(i);
                if (playlist.id.equals(id)) all.set(i,new StoredPlaylist(playlist.id,playlist.name,playlist.description,playlist.coverUri,new Tracks(songs)));
            }
            save(all);
        }
    }

    void add(String id, List<Track> songs) {
        synchronized (state) {
            ArrayList<StoredPlaylist> all = new ArrayList<>(state.latest.playlists.values);
            for (int i = 0; i < all.size(); i++) {
                StoredPlaylist playlist = all.get(i);
                if (!playlist.id.equals(id)) continue;
                ArrayList<Track> tracks = new ArrayList<>(playlist.tracks.values);
                HashSet<String> known = new HashSet<>(playlist.tracks.identities);
                for (Track track : songs) if (known.add(identity(track))) tracks.add(track);
                all.set(i,new StoredPlaylist(playlist.id,playlist.name,playlist.description,playlist.coverUri,new Tracks(tracks)));
            }
            save(all);
        }
    }

    /** Test/diagnostic barrier; UI actions rely on immediate snapshots and never wait for disk. */
    boolean awaitIdle(long timeout, TimeUnit unit) throws InterruptedException {
        long remaining = unit.toNanos(timeout), deadline = System.nanoTime() + remaining;
        synchronized (state) {
            while (state.writing) {
                if (remaining <= 0) return false;
                TimeUnit.NANOSECONDS.timedWait(state, remaining); remaining = deadline - System.nanoTime();
            }
            return true;
        }
    }

    private static String clean(String name) {
        String value = name == null ? "" : name.trim();
        return value.isEmpty() ? "新建歌单" : value.substring(0, Math.min(80, value.length()));
    }
    private static String detail(String value){return value==null?"":value.trim();}
}

