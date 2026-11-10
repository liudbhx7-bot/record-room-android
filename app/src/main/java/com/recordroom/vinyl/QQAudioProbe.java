package com.recordroom.vinyl;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.util.Map;
import javax.net.ssl.HttpsURLConnection;

/** Confirms the returned standard MP3 timeline; a successful HTTP response alone proves nothing. */
class QQAudioProbe {
    private static final int MAX_SAMPLE=262144;
    long durationMs(String url,Map<String,String> headers)throws ProviderException {
        validateAudioUrl(url);HttpsURLConnection connection=null;
        try{
            connection=(HttpsURLConnection)new URL(url).openConnection();connection.setInstanceFollowRedirects(false);connection.setUseCaches(false);
            connection.setConnectTimeout(5000);connection.setReadTimeout(6000);
            // Deliberately do not forward cookies, tickets or authorization to CDN hosts.
            connection.setRequestProperty("Referer","https://y.qq.com/");connection.setRequestProperty("User-Agent",QQHttp.USER_AGENT);
            connection.setRequestProperty("Range","bytes=0-"+(MAX_SAMPLE-1));
            int code=connection.getResponseCode();requireAudioResponse(code,connection.getContentType());
            long total=-1;
            if(code==200)total=connection.getContentLengthLong();
            else{String range=connection.getHeaderField("Content-Range");if(range!=null&&range.matches("bytes 0-[0-9]+/[0-9]+"))total=Long.parseLong(range.substring(range.indexOf('/')+1));}
            ByteArrayOutputStream out=new ByteArrayOutputStream();long deadline=System.nanoTime()+6_000_000_000L;
            try(InputStream in=connection.getInputStream()){byte[] buffer=new byte[8192];int count;while(out.size()<MAX_SAMPLE&&(count=in.read(buffer,0,Math.min(buffer.length,MAX_SAMPLE-out.size())))!=-1){if(Thread.currentThread().isInterrupted()||System.nanoTime()>deadline)throw unavailable("QQ 音乐音频验证已取消或超时，请重试");out.write(buffer,0,count);}}
            long duration=mp3Duration(out.toByteArray(),total);if(duration<=0)throw unavailable("暂时无法识别 QQ 音乐返回音频的完整时长，未开始播放，请稍后重试");return duration;
        }catch(ProviderException failure){throw failure;}catch(Exception failure){throw audioNetworkFailure(failure);}finally{if(connection!=null)connection.disconnect();}
    }
    static void requireAudioResponse(int code,String type)throws ProviderException{
        if(code!=200&&code!=206)throw unavailable("QQ 音乐音频服务器未返回可读取的内容（HTTP "+code+"），请稍后重试");
        if(type!=null&&(type.contains("text/")||type.contains("json")||type.contains("xml")))throw unavailable("QQ 音乐音频地址返回了非音频内容，未开始播放，请稍后重试");
    }
    static ProviderException audioNetworkFailure(Exception failure){ProviderException cause=QQHttp.networkFailure(failure);return new ProviderException(cause.kind,"验证播放音频失败："+cause.getMessage());}
    static void validateAudioUrl(String value)throws ProviderException{
        try{URI uri=new URI(value);String host=uri.getHost();if(!"https".equals(uri.getScheme())||host==null||!(host.equals("stream.qqmusic.qq.com")||host.endsWith(".stream.qqmusic.qq.com"))||uri.getPort()!=-1||uri.getUserInfo()!=null||uri.getFragment()!=null)throw unavailable("QQ 音乐返回了不受支持的音频地址，未开始播放，请稍后重试");}
        catch(ProviderException failure){throw failure;}catch(Exception failure){throw unavailable("QQ 音乐返回的音频地址格式无效，未开始播放，请稍后重试");}
    }
    static long mp3Duration(byte[] bytes,long total){
        int start=0;if(bytes.length>=10&&ascii(bytes,0,"ID3")){for(int i=6;i<10;i++)if((bytes[i]&128)!=0)return -1;start=10+((bytes[6]&127)<<21)+((bytes[7]&127)<<14)+((bytes[8]&127)<<7)+(bytes[9]&127);if((bytes[5]&16)!=0)start+=10;}
        if(start<0||start+4>=bytes.length)return -1;
        for(int at=start;at<Math.min(bytes.length-4,start+4096);at++){
            int[] frame=frame(bytes,at);if(frame==null)continue;
            int side=frame[0]==3?(frame[3]==3?17:32):(frame[3]==3?9:17);
            int xing=at+4+frame[4]+side;
            if(ascii(bytes,xing,"Xing")||ascii(bytes,xing,"Info")){
                if(xing+12>bytes.length)return -1;long flags=u32(bytes,xing+4);if((flags&1)==0)return -1;
                return u32(bytes,xing+8)*(frame[0]==3?1152L:576L)*1000/frame[2];
            }
            int vbri=at+36;if(ascii(bytes,vbri,"VBRI")){if(vbri+18>bytes.length)return -1;return u32(bytes,vbri+14)*(frame[0]==3?1152L:576L)*1000/frame[2];}
            // CBR only: verify consecutive frame headers rather than guessing duration from filename.
            int cursor=at,checked=0;boolean consistent=true;
            while(cursor+4<bytes.length&&checked<100){int[] next=frame(bytes,cursor);if(next==null||next[0]!=frame[0]||next[1]!=frame[1]||next[2]!=frame[2]){consistent=false;break;}int size=(next[0]==3?144000:72000)*next[1]/next[2]+next[5];if(size<24)return -1;checked++;cursor+=size;}
            if(!consistent)return -1;
            if(checked>=4&&total>at&&total<2_000_000_000L)return (total-at)*8/frame[1];
        }
        return -1;
    }
    private static int[] frame(byte[] b,int at){
        if(at<0||at+4>b.length)return null;int value=(int)u32(b,at);if((value&0xffe00000)!=0xffe00000)return null;
        int version=(value>>>19)&3,layer=(value>>>17)&3,rate=(value>>>10)&3,index=(value>>>12)&15;
        if(version==1||layer!=1||rate==3||index==0||index==15)return null;
        int[] rates={44100,48000,32000},v1={0,32,40,48,56,64,80,96,112,128,160,192,224,256,320},v2={0,8,16,24,32,40,48,56,64,80,96,112,128,144,160};
        return new int[]{version,(version==3?v1:v2)[index],rates[rate]/(version==3?1:version==2?2:4),(value>>>6)&3,((value>>>16)&1)==0?2:0,(value>>>9)&1};
    }
    private static boolean ascii(byte[] bytes,int at,String value){if(at<0||at+value.length()>bytes.length)return false;for(int i=0;i<value.length();i++)if(bytes[at+i]!=(byte)value.charAt(i))return false;return true;}
    private static long u32(byte[] b,int at){return ((b[at]&255L)<<24)|((b[at+1]&255L)<<16)|((b[at+2]&255L)<<8)|(b[at+3]&255L);}
    private static ProviderException unavailable(String message){return new ProviderException(ProviderException.Kind.UNAVAILABLE,message);}
}
