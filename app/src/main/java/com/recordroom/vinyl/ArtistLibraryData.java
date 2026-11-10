package com.recordroom.vinyl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;

/** Artist indexes over real track and catalog identities, shared by the three native pages. */
final class ArtistLibraryData {
    static final int COVER=0, SONGS=1, ALBUMS=2, CHAPTER_COUNT=3;
    static final class Collection {
        final String key,name,artist,artwork,year,remoteId,provider;
        final ArrayList<Track> tracks;
        Collection(String key,String name,String artist,String artwork,String year,String remoteId,String provider,List<Track> tracks){
            this.key=key;this.name=name;this.artist=artist;this.artwork=empty(artwork);this.year=empty(year);
            this.remoteId=empty(remoteId);this.provider=empty(provider);this.tracks=uniqueTracks(tracks);
            Collections.sort(this.tracks,(a,b)->Integer.compare(a.trackNumber>0?a.trackNumber:Integer.MAX_VALUE,b.trackNumber>0?b.trackNumber:Integer.MAX_VALUE));
        }
        boolean needsAlbum(){return supportsRemote(provider)&&!remoteId.isEmpty();}
        AlbumStackView.Entry entry(){return new AlbumStackView.Entry(key,name,artist,artwork,tracks);}
    }
    final String name,provider,artistId,portrait,description;
    final ArrayList<Track> songs=new ArrayList<>(),recommended=new ArrayList<>();
    final ArrayList<Collection> albums=new ArrayList<>();
    final boolean providerSongs;

    ArtistLibraryData(String name,String provider,String artistId,List<Track> library,Track seed,MusicProvider.Artist remote){
        this.name=name;this.provider=empty(provider);this.artistId=empty(artistId);
        boolean verified=remote!=null&&!this.artistId.isEmpty()&&this.artistId.equals(remote.id);
        portrait=verified?empty(remote.avatarUrl):"";description=verified?empty(remote.description).trim():"";
        LinkedHashMap<String,Track> source=new LinkedHashMap<>();
        if(library!=null)for(Track track:library)if(track!=null)source.putIfAbsent(LibraryStore.identity(track),track);
        if(seed!=null)source.putIfAbsent(LibraryStore.identity(seed),seed);
        if(verified)for(Track track:remote.topTracks)if(matches(track))source.putIfAbsent(LibraryStore.identity(track),track);
        HashSet<String> selected=new HashSet<>();
        if(verified)for(Track track:remote.topTracks)if(matches(track)&&selected.add(LibraryStore.identity(track)))songs.add(track);
        providerSongs=!songs.isEmpty();
        for(Track track:source.values())if(matches(track)&&selected.add(LibraryStore.identity(track)))songs.add(track);
        for(int i=0;i<Math.min(3,songs.size());i++)recommended.add(songs.get(i));

        LinkedHashMap<String,ArrayList<Track>> grouped=new LinkedHashMap<>();
        for(Track track:songs)grouped.computeIfAbsent(track.albumKey(),key->new ArrayList<>());
        // Keep other performers when the real album is a compilation, without adding them to artist songs.
        for(Track track:source.values()){ArrayList<Track> group=grouped.get(track.albumKey());if(group!=null)group.add(track);}
        LinkedHashMap<String,Collection> local=new LinkedHashMap<>();
        for(java.util.Map.Entry<String,ArrayList<Track>> group:grouped.entrySet()){
            Track first=group.getValue().get(0);String artwork=first.artworkUri;
            if(artwork.isEmpty())for(Track track:group.getValue())if(!track.artworkUri.isEmpty()){artwork=track.artworkUri;break;}
            local.put(group.getKey(),new Collection(group.getKey(),first.album,first.albumArtist,artwork,first.year,first.albumId,OnlineMusic.providerId(first),group.getValue()));
        }
        LinkedHashMap<String,Collection> catalog=new LinkedHashMap<>();
        if(verified)for(MusicProvider.Album album:remote.albums){
            if(album==null||empty(album.id).isEmpty())continue;
            String key=this.provider+"\u001falbum\u001f"+album.id;
            Collection cached=local.remove(key),earlier=catalog.get(key);
            // Match an ID-less cached album only within its own source; distinct catalog IDs stay distinct.
            if(cached==null)for(Collection candidate:new ArrayList<>(local.values()))
                if(candidate.remoteId.isEmpty()&&this.provider.equals(candidate.provider)&&same(candidate.name,album.name)&&same(candidate.artist,album.artist)){cached=local.remove(candidate.key);break;}
            ArrayList<Track> tracks=new ArrayList<>();if(cached!=null)tracks.addAll(cached.tracks);if(earlier!=null)tracks.addAll(earlier.tracks);tracks.addAll(album.tracks);
            String artwork=empty(album.coverUrl);if(artwork.isEmpty()&&cached!=null)artwork=cached.artwork;if(artwork.isEmpty()&&earlier!=null)artwork=earlier.artwork;
            catalog.put(key,new Collection(key,album.name,album.artist,artwork,cached==null?"":cached.year,album.id,this.provider,tracks));
        }
        albums.addAll(catalog.values());albums.addAll(local.values());
    }
    boolean matches(Track track){return performs(track)||matchesRole(track,true);}
    boolean performs(Track track){return matchesRole(track,false);}
    private boolean matchesRole(Track track,boolean albumArtist){
        if(track==null)return false;
        String source=OnlineMusic.providerId(track);
        if(!artistId.isEmpty()&&supportsRemote(provider)&&!source.isEmpty()){
            // Provider IDs are separate namespaces; a shared display name is not a mapping.
            if(!provider.equals(source))return false;
            String roleId=albumArtist?track.albumArtistId:track.artistId;
            if(!roleId.isEmpty())return artistId.equals(roleId);
            String otherRoleId=albumArtist?track.artistId:track.albumArtistId;
            // Missing role metadata cannot turn a known different identity into a name match.
            if(!otherRoleId.isEmpty()&&!artistId.equals(otherRoleId))return false;
        }
        // Local tags and legacy same-provider rows without IDs can still be browsed by name.
        return albumArtist?same(track.albumArtist,name):same(track.artist,name)||containsArtist(track.artist,name);
    }
    static boolean same(String a,String b){return a!=null&&b!=null&&a.trim().equalsIgnoreCase(b.trim());}
    static boolean supportsRemote(String provider){return "netease".equals(provider)||"qq".equals(provider)||"applemusic".equals(provider);}
    private static String empty(String value){return value==null?"":value;}
    private static boolean containsArtist(String all,String name){if(all==null||name==null)return false;for(String part:all.split("\\s*/\\s*"))if(same(part,name))return true;return false;}
    private static ArrayList<Track> uniqueTracks(List<Track> values){LinkedHashMap<String,Track> tracks=new LinkedHashMap<>();if(values!=null)for(Track track:values)if(track!=null)tracks.putIfAbsent(LibraryStore.identity(track),track);return new ArrayList<>(tracks.values());}
    static int clampChapter(int index){return Math.max(0,Math.min(CHAPTER_COUNT-1,index));}
}
