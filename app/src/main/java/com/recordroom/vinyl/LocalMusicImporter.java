package com.recordroom.vinyl;

import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public final class LocalMusicImporter {
    private LocalMusicImporter() { }
    public static Track importTrack(Context c, Uri uri) throws Exception {
        String filename = or(uri.getLastPathSegment(),"本地音频");
        try (Cursor cursor = c.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) filename = or(cursor.getString(0),filename);
        } catch (Exception ignored) { }
        String title=filename.replaceFirst("\\.[^.]{1,6}$", ""),artist="未知歌手",album="本地单曲",albumArtist="",art="",genre="",year="";
        long duration=0;int number=0,disc=0;
        String id=hex(MessageDigest.getInstance("SHA-256").digest(uri.toString().getBytes(StandardCharsets.UTF_8)));
        byte[] cover=null;
        try(LocalFormatDataSource.Reader reader=LocalFormatDataSource.inspect(c,uri,null)){
          if(reader!=null) {
            title=reader.value("title",title);artist=reader.value("artist",artist);album=reader.value("album",album);
            albumArtist=reader.value("album_artist","");genre=reader.value("genre","");year=reader.value("date","");
            number=Track.tagPosition(reader.value("track",reader.value("tracknumber","")));
            disc=Track.tagPosition(reader.value("disc",reader.value("discnumber","")));
            duration=reader.duration();cover=reader.cover();
          } else {
            MediaMetadataRetriever r = new MediaMetadataRetriever();
            try {
            r.setDataSource(c, uri);
            if (!"yes".equalsIgnoreCase(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO))
                    && !hasAudioTrack(c,uri)) throw new java.io.IOException("文件不包含音频");
            title=or(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE),title);
            artist=or(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST),artist);
            albumArtist=or(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST),artist);
            album=or(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM),album);
            genre=or(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE),"");
            year=or(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR),"");
            duration=number(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
            number=Track.tagPosition(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER));
            disc=Track.tagPosition(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER));
            cover=r.getEmbeddedPicture();
            } finally { try { r.release(); } catch (Exception ignored) { } }
          }
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
        }
        return new Track(id,title,artist,album,"本地音乐",uri.toString(),art,duration,number,albumArtist,genre,year).withDiscNumber(disc);
    }
    private static boolean hasAudioTrack(Context c,Uri uri) {
        android.media.MediaExtractor extractor = new android.media.MediaExtractor();
        try {
            extractor.setDataSource(c,uri,null);
            for(int i=0;i<extractor.getTrackCount();i++){
                String mime=extractor.getTrackFormat(i).getString(android.media.MediaFormat.KEY_MIME);
                if(mime!=null && mime.startsWith("audio/"))return true;
            }
        } catch (Exception ignored) { }
        finally { extractor.release(); }
        return false;
    }
    private static String or(String a,String b){return a==null||a.trim().isEmpty()?b:a.trim();}
    private static long number(String s){try{return Math.max(0,Long.parseLong(s));}catch(Exception e){return 0;}}
    private static String hex(byte[] b){StringBuilder s=new StringBuilder();for(byte n:b)s.append(String.format(java.util.Locale.ROOT,"%02x",n));return s.toString();}
    private static int sample(int w,int h,int size){int n=1;while(w/n>size*2||h/n>size*2)n*=2;return n;}
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
