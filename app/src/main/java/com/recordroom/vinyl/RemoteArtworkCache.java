package com.recordroom.vinyl;

import android.content.Context;
import android.util.AtomicFile;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;

/** Public artwork only. No provider cookies, authorization headers or audio are cached here. */
final class RemoteArtworkCache {
    private static final long MAX_IMAGE=8L*1024*1024,MAX_DISK=64L*1024*1024;
    private RemoteArtworkCache(){}
    static URL validatedUrl(String input) throws Exception {
        URI uri=new URI(input);String host=uri.getHost();
        if(host==null||uri.getUserInfo()!=null||(uri.getPort()!=-1&&uri.getPort()!=443))throw new IOException("Unsupported artwork origin");
        host=host.toLowerCase(Locale.ROOT);
        if(!(host.endsWith(".music.126.net")||host.equals("music.163.com")||host.endsWith(".music.163.com")||(host.equals("y.qq.com")&&uri.getPath()!=null&&uri.getPath().matches("/music/photo_new/[A-Za-z0-9_/-]+\\.(jpg|jpeg|png|webp)"))||host.equals("y.gtimg.cn")||host.endsWith(".gtimg.cn")||host.endsWith(".qpic.cn")||host.endsWith(".douyinpic.com")||host.endsWith(".byteimg.com")||host.endsWith(".ibyteimg.com")||host.endsWith(".mzstatic.com")))throw new IOException("Unsupported artwork origin");
        if(!"https".equalsIgnoreCase(uri.getScheme())&&!"http".equalsIgnoreCase(uri.getScheme()))throw new IOException("Unsupported artwork scheme");
        return new URI("https",null,host,-1,uri.getPath(),uri.getQuery(),null).toURL();
    }
    static synchronized File fetch(Context context,String input) throws Exception {
        URL url=validatedUrl(input);String key=hex(MessageDigest.getInstance("SHA-256").digest(url.toString().getBytes(StandardCharsets.UTF_8)));
        File directory=new File(context.getCacheDir(),"public-music-artwork");if(!directory.isDirectory()&&!directory.mkdirs())throw new IOException("Cache unavailable");
        File file=new File(directory,key+".image");if(file.isFile()&&file.length()>0&&file.length()<=MAX_IMAGE){file.setLastModified(System.currentTimeMillis());return file;}
        HttpURLConnection connection=null;AtomicFile atomic=new AtomicFile(file);FileOutputStream output=null;
        try {
            for(int hop=0;hop<4;hop++){
                connection=(HttpURLConnection)url.openConnection();connection.setInstanceFollowRedirects(false);connection.setConnectTimeout(8000);connection.setReadTimeout(12000);
                connection.setRequestProperty("Accept","image/*");connection.setRequestProperty("User-Agent","RecordRoom/0.6.0");
                int status=connection.getResponseCode();
                if(status>=300&&status<400){String location=connection.getHeaderField("Location");if(location==null)throw new IOException("Invalid artwork redirect");URL next=new URL(url,location);url=validatedUrl(next.toString());connection.disconnect();connection=null;continue;}
                if(status!=200)throw new IOException("Artwork unavailable");
                long length=connection.getContentLengthLong();if(length>MAX_IMAGE)throw new IOException("Artwork too large");
                output=atomic.startWrite();long total=0;byte[] buffer=new byte[16384];
                try(InputStream in=connection.getInputStream()){int read;while((read=in.read(buffer))!=-1){if(Thread.currentThread().isInterrupted())throw new InterruptedIOException();total+=read;if(total>MAX_IMAGE)throw new IOException("Artwork too large");output.write(buffer,0,read);}}
                if(total==0)throw new IOException("Empty artwork");atomic.finishWrite(output);output=null;trim(directory,file);return file;
            }
            throw new IOException("Too many artwork redirects");
        } finally {if(output!=null)atomic.failWrite(output);if(connection!=null)connection.disconnect();}
    }
    private static void trim(File directory,File keep){File[] files=directory.listFiles((dir,name)->name.endsWith(".image"));if(files==null)return;long size=0;for(File file:files)size+=file.length();Arrays.sort(files,Comparator.comparingLong(File::lastModified));for(File file:files){if(size<=MAX_DISK)break;if(!file.equals(keep)){long bytes=file.length();if(file.delete())size-=bytes;}}}
    private static String hex(byte[] bytes){StringBuilder s=new StringBuilder();for(byte b:bytes)s.append(String.format(Locale.ROOT,"%02x",b&255));return s.toString();}
}
