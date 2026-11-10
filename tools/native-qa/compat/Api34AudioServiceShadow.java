package com.recordroom.vinyl;

import android.media.IAudioService;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

/** API 34 only: use Android's default service instead of an eager JDK dynamic proxy. */
@Implements(className="android.media.PlayerBase",isInAndroidSdk=false,minSdk=34,maxSdk=34)
public final class Api34AudioServiceShadow {
    @Implementation(minSdk=34,maxSdk=34)
    public static IAudioService getService(){return new IAudioService.Default();}
}
