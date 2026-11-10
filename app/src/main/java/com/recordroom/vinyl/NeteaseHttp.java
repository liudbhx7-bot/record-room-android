package com.recordroom.vinyl;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpCookie;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import java.util.zip.GZIPInputStream;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONObject;

/** Fixed-origin TLS API transport. Credentials never enter audio/artwork requests or redirect destinations. */
class NeteaseHttp {
    static final class Response {
        final JSONObject body; final String cookies;
        Response(JSONObject body,String cookies){this.body=body;this.cookies=cookies;}
    }
    private final String deviceId=UUID.randomUUID().toString().replace("-","");
    private static final int MAX_RESPONSE=16*1024*1024;

    /** Only QR endpoints opt into the current PC EAPI host/header contract. */
    static final class QrRequest {
        final String url,cookie,userAgent;
        final JSONObject data,header;
        QrRequest(String url,String cookie,String userAgent,JSONObject data,JSONObject header){this.url=url;this.cookie=cookie;this.userAgent=userAgent;this.data=data;this.header=header;}
    }
    QrRequest prepareQr(String path,JSONObject parameters,String cookies)throws ProviderException{
        if(!"/api/login/qrcode/unikey".equals(path)&&!"/api/login/qrcode/client/login".equals(path))throw new ProviderException(ProviderException.Kind.INVALID_RESPONSE,"扫码接口路径无效");
        try{
            Map<String,String> values=parseCookies(cookies);long now=System.currentTimeMillis();JSONObject header=new JSONObject();
            header.put("os","pc");header.put("osver","Microsoft-Windows-10-Professional-build-19045-64bit");header.put("appver","3.1.17.204416");
            header.put("deviceId",deviceId);header.put("versioncode","140");header.put("mobilename","");header.put("buildver",Long.toString(now/1000));header.put("resolution","1920x1080");
            header.put("channel","netease");header.put("__csrf",values.getOrDefault("__csrf",""));header.put("requestId",now+"_"+String.format(Locale.ROOT,"%04d",ThreadLocalRandom.current().nextInt(1000)));
            for(String key:new String[]{"MUSIC_U","MUSIC_A","NMTID"})if(values.containsKey(key)&&!values.get(key).isEmpty())header.put(key,values.get(key));
            StringBuilder cookie=new StringBuilder();java.util.Iterator<String> keys=header.keys();while(keys.hasNext()){
                String key=keys.next();if(cookie.length()>0)cookie.append("; ");cookie.append(URLEncoder.encode(key,"UTF-8").replace("+","%20")).append('=').append(URLEncoder.encode(header.getString(key),"UTF-8").replace("+","%20"));
            }
            JSONObject data=new JSONObject(parameters.toString());data.put("e_r",false);data.put("header",header);
            String ua="Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Safari/537.36 Chrome/91.0.4472.164 NeteaseMusicDesktop/3.1.17.204416";
            return new QrRequest("https://interfacepc.music.163.com/eapi/"+path.substring(5),cookie.toString(),ua,data,header);
        }catch(Exception failure){throw new ProviderException(ProviderException.Kind.INVALID_RESPONSE,"无法建立扫码请求");}
    }
    Response postQr(String path,JSONObject parameters,String cookies)throws ProviderException{return perform(path,parameters,false,cookies,prepareQr(path,parameters,cookies));}

