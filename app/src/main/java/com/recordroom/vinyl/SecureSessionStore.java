package com.recordroom.vinyl;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Device-bound AES-GCM session storage. There is deliberately no plaintext fallback. */
public final class SecureSessionStore implements ProviderSession {
    private static final String ALIAS="recordroom.netease.session.v1";
    private static final byte[] AAD="com.recordroom.vinyl:netease:v1".getBytes(StandardCharsets.UTF_8);
    interface KeySupplier {SecretKey get() throws Exception;}
    private final SharedPreferences preferences;
    private final KeySupplier keys;
    private String cached;
    private long epoch;

    public SecureSessionStore(Context context){this(context.getSharedPreferences("netease_private_session",Context.MODE_PRIVATE),SecureSessionStore::deviceKey);}
    SecureSessionStore(SharedPreferences preferences,KeySupplier keys){this.preferences=preferences;this.keys=keys;}
    private static synchronized SecretKey deviceKey() throws Exception {
        KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);
        if(store.containsAlias(ALIAS))return (SecretKey)store.getKey(ALIAS,null);
        KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).setRandomizedEncryptionRequired(true).build());
        return generator.generateKey();
    }
    @Override public synchronized String readCookies() throws ProviderException {
        if(cached!=null)return cached;
        String encoded=preferences.getString("encrypted_v1","");if(encoded.isEmpty())return cached="";
        try {
            byte[] bytes=Base64.decode(encoded,Base64.NO_WRAP);
            if(bytes.length<29||bytes.length>65536)throw new IllegalArgumentException("Invalid encrypted session");
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,keys.get(),new GCMParameterSpec(128,Arrays.copyOfRange(bytes,0,12)));cipher.updateAAD(AAD);
            cached=new String(cipher.doFinal(bytes,12,bytes.length-12),StandardCharsets.UTF_8);return cached;
        } catch(Exception failure){
            cached="";epoch++;preferences.edit().remove("encrypted_v1").commit();
            throw new ProviderException(ProviderException.Kind.LOGIN_EXPIRED,"登录凭据已失效，请重新扫码登录");
        }
    }
    @Override public synchronized void writeCookies(String cookies) throws ProviderException {
        if(cookies==null)cookies="";
        if(cookies.length()>32768||cookies.indexOf('\r')>=0||cookies.indexOf('\n')>=0)throw new ProviderException(ProviderException.Kind.INVALID_RESPONSE,"登录响应格式无效");
        if(cookies.equals(cached))return;
        if(cookies.isEmpty()){if(!preferences.edit().remove("encrypted_v1").commit())throw new ProviderException(ProviderException.Kind.STORAGE,"无法保存登录状态，请检查可用空间");cached="";return;}
        try {
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,keys.get());cipher.updateAAD(AAD);
            byte[] iv=cipher.getIV(),ciphertext=cipher.doFinal(cookies.getBytes(StandardCharsets.UTF_8));
            if(iv.length!=12)throw new IllegalStateException("Unsupported GCM IV");
            byte[] result=Arrays.copyOf(iv,iv.length+ciphertext.length);System.arraycopy(ciphertext,0,result,iv.length,ciphertext.length);
            if(!preferences.edit().putString("encrypted_v1",Base64.encodeToString(result,Base64.NO_WRAP)).commit())throw new IllegalStateException("Write failed");
            cached=cookies;
        } catch(Exception failure){throw new ProviderException(ProviderException.Kind.STORAGE,"无法安全保存登录状态，请重试");}
    }
    @Override public synchronized void clear() throws ProviderException {
        cached="";epoch++;
        if(!preferences.edit().remove("encrypted_v1").commit())throw new ProviderException(ProviderException.Kind.STORAGE,"无法清除本地登录状态，请检查存储空间");
    }
    @Override public synchronized long generation(){return epoch;}
}
