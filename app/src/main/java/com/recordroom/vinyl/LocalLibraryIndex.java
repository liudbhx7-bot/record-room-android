package com.recordroom.vinyl;

import android.content.Context;
import android.util.JsonReader;
import android.util.JsonToken;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/** Reads the original on-disk JSON array one track at a time. All mutations go through merge. */
final class LocalLibraryIndex {
    static final class Snapshot {
        final List<Track> tracks;
        final boolean recovered;
        private final Path source;

        private Snapshot(List<Track> tracks, Path source, boolean recovered) {
            this.tracks = tracks;
            this.source = source;
            this.recovered = recovered;
        }
    }

    private LocalLibraryIndex() {}

    static synchronized Snapshot load(Context context) throws IOException {
        return load(context.getFilesDir().toPath().resolve("library.json"));
    }

    private static Snapshot load(Path index) throws IOException {
        Path legacyBackup = index.resolveSibling("library.json.bak");
        Path previous = index.resolveSibling("library.json.previous");
        Path legacyStage = index.resolveSibling("library.json.new");
        // Older AtomicFile versions move the old committed index to .bak before writing.
        // Modern AtomicFile uses .new, which only matters if no committed copy can be read.
        IOException failure = null;
        for (Path candidate : new Path[]{legacyBackup, index, previous, legacyStage}) {
            if (Files.notExists(candidate)) continue;
            List<Track> tracks;
            try {
                tracks = read(candidate);
            } catch (IOException badCopy) {
                // A truncated .new from the very first legacy import was never committed.
                // It must not mask an actually unreadable committed index or block a fresh retry.
                if (!candidate.equals(legacyStage)) failure = badCopy;
                continue;
            }
            boolean recovered = !candidate.equals(index);
            if (candidate.equals(legacyBackup) || candidate.equals(legacyStage)) {
                replace(candidate, index);
                candidate = index;
            }
            return new Snapshot(tracks, candidate, recovered);
        }
        if (failure != null) throw new IOException("本地曲库索引无法读取，原索引已保留", failure);
        return new Snapshot(new ArrayList<>(), null, false);
    }

    // Read, merge, and publish under one process-wide lock, including imports from
    // separate activity instances. Never treat a read or commit failure as an empty library.
    static synchronized Snapshot merge(Context context, LocalCueImport.Result batch) throws IOException {
        Path index = context.getFilesDir().toPath().resolve("library.json");
        Snapshot prior = load(index);
        if (batch.tracks.isEmpty()) return prior;
        List<Track> merged = batch.merge(prior.tracks);
        Path pending = index.resolveSibling("library.json.pending");
        write(pending, merged);
        // The old generation is recoverable even if the process stops between the moves.
        if (index.equals(prior.source)) {
            replace(index, index.resolveSibling("library.json.previous"));
        } else if (prior.recovered && Files.exists(index)) {
            // Keep the damaged generation available if the user later needs to salvage recent entries.
            replace(index, index.resolveSibling("library.json.unreadable"));
        }
        replace(pending, index);
        return new Snapshot(merged, index, prior.recovered);
    }

    private static void replace(Path source, Path target) throws IOException {
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    private static List<Track> read(Path file) throws IOException {
        ArrayList<Track> tracks = new ArrayList<>();
        try (JsonReader reader = new JsonReader(new BufferedReader(new InputStreamReader(
                new FileInputStream(file.toFile()), StandardCharsets.UTF_8.newDecoder())))) {
            reader.beginArray();
            while (reader.hasNext()) {
                JSONObject row = new JSONObject();
                reader.beginObject();
                while (reader.hasNext()) {
                    String key = reader.nextName();
                    switch (reader.peek()) {
                        case STRING:
                        case NUMBER: row.put(key, reader.nextString()); break;
                        default: reader.skipValue(); break;
                    }
                }
                reader.endObject();
                if (row.optString("uri").trim().isEmpty()) throw new IOException("曲目缺少文件位置");
                tracks.add(Track.fromJson(row));
            }
            reader.endArray();
            if (reader.peek() != JsonToken.END_DOCUMENT) throw new IOException("曲库索引未正常结束");
        } catch (IllegalStateException | JSONException malformed) {
            throw new IOException("曲库索引格式错误", malformed);
        }
        return tracks;
    }

    private static void write(Path file, List<Track> tracks) throws IOException {
        try (FileOutputStream raw = new FileOutputStream(file.toFile());
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(raw, StandardCharsets.UTF_8))) {
            writer.write('[');
            boolean first = true;
            for (Track track : tracks) {
                if (!first) writer.write(',');
                writer.write(track.toJson().toString());
                first = false;
            }
            writer.write(']');
            writer.flush();
            raw.getFD().sync();
        }
    }
}
