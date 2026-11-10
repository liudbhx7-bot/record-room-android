package com.recordroom.vinyl;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.List;
import javax.imageio.ImageIO;

/** Desktop verification using the exact Android adapter. Session exists only in this process. */
public final class ProviderProbe {
    static final class MemorySession implements ProviderSession {
        private String cookies="";private long epoch;
        public synchronized String readCookies(){return cookies;}
        public synchronized void writeCookies(String value){cookies=value;}
        public synchronized void clear(){cookies="";epoch++;}
        public synchronized long generation(){return epoch;}
    }
    private static void message(String value){System.out.println(value);System.out.flush();}
    public static void main(String[] args)throws Exception{
        NeteaseProvider provider=new NeteaseProvider(new MemorySession());
        try{
            if(args.length>0&&args[0].equals("qr")){
                if(args.length<2)throw new IllegalArgumentException("QR output path required");
                MusicProvider.QrLogin qr=provider.beginQrLogin();BitMatrix pixels=new MultiFormatWriter().encode(qr.url,BarcodeFormat.QR_CODE,420,420);
                BufferedImage image=new BufferedImage(420,420,BufferedImage.TYPE_INT_RGB);
                for(int y=0;y<420;y++)for(int x=0;x<420;x++)image.setRGB(x,y,pixels.get(x,y)?0xff111111:0xffffffff);
                File output=new File(args[1]);File parent=output.getAbsoluteFile().getParentFile();if(parent!=null)parent.mkdirs();ImageIO.write(image,"png",output);
                message("QR_READY file="+output.getAbsolutePath()+" credentials=in_memory_only");MusicProvider.LoginStatus previous=null;
                for(int i=0;i<90;i++){
                    MusicProvider.LoginPoll poll=provider.pollQrLogin(qr.key);
                    if(previous!=poll.status){message("QR_STATUS "+poll.status);previous=poll.status;}
                    if(poll.status==MusicProvider.LoginStatus.EXPIRED)return;
                    if(poll.status==MusicProvider.LoginStatus.SUCCESS){
                        MusicProvider.Account account=provider.account();message("ACCOUNT logged_in="+account.loggedIn+" vip_type="+account.vipType);
                        List<MusicProvider.Playlist> lists=provider.playlists(account.id);int owned=0;for(MusicProvider.Playlist list:lists)if(list.owned)owned++;
                        message("PLAYLISTS total="+lists.size()+" created="+owned+" subscribed="+(lists.size()-owned));
                        List<Track> liked=provider.likedTracks(account.id);message("LIKED complete_tracks="+liked.size());
                        for(MusicProvider.Playlist list:lists)if(list.trackCount>0){List<Track> songs=provider.playlistTracks(list.id);message("USER_PLAYLIST complete_tracks="+songs.size()+" declared="+list.trackCount);probeResources(provider,songs);break;}
                        return;
                    }
                    Thread.sleep(2000);
                }
                message("QR_WAIT_TIMEOUT");return;
            }
            if(args.length>1&&args[0].equals("playlist")){List<Track> tracks=provider.playlistTracks(args[1]);message("PUBLIC_PLAYLIST complete_tracks="+tracks.size());probeResources(provider,tracks);return;}
            MusicProvider.Page<MusicProvider.Album> found=provider.searchAlbums(args.length>1?args[1]:"贝多芬",0,3);
            message("ALBUM_SEARCH count="+found.items.size()+" total="+found.total+" has_more="+found.hasMore);
            if(!found.items.isEmpty()){MusicProvider.Album album=provider.album(found.items.get(0).id);message("ALBUM complete_tracks="+album.tracks.size());probeResources(provider,album.tracks);}
        }catch(ProviderException failure){message("PROVIDER_FAILURE kind="+failure.kind+" message="+failure.getMessage());System.exit(2);}
        finally{provider.logout();}
    }
    private static void probeResources(NeteaseProvider provider,List<Track> songs){
        for(int i=0;i<Math.min(songs.size(),8);i++)try{PlaybackResource audio=provider.resolve(songs.get(i));message("RESOURCE full_metadata=true duration_ms="+audio.durationMs+" expected_ms="+songs.get(i).durationMs+" auth_headers="+audio.headers.size());
            try{verifyRanges(audio);}catch(Exception failure){message("RESOURCE_BYTES_FAILURE type="+failure.getClass().getSimpleName());}return;}
        catch(ProviderException failure){message("RESOURCE_DENIED kind="+failure.kind);}
    }
    private static void verifyRanges(PlaybackResource audio)throws Exception{
        File folder=new File("work/private/netease-probe");folder.mkdirs();
        long total=range(audio.url,0,2*1024*1024-1,new File(folder,"audio-head.bin"),false);
        if(total>2*1024*1024)range(audio.url,Math.max(0,total-256*1024),total-1,new File(folder,"audio-tail.bin"),true);
        message("RESOURCE_RANGES total_bytes="+total+" expected_duration_ms="+audio.durationMs+" private_samples_only=true");
    }
    private static long range(String url,long start,long end,File output,boolean requirePartial)throws Exception{
        javax.net.ssl.HttpsURLConnection connection=(javax.net.ssl.HttpsURLConnection)new java.net.URL(url).openConnection();
        connection.setInstanceFollowRedirects(false);connection.setConnectTimeout(12000);connection.setReadTimeout(18000);
        connection.setRequestProperty("Range","bytes="+start+"-"+end);
        try{
            int status=connection.getResponseCode();if(status!=206&&(status!=200||requirePartial))throw new java.io.IOException("Audio range rejected");
            long total=connection.getContentLengthLong();String contentRange=connection.getHeaderField("Content-Range");
            if(status==206){if(contentRange==null||!contentRange.startsWith("bytes "+start+"-"))throw new java.io.IOException("Audio range mismatch");int slash=contentRange.lastIndexOf('/');total=Long.parseLong(contentRange.substring(slash+1));}
            long remaining=end-start+1,written=0;byte[] bytes=new byte[8192];
            try(java.io.InputStream input=connection.getInputStream();java.io.OutputStream file=new java.io.FileOutputStream(output)){
                while(remaining>0){int count=input.read(bytes,0,(int)Math.min(bytes.length,remaining));if(count<0)break;file.write(bytes,0,count);written+=count;remaining-=count;}
            }
            if(written==0)throw new java.io.IOException("Empty audio");message("AUDIO_RANGE start="+start+" bytes="+written+" http="+status);return total;
        }finally{connection.disconnect();}
    }
}
