package com.recordroom.vinyl;

import android.content.Context;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Only packaged, reviewed entries are playable. Saved queues carry a catalog ID, never a remote URL. */
final class PublicMusicProvider implements MusicProvider {
    static final String ID = "publicmusic";
    static final String NAME = "公开音乐精选";
    interface CatalogInput { InputStream open() throws IOException; }
    private final CatalogInput input;
    private LinkedHashMap<String,Entry> catalog;

    PublicMusicProvider(Context context) {
        this(() -> context.getApplicationContext().getAssets().open("public-music/catalog.json"));
    }

    PublicMusicProvider(CatalogInput input) {
        this.input = input;
    }

    static final class Entry {
        final Track track;
        final String audioUrl, credit, license, licenseUrl, sourceUrl, search;
        Entry(JSONObject row) throws JSONException {
            String id = required(row, "id");
            String artist = required(row, "artist");
            String artwork = row.optString("artwork", "");
            if (!artwork.isEmpty()) {
                if (!artwork.startsWith("asset:///public-music/") || artwork.contains("..")) artwork = https(artwork);
            }
            long duration = row.getLong("durationMs");
            if (duration <= 0) throw new JSONException("Missing recording duration");
            track = new Track(id, required(row,"title"), artist, required(row,"album"), NAME,
                    ID + "://track/" + id, artwork, duration, row.getInt("trackNumber"), artist,
                    "", "", ID, required(row,"albumId"), required(row,"artistId")).withPlaybackAccess(Track.PlaybackAccess.STANDARD);
            audioUrl = https(required(row,"url"));
            sourceUrl = https(required(row,"sourceUrl"));
            credit = required(row,"credit");
            license = required(row,"license");
            licenseUrl = https(required(row,"licenseUrl"));
            search = normalized(track.title + " " + track.artist + " " + track.album + " " + row.optString("keywords",""));
        }
    }

    private static String required(JSONObject row, String key) throws JSONException {
        String value = row.getString(key).trim();
        if (value.isEmpty() || row.isNull(key)) throw new JSONException("Empty field " + key);
        return value;
    }

    private static String https(String value) throws JSONException {
        try {
            URI url = URI.create(value);
            if ("https".equalsIgnoreCase(url.getScheme()) && url.getHost() != null && url.getUserInfo() == null) return value;
        } catch (IllegalArgumentException ignored) { }
        throw new JSONException("Invalid link");
    }

