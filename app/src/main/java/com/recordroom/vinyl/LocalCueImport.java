package com.recordroom.vinyl;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import androidx.media3.common.MediaItem;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Only matches audio the user selected. A sheet never grants access to any referenced path. */
final class LocalCueImport {
    private LocalCueImport() {}
    static final class Result {
        final ArrayList<Track> tracks = new ArrayList<>();
        final HashSet<String> indexedFiles = new HashSet<>();
        final HashSet<String> importedSheets = new HashSet<>();
        int failures;
        String firstError = "";
        void failed(String reason) { failures++; if (firstError.isEmpty()) firstError = reason; }
        String summary() {
            return "已导入 " + tracks.size() + " 首" + (failures == 0 ? "" : "，" + failures + " 个文件未导入" +
                    (firstError.isEmpty() ? "" : "。" + firstError));
        }
        ArrayList<Track> merge(List<Track> previous) {
            LinkedHashMap<String,Track> byId = new LinkedHashMap<>();
            for (Track track : previous) {
                if (!indexedFiles.contains(track.uri) && !importedSheets.contains(track.cueDocumentUri))
                    byId.put(LibraryStore.identity(track),track);
            }
            for (Track track : tracks) byId.put(LibraryStore.identity(track),track);
            return new ArrayList<>(byId.values());
        }
    }

    private static String displayName(Context context,Uri uri) {
        try (Cursor c = context.getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getString(0);
        } catch (RuntimeException ignored) { }
        String key = uri.getLastPathSegment();
        return key == null ? "" : key;
    }
    private static String filename(String value) {
        String normalized = value.replace('\\','/');
        return Normalizer.normalize(normalized.substring(normalized.lastIndexOf('/') + 1),Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
    }
    private static LocalCueSheet sheet(Context c, Uri uri) throws IOException {
        try (InputStream in = c.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("无法打开 CUE");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int count;
            while ((count=in.read(chunk)) != -1) {
                if (out.size()+count > 2*1024*1024) throw new IOException("CUE 文件过大");
                out.write(chunk,0,count);
            }
            return LocalCueSheet.parse(LocalCueSheet.decode(out.toByteArray()));
        }
    }

    static Result read(Context context, List<Uri> selected) {
        Result result = new Result();
        ArrayList<Uri> sheets = new ArrayList<>();
        ArrayList<Track> audio = new ArrayList<>();
        Map<String,List<Track>> byName = new HashMap<>();
        for (Uri uri : new LinkedHashSet<>(selected)) {
            if (uri == null) continue;
            String name = filename(displayName(context,uri));
            if (name.endsWith(".cue")) { sheets.add(uri); continue; }
            try {
                Track track = LocalMusicImporter.importTrack(context,uri);
                if (track == null) throw new IOException();
                audio.add(track);
                byName.computeIfAbsent(name,k -> new ArrayList<>()).add(track);
            } catch (Exception failure) { result.failed(""); }
        }
        for (Uri uri : sheets) {
            try {
                ArrayList<Track> segments = expand(sheet(context,uri),byName,uri.toString());
                for (Track t : segments) if (result.indexedFiles.contains(t.uri)) throw new IOException("多个 CUE 引用了同一份音频");
                result.importedSheets.add(uri.toString());
                result.tracks.addAll(segments);
                for (Track t : segments) result.indexedFiles.add(t.uri);
            } catch (IOException | RuntimeException failure) {
                result.failed(failure instanceof IOException && failure.getMessage()!=null ? failure.getMessage() : "无法读取 CUE");
            }
        }
        for (Track t : audio) if (!result.indexedFiles.contains(t.uri)) result.tracks.add(t);
        return result;
    }

    static ArrayList<Track> expand(LocalCueSheet sheet, Map<String,List<Track>> byName,String cueUri) throws IOException {
        ArrayList<Track> sources = new ArrayList<>();
        HashSet<String> completedFiles = new HashSet<>();
        Track previous = null;
        for (LocalCueSheet.Entry entry : sheet.tracks) {
            List<Track> matches = byName.get(filename(entry.file));
            if (matches == null || matches.isEmpty()) throw new IOException("请同时选择 " + entry.file + " 和 CUE");
            if (matches.size() != 1) throw new IOException("选中了同名音频，请分开导入");
            Track track = matches.get(0);
            if (track != previous) {
                if (!completedFiles.add(track.uri)) throw new IOException("CUE 引用了不连续的音频段");
                previous = track;
            }
            if (track.durationMs > 0 && entry.start >= track.durationMs) throw new IOException("CUE 起点超过音频长度");
            sources.add(track);
        }
        ArrayList<Track> tracks = new ArrayList<>();
        for (int i=0; i<sources.size(); i++) {
            LocalCueSheet.Entry e = sheet.tracks.get(i);
            Track source = sources.get(i);
            boolean sameFileNext = i+1<sources.size() && source == sources.get(i+1);
            long end = sameFileNext ? sheet.tracks.get(i+1).start : -1;
            if (sameFileNext && end <= e.start) throw new IOException("CUE 曲目起点顺序无效");
            String songArtist = e.performer.isEmpty() ? sheet.performer : e.performer;
            tracks.add(source.localSegment(e.number,e.title.isEmpty() ? "音轨 " + e.number : e.title,
                    songArtist.isEmpty() ? source.artist : songArtist, sheet.title.isEmpty() ? source.album : sheet.title,
                    sheet.performer.isEmpty() ? source.albumArtist : sheet.performer,sheet.genre.isEmpty()?source.genre:sheet.genre,
                    sheet.year.isEmpty()?source.year:sheet.year,e.start,end,cueUri));
        }
        return tracks;
    }

    static MediaItem mediaItem(Track track, Uri uri) {
        MediaItem.Builder item = new MediaItem.Builder().setMediaId(LibraryStore.identity(track)).setUri(uri);
        if (track.clipStartMs >= 0) {
            MediaItem.ClippingConfiguration.Builder clip = new MediaItem.ClippingConfiguration.Builder().setStartPositionMs(track.clipStartMs);
            if (track.clipEndMs >= 0) clip.setEndPositionMs(track.clipEndMs);
            item.setClippingConfiguration(clip.build());
        }
        return item.build();
    }
}
