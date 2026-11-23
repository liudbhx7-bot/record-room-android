package com.recordroom.vinyl;

import static org.junit.Assert.*;

import android.content.Context;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=34)
public class LocalLibraryIndexTest {
    private final Context context = RuntimeEnvironment.getApplication();
    private final Path index = context.getFilesDir().toPath().resolve("library.json");

    private static Track track(int n, String title) {
        return new Track("t" + n, title, "歌手", "专辑", "本地音乐",
                "content://music/" + n, "", 195000, n).withDiscNumber(2);
    }

    private void append(Track... tracks) throws IOException {
        LocalCueImport.Result batch = new LocalCueImport.Result();
        java.util.Collections.addAll(batch.tracks, tracks);
        LocalLibraryIndex.merge(context, batch);
    }

    private List<Track> read() throws IOException {
        return LocalLibraryIndex.load(context).tracks;
    }

    @Test public void oversizedLegacyIndexRemainsCompleteAfterReadAppendDuplicateAndReload() throws Exception {
        char[] characters = new char[500];
        java.util.Arrays.fill(characters, '歌');
        String prefix = new String(characters);
        int count = 6000;
        try (BufferedWriter out = Files.newBufferedWriter(index, StandardCharsets.UTF_8)) {
            out.write('[');
            for (int i=0; i<count; i++) {
                if (i>0) out.write(',');
                out.write(track(i, prefix + i).toJson().toString());
            }
            out.write(']');
        }
        long size = Files.size(index);
        assertTrue(size > 8L * 1024 * 1024);
        assertEquals(count, read().size());
        append(track(count, "新曲"), track(1, "更新标题"));
        List<Track> restored = read();
        assertEquals(count+1, restored.size());
        assertEquals("更新标题", restored.get(1).title);
        for (int i=0; i<count; i++) {
            assertEquals("t" + i, restored.get(i).id);
            assertEquals(2, restored.get(i).discNumber);
        }
        assertEquals("新曲", restored.get(count).title);
        System.out.println("LIBRARY_OVER_8M bytes="+size+" before="+count+" after="+restored.size());
    }

    @Test public void damagedOrTruncatedIndexNeverPublishesPartialRows() throws Exception {
        String row = track(1, "备份").toJson().toString();
        for (String invalid : new String[]{"", "["+row+",", "["+row+",{}]", "["+row+"] true"}) {
            byte[] bytes = invalid.getBytes(StandardCharsets.UTF_8);
            Files.write(index, bytes);
            assertThrows(IOException.class, this::read);
            assertThrows(IOException.class, () -> append(track(3,"新曲")));
            assertArrayEquals(bytes, Files.readAllBytes(index));
        }
    }

    @Test public void failedSaveKeepsLastCommitAndRetryIsSafe() throws Exception {
        append(track(1, "原曲"));
        Path blocked = index.resolveSibling("library.json.pending");
        Files.createDirectory(blocked);
        assertThrows(IOException.class, () -> append(track(2, "新曲")));
        assertEquals(1, read().size());
        Files.delete(blocked);
        append(track(2, "新曲"));
        assertEquals(2, read().size());
    }

    @Test public void damagedLatestCanUsePreviousGenerationAndAcceptAnImport() throws Exception {
        append(track(1, "一"));
        append(track(2, "二"));
        Files.write(index, new byte[]{'['});
        assertTrue(LocalLibraryIndex.load(context).recovered);
        assertEquals("一", read().get(0).title);
        append(track(3, "三"));
        assertEquals(2, read().size());
        assertEquals("三", read().get(1).title);
        assertArrayEquals(new byte[]{'['}, Files.readAllBytes(index.resolveSibling("library.json.unreadable")));
    }

    @Test public void interruptedRenamesAndOldAtomicFileSnapshotsRecover() throws Exception {
        String json = "["+track(1, "旧曲").toJson()+"]";
        Path old = index.resolveSibling("library.json.bak");
        Files.write(old, json.getBytes(StandardCharsets.UTF_8));
        Files.write(index, "[]".getBytes(StandardCharsets.UTF_8));
        assertEquals("旧曲", read().get(0).title);
        assertFalse(Files.exists(old));
        Path pending = index.resolveSibling("library.json.pending");
        Files.write(pending, new byte[]{'['});
        Files.move(index, index.resolveSibling("library.json.previous"));
        assertEquals(1, read().size());
        append(track(2, "新曲"));
        assertEquals(2, read().size());
    }

    @Test public void interruptedFirstLegacyWriteCanBeRetried() throws Exception {
        Path abandoned = index.resolveSibling("library.json.new");
        Files.write(abandoned, new byte[]{'['});
        assertTrue(read().isEmpty());
        append(track(1, "新曲"));
        assertEquals("新曲", read().get(0).title);
        assertArrayEquals(new byte[]{'['}, Files.readAllBytes(abandoned));
    }

    @Test public void concurrentImportsBothSurvive() throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Void> first = workers.submit(() -> { start.await(); append(track(1,"甲")); return null; });
            Future<Void> second = workers.submit(() -> { start.await(); append(track(2,"乙")); return null; });
            start.countDown();
            first.get();
            second.get();
            assertEquals(2, read().size());
        } finally {
            workers.shutdownNow();
        }
    }
}
