package com.recordroom.vinyl;

import android.app.Activity;
import android.app.Dialog;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.webkit.CookieManager;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** User-operated official QQ/WeChat login; only QQ Music's own session is verified and stored. */
public final class QQMusicLoginActivity extends Activity {
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final QQProvider.LoginAttempt loginAttempt=new QQProvider.LoginAttempt();
    private final ArrayList<WebView> browsers=new ArrayList<>();
    private final ArrayList<Dialog> popups=new ArrayList<>();
    private WebView browser;private TextView status;private Button done;
    private boolean checking,closed;private long retryAt;
    private final Runnable poll=new Runnable(){public void run(){if(!closed){check(false);main.postDelayed(this,2200);}}};
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);setResult(RESULT_CANCELED);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackground(DesignSystem.paper(this));setContentView(root);
        root.setOnApplyWindowInsetsListener((v,insets)->{v.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());return insets;});
        Button back=new Button(this);back.setText("取消 QQ 音乐登录");back.setOnClickListener(v->finish());root.addView(back);
        status=new TextView(this);status.setText("请在 QQ 音乐官方网页登录。账号由官方验证；唱片机不会读取密码。");status.setTextColor(DesignSystem.MUTED);status.setTextSize(13);status.setPadding(16,12,16,12);root.addView(status);
        browser=createBrowser();root.addView(browser,new LinearLayout.LayoutParams(-1,0,1));
        done=new Button(this);done.setText("我已登录，验证并返回");done.setOnClickListener(v->check(true));root.addView(done);
        browser.loadUrl(QQProvider.officialLoginUrl());root.requestApplyInsets();main.postDelayed(poll,2200);
    }
    static boolean allowed(Uri uri){
        if(!"https".equalsIgnoreCase(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null||(uri.getPort()!=-1&&uri.getPort()!=443))return false;
        String host=uri.getHost().toLowerCase(java.util.Locale.ROOT);
        return host.equals("y.qq.com")||host.equals("c.y.qq.com")||host.equals("u.y.qq.com")||host.equals("xui.ptlogin2.qq.com")||host.equals("ssl.ptlogin2.qq.com")||host.equals("ptlogin2.qq.com")||host.equals("graph.qq.com")||host.equals("open.weixin.qq.com");
    }
    private WebView createBrowser(){
        WebView web=new WebView(this);browsers.add(web);WebSettings settings=web.getSettings();settings.setJavaScriptEnabled(true);settings.setDomStorageEnabled(true);settings.setAllowFileAccess(false);settings.setAllowContentAccess(false);settings.setSaveFormData(false);settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);settings.setSupportMultipleWindows(true);settings.setJavaScriptCanOpenWindowsAutomatically(false);settings.setUseWideViewPort(true);settings.setLoadWithOverviewMode(true);
        CookieManager.getInstance().setAcceptCookie(true);CookieManager.getInstance().setAcceptThirdPartyCookies(web,true);
        web.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request){return !allowed(request.getUrl());}
            @Override public boolean shouldOverrideUrlLoading(WebView view,String url){return !allowed(Uri.parse(url));}
            @Override public void onReceivedSslError(WebView view,SslErrorHandler handler,SslError error){handler.cancel();status.setText("官方登录的安全连接未通过，请检查网络后重试。");}
            @Override public void onPageFinished(WebView view,String url){if(allowed(Uri.parse(url)))check(false);}
        });
        web.setWebChromeClient(new WebChromeClient(){
            @Override public boolean onCreateWindow(WebView view,boolean dialog,boolean gesture,Message result){
                if(!gesture||closed)return false;
                WebView child=createBrowser();Dialog popup=new Dialog(QQMusicLoginActivity.this);popups.add(popup);child.setTag(popup);popup.setContentView(child);popup.setOnDismissListener(d->{popups.remove(popup);browsers.remove(child);child.stopLoading();child.destroy();});popup.show();popup.getWindow().setLayout(-1,-1);
                ((WebView.WebViewTransport)result.obj).setWebView(child);result.sendToTarget();return true;
            }
            @Override public void onCloseWindow(WebView window){if(window.getTag() instanceof Dialog)((Dialog)window.getTag()).dismiss();}
        });return web;
    }
    private String musicCookies(){
        CookieManager manager=CookieManager.getInstance();
        return selectMusicCookies(manager.getCookie("https://y.qq.com/"),manager.getCookie("https://c.y.qq.com/"),manager.getCookie("https://u.y.qq.com/"));
    }
    /** One coherent official origin snapshot; never combine a new WeChat identity with another host's old QQ key. */
    static String selectMusicCookies(String... snapshots){
        for(String snapshot:snapshots){Map<String,String> values=NeteaseHttp.parseCookies(snapshot);
            for(String name:new String[]{"qm_keyst","qqmusic_key","music_key","wxskey","wxopenid","wxuin"})if(!values.getOrDefault(name,"").isEmpty())return snapshot;
        }
        return "";
    }
    private void check(boolean explicit){
        if(checking||closed||done==null||(!explicit&&android.os.SystemClock.elapsedRealtime()<retryAt))return;
        String cookies=musicCookies();Map<String,String> values=NeteaseHttp.parseCookies(cookies);
        if(values.getOrDefault("qm_keyst","").isEmpty()&&values.getOrDefault("music_key","").isEmpty()&&values.getOrDefault("qqmusic_key","").isEmpty()&&values.getOrDefault("wxskey","").isEmpty()){
            if(explicit){status.setText("请先完成官方登录，再打开网页播放器完成音乐授权。");browser.loadUrl("https://y.qq.com/n/ryqq/player");}return;
        }
        checking=true;done.setEnabled(false);status.setText("正在验证 QQ 音乐账号与音乐授权…");
        worker.execute(()->{try{((QQProvider)OnlineMusic.provider(getApplicationContext(),"qq")).acceptOfficialWebCookies(cookies,loginAttempt);main.post(()->{if(!closed){setResult(RESULT_OK);finish();}});}catch(ProviderException error){main.post(()->{if(closed)return;checking=false;retryAt=android.os.SystemClock.elapsedRealtime()+15000;done.setEnabled(true);status.setText(error.getMessage());});}});
    }
    @Override public void onBackPressed(){if(browser!=null&&browser.canGoBack())browser.goBack();else super.onBackPressed();}
    @Override public void finish(){loginAttempt.cancel();closed=true;super.finish();}
    @Override protected void onDestroy(){loginAttempt.cancel();closed=true;main.removeCallbacksAndMessages(null);worker.shutdownNow();for(Dialog popup:new ArrayList<>(popups))popup.dismiss();for(WebView web:new ArrayList<>(browsers)){web.stopLoading();web.destroy();}browsers.clear();if(!isChangingConfigurations())CookieManager.getInstance().removeAllCookies(removed->CookieManager.getInstance().flush());super.onDestroy();}
}
