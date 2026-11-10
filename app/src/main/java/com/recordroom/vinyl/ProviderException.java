package com.recordroom.vinyl;

/** A user-visible provider failure; never include cookies, login keys or signed audio URLs. */
public final class ProviderException extends Exception {
    public enum Kind { NETWORK, LOGIN_REQUIRED, LOGIN_EXPIRED, LOGIN_CHALLENGE, PREVIEW_ONLY, UNAVAILABLE, RATE_LIMITED, INVALID_RESPONSE, STORAGE }
    public final Kind kind;
    public ProviderException(Kind kind,String message){super(message);this.kind=kind;}
    public ProviderException(Kind kind,String message,Throwable cause){super(message,cause);this.kind=kind;}
}
