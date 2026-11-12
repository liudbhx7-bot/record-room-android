package com.recordroom.vinyl;

import java.util.Arrays;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=34)
public class NeteaseLyricsTest {
    private static Track song(String id,String title,String artist,String album,long duration) {
        return new Track(id,title,artist,album,"网易云音乐","netease://song/"+id,"",duration,0);
    }

    @Test public void sessionAcceptsOnlySongIdsAndKnownMusicUrls() {
        assertEquals("814",NeteaseLyrics.recordingId("814"));
        assertEquals("814",NeteaseLyrics.recordingId("orpheus://song/814"));
        assertEquals("814",NeteaseLyrics.recordingId("https://music.163.com/song?id=814"));
        assertEquals("",NeteaseLyrics.recordingId("playlist:814"));
        assertEquals("",NeteaseLyrics.recordingId("https://example.com/song?id=814"));
        assertEquals("",NeteaseLyrics.recordingId("0"));
    }

    @Test public void searchWithoutSongIdRejectsWrongArtistOtherReleaseAndAmbiguity() {
        Track recording=song("11","山","甲 / 乙","昨天",190000);
        Track version=song("22","山","甲 / 乙","十周年",194000);
        Track cover=song("33","山","丙","昨天",190000);
        assertEquals("11",NeteaseLyrics.match(Arrays.asList(cover,version,recording),"山","甲/乙","昨天",190211));
        assertEquals("",NeteaseLyrics.match(Arrays.asList(recording,version),"山","甲/乙","",0));
        assertEquals("",NeteaseLyrics.match(Arrays.asList(recording,version),"山 (Live)","甲/乙","",190000));
        assertEquals("",NeteaseLyrics.match(Arrays.asList(cover),"山","甲/乙","昨天",190000));
    }

    @Test public void repeatedTimestampsCannotExpandAnImportedFileIntoAnUnboundedTextView() {
        String words="听".repeat(45000);
        LyricTimeline huge=new LyricTimeline("[00:01]".repeat(10000)+words);
        assertEquals(words,huge.text);
        assertEquals(-1,huge.current(1000));
        LyricTimeline longPlain=new LyricTimeline("无时间码\n".repeat(6000));
        assertTrue(longPlain.text.split("\n").length<=2048);
        assertTrue(longPlain.text.endsWith("…"));
        LyricTimeline longTimed=new LyricTimeline("[00:01]".repeat(64)+"A".repeat(1100));
        assertEquals(1100,longTimed.text.length());
        assertTrue(longTimed.lines.isEmpty());
    }

    @Test public void parsingPreservesTimingAndTreatsTemporaryErrorsSeparately() throws Exception {
        String raw="[offset:100]\n[00:03.10][00:09.000]第三\n[00:01.5]第一";
        JSONObject answer=new JSONObject().put("code",200).put("lrc",new JSONObject().put("lyric",raw));
        LyricTimeline timeline=new LyricTimeline(NeteaseLyrics.unpack(answer));
        assertEquals("第一\n第三\n第三",timeline.text);
        assertEquals(-1,timeline.current(1399)); assertEquals(0,timeline.current(1400));
        assertEquals(0,timeline.current(2999)); assertEquals(1,timeline.current(3000));
        assertEquals(2,timeline.current(8900)); assertEquals(0,timeline.current(2000));
        assertEquals("第一",timeline.text.substring(timeline.lines.get(0).start,2));
        assertEquals("纯音乐",NeteaseLyrics.unpack(new JSONObject().put("code",200).put("nolyric",true)));
        assertEquals("",NeteaseLyrics.unpack(new JSONObject().put("code",200).put("uncollected",true)));
        try {NeteaseLyrics.unpack(new JSONObject().put("code",429));fail();}
        catch (ProviderException expected) {assertFalse(expected.getMessage().isEmpty());}
        assertEquals("旧歌词文件",new LyricTimeline("旧歌词文件").text);
    }
}
