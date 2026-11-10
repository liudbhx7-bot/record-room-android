package com.recordroom.vinyl;

import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.AtomicFile;
import org.json.JSONArray;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

public final class LocalMusicImporter {
    private LocalMusicImporter() { }
    public static Track importTrack(Context c, Uri uri) throws Exception {
        String filename = "本地音频";
        try (Cursor cursor = c.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) filename = or(cursor.getString(0),filename);
        } catch (Exception ignored) { }
        String title=filename.replaceFirst("\\.[^.]{1,6}$", ""),artist="未知歌手",album="本地单曲",albumArtist="",art="",genre="",year="";
        long duration=0;int number=0;
        String id=hex(MessageDigest.getInstance("SHA-256").digest(uri.toString().getBytes(StandardCharsets.UTF_8)));
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(c, uri);
            title=or(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE),title);
            artist=or(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST),artist);
            albumArtist=or(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST),artist);
            album=or(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM),album);
            genre=or(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE),"");
            year=or(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR),"");
            duration=number(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
            String track=or(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER),"0");
            number=(int)number(track.split("/")[0]);
            byte[] cover=r.getEmbeddedPicture();
            if(cover!=null && cover.length<16*1024*1024) {
                BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;
                BitmapFactory.decodeByteArray(cover,0,cover.length,bounds);
                BitmapFactory.Options opts=new BitmapFactory.Options();opts.inSampleSize=sample(bounds.outWidth,bounds.outHeight,1024);
                Bitmap bitmap=BitmapFactory.decodeByteArray(cover,0,cover.length,opts);
                if(bitmap!=null) {
                    File folder=new File(c.getFilesDir(),"covers");folder.mkdirs();File f=new File(folder,id+".jpg");
                    try(FileOutputStream out=new FileOutputStream(f)){bitmap.compress(Bitmap.CompressFormat.JPEG,90,out);}
                    bitmap.recycle();art=Uri.fromFile(f).toString();
                }
            }
        } finally { try { r.release(); } catch (Exception ignored) { } }
        return new Track(id,title,artist,album,"本地音乐",uri.toString(),art,duration,number,albumArtist,genre,year);
    }
    private static String or(String a,String b){return a==null||a.trim().isEmpty()?b:a.trim();}
    private static long number(String s){try{return Math.max(0,Long.parseLong(s));}catch(Exception e){return 0;}}
    private static String hex(byte[] b){StringBuilder s=new StringBuilder();for(byte n:b)s.append(String.format(java.util.Locale.ROOT,"%02x",n));return s.toString();}
    private static int sample(int w,int h,int size){int n=1;while(w/n>size*2||h/n>size*2)n*=2;return n;}
    private static File libraryFile(Context c){return new File(c.getFilesDir(),"library.json");}
    public static synchronized List<Track> loadLibrary(Context c){
        File f=libraryFile(c);if(!f.exists()&&!new File(f.getPath()+".bak").exists())return new ArrayList<>();
        ArrayList<Track> result=new ArrayList<>();
        try(FileInputStream in=new AtomicFile(f).openRead()){
            if(f.length()>8*1024*1024)throw new java.io.IOException("Library exceeds size limit");
            java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();byte[] chunk=new byte[4096];int n;
            while((n=in.read(chunk))!=-1)bytes.write(chunk,0,n);
            JSONArray a=new JSONArray(new String(bytes.toByteArray(),StandardCharsets.UTF_8));
            for(int i=0;i<a.length();i++)result.add(Track.fromJson(a.getJSONObject(i)));
        }catch(Exception e){return new ArrayList<>();}
        return result;
    }
    public static synchronized void saveLibrary(Context c,List<Track> tracks){
        AtomicFile f=new AtomicFile(libraryFile(c));FileOutputStream out=null;
        try{
            JSONArray a=new JSONArray();for(Track t:tracks)a.put(t.toJson());
            out=f.startWrite();out.write(a.toString().getBytes(StandardCharsets.UTF_8));f.finishWrite(out);
        }catch(Exception e){if(out!=null)f.failWrite(out);}
    }
    private static InputStream open(Context c,String value)throws Exception{
        if(value.startsWith("asset:///"))return c.getAssets().open(value.substring(9));
        if(value.startsWith("http://")||value.startsWith("https://"))return new FileInputStream(RemoteArtworkCache.fetch(c,value));
        if(value.startsWith("/"))return new FileInputStream(value);
        return c.getContentResolver().openInputStream(Uri.parse(value));
    }
    public static Bitmap loadArtwork(Context c,String value,int size){
        if(value==null||value.isEmpty())return null;
        try{
            BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;
            try(InputStream in=open(c,value)){if(in==null)return null;BitmapFactory.decodeStream(in,null,bounds);}
            if(bounds.outWidth<=0||bounds.outHeight<=0||bounds.outWidth>30000||bounds.outHeight>30000)return null;
            BitmapFactory.Options options=new BitmapFactory.Options();options.inSampleSize=sample(bounds.outWidth,bounds.outHeight,Math.max(64,size));
            try(InputStream in=open(c,value)){return BitmapFactory.decodeStream(in,null,options);}
        }catch(Exception e){return null;}
    }
}
