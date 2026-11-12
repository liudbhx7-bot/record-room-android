package com.recordroom.vinyl;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;

final class ThemeStore {
    static final int[] COLORS = {DesignSystem.LILAC, DesignSystem.ROSE, 0xff373839, 0xff09090b, DesignSystem.PAPER, 0xff789088, 0xffbed59b};
    static final String[] COLOR_NAMES = {"雾紫", "灰粉", "深灰", "纯黑", "纸白", "鼠尾草", "青柠"};
    static final String[] DISC_NAMES = DiscMaterials.NAMES;
    private final SharedPreferences prefs;
    private final java.util.LinkedHashMap<String,AlbumPalette> coverPalettes=new java.util.LinkedHashMap<String,AlbumPalette>(32,.75f,true){
        @Override protected boolean removeEldestEntry(java.util.Map.Entry<String,AlbumPalette> eldest){return size()>64;}
    };
    ThemeStore(Context c) {
        prefs = c.getSharedPreferences("vinyl_appearance", Context.MODE_PRIVATE);
        // The previous colour picker silently persisted fixed mode. Upgrade once to the requested
        // automatic default, retaining every saved manual colour/material for an explicit switch back.
        if(prefs.getInt("cover_color_policy_version",0)<2) prefs.edit().putInt("cover_color_policy_version",2).putBoolean("follow_cover_colors",true).apply();
    }
    private String key(String album) { return "album:" + album + ":"; }
    boolean hasAlbum(String album) { return album != null && prefs.contains(key(album) + "disc"); }
    int disc(String album) { return Math.max(0, Math.min(DISC_NAMES.length - 1, prefs.getInt(hasAlbum(album) ? key(album) + "disc" : "disc", 5))); }
    // Saved ARGB values and per-album keys are unchanged; only an unset default uses the new palette.
    private int manualBackground(String album){return prefs.getInt(hasAlbum(album)?key(album)+"color":"color",DesignSystem.LILAC);}
    boolean followCoverColors(){return prefs.getBoolean("follow_cover_colors",true);}
    void followCoverColors(boolean value){prefs.edit().putBoolean("follow_cover_colors",value).apply();}
    int background(String album) {
        if(followCoverColors()){
            AlbumPalette palette; synchronized(coverPalettes){palette=coverPalettes.get(album);}
            if(palette!=null)return palette.background;
            if(album!=null&&album.equals(prefs.getString("cover_palette_album",null)))return prefs.getInt("cover_palette_background",DesignSystem.LILAC);
        }
        return manualBackground(album);
    }
    int recordTint(String album){
        if(!followCoverColors())return Color.TRANSPARENT;
        AlbumPalette palette;synchronized(coverPalettes){palette=coverPalettes.get(album);}
        if(palette!=null)return palette.record;
        if(album!=null&&album.equals(prefs.getString("cover_palette_album",null)))return prefs.getInt("cover_palette_record",Color.TRANSPARENT);
        return Color.TRANSPARENT;
    }
    int deckTint(String album) {
        if(!followCoverColors()) return Color.TRANSPARENT;
        AlbumPalette palette; synchronized(coverPalettes){palette=coverPalettes.get(album);}
        if(palette!=null) return palette.deckLight;
        if(album!=null && album.equals(prefs.getString("cover_palette_album",null)))
            return prefs.getInt("cover_palette_lamp",recordTint(album));
        return Color.TRANSPARENT;
    }
    void coverPalette(String album,AlbumPalette palette){
        if(album==null||album.isEmpty()||palette==null)return;
        synchronized(coverPalettes){coverPalettes.put(album,palette);}
        if(album.equals(prefs.getString("cover_palette_album",null))&&prefs.getInt("cover_palette_background",0)==palette.background&&prefs.getInt("cover_palette_record",0)==palette.record&&prefs.getInt("cover_palette_lamp",0)==palette.deckLight)return;
        // Only the current palette is persisted. Large libraries do not grow the preferences file.
        prefs.edit().putString("cover_palette_album",album).putInt("cover_palette_background",palette.background).putInt("cover_palette_record",palette.record).putInt("cover_palette_lamp",palette.deckLight).apply();
    }
    void save(String album, boolean specific, int disc, int color) {
        String k = specific && album != null ? key(album) : "";
        prefs.edit().putInt(k + "disc", disc).putInt(k + "color", color).apply();
    }
    void clearAlbum(String album) { prefs.edit().remove(key(album) + "disc").remove(key(album) + "color").apply(); }
    int discFor(String album, boolean specific) { return specific ? disc(album) : disc(""); }
    int colorFor(String album, boolean specific) { return manualBackground(specific?album:""); }
    float lighting() { return Math.max(0, Math.min(1, prefs.getFloat("lighting", .7f))); }
    void lighting(float value) { prefs.edit().putFloat("lighting", Math.max(0, Math.min(1, value))).apply(); }
    boolean motion() { return prefs.getBoolean("motion", true); }
    void motion(boolean value) { prefs.edit().putBoolean("motion", value).apply(); }
    boolean needleSound() { return prefs.getBoolean("needle_sound", true); }
    void needleSound(boolean value) { prefs.edit().putBoolean("needle_sound", value).apply(); }
    boolean keepAwake() { return prefs.getBoolean("keep_awake", false); }
    void keepAwake(boolean value) { prefs.edit().putBoolean("keep_awake", value).apply(); }
    boolean autoHide() { return prefs.getBoolean("auto_hide", true); }
    void autoHide(boolean value) { prefs.edit().putBoolean("auto_hide", value).apply(); }
    String artwork(String album) { return album == null ? "" : prefs.getString(key(album) + "art", ""); }
    void artwork(String album, String uri) { if (album != null) prefs.edit().putString(key(album) + "art", uri).apply(); }
}
