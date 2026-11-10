package com.recordroom.vinyl;
import java.util.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
/** Only synthetic responses; no user credentials or playable vendor media. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=34)
public class QQPlaybackDiagnosticsTest {
 private static JSONArray matrix;
 @BeforeClass public static void begin(){matrix=new JSONArray();}
 @AfterClass public static void writeMatrix()throws Exception{
  JSONObject report=j("version","0.7.6","private_account_used",false,"real_audio_playback_verified",false,"source","QQPlaybackDiagnosticsTest: synthetic responses through production QQProvider/QQAudioProbe boundaries","cases",matrix);
  java.nio.file.Files.writeString(java.nio.file.Path.of(System.getProperty("vinyl.output.dir"),"diagnostic-matrix.json"),report.toString(2));
 }
 private static void record(String id,String condition,String kind,String message){matrix.put(j("id",id,"condition",condition,"expected_kind",kind,"expected_message",message,"passed",true));}

 static JSONObject j(Object... kv){try{JSONObject o=new JSONObject();for(int i=0;i<kv.length;i+=2)o.put((String)kv[i],kv[i+1]);return o;}catch(Exception e){throw new AssertionError(e);}}
 static JSONObject block(String k,JSONObject data){return j("code",0,k,j("code",0,"data",data));}
 static JSONObject song(){return j("mid","Song01","name","Diagnostic fixture","interval",180,"file",j("media_mid","Media01"));}
 static JSONObject info(){return j("filename","M500Media01.mp3","songmid","Song01","result",0,"purl","M500Media01.mp3?vkey=fixture-private-address");}
 static JSONObject resource(JSONObject row){return j("midurlinfo",new JSONArray().put(row),"sip",new JSONArray().put("https://ws.stream.qqmusic.qq.com/"));}
 static final class Audio extends QQAudioProbe {long duration=180000;int calls;@Override long durationMs(String u,Map<String,String> h){calls++;assertFalse(h.containsKey("Cookie"));return duration;}}
 static final class Http extends QQHttp {JSONObject response;Http(JSONObject r){response=r;}@Override JSONObject request(String url,JSONObject b,String c){if(b.has("songinfo"))return block("songinfo",j("track_info",song()));return block("req_0",response);}}
 static QQProvider provider(Http h,Audio a){return new QQProvider(new QQProviderTest.Session("uin=123; qm_keyst=fixture-private-cookie"),h,a);}
 static ProviderException failure(QQProvider provider)throws Exception{try{provider.resolve(QQProvider.track(song()));fail("Expected failure");return null;}catch(ProviderException f){assertFalse(f.getMessage(),f.getMessage().contains("账号未取得"));assertFalse(f.getMessage(),f.getMessage().contains("fixture-private"));assertFalse(f.getMessage(),f.getMessage().contains("fixture-secret"));assertFalse(f.getMessage(),f.getMessage().contains("vkey="));return f;}}
 @Test public void missingResourceEmptyAddressAndDeniedResultIdentifyTheirStageWithoutClaimingNoVip()throws Exception{
  Audio a=new Audio();String mismatch=failure(provider(new Http(resource(info().put("filename","M800Media01.mp3"))),a)).getMessage();
  String missing=failure(provider(new Http(resource(info().put("purl",""))),a)).getMessage();
  String denied=failure(provider(new Http(resource(info().put("result",104001).put("message","fixture-secret https://stream.qqmusic.qq.com/?vkey=signed-url"))),a)).getMessage();
  assertNotEquals(mismatch,missing);assertNotEquals(missing,denied);assertTrue(mismatch,mismatch.contains("匹配"));assertTrue(missing,missing.contains("播放地址"));assertTrue(denied,denied.contains("104001"));assertEquals(0,a.calls);
  record("resource-mismatch","No matching filename in response","UNAVAILABLE",mismatch);
  record("address-absent","Matching resource has no purl","UNAVAILABLE",missing);
  record("resource-rejected","Matching resource returns numeric result 104001","UNAVAILABLE",denied);
 }
 @Test public void grantedResourceWithUnknownOrWrongDurationDoesNotClaimAccountPermissionFailure()throws Exception{
  Audio a=new Audio();a.duration=-1;String unknown=failure(provider(new Http(resource(info())),a)).getMessage();assertTrue(unknown,unknown.contains("时长"));
  a.duration=200000;String mismatch=failure(provider(new Http(resource(info())),a)).getMessage();assertTrue(mismatch,mismatch.contains("时长"));assertTrue(mismatch,mismatch.contains("180"));assertTrue(mismatch,mismatch.contains("200"));assertNotEquals(unknown,mismatch);assertEquals(2,a.calls);
  record("duration-unconfirmed","Granted address but probe cannot confirm duration","UNAVAILABLE",unknown);
  record("duration-mismatch","Granted address duration 200s differs from catalog 180s","UNAVAILABLE",mismatch);
 }
 @Test public void fullAudioAndExplicitPreviewKeepExistingPolicy()throws Exception{
  Audio a=new Audio();QQProvider p=provider(new Http(resource(info())),a);assertEquals(180000,p.resolve(QQProvider.track(song())).durationMs);record("full-resource-accepted","Matching granted address and full duration","OK","Full duration accepted");
  a.duration=30000;ProviderException shortAudio=failure(p);assertEquals(ProviderException.Kind.PREVIEW_ONLY,shortAudio.kind);record("preview-rejected","Audio is shorter than catalog duration","PREVIEW_ONLY",shortAudio.getMessage());
  assertEquals(ProviderException.Kind.PREVIEW_ONLY,failure(provider(new Http(resource(info().put("trial",true))),a)).kind);
 }
 @Test public void audioHttpAndMimeFailuresRemainSpecificAndNeverEchoAddress()throws Exception{
  QQAudioProbe.requireAudioResponse(200,"audio/mpeg");QQAudioProbe.requireAudioResponse(206,"application/octet-stream");
  for(int status:new int[]{302,403,404,500})try{QQAudioProbe.requireAudioResponse(status,"application/octet-stream");fail("Expected HTTP failure");}catch(ProviderException f){assertEquals(ProviderException.Kind.UNAVAILABLE,f.kind);assertTrue(f.getMessage(),f.getMessage().contains("HTTP "+status));assertFalse(f.getMessage(),f.getMessage().contains("权限"));record("audio-http-"+status,"Audio HTTP response "+status,f.kind.name(),f.getMessage());}
  try{QQAudioProbe.requireAudioResponse(200,"text/html; diagnostic=fixture-secret");fail("Not audio");}catch(ProviderException f){assertTrue(f.getMessage(),f.getMessage().contains("非音频"));assertFalse(f.getMessage(),f.getMessage().contains("fixture-secret"));record("audio-content-invalid","Audio URL returns a text page",f.kind.name(),f.getMessage());}
 }
 @Test public void audioNetworkFailureUsesSafeStageNotPermissionInference()throws Exception{
  for(Exception cause:new Exception[]{new java.net.SocketTimeoutException("fixture-secret signed-url"),new java.net.UnknownHostException("fixture-secret"),new javax.net.ssl.SSLException("fixture-secret")}){
   ProviderException f=QQAudioProbe.audioNetworkFailure(cause);assertEquals(ProviderException.Kind.NETWORK,f.kind);assertTrue(f.getMessage(),f.getMessage().contains("验证播放音频失败"));assertFalse(f.getMessage(),f.getMessage().contains("fixture-secret"));assertFalse(f.getMessage(),f.getMessage().contains("signed-url"));record("audio-network-"+cause.getClass().getSimpleName(),"Audio network exception class only",f.kind.name(),f.getMessage());
  }
 }
}
