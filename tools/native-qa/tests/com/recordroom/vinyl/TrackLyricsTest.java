package com.recordroom.vinyl;

import androidx.media3.extractor.metadata.id3.BinaryFrame;
import androidx.media3.extractor.metadata.vorbis.VorbisComment;
import org.junit.Test;
import static org.junit.Assert.*;

public class TrackLyricsTest {
    @Test public void scopedTagsAndTimestampedTextAreReadable() {
        assertEquals("第一句\n第二句",TrackLyrics.entry(new VorbisComment("LYRICS","[ar:artist]\n[00:14.60]第一句\n[00:21]第二句")));
        assertEquals("",TrackLyrics.entry(new VorbisComment("ARTIST","演唱者")));
        assertEquals("Line one",TrackLyrics.entry(new BinaryFrame("USLT", new byte[]{3,'e','n','g',0,'L','i','n','e',' ','o','n','e',0})));
        assertEquals("",TrackLyrics.entry(new BinaryFrame("APIC",new byte[]{0,0})));
        assertEquals("",TrackLyrics.display("bad\0file"));
    }
}
