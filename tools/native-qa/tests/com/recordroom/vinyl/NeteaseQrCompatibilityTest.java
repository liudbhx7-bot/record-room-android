package com.recordroom.vinyl;

import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Request serialization and isolated candidate sessions; never claims a real 803 was observed. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=34)
public class NeteaseQrCompatibilityTest {
    private static JSONObject json(Object... values){try{JSONObject out=new JSONObject();for(int i=0;i<values.length;i+=2)out.put((String)values[i],values[i+1]);return out;}catch(Exception failure){throw new AssertionError(failure);}}
    private static JSONObject profile(){return json("code",200,"profile",json("userId",8,"nickname","原创测试账户"));}
    private static NeteaseHttp.Response response(JSONObject body,String cookie){return new NeteaseHttp.Response(body,cookie);}
    private static final class Session implements ProviderSession {
        String cookie="MUSIC_U=old-web-fixture; __csrf=old";long generation;int writes;boolean refuse;
        public String readCookies(){return cookie;}public void clear(){cookie="";generation++;}public long generation(){return generation;}
        public void writeCookies(String next)throws ProviderException{if(refuse)throw new ProviderException(ProviderException.Kind.STORAGE,"测试存储不可用");cookie=next;writes++;}
    }
    interface QrHandler{NeteaseHttp.Response handle(String path,JSONObject args,String cookies)throws ProviderException;}
    private static class Http extends NeteaseHttp {
        final QrHandler qr;QrHandler account=(path,args,cookie)->response(profile(),cookie);int accountCalls;
        Http(QrHandler qr){this.qr=qr;}
        @Override Response postQr(String path,JSONObject parameters,String cookies)throws ProviderException{return qr.handle(path,parameters,cookies);}
        @Override Response post(String path,JSONObject parameters,boolean weapi,String cookies)throws ProviderException{assertEquals("/api/w/nuser/account/get",path);assertTrue("Candidate account uses the existing proven account endpoint",weapi);accountCalls++;return account.handle(path,parameters,cookies);}
    }
    private static NeteaseHttp.Response key(String cookie){return response(json("code",200,"unikey","candidate-key"),cookie);}
    interface Action{void run()throws Exception;}
    private static void fails(ProviderException.Kind kind,Action action)throws Exception{try{action.run();fail("Expected "+kind);}catch(ProviderException failure){assertEquals(kind,failure.kind);assertFalse(failure.getMessage().contains("old-web-fixture"));assertFalse(failure.getMessage().contains("new-qr-fixture"));}}

    @Test public void qrOnlySerializationMirrorsPcHeaderCookieUsesStableDeviceAndDecimalRequestIds()throws Exception{
        NeteaseHttp http=new NeteaseHttp();JSONObject args=json("type",3,"key","fixture-key");NeteaseHttp.QrRequest first=http.prepareQr("/api/login/qrcode/client/login",args,"NMTID=fixture; __csrf=test-csrf");
        assertEquals("https://interfacepc.music.163.com/eapi/login/qrcode/client/login",first.url);assertEquals("pc",first.header.getString("os"));assertTrue(first.userAgent.contains("NeteaseMusicDesktop/3.1.17.204416"));assertEquals(3,first.data.getInt("type"));assertFalse(first.data.getBoolean("e_r"));assertEquals("fixture-key",first.data.getString("key"));assertFalse(args.has("header"));
        Map<String,String> headerCookie=NeteaseHttp.parseCookies(first.cookie);java.util.Iterator<String> keys=first.header.keys();while(keys.hasNext()){String name=keys.next();assertEquals(first.header.getString(name),URLDecoder.decode(headerCookie.get(name),"UTF-8"));}
        assertEquals("fixture",first.header.getString("NMTID"));assertFalse(first.header.has("MUSIC_U"));assertTrue(first.header.getString("requestId").matches("[0-9]{13}_[0-9]{4}"));
        NeteaseHttp.QrRequest second=http.prepareQr("/api/login/qrcode/unikey",json("type",3),"");assertEquals(first.header.getString("deviceId"),second.header.getString("deviceId"));assertFalse(second.header.has("NMTID"));
        fails(ProviderException.Kind.INVALID_RESPONSE,()->http.prepareQr("/api/song/enhance/player/url/v1",json(),""));
        fails(ProviderException.Kind.INVALID_RESPONSE,()->http.prepareQr("https://other.example/api/login",json(),""));
    }

