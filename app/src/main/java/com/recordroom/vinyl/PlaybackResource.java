package com.recordroom.vinyl;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/** An ephemeral, entitlement-checked full track. Never serialize this into a saved queue. */
public final class PlaybackResource {
    public final String url;
    public final Map<String,String> headers;
    public final long expiresAtMs,durationMs;
    public PlaybackResource(String url,Map<String,String> headers,long expiresAtMs,long durationMs){
        this.url=url;this.headers=Collections.unmodifiableMap(new HashMap<>(headers));
        this.expiresAtMs=expiresAtMs;this.durationMs=durationMs;
    }
}
