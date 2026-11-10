package com.recordroom.vinyl;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONObject;

/** Apple REST transport. Never follows redirects or forwards credentials to artwork/audio hosts. */
class AppleMusicHttp {
    static final String ORIGIN="https://api.music.apple.com";
    static final int MAX_BYTES=8*1024*1024;
    static final class Response {
        final int status;final JSONObject body;
        Response(int status,JSONObject body){this.status=status;this.body=body;}
    }
    static String safePath(String value)throws AppleMusicApi.Failure{
        try{
            if(value==null||value.length()>8192||value.indexOf('\\')>=0)throw invalid();
            URI uri=new URI(value);
            if(uri.isAbsolute()||uri.getRawAuthority()!=null||uri.getFragment()!=null||!value.startsWith("/v1/")||!uri.normalize().equals(uri))throw invalid();
            String path=uri.getPath();
            if(path==null||path.contains("..")||path.contains("//"))throw invalid();
            for(int i=0;i<value.length();i++)if(value.charAt(i)<32||value.charAt(i)==127)throw invalid();
            return value;
        }catch(AppleMusicApi.Failure failure){throw failure;}catch(Exception failure){throw invalid();}
    }
    Response get(String path,Map<String,String> headers)throws AppleMusicApi.Failure{
        safePath(path);HttpsURLConnection connection=null;
        try{
            connection=(HttpsURLConnection)new URL(ORIGIN+path).openConnection();
            connection.setInstanceFollowRedirects(false);connection.setUseCaches(false);connection.setRequestMethod("GET");
            connection.setConnectTimeout(12000);connection.setReadTimeout(18000);connection.setRequestProperty("Accept","application/json");
            for(Map.Entry<String,String> header:headers.entrySet()){
                if(!"Authorization".equals(header.getKey())&&!"Music-User-Token".equals(header.getKey()))throw invalid();
                connection.setRequestProperty(header.getKey(),header.getValue());
            }
            int status=connection.getResponseCode();
            // Error bodies may contain diagnostics or identifying data; never surface or persist them.
            if(status!=200)return new Response(status,null);
            try(InputStream input=connection.getInputStream()){
                ByteArrayOutputStream output=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int count;
                while((count=input.read(buffer))!=-1){
                    if(Thread.currentThread().isInterrupted())throw new java.io.InterruptedIOException();
                    if(output.size()+count>MAX_BYTES)throw invalid();output.write(buffer,0,count);
                }
                return new Response(status,new JSONObject(new String(output.toByteArray(),StandardCharsets.UTF_8)));
            }
        }catch(AppleMusicApi.Failure failure){throw failure;}
        catch(org.json.JSONException failure){throw invalid();}
        catch(Exception failure){throw new AppleMusicApi.Failure(AppleMusicApi.Error.NETWORK,"无法连接 Apple Music，请检查网络后重试");}
        finally{if(connection!=null)connection.disconnect();}
    }
    static AppleMusicApi.Failure invalid(){return new AppleMusicApi.Failure(AppleMusicApi.Error.INVALID_RESPONSE,"Apple Music 返回了无效数据");}
}
