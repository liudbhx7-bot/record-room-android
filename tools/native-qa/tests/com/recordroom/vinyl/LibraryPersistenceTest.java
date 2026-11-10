package com.recordroom.vinyl;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Checks actual persisted wire data and immediate UI reads while the real writer is blocked. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class LibraryPersistenceTest {
    private static Object field(Object target, String name) throws Exception {
        Class<?> type = target instanceof Class ? (Class<?>) target : target.getClass();
        Field value = type.getDeclaredField(name); value.setAccessible(true);
        return value.get(target instanceof Class ? null : target);
    }

    private static Track track(int index) {
        return new Track("song" + index, "歌曲 " + index, "艺人", "专辑 " + index / 12,
                "本地音乐", "file:///music/" + index + ".mp3", "file:///covers/" + index / 12 + ".png",
                180000 + index, index % 12 + 1, "专辑艺人", "流行", "2026");
    }

    private static ArrayList<Track> tracks(int size) {
        ArrayList<Track> songs = new ArrayList<>(); for (int i = 0; i < size; i++) songs.add(track(i)); return songs;
    }

    private static final class PreferencesProbe extends ContextWrapper {
        final SharedPreferences disk, observed;
        final AtomicInteger stringReads = new AtomicInteger(), commits = new AtomicInteger();
        final AtomicReference<Thread> commitThread = new AtomicReference<>();
        PreferencesProbe(Context context) {
            super(context);
            disk = context.getSharedPreferences("record_room_collections", Context.MODE_PRIVATE);
            observed = (SharedPreferences) Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(),
                    new Class<?>[]{SharedPreferences.class}, (proxy, method, args) -> {
                        try {
                            if (method.getName().equals("getString")) stringReads.incrementAndGet();
                            Object result = method.invoke(disk, args);
                            if (!method.getName().equals("edit")) return result;
                            SharedPreferences.Editor editor = (SharedPreferences.Editor) result;
                            return Proxy.newProxyInstance(SharedPreferences.Editor.class.getClassLoader(),
                                    new Class<?>[]{SharedPreferences.Editor.class}, (editorProxy, editMethod, editArgs) -> {
                                        try {
                                            if (editMethod.getName().equals("commit")) {
                                                commits.incrementAndGet(); commitThread.set(Thread.currentThread());
                                            }
                                            Object edited = editMethod.invoke(editor, editArgs);
                                            return edited == editor ? editorProxy : edited;
                                        } catch (InvocationTargetException failure) { throw failure.getCause(); }
                                    });
                        } catch (InvocationTargetException failure) { throw failure.getCause(); }
                    });
        }
        @Override public SharedPreferences getSharedPreferences(String name, int mode) {
            return "record_room_collections".equals(name) ? observed : super.getSharedPreferences(name, mode);
        }
    }

    private static final class WriterBlock implements AutoCloseable {
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        final Future<?> future;
        WriterBlock() throws Exception {
            ExecutorService writer = (ExecutorService) field(LibraryStore.class, "WRITER");
            future = writer.submit(() -> {
                entered.countDown();
                try { if (!release.await(20, TimeUnit.SECONDS)) throw new AssertionError("Writer barrier timed out"); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
            });
            assertTrue(entered.await(20, TimeUnit.SECONDS));
        }
        public void close() throws Exception { release.countDown(); future.get(20, TimeUnit.SECONDS); }
    }

    private static void flush(LibraryStore store) throws Exception {
        assertTrue("Collection writer did not become idle", store.awaitIdle(20, TimeUnit.SECONDS));
    }

    @Test public void largePlaylistIsImmediatelySharedAndSavedOffTheCallingThread() throws Exception {
        PreferencesProbe context = new PreferencesProbe(RuntimeEnvironment.getApplication());
        context.disk.edit().clear().commit();
        LibraryStore first = new LibraryStore(context), second = new LibraryStore(context);
        ArrayList<Track> songs = tracks(1200); LibraryStore.Playlist created;
        Thread caller = Thread.currentThread();
        try (WriterBlock ignored = new WriterBlock()) {
            created = first.create("  1200 首收藏  ", songs);
            assertEquals(1200, second.playlists().get(0).tracks.size());
            assertEquals(created.id, second.playlists().get(0).id);
            assertEquals("1200 首收藏", second.playlists().get(0).name);
            assertEquals("[]", context.disk.getString("playlists", "[]"));
            assertEquals("No encoding may run on the create caller", 0L, field(field(first, "state"), "encodeCount"));
            // Neither the input nor a returned mutable list can mutate the owned snapshot.
            songs.clear(); created.tracks.clear(); second.playlists().get(0).tracks.clear();
            assertEquals(1200, first.playlists().get(0).tracks.size());
        }
        flush(first);
        JSONArray saved = new JSONArray(context.disk.getString("playlists", "[]"));
        assertEquals(1, saved.length()); JSONObject playlist = saved.getJSONObject(0);
        assertEquals(created.id, playlist.getString("id")); assertEquals("1200 首收藏", playlist.getString("name"));
        assertEquals(3, playlist.length()); assertEquals(1200, playlist.getJSONArray("tracks").length());
        JSONObject last = playlist.getJSONArray("tracks").getJSONObject(1199);
        assertEquals(track(1199).toJson().toString(), last.toString());
        assertNotSame("Disk commit must not run on the mutation caller", caller, context.commitThread.get());
        assertEquals("vinyl-library-store", context.commitThread.get().getName());
        assertEquals("vinyl-library-store", field(field(first, "state"), "lastEncodeThread"));
        // A distinct process-cache key forces a cold parse of the very same persisted preferences.
        LibraryStore reopened = new LibraryStore(new PreferencesProbe(RuntimeEnvironment.getApplication()));
        assertEquals(1200, reopened.playlists().get(0).tracks.size());
        assertEquals("song1199", reopened.playlists().get(0).tracks.get(1199).id);
    }

    @Test public void playlistMetadataSurvivesAllExistingMutationsAndColdReads()throws Exception{
        PreferencesProbe context=new PreferencesProbe(RuntimeEnvironment.getApplication());context.disk.edit().clear().commit();
        LibraryStore store=new LibraryStore(context);LibraryStore.Playlist playlist=store.create("原名","原创简介","content://covers/one",tracks(2));
        store.rename(playlist.id,"新名");store.add(playlist.id,Collections.singletonList(track(2)));store.replaceTracks(playlist.id,tracks(4));flush(store);
        LibraryStore.Playlist read=new LibraryStore(new PreferencesProbe(RuntimeEnvironment.getApplication())).playlists().get(0);
        assertEquals("新名",read.name);assertEquals("原创简介",read.description);assertEquals("content://covers/one",read.coverUri);assertEquals(4,read.tracks.size());
        store.update(playlist.id,"完整草稿","另一段原创简介","content://covers/two",tracks(1));flush(store);
        JSONObject json=new JSONArray(context.disk.getString("playlists","[]")).getJSONObject(0);
        assertEquals("完整草稿",json.getString("name"));assertEquals("另一段原创简介",json.getString("description"));assertEquals("content://covers/two",json.getString("coverUri"));assertEquals(1,json.getJSONArray("tracks").length());
        store.update(playlist.id,"清除封面","","",tracks(1));flush(store);
        read=new LibraryStore(new PreferencesProbe(RuntimeEnvironment.getApplication())).playlists().get(0);assertEquals("",read.description);assertEquals("",read.coverUri);
        context.disk.edit().putString("playlists",new JSONArray().put(new JSONObject().put("id","legacy").put("name","旧歌单").put("tracks",new JSONArray())).toString()).commit();
        read=new LibraryStore(new PreferencesProbe(RuntimeEnvironment.getApplication())).playlists().get(0);assertEquals("legacy",read.id);assertEquals("",read.description);assertEquals("",read.coverUri);
    }

    @Test public void rapidEditsCoalesceAndDeletedPlaylistsNeverReturnToDisk() throws Exception {
        PreferencesProbe context = new PreferencesProbe(RuntimeEnvironment.getApplication());
        context.disk.edit().clear().commit();
        LibraryStore first = new LibraryStore(context), second = new LibraryStore(context);
        LibraryStore.Playlist keep, deleted;
        ArrayList<Track> songs = tracks(1200);
        try (WriterBlock ignored = new WriterBlock()) {
            keep = first.create("初始", songs); deleted = second.create("即将删除", songs);
            for (int i = 0; i < 80; i++) {
                first.rename(keep.id, "版本 " + i);
                second.replaceTracks(keep.id, songs.subList(i, i + 20));
                first.add(keep.id, Arrays.asList(track(i), track(1199), track(1199)));
                second.rename(deleted.id, "过时版本 " + i);
            }
            second.delete(deleted.id);
            first.replaceTracks(keep.id, Arrays.asList(track(42), track(7)));
            second.rename(keep.id, "  最终歌单  ");
            first.rename(deleted.id, "不能复活"); first.add(deleted.id, songs); first.replaceTracks(deleted.id, songs);
            assertEquals(1, second.playlists().size());
            assertEquals("最终歌单", first.playlists().get(0).name);
            assertEquals("song42", first.playlists().get(0).tracks.get(0).id);
            assertEquals(0, context.commits.get());
        }
        flush(first);
        JSONArray saved = new JSONArray(context.disk.getString("playlists", "[]"));
        assertEquals(1, saved.length()); assertEquals(keep.id, saved.getJSONObject(0).getString("id"));
        assertEquals("最终歌单", saved.getJSONObject(0).getString("name"));
        JSONArray finalSongs = saved.getJSONObject(0).getJSONArray("tracks");
        assertEquals(2, finalSongs.length()); assertEquals("song42", finalSongs.getJSONObject(0).getString("id"));
        assertEquals("song7", finalSongs.getJSONObject(1).getString("id"));
        assertEquals("Blocked edits must produce one latest commit, not hundreds of queued writes", 1, context.commits.get());
        assertEquals("Only the three final JSON sections should be encoded", 3L, field(field(first, "state"), "encodeCount"));
    }

    @Test public void repeatedReadsUseCacheAndRecentChangesDoNotReencodePlaylists() throws Exception {
        PreferencesProbe context = new PreferencesProbe(RuntimeEnvironment.getApplication());
        context.disk.edit().clear().commit();
        LibraryStore first = new LibraryStore(context), second = new LibraryStore(context);
        first.create(null, tracks(1200)); first.toggleLike(track(7)); first.toggleLike(track(8));
        flush(first);
        int reads = context.stringReads.get(); long encodes = (Long) field(field(first, "state"), "encodeCount");
        for (int i = 0; i < 1200; i++) {
            assertTrue(second.liked(track(7))); assertFalse(second.liked(track(9)));
            assertEquals(2, second.favorites().size());
            assertEquals(1200, new LibraryStore(context).playlists().get(0).tracks.size());
        }
        assertEquals("Reads and short-lived store instances must not reparse preferences", reads, context.stringReads.get());
        second.favorites().clear(); assertTrue(first.liked(track(7)));
        try (WriterBlock ignored = new WriterBlock()) {
            for (int i = 0; i < 60; i++) second.remember(track(i));
            first.remember(track(20));
            assertEquals(50, first.recent().size()); assertEquals("song20", first.recent().get(0).id);
            assertEquals("song59", first.recent().get(1).id);
            second.recent().clear(); assertEquals(50, first.recent().size());
        }
        flush(first);
        assertEquals("Only the changed recent section may be reencoded", encodes + 1, field(field(first, "state"), "encodeCount"));
        JSONArray recent = new JSONArray(context.disk.getString("recent", "[]"));
        assertEquals(50, recent.length()); assertEquals("song20", recent.getJSONObject(0).getString("id"));
        JSONArray favorites = new JSONArray(context.disk.getString("favorites", "[]"));
        assertEquals("song8", favorites.getJSONObject(0).getString("id"));
        assertEquals("song7", favorites.getJSONObject(1).getString("id"));
        assertEquals("新建歌单", first.playlists().get(0).name);
    }

    @Test public void existingPreferenceFormatKeepsIdentityOrderingAndDefaultNames() throws Exception {
        PreferencesProbe context = new PreferencesProbe(RuntimeEnvironment.getApplication());
        Track local = track(1), otherSource = new Track(local.id, "另一个来源", "艺人", "专辑", "另一来源", "file:///other.mp3", "", 1000, 1);
        JSONObject legacy = new JSONObject().put("id", "legacy").put("tracks", new JSONArray().put(local.toJson()));
        context.disk.edit().clear().putString("playlists", new JSONArray().put(legacy).toString())
                .putString("favorites", new JSONArray().put(local.toJson()).toString()).commit();
        LibraryStore store = new LibraryStore(context);
        assertEquals("未命名歌单", store.playlists().get(0).name);
        assertTrue(store.liked(local)); assertFalse(store.liked(otherSource));
        store.add("legacy", Arrays.asList(local, otherSource, otherSource));
        assertEquals(2, store.playlists().get(0).tracks.size());
        assertEquals("本地音乐", store.playlists().get(0).tracks.get(0).source);
        assertEquals("另一来源", store.playlists().get(0).tracks.get(1).source);
        assertFalse(store.toggleLike(local)); assertTrue(store.favorites().isEmpty());
        store.rename("legacy", " "); assertEquals("新建歌单", store.playlists().get(0).name);
        flush(store);
        assertEquals(0, new JSONArray(context.disk.getString("favorites", "[]")).length());
        assertEquals(2, new JSONArray(context.disk.getString("playlists", "[]")).getJSONObject(0).getJSONArray("tracks").length());
    }
}
