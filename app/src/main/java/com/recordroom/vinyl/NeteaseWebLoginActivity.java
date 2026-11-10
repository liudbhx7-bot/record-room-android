package com.recordroom.vinyl;

import android.app.Activity;
import android.graphics.Color;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.webkit.CookieManager;
import android.webkit.SslErrorHandler;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** User-operated official web login. No JS bridge, password access, cookie export or TLS bypass. */
public final class NeteaseWebLoginActivity extends Activity {
    private static final String START="https://music.163.com/";
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private WebView browser;private TextView status;private Button done;private boolean checking,closed,desktop;
    private long nextAutomaticCheck;
    private final Runnable checkCookies=new Runnable(){public void run(){if(closed)return;check(false);main.postDelayed(this,2000);}};
    @Override public void onCreate(Bundle state){
        super.onCreate(state);setResult(RESULT_CANCELED);getWindow().setStatusBarColor(0xfff5f0e8);getWindow().setNavigationBarColor(0xfff5f0e8);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(0xfff5f0e8);setContentView(root);
        root.setOnApplyWindowInsetsListener((v,insets)->{v.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());return insets;});
        LinearLayout bar=new LinearLayout(this);bar.setGravity(Gravity.CENTER_VERTICAL);
        Button back=new Button(this);back.setText("取消");back.setOnClickListener(v->finish());bar.addView(back);
        TextView title=new TextView(this);title.setText("网易云官方网页登录");title.setTextColor(0xff2d2830);title.setTextSize(17);bar.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        Button layout=new Button(this);layout.setText("桌面版");bar.addView(layout);root.addView(bar);
        status=new TextView(this);status.setText("请在官方网页中点“登录”，完成验证后点下方按钮。凭据只保存在本应用中。");status.setTextColor(0xff655b65);status.setTextSize(13);status.setPadding(dp(14),dp(10),dp(14),dp(10));root.addView(status);
        browser=new WebView(this);root.addView(browser,new LinearLayout.LayoutParams(-1,0,1));
        WebSettings settings=browser.getSettings();settings.setJavaScriptEnabled(true);settings.setDomStorageEnabled(true);settings.setAllowFileAccess(false);settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);settings.setSupportMultipleWindows(false);settings.setSaveFormData(false);settings.setUseWideViewPort(true);settings.setLoadWithOverviewMode(true);
        String normalAgent=settings.getUserAgentString();
        layout.setOnClickListener(v->{desktop=!desktop;settings.setUserAgentString(desktop?"Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36":normalAgent);layout.setText(desktop?"手机版":"桌面版");browser.loadUrl(START);});
        CookieManager.getInstance().setAcceptCookie(true);CookieManager.getInstance().setAcceptThirdPartyCookies(browser,false);
        browser.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request){return !allowed(request.getUrl());}
            @Override public boolean shouldOverrideUrlLoading(WebView view,String url){return !allowed(Uri.parse(url));}
            @Override public void onReceivedSslError(WebView view,SslErrorHandler handler,SslError error){handler.cancel();status.setText("官方网页的安全连接未通过，请检查网络和系统时间后重试。");}
            @Override public void onPageFinished(WebView view,String url){if(allowed(Uri.parse(url)))check(false);}
        });
        done=new Button(this);done.setText("我已登录，返回应用");done.setOnClickListener(v->check(true));root.addView(done,new LinearLayout.LayoutParams(-1,dp(54)));
        browser.loadUrl(START);root.requestApplyInsets();main.postDelayed(checkCookies,2000);
    }
    static boolean allowed(Uri uri){String host=uri.getHost();if(!"https".equalsIgnoreCase(uri.getScheme())||host==null||uri.getUserInfo()!=null||(uri.getPort()!=-1&&uri.getPort()!=443))return false;host=host.toLowerCase(java.util.Locale.ROOT);return host.equals("music.163.com")||host.endsWith(".music.163.com")||host.equals("reg.163.com")||host.endsWith(".reg.163.com");}
    private void check(boolean explicit){
        if(checking||closed||browser==null)return;
        if(!explicit&&android.os.SystemClock.elapsedRealtime()<nextAutomaticCheck)return;
        String raw=CookieManager.getInstance().getCookie(START);Map<String,String> values=NeteaseHttp.parseCookies(raw);
        if(values.getOrDefault("MUSIC_U","").isEmpty()){if(explicit)status.setText("尚未检测到登录完成，请先在官方网页完成登录及验证。");return;}
        Map<String,String> needed=new LinkedHashMap<>();for(String key:new String[]{"MUSIC_U","__csrf","MUSIC_A","NMTID"})if(values.containsKey(key))needed.put(key,values.get(key));
        String cookies=NeteaseHttp.cookieHeader(needed);checking=true;done.setEnabled(false);status.setText("正在验证网易云账号…");
        worker.execute(()->{
            try{OnlineMusic.acceptOfficialWebCookies(getApplicationContext(),cookies);main.post(()->{if(closed)return;setResult(RESULT_OK);finish();});}
            catch(ProviderException failure){main.post(()->{if(closed)return;checking=false;nextAutomaticCheck=android.os.SystemClock.elapsedRealtime()+30000;done.setEnabled(true);status.setText(failure.getMessage());});}
        });
    }
    @Override public void onBackPressed(){if(browser!=null&&browser.canGoBack())browser.goBack();else super.onBackPressed();}
    @Override public void onDestroy(){closed=true;main.removeCallbacksAndMessages(null);worker.shutdownNow();if(browser!=null){browser.stopLoading();browser.destroy();browser=null;}
        if(!isChangingConfigurations()){CookieManager manager=CookieManager.getInstance();manager.removeAllCookies(removed->manager.flush());}
        super.onDestroy();}
    private int dp(float value){return Math.round(value*getResources().getDisplayMetrics().density);}
}
