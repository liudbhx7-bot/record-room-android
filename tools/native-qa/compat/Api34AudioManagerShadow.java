package com.recordroom.vinyl;

import android.media.AudioManager;
import android.media.IAudioService;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.Implementation;
import org.robolectric.shadows.ShadowAudioManager;

/** API-34 host compatibility only: keep upstream AudioManager shadow behavior, avoid its newer-type proxy. */
@Implements(value=AudioManager.class, minSdk=34, maxSdk=34)
public class Api34AudioManagerShadow extends ShadowAudioManager {
    private static final IAudioService SERVICE=new IAudioService.Default();
    @Implementation(minSdk=34,maxSdk=34)
    protected static IAudioService getService(){return SERVICE;}
}
