/* SPDX-License-Identifier: GPL-3.0-only
 * Protocol adaptation of XxHuberrr/Mineradio-paused, d43de565acabfdc1a9c9820a27e81a98ccbebcef.
 * See docs/qishui-integration.md and app/src/main/assets/licenses/mineradio-GPL-3.0.txt.
 */
package com.recordroom.vinyl;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONObject;

/** Session cookies are sent only to the fixed Qishui API origin; no redirect following. */
class QishuiHttp {
    static final Set<String> PATHS=new java.util.HashSet<>(java.util.Arrays.asList("/luna/pc/me","/luna/pc/user/playlist","/luna/pc/me/collection/mixed","/luna/pc/me/recently-played-media","/luna/pc/playlist/detail","/luna/pc/track_v2","/luna/pc/search/track"));
    static Map<String,String> pcParams(){
        Map<String,String> out=new LinkedHashMap<>();
        String now=Long.toString(System.currentTimeMillis());
        String[] pairs={"aid","386088","app_name","luna_pc","region","cn","geo_region","cn","os_region","cn","device_id",now,"iid",now,"version_name","3.3.0","version_code","30030000","channel","official","build_mode","master","ac","wifi","tz_name","Asia/Shanghai","device_platform","windows","device_type","Windows","os_version","Windows 11","fp",now};
        for(int i=0;i<pairs.length;i+=2)out.put(pairs[i],pairs[i+1]);return out;
    }
    JSONObject request(String path,Map<String,String> query,JSONObject body,String cookie)throws ProviderException{
        if(!PATHS.contains(path))throw invalid();
        Map<String,String> params=pcParams();params.putAll(query);StringBuilder url=new StringBuilder("https://api.qishui.com").append(path).append('?');
        try{for(Map.Entry<String,String> entry:params.entrySet())url.append(URLEncoder.encode(entry.getKey(),"UTF-8")).append('=').append(URLEncoder.encode(entry.getValue(),"UTF-8")).append('&');}
        catch(Exception failure){throw invalid();}
        return fetch(url.toString(),body,cookie);
    }
    /** Media metadata uses an explicit host allowlist and receives no account cookies. */
    JSONObject playerInfo(String url)throws ProviderException{
        if(!safeMediaUrl(url))throw invalid();return fetch(url,null,"");
    }
    static boolean safeMediaUrl(String value){
        try{URI u=new URI(value);String h=u.getHost();if(!"https".equals(u.getScheme())||h==null||u.getUserInfo()!=null||u.getFragment()!=null||(u.getPort()!=-1&&u.getPort()!=443))return false;
            return h.endsWith(".qishui.com")||h.endsWith(".byteoversea.com")||h.endsWith(".volces.com")||h.endsWith(".byteimg.com")||h.endsWith(".douyinvod.com")||h.endsWith(".bytecdn.cn")||h.endsWith(".bytecdn.com")||h.endsWith(".bytescm.com")||h.endsWith(".snssdk.com")||h.endsWith(".bytedance.com");
        }catch(Exception failure){return false;}
    }
    private JSONObject fetch(String url,JSONObject body,String cookie)throws ProviderException{
        if(cookie!=null&&(cookie.indexOf('\r')>=0||cookie.indexOf('\n')>=0||cookie.length()>32768))throw invalid();
        HttpsURLConnection c=null;
        try{
            c=(HttpsURLConnection)new URL(url).openConnection();c.setInstanceFollowRedirects(false);c.setUseCaches(false);c.setConnectTimeout(10000);c.setReadTimeout(15000);
            c.setRequestProperty("Accept","application/json");c.setRequestProperty("User-Agent","LunaPC/3.3.0(359450208)");
            c.setRequestProperty("x-luna-background-type","foreground");c.setRequestProperty("x-luna-is-background-req","0");c.setRequestProperty("x-luna-is-local-user","1");
            if(cookie!=null&&!cookie.isEmpty())c.setRequestProperty("Cookie",cookie);
            if(body!=null){byte[] bytes=body.toString().getBytes(StandardCharsets.UTF_8);c.setRequestMethod("POST");c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json; charset=utf-8");c.setFixedLengthStreamingMode(bytes.length);try(java.io.OutputStream output=c.getOutputStream()){output.write(bytes);}}
            int status=c.getResponseCode();
            if(status==401)throw new ProviderException(ProviderException.Kind.LOGIN_EXPIRED,"汽水音乐登录已失效，请重新登录");
            if(status==403)throw new ProviderException(ProviderException.Kind.LOGIN_CHALLENGE,"汽水音乐暂未允许此次请求，请在官方验证页完成验证");
            if(status==429)throw new ProviderException(ProviderException.Kind.RATE_LIMITED,"汽水音乐请求过于频繁，请稍后重试");
            if(status!=200)throw new ProviderException(ProviderException.Kind.NETWORK,"汽水音乐连接失败（HTTP "+status+"）");
            try(InputStream input=c.getInputStream()){
                ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] bytes=new byte[8192];int count;
                while((count=input.read(bytes))!=-1){if(Thread.currentThread().isInterrupted())throw new java.io.InterruptedIOException();if(out.size()+count>8*1024*1024)throw invalid();out.write(bytes,0,count);}
                JSONObject result=new JSONObject(new String(out.toByteArray(),StandardCharsets.UTF_8));check(result);return result;
            }
        }catch(ProviderException failure){throw failure;}catch(org.json.JSONException failure){throw invalid();}
        catch(Exception failure){throw new ProviderException(ProviderException.Kind.NETWORK,"无法连接汽水音乐，请检查网络后重试");}finally{if(c!=null)c.disconnect();}
    }
    static void check(JSONObject body)throws ProviderException{
        if(body==null)throw invalid();int status=body.has("status_code")?body.optInt("status_code",-1):body.optInt("error_code",0);
        if(status==1000016)throw new ProviderException(ProviderException.Kind.LOGIN_EXPIRED,"汽水音乐登录已失效，请重新登录");
        if(status!=0)throw new ProviderException(status==2046?ProviderException.Kind.LOGIN_CHALLENGE:ProviderException.Kind.UNAVAILABLE,status==2046?"汽水音乐需要用户完成二次验证":"汽水音乐暂未提供此内容（"+status+"）");
    }
    static ProviderException invalid(){return new ProviderException(ProviderException.Kind.INVALID_RESPONSE,"汽水音乐返回的数据不完整或无法识别");}
}
