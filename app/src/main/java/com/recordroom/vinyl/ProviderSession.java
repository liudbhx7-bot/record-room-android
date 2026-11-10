package com.recordroom.vinyl;

/** Cookie header is private to the platform HTTPS transport. Never attach it to artwork/audio. */
public interface ProviderSession {
    String readCookies() throws ProviderException;
    void writeCookies(String cookies) throws ProviderException;
    void clear() throws ProviderException;
    long generation();
}
