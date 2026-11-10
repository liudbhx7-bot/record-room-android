package com.recordroom.vinyl;

import android.media.AudioAttributes;
import android.media.SoundPool;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowSoundPool;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=34)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
public class AudioServiceCompatibilityTest {
    @Test public void originalNeedleSoundConstructorAndReleaseRemainEnabled(){
        NeedleSound needle=new NeedleSound(RuntimeEnvironment.getApplication());
        needle.play(.5f); // Before the asynchronous load callback, the real class must safely wait.
        needle.close();needle.play(.5f);
    }
    @Test public void stockSoundPoolStillLoadsNotifiesAndRecordsPlayback() {
        String file=System.getProperty("vinyl.project.dir")+"/app/src/main/assets/sfx/needle.wav";
        assertTrue("Use the actual needle audio asset",Files.isRegularFile(Path.of(file)));
        SoundPool pool=new SoundPool.Builder().setMaxStreams(1).setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build();
        try{
            ShadowSoundPool shadow=Shadows.shadowOf(pool);assertEquals(ShadowSoundPool.class,shadow.getClass());
            boolean[] loaded={false};pool.setOnLoadCompleteListener((p,id,status)->loaded[0]=status==0);
            int id=pool.load(file,1);assertTrue(id>0);shadow.notifyPathLoaded(file,true);assertTrue(loaded[0]);
            assertTrue(pool.play(id,.24f,.24f,1,0,1)>0);
            List<ShadowSoundPool.Playback> events=shadow.getPathPlaybacks(file);assertEquals(1,events.size());
            assertEquals(.24f,events.get(0).leftVolume,.0001f);assertEquals(.24f,events.get(0).rightVolume,.0001f);assertEquals(0,events.get(0).loop);
        }finally{pool.release();}
    }
}
