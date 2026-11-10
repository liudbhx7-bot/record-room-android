package com.recordroom.vinyl;

import android.app.Activity;
import android.content.ComponentName;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.net.Uri;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.TextView;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowWebView;
import static org.junit.Assert.*;

/** Offline WebView lifecycle and navigation checks, never a real-login acceptance test. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=34)
@LooperMode(LooperMode.Mode.PAUSED)
public class NeteaseWebLoginTest {
    private ActivityController<NeteaseWebLoginActivity> controller;
    private NeteaseWebLoginActivity activity;
    private WebView browser;

    @Before public void create() throws Exception {
        CookieManager.getInstance().removeAllCookies(null);
        controller=Robolectric.buildActivity(NeteaseWebLoginActivity.class).setup();
        activity=controller.get();browser=(WebView)field(activity,"browser");
    }
    @After public void close() {
        if(controller!=null)controller.pause().stop().destroy();
        CookieManager.getInstance().removeAllCookies(null);
    }
    private static Object field(Object object,String name)throws Exception {
        Field value=object.getClass().getDeclaredField(name);value.setAccessible(true);return value.get(object);
    }
    private static TextView text(View view,String value) {
        if(view instanceof TextView&&value.contentEquals(((TextView)view).getText()))return (TextView)view;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){
            TextView found=text(((ViewGroup)view).getChildAt(i),value);if(found!=null)return found;
        }
        return null;
    }
    private TextView text(String value) {TextView found=text(activity.getWindow().getDecorView(),value);assertNotNull(value,found);return found;}
    private static WebResourceRequest request(final String value) {
        return new WebResourceRequest(){
            public Uri getUrl(){return Uri.parse(value);}
            public boolean isForMainFrame(){return true;}
            public boolean isRedirect(){return false;}
            public boolean hasGesture(){return true;}
            public String getMethod(){return "GET";}
            public Map<String,String> getRequestHeaders(){return Collections.emptyMap();}
        };
    }

    @Test public void startsOfficialHttpsWithRequiredPageFeaturesAndNoLocalAccessOrBridge()throws Exception {
        ShadowWebView shadow=Shadows.shadowOf(browser);WebSettings settings=browser.getSettings();
        assertEquals("https://music.163.com/",shadow.getLastLoadedUrl());
        assertTrue(settings.getJavaScriptEnabled());assertTrue(settings.getDomStorageEnabled());
        assertFalse(settings.getAllowFileAccess());assertFalse(settings.getAllowContentAccess());
        assertEquals(WebSettings.MIXED_CONTENT_NEVER_ALLOW,settings.getMixedContentMode());
        assertFalse(settings.supportMultipleWindows());assertFalse(settings.getSaveFormData());
        assertFalse(CookieManager.getInstance().acceptThirdPartyCookies(browser));
        assertTrue("No native JS interface is registered",((Map<?,?>)field(shadow,"javascriptInterfaces")).isEmpty());
        assertNull("No script reads the official login page",shadow.getLastEvaluatedJavascript());
        assertEquals(Activity.RESULT_CANCELED,Shadows.shadowOf(activity).getResultCode());
    }

    @Test public void bothNavigationCallbacksRestrictSchemeHostUserInfoAndPort() {
        WebViewClient client=Shadows.shadowOf(browser).getWebViewClient();
        for(String url:new String[]{"https://music.163.com/","https://music.163.com:443/login",
                "https://st.music.163.com/page","https://reg.163.com/","https://dl.reg.163.com/login"}){
            assertTrue(url,NeteaseWebLoginActivity.allowed(Uri.parse(url)));
            assertFalse(url,client.shouldOverrideUrlLoading(browser,url));
            assertFalse(url,client.shouldOverrideUrlLoading(browser,request(url)));
        }
        for(String url:new String[]{"http://music.163.com/","file:///sdcard/login.html","content://login",
                "javascript:alert(1)","intent://login","","https://163.com/","https://evil.example/",
                "https://music.163.com.evil.example/","https://evilmusic.163.com/",
                "https://name@music.163.com/","https://music.163.com@evil.example/",
                "https://music.163.com:8443/","https://music.163.com:80/","https://reg.163.com:444/"}){
            assertFalse(url,NeteaseWebLoginActivity.allowed(Uri.parse(url)));
            assertTrue(url,client.shouldOverrideUrlLoading(browser,url));
            assertTrue(url,client.shouldOverrideUrlLoading(browser,request(url)));
        }
    }

    @Test public void buttonWithoutAuthenticatedCookieCannotReportSuccess()throws Exception {
        text("我已登录，返回应用").performClick();
        assertTrue(((TextView)field(activity,"status")).getText().toString().contains("尚未检测到登录完成"));
        assertFalse((Boolean)field(activity,"checking"));assertFalse(activity.isFinishing());
        assertTrue(text("我已登录，返回应用").isEnabled());
        assertEquals(Activity.RESULT_CANCELED,Shadows.shadowOf(activity).getResultCode());
    }

    @Test public void cancelKeepsCanceledResultAndDestroyReleasesWebViewAndWorker()throws Exception {
        ShadowWebView shadow=Shadows.shadowOf(browser);ExecutorService worker=(ExecutorService)field(activity,"worker");
        text("取消").performClick();assertTrue(activity.isFinishing());
        assertEquals(Activity.RESULT_CANCELED,Shadows.shadowOf(activity).getResultCode());
        controller.pause().stop().destroy();controller=null;
        assertTrue(shadow.wasDestroyCalled());assertTrue(worker.isShutdown());assertNull(field(activity,"browser"));
    }

    @Test public void backUsesOfficialPageHistoryBeforeCancelingActivity() {
        ShadowWebView shadow=Shadows.shadowOf(browser);shadow.setCanGoBack(true);
        activity.onBackPressed();assertEquals(1,shadow.getGoBackInvocations());assertFalse(activity.isFinishing());
        shadow.setCanGoBack(false);activity.onBackPressed();assertTrue(activity.isFinishing());
        assertEquals(Activity.RESULT_CANCELED,Shadows.shadowOf(activity).getResultCode());
    }

    @Test public void orientationChangeKeepsCurrentOfficialPageAndWebView()throws Exception {
        ActivityInfo info=activity.getPackageManager().getActivityInfo(new ComponentName(activity,NeteaseWebLoginActivity.class),0);
        int handled=ActivityInfo.CONFIG_ORIENTATION|ActivityInfo.CONFIG_SCREEN_SIZE|ActivityInfo.CONFIG_SMALLEST_SCREEN_SIZE|ActivityInfo.CONFIG_SCREEN_LAYOUT|ActivityInfo.CONFIG_UI_MODE;
        assertEquals(handled,info.configChanges&handled);
        String currentPage="https://reg.163.com/offline-test-login-step";
        browser.loadUrl(currentPage);
        Configuration rotated=new Configuration(activity.getResources().getConfiguration());
        rotated.orientation=rotated.orientation==Configuration.ORIENTATION_LANDSCAPE?Configuration.ORIENTATION_PORTRAIT:Configuration.ORIENTATION_LANDSCAPE;
        controller.configurationChange(rotated);
        assertSame(activity,controller.get());assertSame(browser,field(controller.get(),"browser"));
        assertEquals(currentPage,Shadows.shadowOf(browser).getLastLoadedUrl());
        assertEquals(Activity.RESULT_CANCELED,Shadows.shadowOf(activity).getResultCode());
    }
}
