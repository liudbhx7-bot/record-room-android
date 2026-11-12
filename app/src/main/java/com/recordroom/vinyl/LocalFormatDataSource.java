package com.recordroom.vinyl;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import androidx.media3.common.C;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/** Presents local APE, AIFF and WavPack as a seekable PCM stream to the existing Media3 player. */
final class LocalFormatDataSource implements DataSource {
    interface SourceInfo { void found(Uri uri,String name, int rate, int bits); }
    private static native long openReader(int fd,long start,long length,int kind,long[] format) throws IOException;
    private static native void seekReader(long handle,long frame) throws IOException;
    private static native int readPcm(long handle,byte[] block) throws IOException;
    private static native void closeReader(long handle);
    private static native byte[] tag(long handle,String name);
    private static native byte[] picture(long handle);
    private final Context context;
    private final DataSource regular;
    private final SourceInfo observer;
    private Reader reader;
    private Uri uri;
    private byte[] header, block;
    private int offset, end, skipBytes;
    private long position, remaining;
    LocalFormatDataSource(Context context, DataSource regular, SourceInfo observer) {
        this.context=context; this.regular=regular; this.observer=observer;
    }

    static final class Reader implements Closeable {
        final long[] format=new long[6];
        final String name;
        private long handle;
        Reader(AssetFileDescriptor asset,int kind,long length) throws IOException {
            name=kind==1?"APE":kind==2?"WavPack":kind==3?"AIFF":"ALAC";
            try { System.loadLibrary("record_formats"); }
            catch (UnsatisfiedLinkError failure) {throw new IOException("当前设备缺少本地格式解码器",failure);}
            handle=openReader(asset.getParcelFileDescriptor().getFd(),asset.getStartOffset(),length,kind,format);
        }
        String value(String key,String fallback) {
            byte[] b=tag(handle,key);
            return b==null || b.length==0 ? fallback : new String(b,StandardCharsets.UTF_8);
        }
        byte[] cover(){return picture(handle);}
        long duration(){return format[4]/format[0]*1000+(format[4]%format[0])*1000/format[0];}
        public void close(){if(handle!=0){closeReader(handle);handle=0;}}
    }

    private static Reader openIfHandled(AssetFileDescriptor file,int kind,long length) throws IOException {
        Reader reader=new Reader(file,kind,length);
        return reader.handle!=0?reader:null;
    }

