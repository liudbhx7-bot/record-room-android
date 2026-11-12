package com.recordroom.vinyl;

import android.content.Context;
import android.net.Uri;
import android.util.AtomicFile;

import androidx.media3.common.Metadata;
import androidx.media3.common.Tracks;
import androidx.media3.extractor.metadata.id3.BinaryFrame;
import androidx.media3.extractor.metadata.id3.TextInformationFrame;
import androidx.media3.extractor.metadata.vorbis.VorbisComment;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.regex.Pattern;

/** Bounded text lyrics scoped to the same stable identity as the library. */
final class TrackLyrics {
    private static final int LIMIT = 256 * 1024;
    private static final Pattern TIME = Pattern.compile("\\[\\d{1,3}:\\d{2}(?:[.:]\\d{1,3})?\\]");
    private static final Pattern TAG = Pattern.compile("(?im)^\\s*\\[(?:ar|al|ti|by|offset|length|re|ve):[^\\]\r\n]*\\]\\s*$");

    private TrackLyrics() {}

    static String embedded(Tracks tracks) {
        for (Tracks.Group group : tracks.getGroups()) {
            for (int i=0; i<group.length; i++) {
                Metadata meta = group.getTrackFormat(i).metadata;
                if (meta == null) continue;
                for (int n=0; n<meta.length(); n++) {
                    String value = entry(meta.get(n));
                    if (!value.isEmpty()) return value;
                }
            }
        }
        return "";
    }

    static String entry(Metadata.Entry item) {
        if (item instanceof VorbisComment) {
            VorbisComment tag = (VorbisComment)item;
            if (isLyricKey(tag.key)) return display(tag.value);
        } else if (item instanceof TextInformationFrame) {
            TextInformationFrame tag = (TextInformationFrame)item;
            if (isLyricKey(tag.description)) return display(tag.value);
        } else if (item instanceof BinaryFrame) {
            BinaryFrame tag = (BinaryFrame)item;
            if (!"USLT".equals(tag.id) && !"ULT".equals(tag.id)) return "";
            byte[] data = tag.data;
            if (data.length < 6 || data.length > LIMIT) return "";
            Charset encoding;
            switch (data[0]) {
                case 0: encoding = StandardCharsets.ISO_8859_1; break;
                case 1: encoding = StandardCharsets.UTF_16; break;
                case 2: encoding = StandardCharsets.UTF_16BE; break;
                case 3: encoding = StandardCharsets.UTF_8; break;
                default: return "";
            }
            int width = data[0]==1 || data[0]==2 ? 2 : 1;
            // ID3 USLT begins with encoding, a 3-byte language, and a terminated descriptor.
            for (int n=4; n+width<=data.length; n+=width) {
                if (data[n]!=0 || (width==2 && data[n+1]!=0)) continue;
                int start = n+width;
                int end = data.length;
                while (end-start >= width && data[end-1] == 0 && (width == 1 || data[end-2] == 0)) end -= width;
                return display(new String(data,start,end-start,encoding));
            }
        }
        return "";
    }

    private static boolean isLyricKey(String key) {
        return "lyrics".equalsIgnoreCase(key) || "unsyncedlyrics".equalsIgnoreCase(key)
            || "unsynced lyrics".equalsIgnoreCase(key);
    }

    static void importFile(Context context,String id,Uri uri) throws IOException {
        if (!"content".equals(uri.getScheme())) throw new IOException("Unsupported document");
        byte[] raw;
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("Empty document");
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] part = new byte[4096];
            int count;
            while ((count=in.read(part))!=-1) {
                if (buffer.size()+count > LIMIT) throw new IOException("Document too large");
                buffer.write(part,0,count);
            }
            raw = buffer.toByteArray();
        }
        boolean utf16 = raw.length>=2 && ((raw[0]==-1 && raw[1]==-2)||(raw[0]==-2 && raw[1]==-1));
        Charset encoding = utf16 ? StandardCharsets.UTF_16 : StandardCharsets.UTF_8;
        String value = display(encoding.newDecoder().decode(ByteBuffer.wrap(raw)).toString());
        if (value.isEmpty()) throw new IOException("No lyric text");
        AtomicFile file = file(context,id);
        FileOutputStream out = file.startWrite();
        try {
            out.write(value.getBytes(StandardCharsets.UTF_8));
            file.finishWrite(out);
        } catch (IOException e) {
            file.failWrite(out);
            throw e;
        }
    }

    static String load(Context context,String id) {
        try { return new String(file(context,id).readFully(),StandardCharsets.UTF_8); }
        catch (IOException missing) { return ""; }
    }

    private static AtomicFile file(Context context,String id) throws IOException {
        File folder = new File(context.getFilesDir(),"track-lyrics");
        if (!folder.isDirectory() && !folder.mkdirs() && !folder.isDirectory()) throw new IOException("Lyrics directory unavailable");
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(id.getBytes(StandardCharsets.UTF_8));
            StringBuilder name = new StringBuilder();
            for (byte b : hash) name.append(String.format(Locale.ROOT,"%02x",b & 255));
            return new AtomicFile(new File(folder,name.toString()));
        } catch (java.security.NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }

    static String display(String value) {
        if (value==null || value.length()>LIMIT || value.indexOf('\0')>=0) return "";
        String lines = value.replace("\ufeff","").replace("\r\n","\n").replace('\r','\n');
        return TAG.matcher(TIME.matcher(lines).replaceAll("")).replaceAll("").trim();
    }
}
