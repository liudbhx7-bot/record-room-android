package com.recordroom.vinyl;

import android.net.Uri;
import android.util.LruCache;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.List;
import java.util.ArrayList;
import java.util.Locale;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONException;
import org.json.JSONArray;
import org.json.JSONObject;

/** Public lyrics keyed by NetEase's recording ID; user imports live in TrackLyrics. */
final class NeteaseLyrics {
    private static final int LIMIT = 512 * 1024;
    private static final LruCache<String,String> cache = new LruCache<String,String>(512 * 1024) {
        @Override protected int sizeOf(String id,String text) { return Math.max(1, text.length() * 2); }
    };

    static String resolve(Track track,ExternalMusicBridge.State linked) throws ProviderException {
        String id;
        if (linked != null) {
            if (!"netease_music".equals(linked.selectedProviderId)) return "";
            id = recordingId(linked.mediaId);
            if (id.isEmpty() && !linked.title.isEmpty() && !linked.artist.isEmpty()) {
                List<Track> candidates = search(linked.title,linked.artist);
                id = match(candidates,linked.title,linked.artist,linked.album,linked.durationMs);
            }
        } else {
            if (!OnlineMusic.isNetease(track)) return "";
            id = recordingId(track.id);
            if (id.isEmpty()) id = recordingId(track.uri);
        }
        if (id.isEmpty()) return "";
        String previous = cache.get(id);
        if (previous != null) return previous;
        String value = fetch(id);
        if (!value.isEmpty()) cache.put(id,value);
        return value;
    }

    static String recordingId(String value) {
        if (value == null) return "";
        value = value.trim();
        if (value.startsWith("netease://song/")) value = value.substring("netease://song/".length());
        else if (value.startsWith("orpheus://song/")) value = value.substring("orpheus://song/".length());
        else if (value.startsWith("https://music.163.com/song?"))
            value = Uri.parse(value).getQueryParameter("id");
        return value != null && value.matches("[1-9][0-9]{0,18}") ? value : "";
    }

    private static String normalized(String value) {
        return Normalizer.normalize(value,Normalizer.Form.NFKC).trim().replaceAll("\\s+"," ").toLowerCase(Locale.ROOT);
    }

    private static String normalizedArtist(String text) {
        return normalized(text).replaceAll("\\s*[/、]\\s*"," / ");
    }

    static String match(List<Track> candidates,String title,String artist,String album,long duration) {
        String name = normalized(title), performer = normalizedArtist(artist), release = normalized(album);
        if (name.isEmpty() || performer.isEmpty()) return "";
        String result = "";
        int best = -1;
        for (Track song : candidates) {
            if (!name.equals(normalized(song.title)) || !performer.equals(normalizedArtist(song.artist))) continue;
            String id = recordingId(song.id);
            if (id.isEmpty()) continue;
            if (duration > 0 && song.durationMs > 0 && Math.abs(song.durationMs-duration) > 2000) continue;
            String albumName = normalized(song.album);
            if (!release.isEmpty() && !albumName.isEmpty() && !release.equals(albumName)) continue;
            int score = (release.isEmpty() || !release.equals(albumName) ? 0 : 2) +
                        (duration > 0 && song.durationMs > 0 ? 1 : 0);
            if (score > best) { result = id; best = score; }
            else if (score == best && !result.equals(id)) result = "";
        }
        return result;
    }

    static String fetch(String id) throws ProviderException {
        if (id.isEmpty() || !id.equals(recordingId(id))) return "";
        return unpack(request("song/lyric?id="+id+"&lv=-1&kv=-1&tv=-1"));
    }

    static List<Track> search(String title,String artist) throws ProviderException {
        String query = (title+" "+artist).trim();
        if (query.isEmpty() || query.length()>512) return java.util.Collections.emptyList();
        String encoded;
        try { encoded = URLEncoder.encode(query,"UTF-8"); }
        catch (java.io.UnsupportedEncodingException impossible) { throw new AssertionError(impossible); }
        JSONObject data = request("cloudsearch/pc?s="+encoded+"&type=1&limit=30&offset=0");
        JSONObject result = data.optJSONObject("result");
        if (data.optInt("code")!=200 || result==null)
            throw new ProviderException(ProviderException.Kind.INVALID_RESPONSE,"网易云暂未返回曲目资料，请稍后重试");
        JSONArray entries = result.optJSONArray("songs");
        ArrayList<Track> candidates = new ArrayList<>();
        if (entries!=null) for (int i=0;i<entries.length();i++) {
            candidates.add(NeteaseProvider.track(entries.optJSONObject(i)));
        }
        return candidates;
    }

    private static JSONObject request(String path) throws ProviderException {
        HttpsURLConnection conn = null;
        try {
            URL endpoint = new URL("https://music.163.com/api/"+path);
            conn = (HttpsURLConnection)endpoint.openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(8000);
            conn.setRequestProperty("Referer","https://music.163.com/");
            conn.setRequestProperty("Accept","application/json");
            int code = conn.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code);
            try (InputStream input = conn.getInputStream()) {
                ByteArrayOutputStream data = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int count;
                while ((count=input.read(buffer)) != -1) {
                    if (data.size()+count > LIMIT || Thread.currentThread().isInterrupted())
                        throw new IOException("Interrupted or oversized response");
                    data.write(buffer,0,count);
                }
                return new JSONObject(new String(data.toByteArray(),StandardCharsets.UTF_8));
            }
        } catch (IOException | JSONException failure) {
            throw new ProviderException(ProviderException.Kind.NETWORK,"网易云歌词读取失败，请检查网络后重试",failure);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    static String unpack(JSONObject response) throws ProviderException {
        if (response.optInt("code") != 200)
            throw new ProviderException(ProviderException.Kind.INVALID_RESPONSE,"网易云暂未返回歌词，请稍后重试");
        if (response.optBoolean("nolyric")) return "纯音乐";
        JSONObject lrc = response.optJSONObject("lrc");
        if (response.optBoolean("uncollected") || lrc == null || lrc.isNull("lyric")) return "";
        String raw = lrc.optString("lyric","");
        return TrackLyrics.display(raw).isEmpty() ? "" : raw;
    }
}
