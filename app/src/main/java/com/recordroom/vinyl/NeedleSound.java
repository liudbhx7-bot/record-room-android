package com.recordroom.vinyl;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.os.SystemClock;

final class NeedleSound {
    private static final long MAX_START_DELAY_MS = 300;
    private SoundPool pool;
    private final int sound;
    private boolean ready;
    private float pendingGain;
    private long expiresAt;

    NeedleSound(Context context) {
        AudioAttributes attributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
        pool = new SoundPool.Builder().setMaxStreams(1).setAudioAttributes(attributes).build();
        pool.setOnLoadCompleteListener(this::loaded);
        sound = pool.load(context, R.raw.needle, 1);
    }

    private void loaded(SoundPool source, int id, int status) {
        if (source != pool || id != sound) return;
        ready = status == 0;
        if (ready && pendingGain > 0 && SystemClock.uptimeMillis() <= expiresAt) {
            play(pendingGain);
        }
        cancelPending();
    }

    void play(float volume) {
        float gain = Math.min(1f, volume);
        cancelPending();
        if (pool == null || !(gain > 0)) return;
        if (ready) {
            pool.play(sound, gain, gain, 1, 0, 1f);
        } else {
            pendingGain = gain;
            expiresAt = SystemClock.uptimeMillis() + MAX_START_DELAY_MS;
        }
    }

    void cancelPending() {
        pendingGain = 0;
    }

    void close() {
        cancelPending();
        if (pool == null) return;
        pool.setOnLoadCompleteListener(null);
        pool.release();
        pool = null;
    }
}