    Response post(String path,JSONObject parameters,boolean weapi,String cookies)throws ProviderException{
        return perform(path,parameters,weapi,cookies,null);
    }
    private Response perform(String path,JSONObject parameters,boolean weapi,String cookies,QrRequest qr)throws ProviderException{
        if(path==null||!path.matches("/api/[A-Za-z0-9/_-]+"))throw new ProviderException(ProviderException.Kind.INVALID_RESPONSE,"接口路径无效");
        HttpsURLConnection connection=null;
        try{
            JSONObject data=new JSONObject((qr==null?parameters:qr.data).toString());data.put("e_r",false);
            Map<String,String> cookieValues=parseCookies(cookies);
            String csrf=cookieValues.getOrDefault("__csrf","");
            String host=weapi?"music.163.com":"interface.music.163.com";
            String url=qr==null?"https://"+host+(weapi?"/weapi/":"/eapi/")+path.substring(5):qr.url;
            if(weapi)data.put("csrf_token",csrf);
            else if(qr==null){
                JSONObject header=new JSONObject();header.put("os","pc");header.put("osver","Microsoft-Windows-10-Professional-build-19045-64bit");
                header.put("appver","3.1.17.204416");header.put("versioncode","140");header.put("deviceId",deviceId);
                header.put("channel","netease");header.put("__csrf",csrf);header.put("requestId",System.currentTimeMillis()+"_"+UUID.randomUUID().toString().substring(0,4));
                if(cookieValues.containsKey("MUSIC_U"))header.put("MUSIC_U",cookieValues.get("MUSIC_U"));
                if(cookieValues.containsKey("MUSIC_A"))header.put("MUSIC_A",cookieValues.get("MUSIC_A"));
                data.put("header",header);
            }
            Map<String,String> form=weapi?NeteaseCrypto.weapi(data.toString()):NeteaseCrypto.eapi(path,data.toString());
            StringBuilder encoded=new StringBuilder();for(Map.Entry<String,String> item:form.entrySet()){
                if(encoded.length()>0)encoded.append('&');encoded.append(URLEncoder.encode(item.getKey(),"UTF-8")).append('=').append(URLEncoder.encode(item.getValue(),"UTF-8"));
            }
            byte[] bytes=encoded.toString().getBytes(StandardCharsets.UTF_8);
            connection=(HttpsURLConnection)new URL(url).openConnection();connection.setConnectTimeout(12000);connection.setReadTimeout(18000);
            connection.setInstanceFollowRedirects(false);connection.setUseCaches(false);connection.setRequestMethod("POST");connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type","application/x-www-form-urlencoded; charset=UTF-8");
            connection.setRequestProperty("Accept","application/json");connection.setRequestProperty("Accept-Encoding","gzip");
            connection.setRequestProperty("Referer","https://music.163.com/");
            connection.setRequestProperty("User-Agent",qr==null?"Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/124.0.0.0 Safari/537.36":qr.userAgent);
            if(qr!=null)connection.setRequestProperty("Cookie",qr.cookie);
            else if(cookies!=null&&!cookies.isEmpty())connection.setRequestProperty("Cookie",cookieHeader(cookieValues));
            connection.setFixedLengthStreamingMode(bytes.length);try(java.io.OutputStream output=connection.getOutputStream()){output.write(bytes);}
            int status=connection.getResponseCode();
            if(status==429)throw new ProviderException(ProviderException.Kind.RATE_LIMITED,"网易云请求过于频繁，请稍后重试");
            if(status==401)throw new ProviderException(ProviderException.Kind.LOGIN_EXPIRED,"网易云登录已失效，请重新扫码");
            if(status<200||status>=300)throw new ProviderException(ProviderException.Kind.NETWORK,"网易云连接失败（HTTP "+status+"）");
            InputStream raw=connection.getInputStream();
            try(InputStream input="gzip".equalsIgnoreCase(connection.getContentEncoding())?new GZIPInputStream(raw):raw){
                ByteArrayOutputStream output=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int count;
                while((count=input.read(buffer))!=-1){if(Thread.currentThread().isInterrupted())throw new java.io.InterruptedIOException();if(output.size()+count>MAX_RESPONSE)throw new ProviderException(ProviderException.Kind.INVALID_RESPONSE,"网易云返回内容过大");output.write(buffer,0,count);}
                JSONObject body=new JSONObject(new String(output.toByteArray(),StandardCharsets.UTF_8));
                return new Response(body,mergeCookies(cookies,connection.getHeaderFields(),qr!=null));
            }
        }catch(ProviderException failure){throw failure;}
        catch(org.json.JSONException failure){throw new ProviderException(ProviderException.Kind.INVALID_RESPONSE,"网易云返回了无法解析的数据");}
        catch(Exception failure){throw new ProviderException(ProviderException.Kind.NETWORK,"无法连接网易云音乐，请检查网络后重试");}
        finally{if(connection!=null)connection.disconnect();}
    }

    static Map<String,String> parseCookies(String header){
        Map<String,String> values=new LinkedHashMap<>();if(header==null)return values;
        for(String part:header.split(";")){int split=part.indexOf('=');if(split<=0)continue;String key=part.substring(0,split).trim(),value=part.substring(split+1).trim();
            if(key.matches("[A-Za-z0-9_]+")&&value.indexOf('\r')<0&&value.indexOf('\n')<0)values.put(key,value);}
        return values;
    }
    static String cookieHeader(Map<String,String> values){StringBuilder out=new StringBuilder();for(Map.Entry<String,String> value:values.entrySet()){if(out.length()>0)out.append("; ");out.append(value.getKey()).append('=').append(value.getValue());}return out.toString();}
    static String mergeCookies(String existing,Map<String,List<String>> headers){
        return mergeCookies(existing,headers,false);
    }
    private static String mergeCookies(String existing,Map<String,List<String>> headers,boolean qr){
        Map<String,String> values=parseCookies(existing);
        for(Map.Entry<String,List<String>> header:headers.entrySet())if("Set-Cookie".equalsIgnoreCase(header.getKey())&&header.getValue()!=null){
            for(String folded:header.getValue())for(String value:folded.split(",(?=\\s*[A-Za-z0-9_]+\\s*=)"))try{for(HttpCookie cookie:HttpCookie.parse(value)){
                String domain=cookie.getDomain();if(domain!=null&&!domain.equalsIgnoreCase("music.163.com")&&!domain.equalsIgnoreCase(".music.163.com")&&!domain.equalsIgnoreCase("interface.music.163.com")&&!(qr&&domain.equalsIgnoreCase("interfacepc.music.163.com")))continue;
                if(cookie.hasExpired()||cookie.getValue().isEmpty())values.remove(cookie.getName());else if(cookie.getName().matches("[A-Za-z0-9_]+")&&cookie.getValue().indexOf('\r')<0&&cookie.getValue().indexOf('\n')<0)values.put(cookie.getName(),cookie.getValue());
            }}catch(IllegalArgumentException ignored){}
        }
        return cookieHeader(values);
    }
}
