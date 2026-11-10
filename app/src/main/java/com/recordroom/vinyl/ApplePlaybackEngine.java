package com.recordroom.vinyl;

import java.util.List;

/** Service-owned MusicKit transport boundary; no Apple media URLs enter the Media3 player. */
public interface ApplePlaybackEngine extends AutoCloseable {
    AppleMusicPlayback.State state();
    boolean play(List<Track> tracks,int index);
    boolean prepare(List<Track> tracks,int index,boolean autoPlay);
    boolean resume();
    boolean pause();
    boolean next();
    boolean previous();
    boolean seek(long positionMs);
    boolean stop();
    void tokensChanged();
    void refresh();
    @Override void close();
}