    static Reader inspect(Context c,Uri uri,SourceInfo observer) throws IOException {
        if(!"content".equals(uri.getScheme()) && !"file".equals(uri.getScheme()))return null;
        try(AssetFileDescriptor a=c.getContentResolver().openAssetFileDescriptor(uri,"r")){
            if(a==null)return null;
            byte[] b=new byte[48];
            int count; boolean pipe=false;
            try{count=Os.pread(a.getFileDescriptor(),b,0,b.length,a.getStartOffset());}
            catch(ErrnoException e){
                if(e.errno!=OsConstants.ESPIPE)throw new IOException(e);
                pipe=true;
                int n=0;
                try { while(n<b.length){int k=Os.read(a.getFileDescriptor(),b,n,b.length-n);if(k<=0)break;n+=k;} }
                catch(ErrnoException failure){throw new IOException(failure);}
                count=n;
            }
            if(count<12)return null;
            int kind=starts(b,0,"MAC ")?1:starts(b,0,"wvpk")?2:
                starts(b,0,"FORM")&&(starts(b,8,"AIFF")||starts(b,8,"AIFC"))?3:starts(b,4,"ftyp")?4:0;
            if(kind==0){
                if(observer!=null && count>=26 && starts(b,0,"fLaC") && (b[4]&127)==0){
                    int rate=(b[18]&255)<<12|(b[19]&255)<<4|(b[20]&240)>>4;
                    int bits=(((b[20]&1)<<4)|((b[21]&240)>>4))+1;
                    observer.found(uri,"FLAC",rate,bits);
                }
                return null;
            }
            long size=a.getLength();
            if(!pipe && size>0)return openIfHandled(a,kind,size);
            // A cloud provider may expose only a pipe. Copy the compressed input
            // into an unlinked temporary file so seeks never expand it into a PCM file.
            File tmp=File.createTempFile("local-audio-",".tmp",c.getCacheDir());
            try{
                try(OutputStream out=new FileOutputStream(tmp);InputStream in=a.createInputStream()){
                    if(pipe)out.write(b,0,count);
                    byte[] bytes=new byte[65536];int n;
                    while((n=in.read(bytes))!=-1){if(Thread.currentThread().isInterrupted())throw new InterruptedIOException();out.write(bytes,0,n);}
                }
                try(AssetFileDescriptor file=new AssetFileDescriptor(ParcelFileDescriptor.open(tmp,ParcelFileDescriptor.MODE_READ_ONLY),0,tmp.length())){
                    return openIfHandled(file,kind,tmp.length());
                }
            }finally{tmp.delete();}
        }
    }
    private static boolean starts(byte[] bytes,int offset,String value){
        for(int i=0;i<value.length();i++)if(bytes[offset+i]!=value.charAt(i))return false;
        return true;
    }
    private static byte[] header(long[] info){
        int rate=(int)info[0],channels=(int)info[1],bytes=(int)info[2],stride=channels*bytes;
        long size=info[4]*stride;
        boolean rf64=size>0xffffffffL-36;
        ByteBuffer b=ByteBuffer.allocate(rf64?80:44).order(ByteOrder.LITTLE_ENDIAN);
        fourcc(b,rf64?"RF64":"RIFF");b.putInt(rf64?-1:(int)(36+size));fourcc(b,"WAVE");
        if(rf64){fourcc(b,"ds64");b.putInt(28).putLong(size+72).putLong(size).putLong(info[4]).putInt(0);}
        fourcc(b,"fmt ");b.putInt(16).putShort((short)(info[3]!=0?3:1)).putShort((short)channels)
            .putInt(rate).putInt(rate*stride).putShort((short)stride).putShort((short)(bytes*8));
        fourcc(b,"data");b.putInt(rf64?-1:(int)size);
        return b.array();
    }
    private static void fourcc(ByteBuffer b,String s){b.put(s.getBytes(StandardCharsets.US_ASCII));}

    @Override public long open(DataSpec spec) throws IOException{
        reader=inspect(context,spec.uri,observer);
        if(reader==null)return regular.open(spec);
        try{
            uri=spec.uri;
            long[] f=reader.format;
            if(observer!=null)observer.found(spec.uri,reader.name,(int)f[0],(int)f[5]);
            header=header(f);int stride=(int)(f[1]*f[2]);
            long length=header.length+f[4]*stride;
            position=spec.position;
            if(position>length)throw new EOFException("读取位置超出音频长度");
            remaining=spec.length==C.LENGTH_UNSET?length-position:Math.min(spec.length,length-position);
            long pcmOffset=Math.max(0,position-header.length);
            skipBytes=(int)(pcmOffset%stride);
            if(remaining>0)seekReader(reader.handle,pcmOffset/stride);
            block=new byte[32768];offset=end=0;return remaining;
        }catch(IOException|RuntimeException e){close();throw e;}
    }
    @Override public int read(byte[] bytes,int at,int len) throws IOException{
        if(reader==null)return regular.read(bytes,at,len);
        if(len==0)return 0;
        if(remaining<=0)return C.RESULT_END_OF_INPUT;
        int n=(int)Math.min(len,remaining);
        if(position<header.length){
            n=(int)Math.min(n,header.length-position);
            System.arraycopy(header,(int)position,bytes,at,n);
        }else{
            if(offset==end){
                end=readPcm(reader.handle,block);offset=skipBytes;skipBytes=0;
                if(end<=offset)throw new EOFException("音频数据提前结束");
            }
            n=Math.min(n,end-offset);System.arraycopy(block,offset,bytes,at,n);offset+=n;
        }
        position+=n;remaining-=n;return n;
    }
    @Override public Uri getUri(){return reader==null?regular.getUri():uri;}
    @Override public void addTransferListener(TransferListener listener){regular.addTransferListener(listener);}
    @Override public Map<String,List<String>> getResponseHeaders(){return reader==null?regular.getResponseHeaders():java.util.Collections.emptyMap();}
    @Override public void close() throws IOException {
        if(reader!=null){reader.close();reader=null;uri=null;block=null;header=null;}
        else regular.close();
    }
}
