/* SPDX-License-Identifier: GPL-3.0-only
 * Android adaptation of Mineradio-paused Passport flow, d43de565acabfdc1a9c9820a27e81a98ccbebcef.
 */
package com.recordroom.vinyl;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.SslErrorHandler;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

/** Real Passport QR authorization. No JS-to-Java interface, passwords, Web security disablement or TLS bypass. */
public final class QishuiAuthActivity extends Activity {
    static final String HOST="https://api.qishui.com/__recordroom_auth__/";
    private static final String BDMS="https://lf-headquarters-speed.yhgfb-cn-static.com/obj/rc-client-security/web/stable/1.0.0.41/bdms.js";
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private WebView web;private TextView status;private ImageView qr;private LinearLayout qrPanel;private Button refresh;
    private boolean closed,checking,hostReady;private String lastScan="";private long sessionGeneration,attempt;
    private QishuiProvider provider;private QishuiProvider.AuthorizationAttempt authorization;
    private final Runnable tick=new Runnable(){public void run(){if(closed||web==null)return;if(hostReady&&!checking)readState();main.postDelayed(this,1200);}};
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);setResult(RESULT_CANCELED);provider=(QishuiProvider)OnlineMusic.provider(getApplicationContext(),"qishui");sessionGeneration=OnlineMusic.sessionGeneration(getApplicationContext(),"qishui");
        getWindow().setStatusBarColor(0xffeee7ee);getWindow().setNavigationBarColor(0xffeee7ee);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(0xffeee7ee);setContentView(root);
        root.setOnApplyWindowInsetsListener((v,insets)->{v.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());return insets;});
        LinearLayout bar=new LinearLayout(this);bar.setGravity(Gravity.CENTER_VERTICAL);Button back=new Button(this);back.setText("取消");back.setOnClickListener(v->finish());bar.addView(back);
        TextView title=new TextView(this);title.setText("汽水音乐扫码授权");title.setTextColor(0xff252129);title.setTextSize(18);bar.addView(title,new LinearLayout.LayoutParams(0,-2,1));root.addView(bar);
        status=new TextView(this);status.setPadding(dp(18),dp(12),dp(18),dp(12));status.setTextSize(14);status.setTextColor(0xff524a56);root.addView(status);
        FrameLayout area=new FrameLayout(this);root.addView(area,new LinearLayout.LayoutParams(-1,0,1));web=new WebView(this);area.addView(web,new FrameLayout.LayoutParams(-1,-1));
        qrPanel=new LinearLayout(this);qrPanel.setOrientation(LinearLayout.VERTICAL);qrPanel.setGravity(Gravity.CENTER);qrPanel.setBackgroundColor(0xffeee7ee);area.addView(qrPanel,new FrameLayout.LayoutParams(-1,-1));
        qr=new ImageView(this);qr.setContentDescription("使用抖音扫码授权汽水音乐");qrPanel.addView(qr,new LinearLayout.LayoutParams(dp(280),dp(280)));
        TextView note=new TextView(this);note.setText("使用抖音 App 扫码，并在手机上确认登录\n官方验证完成后，将自动读取你的汽水曲库");note.setGravity(Gravity.CENTER);note.setTextSize(14);note.setTextColor(0xff524a56);note.setPadding(dp(20),dp(24),dp(20),0);qrPanel.addView(note);
        refresh=new Button(this);refresh.setText("刷新二维码");refresh.setOnClickListener(v->start());root.addView(refresh,new LinearLayout.LayoutParams(-1,dp(54)));
        WebSettings settings=web.getSettings();settings.setJavaScriptEnabled(true);settings.setDomStorageEnabled(true);settings.setAllowFileAccess(false);settings.setAllowContentAccess(false);settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);settings.setSaveFormData(false);settings.setSupportMultipleWindows(false);settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        // The real WebView user agent and browser information are retained.
        CookieManager.getInstance().setAcceptCookie(true);CookieManager.getInstance().setAcceptThirdPartyCookies(web,false);
        web.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest req){return !navigationAllowed(req.getUrl());}
            @Override public boolean shouldOverrideUrlLoading(WebView view,String url){return !navigationAllowed(Uri.parse(url));}
            @Override public WebResourceResponse shouldInterceptRequest(WebView view,WebResourceRequest req){
                String url=req.getUrl().toString();String asset=null;
                if(url.startsWith(HOST)){String name=req.getUrl().getLastPathSegment();if(name!=null&&name.matches("host\\.html|seed\\.html|android-host\\.js|sdk-glue\\.js|bdms\\.js|react\\.js|react-dom\\.js"))asset=name;else return denied();}
                if(BDMS.equals(url))asset="bdms.js";
                if(asset!=null)try{return new WebResourceResponse(asset.endsWith(".html")?"text/html":"application/javascript","UTF-8",getAssets().open("qishui-auth/"+asset));}catch(Exception missing){return denied();}
                if(!resourceAllowed(req.getUrl()))return denied();return super.shouldInterceptRequest(view,req);
            }
            @Override public void onPageFinished(WebView view,String url){
                if(closed)return;
                if((HOST+"seed.html").equals(url)){
                    // Set anti-abuse SDK state before its script is loaded, as required by the source flow.
                    byte[] random=new byte[88];new SecureRandom().nextBytes(random);String token=android.util.Base64.encodeToString(random,android.util.Base64.URL_SAFE|android.util.Base64.NO_WRAP|android.util.Base64.NO_PADDING)+"==";
                    web.evaluateJavascript("localStorage.setItem('xmst',"+JSONObject.quote(token)+");localStorage.setItem('xmsty',"+JSONObject.quote(token)+");true",value->{if(!closed)web.loadUrl(HOST+"host.html");});
                }else if((HOST+"host.html").equals(url)){
                    hostReady=true;String init="window.__rrQishuiInitialize("+identity().toString()+");true";web.evaluateJavascript(init,null);
                }
            }
            @Override public void onReceivedSslError(WebView view,SslErrorHandler handler,SslError error){handler.cancel();fail("汽水安全连接未通过，请检查网络或系统时间");}
        });
        start();main.postDelayed(tick,1200);root.requestApplyInsets();
    }
    private void start(){if(closed)return;if(authorization!=null)authorization.cancel();authorization=new QishuiProvider.AuthorizationAttempt();sessionGeneration=OnlineMusic.sessionGeneration(getApplicationContext(),"qishui");attempt++;checking=false;hostReady=false;lastScan="";qr.setImageDrawable(null);qrPanel.setVisibility(View.VISIBLE);refresh.setEnabled(true);status.setText("正在初始化汽水官方安全组件…");web.stopLoading();CookieManager.getInstance().removeAllCookies(done->{if(!closed)web.loadUrl(HOST+"seed.html");});}
    private JSONObject identity(){
        android.content.SharedPreferences prefs=getSharedPreferences("qishui_auth_identity",MODE_PRIVATE);
        String device=prefs.getString("device","");String install=prefs.getString("install","");String portrait=prefs.getString("portrait","");
        if(device.isEmpty()){device=digits(16);install=digits(15);portrait=UUID.randomUUID()+".login";prefs.edit().putString("device",device).putString("install",install).putString("portrait",portrait).apply();}
        JSONObject o=new JSONObject();try{o.put("deviceId",device);o.put("installId",install);o.put("verifyPortraitId",portrait);o.put("deviceName",Build.MANUFACTURER+" "+Build.MODEL);}catch(Exception ignored){}return o;
    }
    private static String digits(int n){SecureRandom random=new SecureRandom();StringBuilder out=new StringBuilder("1");while(out.length()<n)out.append(random.nextInt(10));return out.toString();}
    private void readState(){
        final long ticket=attempt;web.evaluateJavascript("window.__rrQishuiState ? window.__rrQishuiState() : null",encoded->{
            if(closed||ticket!=attempt)return;
            try{Object parsed=new org.json.JSONTokener(encoded).nextValue();if(!(parsed instanceof String))return;JSONObject state=new JSONObject((String)parsed);String kind=state.optString("status",""),message=state.optString("message","");
                if(!message.isEmpty())status.setText(message);
                if("challenge".equals(kind)){qrPanel.setVisibility(View.GONE);return;}
                if("confirmed".equals(kind)){complete(state.optString("sessionCookie",""),ticket);return;}
                if("qr".equals(kind)||"waiting".equals(kind)){
                    qrPanel.setVisibility(View.VISIBLE);String scan=state.optString("scanUrl","");if(!scan.isEmpty()&&!scan.equals(lastScan)){if(!scanAllowed(Uri.parse(scan))){fail("汽水返回了不受信任的二维码");return;}lastScan=scan;drawQr(scan);}
                    web.evaluateJavascript("window.__rrQishuiPoll();true",null);
                }
            }catch(Exception invalid){fail("汽水授权响应无法读取，请刷新重试");}
        });
    }
    private void drawQr(String url)throws Exception{
        Map<EncodeHintType,Object> hints=new HashMap<>();hints.put(EncodeHintType.MARGIN,2);hints.put(EncodeHintType.CHARACTER_SET,"UTF-8");BitMatrix matrix=new QRCodeWriter().encode(url,BarcodeFormat.QR_CODE,560,560,hints);int[] pixels=new int[560*560];for(int y=0;y<560;y++)for(int x=0;x<560;x++)pixels[y*560+x]=matrix.get(x,y)?Color.BLACK:Color.WHITE;qr.setImageBitmap(Bitmap.createBitmap(pixels,560,560,Bitmap.Config.ARGB_8888));
    }
    private void complete(String responseCookie,long ticket){
        if(checking||closed)return;checking=true;refresh.setEnabled(false);status.setText("已确认授权，正在核验汽水账户…");
        String browserCookie=CookieManager.getInstance().getCookie("https://api.qishui.com/");final String cookies=mergeCookies(browserCookie,responseCookie);final QishuiProvider.AuthorizationAttempt gate=authorization;final long expectedGeneration=sessionGeneration;
        worker.execute(()->{try{
            if(closed||ticket!=attempt)return;provider.acceptAuthorizedSession(cookies,expectedGeneration,gate);
            main.post(()->{if(closed||ticket!=attempt)return;setResult(RESULT_OK);finish();});
        }catch(ProviderException failure){main.post(()->{if(closed||ticket!=attempt)return;checking=false;refresh.setEnabled(true);fail(failure.getMessage());});}});
    }
    static String mergeCookies(String first,String second){Map<String,String> values=new java.util.LinkedHashMap<>();for(String raw:new String[]{first,second})if(raw!=null)for(String part:raw.split(";")){int split=part.indexOf('=');if(split<=0)continue;String k=part.substring(0,split).trim(),v=part.substring(split+1).trim();if(k.matches("[A-Za-z0-9_-]+")&&v.indexOf('\r')<0&&v.indexOf('\n')<0)values.put(k,v);}StringBuilder out=new StringBuilder();for(Map.Entry<String,String> e:values.entrySet()){if(out.length()>0)out.append("; ");out.append(e.getKey()).append('=').append(e.getValue());}return out.toString();}
    private void fail(String message){if(closed)return;hostReady=false;status.setText(message);refresh.setEnabled(true);}
    static boolean navigationAllowed(Uri u){return u!=null&&u.toString().startsWith(HOST)&&baseAllowed(u);}
    static boolean scanAllowed(Uri u){return baseAllowed(u)&&"bff-pc.qishui.com".equals(u.getHost())&&"/light/invoke/scan_login".equals(u.getPath())&&u.getQueryParameter("token")!=null;}
    private static boolean baseAllowed(Uri u){return u!=null&&"https".equals(u.getScheme())&&u.getHost()!=null&&u.getUserInfo()==null&&(u.getPort()==-1||u.getPort()==443);}
    static boolean resourceAllowed(Uri u){
        if(!baseAllowed(u))return false;String host=u.getHost();
        return host.equals("api.qishui.com")||host.equals("verify.zijieapi.com")||host.equals("auth.zijieapi.com")||host.equals("mon.zijieapi.com")||host.equals("lf-headquarters-speed.yhgfb-cn-static.com")||host.equals("lf-rc1.yhgfb-cn-static.com")||host.equals("lf-rc2.yhgfb-cn-static.com")||host.equals("lf1-cdn-tos.bytegoofy.com")||host.equals("lf3-cdn-tos.bytegoofy.com")||host.equals("lf6-cdn-tos.bytegoofy.com")||host.equals("lf-c-flwb.bytetos.com")||host.endsWith(".byteimg.com")||host.endsWith(".ibyteimg.com");
    }
    private static WebResourceResponse denied(){return new WebResourceResponse("text/plain","UTF-8",403,"Blocked",Collections.emptyMap(),new ByteArrayInputStream("Blocked".getBytes(StandardCharsets.UTF_8)));}
    @Override public void finish(){if(authorization!=null)authorization.cancel();super.finish();}
    @Override public void onDestroy(){if(authorization!=null)authorization.cancel();closed=true;attempt++;main.removeCallbacksAndMessages(null);worker.shutdownNow();if(web!=null){web.evaluateJavascript("window.__rrQishuiCancel && window.__rrQishuiCancel()",null);web.stopLoading();web.clearHistory();web.clearCache(true);web.destroy();web=null;}CookieManager.getInstance().removeAllCookies(done->CookieManager.getInstance().flush());WebStorage.getInstance().deleteOrigin("https://api.qishui.com");super.onDestroy();}
    private int dp(float value){return Math.round(value*getResources().getDisplayMetrics().density);}
}