    private static String normalized(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC).trim().toLowerCase(Locale.ROOT);
    }

    private synchronized LinkedHashMap<String,Entry> entries() throws ProviderException {
        if (catalog != null) return catalog;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                input.open(), StandardCharsets.UTF_8))) {
            StringBuilder data = new StringBuilder();
            char[] buffer = new char[4096];
            int n;
            while ((n = reader.read(buffer)) != -1) data.append(buffer,0,n);
            JSONArray rows = new JSONArray(data.toString());
            LinkedHashMap<String,Entry> loaded = new LinkedHashMap<>();
            for (int i = 0; i < rows.length(); i++) {
                Entry entry = new Entry(rows.getJSONObject(i));
                if (loaded.put(entry.track.id, entry) != null) throw new JSONException("Repeated recording ID");
            }
            if (loaded.isEmpty()) throw new JSONException("Empty catalog");
            catalog = loaded;
            return catalog;
        } catch (IOException | JSONException e) {
            throw new ProviderException(ProviderException.Kind.INVALID_RESPONSE,"公开音乐目录无法读取",e);
        }
    }

    Entry entry(String id) throws ProviderException {
        Entry result = entries().get(id);
        if (result == null) throw new ProviderException(ProviderException.Kind.UNAVAILABLE,"这首作品已从公开精选移除");
        return result;
    }

    @Override public String id() { return ID; }
    @Override public boolean supportsAccount() { return false; }
    @Override public boolean supportsArtistSearch() { return true; }
    @Override public boolean supportsAlbumSearch() { return true; }

    @Override public Page<Track> searchTracks(String query, int offset, int limit) throws ProviderException {
        String[] terms = normalized(query).split("\\s+");
        List<Track> found = new ArrayList<>();
        for (Entry entry : entries().values()) {
            boolean match = true;
            for (String term : terms) if (!entry.search.contains(term)) { match = false; break; }
            if (match) found.add(entry.track);
        }
        return page(found,offset,limit);
    }

    @Override public Page<Album> searchAlbums(String query, int offset, int limit) throws ProviderException {
        String term = normalized(query);
        LinkedHashMap<String,Album> found = new LinkedHashMap<>();
        for (Entry entry : entries().values()) {
            Track t = entry.track;
            if (!found.containsKey(t.albumId) && normalized(t.album + " " + t.artist).contains(term))
                found.put(t.albumId,album(t.albumId));
        }
        return page(new ArrayList<>(found.values()),offset,limit);
    }

    @Override public Album album(String id) throws ProviderException {
        ArrayList<Track> tracks = new ArrayList<>();
        for (Entry e : entries().values()) if (e.track.albumId.equals(id)) tracks.add(e.track);
        if (tracks.isEmpty()) throw new ProviderException(ProviderException.Kind.UNAVAILABLE,"公开精选未收录这张专辑");
        tracks.sort(Track.ALBUM_ORDER);
        Track t = tracks.get(0);
        return new Album(id,t.album,t.albumArtist,t.albumArtistId,t.artworkUri,tracks);
    }

    @Override public Page<Artist> searchArtists(String query, int offset, int limit) throws ProviderException {
        String term = normalized(query);
        LinkedHashMap<String,Artist> found = new LinkedHashMap<>();
        for (Entry e : entries().values()) {
            Track t = e.track;
            if (!found.containsKey(t.artistId) && normalized(t.artist).contains(term))
                found.put(t.artistId,artist(t.artistId));
        }
        return page(new ArrayList<>(found.values()),offset,limit);
    }

    @Override public Artist artist(String id) throws ProviderException {
        List<Track> songs = new ArrayList<>();
        LinkedHashMap<String,Album> albums = new LinkedHashMap<>();
        for (Entry e : entries().values()) {
            Track t = e.track;
            if (!t.artistId.equals(id)) continue;
            songs.add(t);
            if (!albums.containsKey(t.albumId)) albums.put(t.albumId,album(t.albumId));
        }
        if (songs.isEmpty()) throw new ProviderException(ProviderException.Kind.UNAVAILABLE,"公开精选未收录这位音乐人");
        return new Artist(id,songs.get(0).artist,"公开精选收录了 "+songs.size()+" 首作品。","",songs,new ArrayList<>(albums.values()));
    }

    private static <T> Page<T> page(List<T> items, int offset, int limit) {
        int start = Math.min(Math.max(0,offset),items.size());
        int next = start + Math.min(items.size()-start,Math.max(1,limit));
        return new Page<>(items.subList(start,next),items.size(),next,next<items.size());
    }

    @Override public PlaybackResource resolve(Track track) throws ProviderException {
        Entry e = entry(track.id);
        return new PlaybackResource(e.audioUrl,Collections.emptyMap(),0,e.track.durationMs);
    }

    private static ProviderException noAccount() {
        return new ProviderException(ProviderException.Kind.UNAVAILABLE,"公开精选无需账号");
    }
    @Override public QrLogin beginQrLogin() throws ProviderException { throw noAccount(); }
    @Override public LoginPoll pollQrLogin(String key) throws ProviderException { throw noAccount(); }
    @Override public Account account() throws ProviderException { throw noAccount(); }
    @Override public List<Playlist> playlists(String userId) throws ProviderException { throw noAccount(); }
    @Override public List<Track> likedTracks(String userId) throws ProviderException { throw noAccount(); }
    @Override public List<Track> playlistTracks(String playlistId) throws ProviderException { throw noAccount(); }
    @Override public void logout() throws ProviderException { throw noAccount(); }
}
