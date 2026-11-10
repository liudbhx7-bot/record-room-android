package com.recordroom.vinyl;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Looper;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import com.apple.android.sdk.authentication.AuthenticationFactory;
import com.apple.android.sdk.authentication.AuthenticationManager;
import com.apple.android.sdk.authentication.AuthIntentBuilder;
import com.apple.android.sdk.authentication.TokenProvider;
import com.apple.android.sdk.authentication.TokenRefreshListener;
import com.apple.android.sdk.authentication.TokenResult;
import com.apple.android.sdk.authentication.StartAuthenticationActivity;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=34,qualifiers="w412dp-h860dp-mdpi",fontScale=1f)
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class AppleMusicLoginLifecycleTest {
    static Object field(Object o,String name)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}
    static void set(Object o,String name,Object value)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);f.set(o,value);}
    static final class Auth implements AuthenticationManager{
        int starts,results;
        public AuthIntentBuilder createIntentBuilder(String token){starts++;return null;}
        public TokenResult handleTokenResult(Intent data){results++;return null;}
        public void refreshTokenAsync(TokenProvider provider,TokenRefreshListener listener){}
    }
    @Test public void finishingBeforeQueuedPrepareCallbackCannotLaunchSdkAuth()throws Exception{
        ActivityController<AppleMusicLoginActivity> controller=Robolectric.buildActivity(AppleMusicLoginActivity.class).create();AppleMusicLoginActivity activity=controller.get();
        AppleMusicSessionStore store=AppleMusicIntegrationTest.store(AppleMusicIntegrationTest.prefs());store.importDeveloperToken(AppleMusicIntegrationTest.jwt());set(activity,"store",store);Auth auth=new Auth();set(activity,"auth",auth);
        Method start=AppleMusicLoginActivity.class.getDeclaredMethod("authorize");start.setAccessible(true);start.invoke(activity);
        CountDownLatch posted=new CountDownLatch(1);((ExecutorService)field(activity,"io")).execute(posted::countDown);assertTrue(posted.await(3,TimeUnit.SECONDS));
        activity.finish();Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals(0,auth.starts);controller.destroy();
    }
    @Test public void restoredPendingResultDisablesNewAttemptAndCanceledDataNeverReachesSdkParser()throws Exception{
        Bundle saved=new Bundle();saved.putBoolean("pendingAuth",true);saved.putLong("generation",0);
        ActivityController<AppleMusicLoginActivity> controller=Robolectric.buildActivity(AppleMusicLoginActivity.class).create(saved);AppleMusicLoginActivity activity=controller.get();Auth auth=new Auth();set(activity,"auth",auth);
        assertFalse(((Button)field(activity,"connect")).isEnabled());assertFalse(((Button)field(activity,"importFile")).isEnabled());
        activity.onActivityResult(712,Activity.RESULT_CANCELED,new Intent("synthetic-canceled-data"));assertEquals(0,auth.results);assertTrue(((Button)field(activity,"connect")).isEnabled());controller.destroy();
    }
    @Test public void officialSdkConsentInflatesFromMergedResourcesWithoutLaunchingLogin()throws Exception{
        android.content.Context context=org.robolectric.RuntimeEnvironment.getApplication();String prompt="连接你的 Apple Music 资料库";
        Intent intent=AuthenticationFactory.createAuthenticationManager(context).createIntentBuilder(AppleMusicIntegrationTest.jwt()).setHideStartScreen(false).setStartScreenMessage(prompt).build();
        assertEquals(StartAuthenticationActivity.class.getName(),intent.getComponent().getClassName());
        ActivityController<StartAuthenticationActivity> controller=Robolectric.buildActivity(StartAuthenticationActivity.class,intent).setup().visible();
        StartAuthenticationActivity activity=controller.get();int promptId=activity.getResources().getIdentifier("access_request_text1","id",activity.getPackageName()),buttonId=activity.getResources().getIdentifier("btn1","id",activity.getPackageName());
        assertNotEquals(0,promptId);assertEquals(prompt,((TextView)activity.findViewById(promptId)).getText().toString());assertTrue(activity.findViewById(buttonId) instanceof Button);assertFalse(((Button)activity.findViewById(buttonId)).getText().toString().isEmpty());
        assertNull("Consent page must not start Apple Music or Play Store before user action",Shadows.shadowOf(activity).getNextStartedActivity());
        snapshot(activity.getWindow().getDecorView(),"apple-sdk-consent-native.png");controller.pause().stop().destroy();
    }
    @Test public void unconfiguredEntryRendersWithoutClaimingConnected()throws Exception{
        ActivityController<AppleMusicLoginActivity> controller=Robolectric.buildActivity(AppleMusicLoginActivity.class).setup().visible();AppleMusicLoginActivity activity=controller.get();
        assertTrue(((TextView)field(activity,"status")).getText().toString().contains("尚未连接"));assertTrue(((Button)field(activity,"connect")).isEnabled());
        snapshot(activity.getWindow().getDecorView(),"apple-configuration-native.png");controller.pause().stop().destroy();
    }
    @Test public void sdkResultDoesNotWriteMusicUserTokenToAndroidLog()throws Exception{
        android.content.Context context=org.robolectric.RuntimeEnvironment.getApplication();Intent intent=AuthenticationFactory.createAuthenticationManager(context).createIntentBuilder(AppleMusicIntegrationTest.jwt()).setHideStartScreen(false).build();
        ActivityController<StartAuthenticationActivity> controller=Robolectric.buildActivity(StartAuthenticationActivity.class,intent).setup();StartAuthenticationActivity activity=controller.get();
        String token="fixture-only-not-a-real-music-user-token";org.robolectric.shadows.ShadowLog.clear();activity.onActivityResult(2021,Activity.RESULT_OK,new Intent().putExtra("music_user_token",token));
        for(org.robolectric.shadows.ShadowLog.LogItem item:org.robolectric.shadows.ShadowLog.getLogs())assertFalse("MusicKit must not emit the user token to logcat",item.msg!=null&&item.msg.contains(token));controller.pause().stop().destroy();
    }
    private static void snapshot(View view,String name)throws Exception{
        view.measure(View.MeasureSpec.makeMeasureSpec(412,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(860,View.MeasureSpec.EXACTLY));view.layout(0,0,412,860);
        Bitmap bitmap=Bitmap.createBitmap(412,860,Bitmap.Config.ARGB_8888);try{view.draw(new Canvas(bitmap));File output=new File(System.getProperty("vinyl.output.dir"));output.mkdirs();try(FileOutputStream stream=new FileOutputStream(new File(output,name))){assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,stream));}}finally{bitmap.recycle();}
    }
}