    @Test public void server8821CannotSubmitOrOverwriteExistingWebSession()throws Exception{
        Session session=new Session();List<String> sent=new ArrayList<>();Http http=new Http((path,args,cookies)->{sent.add(cookies);assertEquals(3,args.optInt("type"));assertFalse(cookies.contains("old-web-fixture"));return path.endsWith("unikey")?key("NMTID=qr-only"):response(json("code",8821),"NMTID=qr-only");});
        NeteaseProvider provider=new NeteaseProvider(session,http);provider.beginQrLogin();fails(ProviderException.Kind.LOGIN_CHALLENGE,()->provider.pollQrLogin("candidate-key"));
        assertEquals("",sent.get(0));assertEquals("NMTID=qr-only",sent.get(1));assertEquals("MUSIC_U=old-web-fixture; __csrf=old",session.cookie);assertEquals(0,session.writes);assertEquals(0,http.accountCalls);
    }

    @Test public void oldAuthenticationCannotMasqueradeAsAValid803WithoutNewCandidateToken()throws Exception{
        Session session=new Session();Http http=new Http((path,args,cookie)->path.endsWith("unikey")?key("NMTID=qr-only"):response(json("code",803),cookie));
        NeteaseProvider provider=new NeteaseProvider(session,http);provider.beginQrLogin();fails(ProviderException.Kind.INVALID_RESPONSE,()->provider.pollQrLogin("candidate-key"));assertEquals(0,http.accountCalls);assertEquals(0,session.writes);assertTrue(session.cookie.contains("old-web-fixture"));
    }

    @Test public void candidateProfileMustValidateBeforeTheSingleCredentialCommit()throws Exception{
        Session session=new Session();Http http=new Http((path,args,cookie)->path.endsWith("unikey")?key("NMTID=qr-only"):response(json("code",803),"MUSIC_U=new-qr-fixture; __csrf=new"));
        http.account=(path,args,cookie)->{assertTrue(cookie.contains("new-qr-fixture"));assertFalse(cookie.contains("old-web-fixture"));assertTrue(session.cookie.contains("old-web-fixture"));assertEquals(0,session.writes);return response(profile(),cookie);};
        NeteaseProvider provider=new NeteaseProvider(session,http);provider.beginQrLogin();assertEquals(MusicProvider.LoginStatus.SUCCESS,provider.pollQrLogin("candidate-key").status);assertEquals(1,http.accountCalls);assertEquals(1,session.writes);assertTrue(session.cookie.contains("new-qr-fixture"));assertFalse(session.cookie.contains("old-web-fixture"));
    }

    @Test public void absentProfileOrStorageFailureLeavesThePreviousAccountIntact()throws Exception{
        Session session=new Session();Http http=new Http((path,args,cookie)->path.endsWith("unikey")?key(""):response(json("code",803),"MUSIC_U=new-qr-fixture"));http.account=(path,args,cookie)->response(json("code",200,"profile",JSONObject.NULL),cookie);
        NeteaseProvider provider=new NeteaseProvider(session,http);provider.beginQrLogin();fails(ProviderException.Kind.INVALID_RESPONSE,()->provider.pollQrLogin("candidate-key"));assertTrue(session.cookie.contains("old-web-fixture"));assertEquals(0,session.writes);
        http.account=(path,args,cookie)->response(profile(),cookie);session.refuse=true;provider.beginQrLogin();fails(ProviderException.Kind.STORAGE,()->provider.pollQrLogin("candidate-key"));assertTrue(session.cookie.contains("old-web-fixture"));assertEquals(0,session.writes);
    }

    @Test public void canceledOrReplacedCandidateNeverCommitsLateCredentials()throws Exception{
        Session session=new Session();Http http=new Http((path,args,cookie)->path.endsWith("unikey")?key(""):response(json("code",803),"MUSIC_U=new-qr-fixture"));
        http.account=(path,args,cookie)->{Thread.currentThread().interrupt();return response(profile(),cookie);};NeteaseProvider provider=new NeteaseProvider(session,http);provider.beginQrLogin();
        try{fails(ProviderException.Kind.LOGIN_EXPIRED,()->provider.pollQrLogin("candidate-key"));}finally{Thread.interrupted();}
        assertEquals(0,session.writes);assertTrue(session.cookie.contains("old-web-fixture"));
        http.account=(path,args,cookie)->{session.clear();return response(profile(),cookie);};provider.beginQrLogin();fails(ProviderException.Kind.LOGIN_EXPIRED,()->provider.pollQrLogin("candidate-key"));assertEquals("",session.cookie);assertEquals(0,session.writes);
        fails(ProviderException.Kind.INVALID_RESPONSE,()->provider.pollQrLogin("unrelated-key"));
    }
}
