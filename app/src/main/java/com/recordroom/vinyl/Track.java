package com.recordroom.vinyl;

import org.json.JSONObject;

public final class Track {
    /** Catalog requirement, not a claim about the signed-in account entitlement. */
    public enum PlaybackAccess { UNKNOWN, STANDARD, VIP, PURCHASE }
    public final PlaybackAccess playbackAccess;
    public final String id, title, artist, album, source, uri, artworkUri, albumArtist, genre, year;
    public final String provider, albumId, artistId, albumArtistId;
    public final long durationMs;
    public final int trackNumber;
    public Track(String id, String title, String artist, String album, String source, String uri, String artworkUri, long durationMs, int trackNumber) {
        this(id,title,artist,album,source,uri,artworkUri,durationMs,trackNumber,artist);
    }
    public Track(String id, String title, String artist, String album, String source, String uri, String artworkUri, long durationMs, int trackNumber, String albumArtist) {
        this(id,title,artist,album,source,uri,artworkUri,durationMs,trackNumber,albumArtist,"","");
    }
    public Track(String id, String title, String artist, String album, String source, String uri, String artworkUri, long durationMs, int trackNumber, String albumArtist, String genre, String year) {
        this(id,title,artist,album,source,uri,artworkUri,durationMs,trackNumber,albumArtist,genre,year,uri!=null&&uri.startsWith("netease://")?"netease":"","","");
    }
    public Track(String id, String title, String artist, String album, String source, String uri, String artworkUri, long durationMs, int trackNumber, String albumArtist, String genre, String year, String provider, String albumId, String artistId) {
        this(id,title,artist,album,source,uri,artworkUri,durationMs,trackNumber,albumArtist,genre,year,provider,albumId,artistId,artistId);
    }
    public Track(String id, String title, String artist, String album, String source, String uri, String artworkUri, long durationMs, int trackNumber, String albumArtist, String genre, String year, String provider, String albumId, String artistId, String albumArtistId) {
        this(id,title,artist,album,source,uri,artworkUri,durationMs,trackNumber,albumArtist,genre,year,provider,albumId,artistId,albumArtistId,PlaybackAccess.UNKNOWN);
    }
    private Track(String id, String title, String artist, String album, String source, String uri, String artworkUri, long durationMs, int trackNumber, String albumArtist, String genre, String year, String provider, String albumId, String artistId, String albumArtistId, PlaybackAccess playbackAccess) {
        this.playbackAccess=playbackAccess==null?PlaybackAccess.UNKNOWN:playbackAccess;
        this.provider=clean(provider,"");this.albumId=canonicalAlbumId(this.provider,clean(albumId,""));this.artistId=clean(artistId,"");this.albumArtistId=clean(albumArtistId,this.artistId);
        this.id = clean(id, uri); this.title = clean(title, "未知歌曲"); this.artist = clean(artist, "未知歌手");
        this.album = clean(album, "未分类专辑"); this.source = clean(source, "本地音乐"); this.uri = clean(uri, "");
        this.artworkUri = clean(artworkUri, ""); this.durationMs = Math.max(0, durationMs); this.trackNumber = Math.max(0, trackNumber);
        this.albumArtist = clean(albumArtist,this.artist);
        this.genre = clean(genre, ""); this.year = clean(year, "");
    }
    public Track withPlaybackAccess(PlaybackAccess access) {
        PlaybackAccess value=access==null?PlaybackAccess.UNKNOWN:access;
        return value==playbackAccess?this:new Track(id,title,artist,album,source,uri,artworkUri,durationMs,trackNumber,albumArtist,genre,year,provider,albumId,artistId,albumArtistId,value);
    }
    public String accessLabel() { return playbackAccess==PlaybackAccess.VIP?"需 VIP":playbackAccess==PlaybackAccess.PURCHASE?"需单独购买":""; }
    private static PlaybackAccess readPlaybackAccess(JSONObject json) {
        try { return PlaybackAccess.valueOf(json.optString("playbackAccess", "UNKNOWN")); } catch (IllegalArgumentException ignored) { return PlaybackAccess.UNKNOWN; }
    }
    private static String clean(String value, String fallback) { return value == null || value.trim().isEmpty() ? fallback : value.trim(); }
    public String albumKey() { return !provider.isEmpty()&&!albumId.isEmpty()?provider+"\u001falbum\u001f"+albumId:canonical(source) + "\u001f" + canonical(albumArtist) + "\u001f" + canonical(album); }
    private static String canonicalAlbumId(String provider,String value){
        // Earlier QQ imports mistook a versioned cover pmid for the canonical album mid.
        return "qq".equals(provider)&&value.matches("[A-Za-z0-9]{14}_[0-9]+")?value.substring(0,14):value;
    }
    private static String canonical(String value){return java.text.Normalizer.normalize(value,java.text.Normalizer.Form.NFKC).trim().replaceAll("\\s+"," ").toLowerCase(java.util.Locale.ROOT);}
    public JSONObject toJson() {
        JSONObject j = new JSONObject();
        try { j.put("id",id); j.put("title",title); j.put("artist",artist); j.put("album",album); j.put("albumArtist",albumArtist); j.put("source",source); j.put("uri",uri); j.put("artworkUri",artworkUri); j.put("durationMs",durationMs); j.put("trackNumber",trackNumber); j.put("genre",genre); j.put("year",year); j.put("provider",provider);j.put("albumId",albumId);j.put("artistId",artistId);j.put("albumArtistId",albumArtistId);j.put("playbackAccess",playbackAccess.name()); } catch (Exception ignored) { }
        return j;
    }
    public static Track fromJson(JSONObject j) {
        return new Track(j.optString("id"),j.optString("title"),j.optString("artist"),j.optString("album"),j.optString("source"),j.optString("uri"),j.optString("artworkUri"),j.optLong("durationMs"),j.optInt("trackNumber"),j.optString("albumArtist"),j.isNull("genre") ? "" : j.optString("genre", ""),j.isNull("year") ? "" : j.optString("year", ""), j.optString("provider",j.optString("uri").startsWith("netease://")?"netease":""),j.optString("albumId",""),j.optString("artistId",""),j.isNull("albumArtistId")?"":j.optString("albumArtistId",""),readPlaybackAccess(j));
    }
}
