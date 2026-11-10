package com.recordroom.vinyl;

import android.content.Context;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import org.json.JSONObject;

/** Non-secret integration settings. Tokens and private keys must never be packaged in this asset. */
public final class AppleMusicConfig {
    public static final String ASSET="providers/apple-music.json";
    public final String mediaId,storefront,tokenServiceUrl;
    private AppleMusicConfig(String mediaId,String storefront,String tokenServiceUrl){
        this.mediaId=mediaId;this.storefront=storefront;this.tokenServiceUrl=tokenServiceUrl;
    }
    public static AppleMusicConfig unconfigured(){return new AppleMusicConfig("","","");}
    public boolean hasTokenService(){return !mediaId.isEmpty()&&!tokenServiceUrl.isEmpty();}
    public static AppleMusicConfig load(Context context)throws AppleMusicApi.Failure{
        try(InputStream input=context.getAssets().open(ASSET)){
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[2048];int count;
            while((count=input.read(buffer))!=-1){if(bytes.size()+count>16384)throw invalid();bytes.write(buffer,0,count);}
            return parse(new JSONObject(new String(bytes.toByteArray(),StandardCharsets.UTF_8)));
        }catch(FileNotFoundException absent){return unconfigured();}
        catch(AppleMusicApi.Failure failure){throw failure;}
        catch(Exception failure){throw invalid();}
    }
    public static AppleMusicConfig parse(JSONObject object)throws AppleMusicApi.Failure{
        if(object==null)throw invalid();
        Iterator<String> keys=object.keys();while(keys.hasNext()){
            String key=keys.next();
            if(!"mediaId".equals(key)&&!"storefront".equals(key)&&!"tokenServiceUrl".equals(key))throw invalid();
            if(!(object.opt(key) instanceof String))throw invalid();
        }
        String media=object.optString("mediaId","").trim(),store=object.optString("storefront","").trim(),url=object.optString("tokenServiceUrl","").trim();
        if(!media.isEmpty()&&!media.matches("[A-Za-z0-9][A-Za-z0-9.-]{2,254}"))throw invalid();
        if(!store.isEmpty()&&!store.matches("[a-z]{2}"))throw invalid();
        if(!url.isEmpty())try{
            URI uri=new URI(url);
            if(!"https".equals(uri.getScheme())||uri.getHost()==null||uri.getHost().isEmpty()||uri.getUserInfo()!=null||uri.getFragment()!=null||uri.getRawQuery()!=null||url.length()>2048)throw invalid();
        }catch(AppleMusicApi.Failure failure){throw failure;}catch(Exception failure){throw invalid();}
        return new AppleMusicConfig(media,store,url);
    }
    private static AppleMusicApi.Failure invalid(){return new AppleMusicApi.Failure(AppleMusicApi.Error.CONFIGURATION,"Apple Music 配置无效；配置只能包含应用标识、地区和 HTTPS 令牌服务地址");}
}
