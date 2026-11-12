package com.recordroom.vinyl;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Audio CUE indexes are offsets in the current FILE, using 75 frames per second. */
final class LocalCueSheet {
    private static final Pattern FILE = Pattern.compile("^(.+)\\s+\\S+$");
    private static final Pattern TRACK = Pattern.compile("^(\\d{1,3})\\s+AUDIO$", Pattern.CASE_INSENSITIVE);
    private static final Pattern INDEX = Pattern.compile("^01\\s+(\\d{1,6}):([0-5]\\d):([0-7]\\d)$");

    static final class Entry {
        final int number;
        final String file;
        String title = "", performer = "";
        long start = -1;
        Entry(int number, String file) { this.number = number; this.file = file; }
    }
    final ArrayList<Entry> tracks = new ArrayList<>();
    String title = "", performer = "", genre = "", year = "";

    private static String text(String value) {
        return value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")
                ? value.substring(1,value.length()-1).trim() : value;
    }

    static String decode(byte[] bytes) {
        if (bytes.length >= 2 && ((bytes[0] == (byte)0xfe && bytes[1] == (byte)0xff)
                || (bytes[0] == (byte)0xff && bytes[1] == (byte)0xfe))) return new String(bytes,StandardCharsets.UTF_16);
        try {
            return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException legacy) {
            return new String(bytes, Charset.forName("GB18030"));
        }
    }

    static LocalCueSheet parse(String content) throws IOException {
        LocalCueSheet sheet = new LocalCueSheet();
        String file = "";
        Entry entry = null;
        HashSet<Integer> numbers = new HashSet<>();
        for (String raw : content.replace("\uFEFF","").split("\\r\\n|\\n|\\r")) {
            String[] parts = raw.trim().split("\\s+",2);
            if (parts.length != 2) continue;
            String value = parts[1].trim();
            switch (parts[0].toUpperCase(Locale.ROOT)) {
                case "FILE":
                    Matcher filename = FILE.matcher(value);
                    if (!filename.matches()) throw new IOException("CUE 文件名无效");
                    file = text(filename.group(1).trim());
                    entry = null;
                    break;
                case "TRACK":
                    Matcher track = TRACK.matcher(value);
                    if (!track.matches() || file.isEmpty()) throw new IOException("CUE 包含无效或非音频曲目");
                    int number = Integer.parseInt(track.group(1));
                    if (number < 1 || !numbers.add(number)) throw new IOException("CUE 曲号无效或重复");
                    entry = new Entry(number,file);
                    sheet.tracks.add(entry);
                    break;
                case "TITLE":
                    if (entry == null) sheet.title = text(value); else entry.title = text(value);
                    break;
                case "PERFORMER":
                    if (entry == null) sheet.performer = text(value); else entry.performer = text(value);
                    break;
                case "INDEX":
                    if (value.startsWith("01")) {
                        Matcher index = INDEX.matcher(value);
                        if (entry == null || !index.matches() || entry.start >= 0)
                            throw new IOException("CUE 曲目起点无效");
                        int frames = Integer.parseInt(index.group(3));
                        if (frames >= 75) throw new IOException("CUE 帧号超出范围");
                        entry.start = (Long.parseLong(index.group(1))*60 + Integer.parseInt(index.group(2)))*1000
                                + frames*1000L/75;
                    }
                    break;
                case "REM":
                    String[] tag = value.split("\\s+",2);
                    if (tag.length == 2 && entry == null) {
                        if (tag[0].equalsIgnoreCase("GENRE")) sheet.genre = text(tag[1]);
                        if (tag[0].equalsIgnoreCase("DATE")) sheet.year = text(tag[1]);
                    }
                    break;
                default: break;
            }
        }
        if (sheet.tracks.isEmpty()) throw new IOException("CUE 没有音频曲目");
        for (Entry t : sheet.tracks) if (t.start < 0) throw new IOException("CUE 缺少 INDEX 01");
        return sheet;
    }
}
