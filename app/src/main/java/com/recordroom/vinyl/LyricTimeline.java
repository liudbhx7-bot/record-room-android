package com.recordroom.vinyl;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class LyricTimeline {
    private static final Pattern STAMP = Pattern.compile("\\[(\\d{1,3}):([0-5]\\d)(?:[.:](\\d{1,3}))?\\]");
    private static final Pattern OFFSET = Pattern.compile("(?im)^[ \\t]*\\[offset:([+-]?\\d{1,8})\\][ \\t]*$");
    private static final int MAX_LINES = 2048, MAX_TEXT = 64 * 1024;
    final ArrayList<Line> lines = new ArrayList<>();
    final String text;

    static final class Line {
        final long time;
        final String words;
        int start;
        Line(long time,String words) { this.time=time; this.words=words; }
    }

    LyricTimeline(String raw) {
        String plain = TrackLyrics.display(raw);
        if (plain.isEmpty()) { text=""; return; }
        String value = raw.replace("\ufeff","").replace("\r\n","\n").replace('\r','\n');
        Matcher offset = OFFSET.matcher(value);
        long shift = offset.find() ? Long.parseLong(offset.group(1)) : 0;
        if (!readTimes(value,shift) || lines.isEmpty()) {
            lines.clear();
            text = boundedPlain(plain);
            return;
        }
        lines.sort(Comparator.comparingLong(line -> line.time));
        StringBuilder visible = new StringBuilder();
        for (Line line : lines) {
            if (visible.length()>0) visible.append('\n');
            line.start = visible.length();
            visible.append(line.words);
        }
        text = visible.toString();
    }

    private boolean readTimes(String value,long shift) {
        int size = 0;
        for (String row : value.split("\n")) {
            row = row.trim();
            Matcher stamp = STAMP.matcher(row);
            ArrayList<Long> times = new ArrayList<>();
            int end = 0;
            while (stamp.find(end) && row.substring(end,stamp.start()).trim().isEmpty()) {
                if (times.size() >= MAX_LINES-lines.size()) return false;
                String fraction = stamp.group(3);
                int millis = fraction == null ? 0 : Integer.parseInt((fraction+"00").substring(0,3));
                times.add((Long.parseLong(stamp.group(1))*60+Integer.parseInt(stamp.group(2)))*1000+millis-shift);
                end = stamp.end();
            }
            if (times.isEmpty()) continue;
            String words = row.substring(end).trim();
            if (words.isEmpty()) continue;
            // One source line may repeat thousands of times. Check before expanding it.
            long added = (long)times.size()*(words.length()+1);
            if (added > MAX_TEXT-size) return false;
            size += (int)added;
            for (long time : times) lines.add(new Line(time,words));
        }
        return true;
    }

    private static String boundedPlain(String plain) {
        int end=Math.min(plain.length(),MAX_TEXT-1), count=1;
        for (int i=0;i<end;i++) {
            if (plain.charAt(i)=='\n' && ++count>MAX_LINES) { end=i; break; }
        }
        if (end==plain.length()) return plain;
        if (end>0 && Character.isHighSurrogate(plain.charAt(end-1))) end--;
        return plain.substring(0,end)+"…";
    }

    int current(long position) {
        int low=0, high=lines.size();
        while (low<high) {
            int middle=(low+high)>>>1;
            if (lines.get(middle).time<=position) low=middle+1;
            else high=middle;
        }
        return low-1;
    }
}
