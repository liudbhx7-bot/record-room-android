package com.recordroom.vinyl;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import com.apple.android.sdk.authentication.TokenProvider;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONObject;

/** Device-bound encrypted MusicKit credentials; no bundled JWT, private key, plaintext fallback or network in SDK getters. */
public final class AppleMusicSessionStore implements AppleMusicApi.Tokens,TokenProvider {
    static final Object LOCK=new Object();
    private static final String ALIAS="recordroom.applemusic.session.v1";
    private static final byte[] AAD="com.recordroom.vinyl:applemusic:v1".getBytes(StandardCharsets.UTF_8);
    private static long epoch;
    private static final CopyOnWriteArrayList<Runnable> listeners=new CopyOnWriteArrayList<>();
    interface KeySupplier {SecretKey get()throws Exception;}
    interface TokenService {String fetch(String url)throws AppleMusicApi.Failure;}
    private final SharedPreferences preferences;private final KeySupplier keys;private final TokenService service;private final Context appContext;
    public AppleMusicSessionStore(Context context){this(context.getSharedPreferences("applemusic_private_session",Context.MODE_PRIVATE),AppleMusicSessionStore::deviceKey,AppleMusicSessionStore::fetchToken,context.getApplicationContext());}
    AppleMusicSessionStore(SharedPreferences preferences,KeySupplier keys,TokenService service){this(preferences,keys,service,null);}
    private AppleMusicSessionStore(SharedPreferences preferences,KeySupplier keys,TokenService service,Context context){this.preferences=preferences;this.keys=keys;this.service=service;this.appContext=context;}
    private void clearSdkSession(){if(appContext!=null)AppleMusicPlayback.clearStoredSession(appContext);}
    public static final class Attempt {
        private boolean cancelled,committed;
        public synchronized void cancel(){if(!committed)cancelled=true;}
        public synchronized boolean isCommitted(){return committed;}
    }
    private static SecretKey deviceKey()throws Exception{
        KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);if(store.containsAlias(ALIAS))return (SecretKey)store.getKey(ALIAS,null);
        KeyGenerator g=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");g.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).setRandomizedEncryptionRequired(true).build());return g.generateKey();
    }
    public void addChangeListener(Runnable listener){if(listener!=null)listeners.addIfAbsent(listener);}
    public void removeChangeListener(Runnable listener){listeners.remove(listener);}
    private static void changed(){epoch++;if(!listeners.isEmpty())new Handler(Looper.getMainLooper()).post(()->{for(Runnable listener:listeners)listener.run();});}
    @Override public long generation(){synchronized(LOCK){return epoch;}}
    private JSONObject readLocked()throws AppleMusicApi.Failure{
        String encoded=preferences.getString("encrypted_v1","");if(encoded.isEmpty())return new JSONObject();
        try{byte[] bytes=Base64.decode(encoded,Base64.NO_WRAP);if(bytes.length<29||bytes.length>131072)throw new IllegalArgumentException();Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,keys.get(),new GCMParameterSpec(128,Arrays.copyOfRange(bytes,0,12)));c.updateAAD(AAD);return new JSONObject(new String(c.doFinal(bytes,12,bytes.length-12),StandardCharsets.UTF_8));}
        catch(Exception failure){throw new AppleMusicApi.Failure(AppleMusicApi.Error.USER_AUTH_REQUIRED,"Apple Music 本机凭据无法解锁，请重新配置并授权");}
    }
    private void writeLocked(JSONObject state)throws AppleMusicApi.Failure{
        try{byte[] plain=state.toString().getBytes(StandardCharsets.UTF_8);if(plain.length>98304)throw new IllegalArgumentException();Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,keys.get());c.updateAAD(AAD);byte[] iv=c.getIV(),encrypted=c.doFinal(plain),result=Arrays.copyOf(iv,iv.length+encrypted.length);if(iv.length!=12)throw new IllegalArgumentException();System.arraycopy(encrypted,0,result,iv.length,encrypted.length);if(!preferences.edit().putString("encrypted_v1",Base64.encodeToString(result,Base64.NO_WRAP)).commit())throw new IllegalStateException();changed();}
        catch(Exception failure){throw new AppleMusicApi.Failure(AppleMusicApi.Error.STORAGE,"无法安全保存 Apple Music 会话，请检查存储后重试");}
    }
    @Override public String developerToken()throws AppleMusicApi.Failure{synchronized(LOCK){String token=readLocked().optString("developer","");validateDeveloperToken(token,System.currentTimeMillis()/1000);return token;}}
    @Override public String musicUserToken()throws AppleMusicApi.Failure{synchronized(LOCK){String value=readLocked().optString("user","");if(value.isEmpty())throw new AppleMusicApi.Failure(AppleMusicApi.Error.USER_AUTH_REQUIRED,"尚未授权 Apple Music 账户");return value;}}
    public String storefront()throws AppleMusicApi.Failure{synchronized(LOCK){return readLocked().optString("storefront","");}}
    public String accountIdentity()throws AppleMusicApi.Failure{synchronized(LOCK){return readLocked().optString("accountIdentity","");}}
    public String tokenServiceUrl(){synchronized(LOCK){return preferences.getString("token_service_url","");}}
    @Override public String getDeveloperToken(){try{return developerToken();}catch(AppleMusicApi.Failure failure){return null;}}
    @Override public String getUserToken(){try{return musicUserToken();}catch(AppleMusicApi.Failure failure){return null;}}
    @Override public String getAppleMusicStoreFrontId(){synchronized(LOCK){try{JSONObject s=readLocked();return s.optString("user","").isEmpty()?null:emptyNull(s.optString("encodedStorefront",""));}catch(AppleMusicApi.Failure failure){return null;}}}
    private static String emptyNull(String value){return value.isEmpty()?null:value;}
    public void configureTokenService(String url)throws AppleMusicApi.Failure{
        validateServiceUrl(url);synchronized(LOCK){if(url.equals(preferences.getString("token_service_url","")))return;if(!preferences.edit().putString("token_service_url",url).remove("encrypted_v1").commit())throw new AppleMusicApi.Failure(AppleMusicApi.Error.STORAGE,"无法保存 Apple Music 令牌服务地址");changed();clearSdkSession();}
    }
    public void importDeveloperToken(String token)throws AppleMusicApi.Failure{importDeveloperToken(token,generation(),new Attempt());}
    public void importDeveloperToken(String token,long expected,Attempt gate)throws AppleMusicApi.Failure{
        token=token==null?"":token.trim();JSONObject claims=validateDeveloperToken(token,System.currentTimeMillis()/1000);
        synchronized(gate){checkAttempt(gate);synchronized(LOCK){if(epoch!=expected)throw stale();JSONObject state=readLocked();String old=state.optString("developer","");
            boolean changedIssuer=!old.isEmpty()&&!sameIssuer(old,token);if(changedIssuer)clearUser(state);try{state.put("developer",token);state.put("developerExpires",claims.optLong("exp"));}catch(Exception failure){throw invalidToken();}writeLocked(state);if(changedIssuer)clearSdkSession();gate.committed=true;}}
    }
    /** Worker-thread operation. Our configured backend contract is GET -> {"developerToken":"signed JWT"}. */
    public String prepareDeveloperToken()throws AppleMusicApi.Failure{return prepareDeveloperToken(new Attempt());}
    public String prepareDeveloperToken(Attempt gate)throws AppleMusicApi.Failure{
        synchronized(gate){checkAttempt(gate);}
        try{return developerToken();}catch(AppleMusicApi.Failure failure){if(failure.error!=AppleMusicApi.Error.CONFIGURATION&&failure.error!=AppleMusicApi.Error.DEVELOPER_AUTH)throw failure;}
        long expected=generation();String url=tokenServiceUrl();if(url.isEmpty())throw new AppleMusicApi.Failure(AppleMusicApi.Error.CONFIGURATION,"请先配置开发者令牌服务，或从本机文件导入已签名的开发者 JWT");
        String value=service.fetch(url);synchronized(gate){checkAttempt(gate);importDeveloperToken(value,expected,new Attempt());}return developerToken();
    }
    void commitAuthorization(String user,String storefront,String encoded,long expected,Attempt gate)throws AppleMusicApi.Failure{
        validateUser(user);if(storefront==null||!storefront.matches("[a-z]{2}")||encoded==null||encoded.isEmpty()||encoded.length()>256||encoded.indexOf('\n')>=0||encoded.indexOf('\r')>=0)throw new AppleMusicApi.Failure(AppleMusicApi.Error.INVALID_RESPONSE,"Apple Music 授权缺少完整地区信息，请重新授权");
        synchronized(gate){checkAttempt(gate);synchronized(LOCK){if(epoch!=expected)throw stale();JSONObject state=readLocked();validateDeveloperToken(state.optString("developer",""),System.currentTimeMillis()/1000);boolean replacingUser=!state.optString("user","").isEmpty();try{state.put("user",user);state.put("storefront",storefront);state.put("encodedStorefront",encoded);state.put("accountIdentity","local-"+UUID.randomUUID());}catch(Exception failure){throw AppleMusicHttp.invalid();}writeLocked(state);if(replacingUser)clearSdkSession();gate.committed=true;}}
    }
    public void refreshUserToken(String token,long expected)throws AppleMusicApi.Failure{
        validateUser(token);synchronized(LOCK){if(epoch!=expected)throw stale();JSONObject state=readLocked();if(state.optString("user","").isEmpty())throw stale();try{state.put("user",token);}catch(Exception failure){throw AppleMusicHttp.invalid();}writeLocked(state);}
    }
    public void clearUserSession()throws AppleMusicApi.Failure{synchronized(LOCK){JSONObject state=readLocked();clearUser(state);writeLocked(state);clearSdkSession();}}
    public void clearAll()throws AppleMusicApi.Failure{synchronized(LOCK){if(!preferences.edit().remove("encrypted_v1").commit())throw new AppleMusicApi.Failure(AppleMusicApi.Error.STORAGE,"无法清除 Apple Music 会话");changed();clearSdkSession();}}
    private static void clearUser(JSONObject state){for(String k:new String[]{"user","storefront","encodedStorefront","accountIdentity"})state.remove(k);}
    private static void checkAttempt(Attempt gate)throws AppleMusicApi.Failure{if(gate==null||gate.cancelled||gate.committed||Thread.currentThread().isInterrupted())throw stale();}
    private static AppleMusicApi.Failure stale(){return new AppleMusicApi.Failure(AppleMusicApi.Error.SESSION_CHANGED,"这次 Apple Music 授权已取消或会话已变化");}
    private static AppleMusicApi.Failure invalidToken(){return new AppleMusicApi.Failure(AppleMusicApi.Error.CONFIGURATION,"开发者令牌格式无效；请选择已签名的 ES256 JWT，不是私钥文件");}
    static JSONObject validateDeveloperToken(String token,long now)throws AppleMusicApi.Failure{
        if(token==null||token.length()>32768||!token.matches("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+"))throw invalidToken();
        try{String[] parts=token.split("\\.");JSONObject header=new JSONObject(new String(Base64.decode(parts[0],Base64.URL_SAFE|Base64.NO_WRAP),StandardCharsets.UTF_8)),claims=new JSONObject(new String(Base64.decode(parts[1],Base64.URL_SAFE|Base64.NO_WRAP),StandardCharsets.UTF_8));
            if(!"ES256".equals(header.optString("alg"))||!header.optString("kid","").matches("[A-Za-z0-9]{10}")||!claims.optString("iss","").matches("[A-Za-z0-9]{10}")||Base64.decode(parts[2],Base64.URL_SAFE|Base64.NO_WRAP).length!=64)throw invalidToken();
            long iat=claims.optLong("iat",0),exp=claims.optLong("exp",0);if(iat<=0||iat>now+300||exp<=iat||exp-iat>15777000)throw invalidToken();
            if(exp<=now+30)throw new AppleMusicApi.Failure(AppleMusicApi.Error.DEVELOPER_AUTH,"Apple Music 开发者令牌已过期或即将过期，请更新后重试");return claims;
        }catch(AppleMusicApi.Failure failure){throw failure;}catch(Exception failure){throw invalidToken();}
    }
    private static boolean sameIssuer(String first,String second){try{String[] a=first.split("\\."),b=second.split("\\.");JSONObject ah=new JSONObject(new String(Base64.decode(a[0],Base64.URL_SAFE),StandardCharsets.UTF_8)),bh=new JSONObject(new String(Base64.decode(b[0],Base64.URL_SAFE),StandardCharsets.UTF_8)),ac=new JSONObject(new String(Base64.decode(a[1],Base64.URL_SAFE),StandardCharsets.UTF_8)),bc=new JSONObject(new String(Base64.decode(b[1],Base64.URL_SAFE),StandardCharsets.UTF_8));return ah.optString("kid").equals(bh.optString("kid"))&&ac.optString("iss").equals(bc.optString("iss"));}catch(Exception failure){return false;}}
    private static void validateUser(String token)throws AppleMusicApi.Failure{if(token==null||token.isEmpty()||token.length()>32768)throw AppleMusicHttp.invalid();for(int i=0;i<token.length();i++)if(token.charAt(i)<=32||token.charAt(i)>=127)throw AppleMusicHttp.invalid();}
    static void validateServiceUrl(String url)throws AppleMusicApi.Failure{
        if(url==null||url.length()>2048)throw invalidService();if(url.isEmpty())return;try{URI u=new URI(url);if(!"https".equals(u.getScheme())||u.getHost()==null||u.getUserInfo()!=null||u.getRawQuery()!=null||u.getFragment()!=null||(u.getPort()!=-1&&u.getPort()!=443))throw invalidService();}catch(AppleMusicApi.Failure failure){throw failure;}catch(Exception failure){throw invalidService();}
    }
    private static AppleMusicApi.Failure invalidService(){return new AppleMusicApi.Failure(AppleMusicApi.Error.CONFIGURATION,"令牌服务需要不含口令或查询凭据的 HTTPS 地址");}
    private static String fetchToken(String url)throws AppleMusicApi.Failure{
        validateServiceUrl(url);HttpsURLConnection c=null;try{c=(HttpsURLConnection)new URL(url).openConnection();c.setInstanceFollowRedirects(false);c.setUseCaches(false);c.setConnectTimeout(12000);c.setReadTimeout(18000);c.setRequestProperty("Accept","application/json");int code=c.getResponseCode();if(code!=200)throw new AppleMusicApi.Failure(AppleMusicApi.Error.CONFIGURATION,"开发者令牌服务暂未提供可用令牌（HTTP "+code+"）");
            try(InputStream in=c.getInputStream()){ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buf=new byte[4096];int n;while((n=in.read(buf))!=-1){if(Thread.currentThread().isInterrupted())throw stale();if(out.size()+n>49152)throw invalidToken();out.write(buf,0,n);}JSONObject json=new JSONObject(new String(out.toByteArray(),StandardCharsets.UTF_8));return json.optString("developerToken","");}
        }catch(AppleMusicApi.Failure failure){throw failure;}catch(Exception failure){throw new AppleMusicApi.Failure(AppleMusicApi.Error.NETWORK,"无法连接开发者令牌服务，请检查配置后重试");}finally{if(c!=null)c.disconnect();}
    }
}
