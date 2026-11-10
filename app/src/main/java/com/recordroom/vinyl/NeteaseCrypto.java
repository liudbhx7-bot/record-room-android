package com.recordroom.vinyl;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Request-envelope interoperability only; this code never decrypts audio or DRM.
 * Protocol reference: NeteaseCloudMusicApi 4.32.0 util/crypto.js, MIT (Binaryify).
 * See docs/NETEASE_PROTOCOL.md and docs/licenses/NeteaseCloudMusicApi-MIT.txt. */
final class NeteaseCrypto {
    private static final String PRESET = "0CoJUm6Qyw8W8jud", IV = "0102030405060708", EAPI = "e82ckenh8dichen8";
    private static final String PUBLIC_KEY = "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDgtQn2JZ34ZC28NWYpAUd98iZ37BUrX/aKzmFbt7clFSs6sXqHauqKWqdtLkF2KexO40H1YTX8z2lSgBBOAxLsvaklV8k4cBFK9snQXE9/DDaFt6Rr7iVZMldczhC0JNgTz+SHXT6CBHuX3e9SdB1Ua44oncaTWz7OBGLbCiK45wIDAQAB";
    private static final SecureRandom RANDOM = new SecureRandom();
    private NeteaseCrypto() { }

    static Map<String,String> weapi(String json) throws Exception {
        String alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        StringBuilder secret = new StringBuilder();
        for (int i=0;i<16;i++) secret.append(alphabet.charAt(RANDOM.nextInt(alphabet.length())));
        return weapi(json, secret.toString());
    }

    static Map<String,String> weapi(String json, String secret) throws Exception {
        if (secret.length()!=16) throw new IllegalArgumentException("Invalid request key");
        String inner = Base64.getEncoder().encodeToString(aes(json, PRESET, true));
        String params = Base64.getEncoder().encodeToString(aes(inner, secret, true));
        RSAPublicKey key=(RSAPublicKey)KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(PUBLIC_KEY)));
        BigInteger message=new BigInteger(1,new StringBuilder(secret).reverse().toString().getBytes(StandardCharsets.UTF_8));
        String encrypted=message.modPow(key.getPublicExponent(),key.getModulus()).toString(16);
        StringBuilder padded=new StringBuilder();for(int i=encrypted.length();i<256;i++)padded.append('0');padded.append(encrypted);
        Map<String,String> values=new LinkedHashMap<>();values.put("params",params);values.put("encSecKey",padded.toString());return values;
    }

    static Map<String,String> eapi(String path,String json) throws Exception {
        String digest=hex(MessageDigest.getInstance("MD5").digest(("nobody"+path+"use"+json+"md5forencrypt").getBytes(StandardCharsets.UTF_8)),false);
        String payload=path+"-36cd479b6b5-"+json+"-36cd479b6b5-"+digest;
        Map<String,String> values=new LinkedHashMap<>();values.put("params",hex(aes(payload,EAPI,false),true));return values;
    }

    private static byte[] aes(String value,String key,boolean cbc)throws Exception{
        Cipher cipher=Cipher.getInstance(cbc?"AES/CBC/PKCS5Padding":"AES/ECB/PKCS5Padding");
        SecretKeySpec secret=new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8),"AES");
        if(cbc)cipher.init(Cipher.ENCRYPT_MODE,secret,new IvParameterSpec(IV.getBytes(StandardCharsets.UTF_8)));else cipher.init(Cipher.ENCRYPT_MODE,secret);
        return cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
    }
    private static String hex(byte[] bytes,boolean upper){String digits=upper?"0123456789ABCDEF":"0123456789abcdef";StringBuilder out=new StringBuilder();for(byte b:bytes){out.append(digits.charAt((b&255)>>>4));out.append(digits.charAt(b&15));}return out.toString();}
}
