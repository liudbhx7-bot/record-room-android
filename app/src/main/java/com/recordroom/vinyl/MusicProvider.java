package com.recordroom.vinyl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Synchronous provider boundary: all methods which may do I/O are called off the UI thread. */
public interface MusicProvider {
    String id();
    default boolean supportsAccount(){return true;}
    QrLogin beginQrLogin() throws ProviderException;
    LoginPoll pollQrLogin(String key) throws ProviderException;
    Account account() throws ProviderException;
    List<Playlist> playlists(String userId) throws ProviderException;
    List<Track> likedTracks(String userId) throws ProviderException;
    List<Track> playlistTracks(String playlistId) throws ProviderException;
    default Page<Track> searchTracks(String query,int offset,int limit) throws ProviderException {
        throw new ProviderException(ProviderException.Kind.UNAVAILABLE,"当前来源尚未提供歌曲搜索");
    }
    default boolean supportsArtistSearch(){return false;}
    default Page<Artist> searchArtists(String query,int offset,int limit) throws ProviderException {
        throw new ProviderException(ProviderException.Kind.UNAVAILABLE,"当前来源尚未提供音乐人搜索");
    }
    default Page<Album> artistAlbums(String artistId,int offset,int limit) throws ProviderException {
        List<Album> values=artist(artistId).albums;
        int start=Math.min(Math.max(0,offset),values.size()),end=Math.min(values.size(),start+Math.max(1,limit));
        return new Page<>(values.subList(start,end),values.size(),end,end<values.size());
    }
    Page<Album> searchAlbums(String query,int offset,int limit) throws ProviderException;
    Album album(String albumId) throws ProviderException;
    Artist artist(String artistId) throws ProviderException;
    PlaybackResource resolve(Track track) throws ProviderException;
    void logout() throws ProviderException;

    final class QrLogin {
        public final String key,url;public final long expiresAtMs;
        public QrLogin(String key,String url,long expiresAtMs){this.key=key;this.url=url;this.expiresAtMs=expiresAtMs;}
    }
    enum LoginStatus { WAITING_SCAN, WAITING_CONFIRM, SUCCESS, EXPIRED }
    final class LoginPoll {
        public final LoginStatus status;public final String message;
        public LoginPoll(LoginStatus status,String message){this.status=status;this.message=message;}
    }
    final class Account {
        public final String id,nickname,avatarUrl;public final int vipType;public final boolean loggedIn;
        public Account(String id,String nickname,String avatarUrl,int vipType,boolean loggedIn){this.id=id;this.nickname=nickname;this.avatarUrl=avatarUrl;this.vipType=vipType;this.loggedIn=loggedIn;}
    }
    final class Playlist {
        public final String id,name,description,coverUrl,ownerId;public final boolean owned;public final int trackCount;
        public Playlist(String id,String name,String description,String coverUrl,String ownerId,boolean owned,int trackCount){this.id=id;this.name=name;this.description=description;this.coverUrl=coverUrl;this.ownerId=ownerId;this.owned=owned;this.trackCount=trackCount;}
    }
    final class Album {
        public final String id,name,artist,artistId,coverUrl;public final List<Track> tracks;
        public Album(String id,String name,String artist,String artistId,String coverUrl,List<Track> tracks){this.id=id;this.name=name;this.artist=artist;this.artistId=artistId;this.coverUrl=coverUrl;this.tracks=Collections.unmodifiableList(new ArrayList<>(tracks));}
    }
    final class Artist {
        public final String id,name,description,avatarUrl;public final List<Track> topTracks;public final List<Album> albums;
        public Artist(String id,String name,String description,String avatarUrl,List<Track> tracks,List<Album> albums){this.id=id;this.name=name;this.description=description;this.avatarUrl=avatarUrl;this.topTracks=Collections.unmodifiableList(new ArrayList<>(tracks));this.albums=Collections.unmodifiableList(new ArrayList<>(albums));}
    }
    final class Page<T> {
        public final List<T> items;public final int total,nextOffset;public final boolean hasMore;
        public Page(List<T> items,int total,int nextOffset,boolean hasMore){this.items=Collections.unmodifiableList(new ArrayList<>(items));this.total=total;this.nextOffset=nextOffset;this.hasMore=hasMore;}
    }
}
