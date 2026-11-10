package com.recordroom.vinyl;

import android.content.Context;
import org.json.JSONArray;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

public final class DemoCatalog {
    private DemoCatalog() { }
    public static List<Track> load(Context context) {
        ArrayList<Track> tracks = new ArrayList<>();
        try (InputStream in = context.getAssets().open("demo/catalog.json")) {
            java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();byte[] chunk=new byte[4096];int n;
            while((n=in.read(chunk))!=-1)bytes.write(chunk,0,n);
            JSONArray list = new JSONArray(new String(bytes.toByteArray(), java.nio.charset.StandardCharsets.UTF_8));
            for (int i=0;i<list.length();i++) tracks.add(Track.fromJson(list.getJSONObject(i)));
        } catch (Exception ignored) { }
        return tracks;
    }
}
