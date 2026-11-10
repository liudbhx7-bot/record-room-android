package com.recordroom.vinyl;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONObject;

/** HTTPS only; account cookies never leave the two QQ Music API hosts or follow a redirect. */
class QQHttp {
    static final String USER_AGENT="QQMusic 14090508(android 12)";
    JSONObject request(String url,JSONObject body,String cookies)throws ProviderException {
        validateApiUrl(url);HttpsURLConnection connection=null;
        try {
            connection=(HttpsURLConnection)new URL(url).openConnection();
            connection.setInstanceFollowRedirects(false);connection.setUseCaches(false);
            connection.setConnectTimeout(6000);connection.setReadTimeout(8000);
            connection.setRequestProperty("User-Agent",USER_AGENT);connection.setRequestProperty("Referer","https://y.qq.com/");
            connection.setRequestProperty("Accept","application/json");
            if(cookies!=null&&!cookies.isEmpty())connection.setRequestProperty("Cookie",cookies);
            if(body!=null){connection.setRequestMethod("POST");connection.setDoOutput(true);connection.setRequestProperty("Content-Type","application/json;charset=UTF-8");byte[] bytes=body.toString().getBytes(StandardCharsets.UTF_8);connection.setFixedLengthStreamingMode(bytes.length);try(java.io.OutputStream out=connection.getOutputStream()){out.write(bytes);}}
            int status=connection.getResponseCode();
            requireSuccess(status);
            try(InputStream in=connection.getInputStream()){
                ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int count;
                while((count=in.read(buffer))!=-1){if(Thread.currentThread().isInterrupted())throw new java.io.InterruptedIOException();if(bytes.size()+count>8*1024*1024)throw invalid();bytes.write(buffer,0,count);}
                return parseBody(new String(bytes.toByteArray(),StandardCharsets.UTF_8));
            }
        }catch(ProviderException failure){throw failure;}
        catch(Exception failure){throw networkFailure(failure);}
        finally{if(connection!=null)connection.disconnect();}
    }
    static void requireSuccess(int status)throws ProviderException{
        if(status==200)return;String code="（HTTP "+status+"）";
        if(status==401)throw new ProviderException(ProviderException.Kind.LOGIN_EXPIRED,"QQ 音乐授权已失效，请重新登录"+code);
        if(status==403||status>=300&&status<400)throw new ProviderException(ProviderException.Kind.LOGIN_CHALLENGE,"官方服务要求检查访问授权，请打开官方登录完成验证后重试"+code);
        if(status==429)throw new ProviderException(ProviderException.Kind.RATE_LIMITED,"QQ 音乐暂时限制请求，请稍后重试"+code);
        if(status>=500)throw new ProviderException(ProviderException.Kind.NETWORK,"QQ 音乐服务暂时没有响应，请稍后重试"+code);
        throw new ProviderException(ProviderException.Kind.UNAVAILABLE,"QQ 音乐未允许本次请求，请重试或在官方页面检查授权"+code);
    }
    static JSONObject parseBody(String raw)throws ProviderException{
        String text=raw==null?"":raw.trim();if(text.isEmpty())throw new ProviderException(ProviderException.Kind.INVALID_RESPONSE,"QQ 音乐返回了空响应，请重试；未将其显示为曲库为空");
        if(text.startsWith("callback(")){int end=text.lastIndexOf(')');if(end<9)throw invalid();text=text.substring(9,end);}
        if(text.startsWith("<"))throw new ProviderException(ProviderException.Kind.LOGIN_CHALLENGE,"QQ 音乐接口返回了网页，请在官方登录页检查是否需要验证后重试");
        try{return new JSONObject(text);}catch(org.json.JSONException failure){throw new ProviderException(ProviderException.Kind.INVALID_RESPONSE,"QQ 音乐返回的内容无法解析，请稍后重试；未将其显示为没有歌曲");}
    }
    static ProviderException networkFailure(Exception failure){
        String message=failure instanceof java.net.SocketTimeoutException?"连接 QQ 音乐超时，请检查网络后重试":failure instanceof java.net.UnknownHostException?"无法找到 QQ 音乐服务器，请检查网络或 DNS 后重试":failure instanceof javax.net.ssl.SSLException?"QQ 音乐安全连接未通过，请检查系统时间或网络后重试":"暂时无法连接 QQ 音乐，请检查网络后重试";
        return new ProviderException(ProviderException.Kind.NETWORK,message);
    }
    static void validateApiUrl(String value)throws ProviderException {
        try{URI uri=new URI(value);String host=uri.getHost();if(!"https".equals(uri.getScheme())||!("u.y.qq.com".equals(host)||"c.y.qq.com".equals(host))||uri.getUserInfo()!=null||uri.getPort()!=-1||uri.getFragment()!=null)throw invalid();}
        catch(ProviderException failure){throw failure;}catch(Exception failure){throw invalid();}
    }
    static ProviderException invalid(){return new ProviderException(ProviderException.Kind.INVALID_RESPONSE,"QQ 音乐返回数据不完整，请稍后重试");}
}
