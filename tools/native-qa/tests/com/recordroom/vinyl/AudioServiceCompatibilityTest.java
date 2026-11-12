package com.recordroom.vinyl;

import android.app.Application;
import android.content.res.AssetFileDescriptor;
import android.media.SoundPool;
import android.os.Looper;
import java.io.BufferedInputStream;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowSoundPool;
import org.robolectric.util.ReflectionHelpers;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=34)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
public class AudioServiceCompatibilityTest {
    private final Application app = RuntimeEnvironment.getApplication();

    private ShadowSoundPool soundPool(NeedleSound needle) {
        return Shadows.shadowOf((SoundPool) ReflectionHelpers.getField(needle, "pool"));
    }

    @Test public void firstContactCanWaitForDecodeAndKeepsUserVolume() {
        NeedleSound needle = new NeedleSound(app);
        ShadowSoundPool pool = soundPool(needle);
        try {
            needle.play(.3f);
            needle.play(.55f);
            assertFalse(pool.wasResourcePlayed(R.raw.needle));
            pool.notifyResourceLoaded(R.raw.needle, true);
            List<ShadowSoundPool.Playback> events = pool.getResourcePlaybacks(R.raw.needle);
            assertEquals(1, events.size());
            assertEquals(.55f, events.get(0).leftVolume, .0001f);
            assertEquals(.55f, events.get(0).rightVolume, .0001f);
            assertEquals(0, events.get(0).loop);
            needle.play(0);
            assertEquals(1, pool.getResourcePlaybacks(R.raw.needle).size());
            needle.play(1);
            assertEquals(1f, pool.getResourcePlaybacks(R.raw.needle).get(1).leftVolume, .0001f);
        } finally { needle.close(); }
        needle.play(1);
        assertEquals(2, pool.getResourcePlaybacks(R.raw.needle).size());
    }

    @Test public void lateOrCancelledContactNeverSoundsAfterTheGesture() {
        for (boolean cancel : new boolean[]{false, true}) {
            NeedleSound needle = new NeedleSound(app);
            ShadowSoundPool pool = soundPool(needle);
            try {
                needle.play(1);
                if (cancel) needle.cancelPending();
                else Shadows.shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS);
                pool.notifyResourceLoaded(R.raw.needle, true);
                assertFalse(pool.wasResourcePlayed(R.raw.needle));
            } finally { needle.close(); }
        }
    }

    @Test public void packagedNeedleIsAnUncompressedAudiblePcmPulse() throws Exception {
        try (AssetFileDescriptor descriptor = app.getResources().openRawResourceFd(R.raw.needle);
             BufferedInputStream stream = new BufferedInputStream(descriptor.createInputStream());
             AudioInputStream audio = AudioSystem.getAudioInputStream(stream)) {
            AudioFormat format = audio.getFormat();
            assertEquals(AudioFormat.Encoding.PCM_SIGNED, format.getEncoding());
            assertEquals(48000f, format.getSampleRate(), .01f);
            assertEquals(1, format.getChannels());
            assertEquals(16, format.getSampleSizeInBits());
            assertFalse(format.isBigEndian());
            byte[] pcm = audio.readAllBytes();
            int samples = pcm.length / 2;
            assertEquals(9600, samples);
            double energy = 0, peak = 0;
            for (int i = 0; i < pcm.length; i += 2) {
                double sample = (short)((pcm[i] & 255) | (pcm[i+1] << 8)) / 32768.0;
                energy += sample * sample;
                peak = Math.max(peak, Math.abs(sample));
            }
            assertTrue("Transient audible with headroom", peak > .45 && peak < .82);
            assertTrue("Audible body across the pulse", Math.sqrt(energy / samples) > .055);
        }
    }
}
