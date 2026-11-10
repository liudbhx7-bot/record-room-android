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

/** QQ-only device-bound storage. Never shares keys, ciphertext, preferences, or account epochs with NetEase. */
public final class QQSessionStore implements ProviderSession {
    private static final String ALIAS="recordroom.qq.session.v1";
    private static final byte[] AAD="com.recordroom.vinyl:qq:v1".getBytes(StandardCharsets.UTF_8);
    interface Keys{SecretKey get()throws Exception;}
    private final SharedPreferences preferences;private final Keys keys;private String cached;private long epoch;
    public QQSessionStore(Context context){this(context.getSharedPreferences("qq_private_session",Context.MODE_PRIVATE),QQSessionStore::deviceKey);}
    QQSessionStore(SharedPreferences preferences,Keys keys){this.preferences=preferences;this.keys=keys;}
    private static synchronized SecretKey deviceKey()throws Exception{KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);if(store.containsAlias(ALIAS))return (SecretKey)store.getKey(ALIAS,null);KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");generator.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).setRandomizedEncryptionRequired(true).build());return generator.generateKey();}
    @Override public synchronized String readCookies()throws ProviderException{
        if(cached!=null)return cached;String value=preferences.getString("encrypted_v1","");if(value.isEmpty())return cached="";
        try{byte[] bytes=Base64.decode(value,Base64.NO_WRAP);if(bytes.length<29||bytes.length>65536)throw new IllegalArgumentException();Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,keys.get(),new GCMParameterSpec(128,Arrays.copyOfRange(bytes,0,12)));cipher.updateAAD(AAD);return cached=new String(cipher.doFinal(bytes,12,bytes.length-12),StandardCharsets.UTF_8);}
        catch(Exception failure){cached="";epoch++;preferences.edit().remove("encrypted_v1").commit();throw new ProviderException(ProviderException.Kind.LOGIN_EXPIRED,"QQ 音乐登录凭据已失效，请重新授权");}
    }
    @Override public synchronized void writeCookies(String value)throws ProviderException{
        if(value==null||value.length()>32768||value.indexOf('\r')>=0||value.indexOf('\n')>=0)throw QQHttp.invalid();
        if(value.equals(cached))return;if(value.isEmpty()){clear();return;}
        try{Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,keys.get());cipher.updateAAD(AAD);byte[] iv=cipher.getIV(),encrypted=cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));if(iv.length!=12)throw new IllegalStateException();byte[] all=Arrays.copyOf(iv,iv.length+encrypted.length);System.arraycopy(encrypted,0,all,iv.length,encrypted.length);if(!preferences.edit().putString("encrypted_v1",Base64.encodeToString(all,Base64.NO_WRAP)).commit())throw new IllegalStateException();cached=value;epoch++;}
        catch(Exception failure){throw new ProviderException(ProviderException.Kind.STORAGE,"无法安全保存 QQ 音乐授权，请重试");}
    }
    @Override public synchronized void clear()throws ProviderException{cached="";epoch++;if(!preferences.edit().remove("encrypted_v1").commit())throw new ProviderException(ProviderException.Kind.STORAGE,"无法清除 QQ 音乐授权，请检查存储空间");}
    @Override public synchronized long generation(){return epoch;}
}
