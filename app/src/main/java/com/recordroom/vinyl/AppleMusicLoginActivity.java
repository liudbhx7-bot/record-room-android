package com.recordroom.vinyl;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.apple.android.sdk.authentication.AuthenticationFactory;
import com.apple.android.sdk.authentication.AuthenticationManager;
import com.apple.android.sdk.authentication.TokenError;
import com.apple.android.sdk.authentication.TokenResult;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

/** Official SDK authorization and developer configuration entry; no token or Apple ID credentials in Intent extras/logs. */
public final class AppleMusicLoginActivity extends Activity {
    private static final int IMPORT_TOKEN=711,AUTHORIZE=712;
    private final Handler main=new Handler(Looper.getMainLooper());private final ExecutorService io=Executors.newSingleThreadExecutor();
    private AppleMusicSessionStore store;private AppleMusicProvider provider;private AuthenticationManager auth;
    private AppleMusicSessionStore.Attempt attempt;private long sequence,expectedGeneration;private boolean closed,busy,pendingAuth;
    private TextView status;private EditText serviceUrl;private Button connect,save,importFile;
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);setResult(RESULT_CANCELED);store=OnlineMusic.appleSession(getApplicationContext());provider=(AppleMusicProvider)OnlineMusic.provider(getApplicationContext(),"applemusic");
        auth=AuthenticationFactory.createAuthenticationManager(this);attempt=new AppleMusicSessionStore.Attempt();
        pendingAuth=saved!=null&&saved.getBoolean("pendingAuth",false);expectedGeneration=saved==null?store.generation():saved.getLong("generation",store.generation());
        getWindow().setStatusBarColor(DesignSystem.PAPER);getWindow().setNavigationBarColor(DesignSystem.PAPER);getWindow().getDecorView().setSystemUiVisibility(android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        android.widget.ScrollView scroll=new android.widget.ScrollView(this);scroll.setFillViewport(true);scroll.setBackgroundColor(DesignSystem.PAPER);LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackground(DesignSystem.paper(this));root.setPadding(dp(22),dp(18),dp(22),dp(24));scroll.addView(root,new android.widget.ScrollView.LayoutParams(-1,-2));setContentView(scroll);
        root.setOnApplyWindowInsetsListener((v,insets)->{v.setPadding(insets.getSystemWindowInsetLeft()+dp(22),insets.getSystemWindowInsetTop()+dp(18),insets.getSystemWindowInsetRight()+dp(22),insets.getSystemWindowInsetBottom()+dp(24));return insets;});
        LinearLayout bar=new LinearLayout(this);bar.setGravity(Gravity.CENTER_VERTICAL);Button cancel=button("‹",false);cancel.setTextSize(29);cancel.setContentDescription("返回");cancel.setBackground(DesignSystem.rounded(this,0xffffffff,24));cancel.setOnClickListener(v->finish());bar.addView(cancel,new LinearLayout.LayoutParams(dp(48),dp(48)));TextView title=text("音乐来源",15,DesignSystem.MUTED);title.setGravity(Gravity.END|Gravity.CENTER_VERTICAL);bar.addView(title,new LinearLayout.LayoutParams(0,dp(48),1));root.addView(bar);
        TextView wordmark=text("APPLE MUSIC",12,DesignSystem.ACCENT);wordmark.setLetterSpacing(.16f);wordmark.setTypeface(android.graphics.Typeface.DEFAULT,android.graphics.Typeface.BOLD);wordmark.setPadding(0,dp(34),0,dp(12));root.addView(wordmark);
        TextView intro=text("把你的资料库\n带进唱片室。",28,DesignSystem.INK);intro.setTypeface(android.graphics.Typeface.DEFAULT,android.graphics.Typeface.BOLD);intro.setLineSpacing(dp(3),1f);root.addView(intro);
        TextView description=text("配置完成后，通过 Apple 官方页面连接账户。\n你的歌曲与歌单将保留真实来源。",15,DesignSystem.MUTED);description.setLineSpacing(dp(5),1f);description.setPadding(0,dp(16),0,dp(26));root.addView(description);
        LinearLayout card=new LinearLayout(this);card.setOrientation(LinearLayout.VERTICAL);card.setPadding(dp(18),dp(20),dp(18),dp(18));card.setBackground(DesignSystem.rounded(this,0xffffffff,24));root.addView(card,new LinearLayout.LayoutParams(-1,-2));
        TextView label=text("开发者配置",17,DesignSystem.INK);label.setTypeface(android.graphics.Typeface.DEFAULT,android.graphics.Typeface.BOLD);card.addView(label);TextView detail=text("导入已签名 JWT，或配置令牌服务。\n私钥留在你的签发服务中。",13,DesignSystem.MUTED);detail.setLineSpacing(dp(3),1f);detail.setPadding(0,dp(8),0,dp(16));card.addView(detail);
        serviceUrl=new EditText(this);serviceUrl.setSingleLine(true);serviceUrl.setTextSize(14);serviceUrl.setHint("HTTPS 令牌服务地址（可选）");serviceUrl.setTextColor(DesignSystem.INK);serviceUrl.setHintTextColor(DesignSystem.MUTED);serviceUrl.setPadding(dp(14),0,dp(14),0);serviceUrl.setBackground(DesignSystem.rounded(this,DesignSystem.PAPER,16));serviceUrl.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_URI);serviceUrl.setText(store.tokenServiceUrl());card.addView(serviceUrl,new LinearLayout.LayoutParams(-1,dp(52)));
        save=button("保存服务地址",false);save.setOnClickListener(v->saveService());addButton(card,save,12);
        importFile=button("从本机文件导入 JWT",false);importFile.setOnClickListener(v->{replaceAttempt();Intent picker=new Intent(Intent.ACTION_OPEN_DOCUMENT);picker.addCategory(Intent.CATEGORY_OPENABLE);picker.setType("*/*");try{startActivityForResult(picker,IMPORT_TOKEN);}catch(Exception missing){message("当前设备没有可用的文件选择器");}});addButton(card,importFile,8);
        connect=button("通过 Apple Music 官方授权",true);connect.setOnClickListener(v->authorize());addButton(root,connect,20);
        status=text("",13,DesignSystem.MUTED);status.setLineSpacing(dp(4),1f);status.setPadding(dp(4),dp(16),dp(4),dp(16));root.addView(status);
        Button reset=button("清除本机授权与令牌",false);reset.setBackgroundColor(android.graphics.Color.TRANSPARENT);reset.setTextColor(DesignSystem.MUTED);reset.setOnClickListener(v->{replaceAttempt();try{store.clearAll();setBusy(false);message("已清除本机令牌，Apple Music 官方账户未退出。");}catch(AppleMusicApi.Failure error){message(error.getMessage());}});addButton(root,reset,0);
        message(pendingAuth?"等待 Apple Music 官方授权结果…":store.getDeveloperToken()==null?"尚未连接 · 开发者令牌未配置\n完成配置后，即可继续官方账户授权。":"尚未连接账户。开发者令牌已就绪，可继续官方授权。");root.requestApplyInsets();setBusy(pendingAuth);
        // Optional packaged configuration contains endpoint/Media ID only, never tokens.
        if(store.tokenServiceUrl().isEmpty())try{AppleMusicConfig config=AppleMusicConfig.load(this);if(!config.tokenServiceUrl.isEmpty()){store.configureTokenService(config.tokenServiceUrl);serviceUrl.setText(config.tokenServiceUrl);}}catch(AppleMusicApi.Failure invalid){message(invalid.getMessage());}
    }
    private void replaceAttempt(){if(attempt!=null)attempt.cancel();attempt=new AppleMusicSessionStore.Attempt();sequence++;pendingAuth=false;}
    private void setBusy(boolean value){busy=value;connect.setEnabled(!value);save.setEnabled(!value);importFile.setEnabled(!value);serviceUrl.setEnabled(!value);for(Button button:new Button[]{connect,save,importFile})button.setAlpha(value?.55f:1f);}
    private TextView text(String value,float size,int color){TextView view=new TextView(this);view.setText(value);view.setTextSize(size);view.setTextColor(color);return view;}
    private Button button(String label,boolean primary){Button button=new Button(this);button.setText(label);button.setAllCaps(false);button.setTextSize(14);button.setTypeface(android.graphics.Typeface.DEFAULT,android.graphics.Typeface.BOLD);button.setTextColor(primary?0xffffffff:DesignSystem.INK);button.setBackground(DesignSystem.rounded(this,primary?DesignSystem.INK:DesignSystem.LILAC,26));button.setStateListAnimator(null);button.setMinHeight(0);button.setMinimumHeight(0);button.setMinWidth(0);button.setMinimumWidth(0);button.setPadding(dp(12),0,dp(12),0);return button;}
    private void addButton(LinearLayout parent,Button button,int margin){LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,dp(52));params.topMargin=dp(margin);parent.addView(button,params);}
    private void message(String text){if(status!=null&&!closed)status.setText(text);}
    private void saveService(){
        if(busy)return;replaceAttempt();String value=serviceUrl.getText().toString().trim();try{store.configureTokenService(value);message(value.isEmpty()?"已清除服务地址，可使用本机 JWT 文件":"已保存服务地址并清除旧连接。授权时将请求该服务提供已签名 JWT。");}catch(AppleMusicApi.Failure error){message(error.getMessage());}
    }
    private void authorize(){
        if(busy||closed)return;replaceAttempt();final long ticket=sequence;final AppleMusicSessionStore.Attempt gate=attempt;setBusy(true);message("正在准备开发者授权…");
        io.execute(()->{try{String developer=store.prepareDeveloperToken(gate);long epoch=store.generation();main.post(()->{
            if(closed||ticket!=sequence)return;try{expectedGeneration=epoch;pendingAuth=true;Intent intent=auth.createIntentBuilder(developer).setHideStartScreen(false).setStartScreenMessage("授权唱片室访问你的 Apple Music 资料库，并通过官方播放引擎播放音乐").build();startActivityForResult(intent,AUTHORIZE);message("请在 Apple Music 官方页面完成授权…");}catch(Exception error){pendingAuth=false;setBusy(false);message("无法启动 Apple Music 官方授权，请检查 Apple Music 安装状态");}
        });}catch(AppleMusicApi.Failure error){main.post(()->{if(closed||ticket!=sequence)return;setBusy(false);message(error.getMessage());});}});
    }
    @Override protected void onActivityResult(int request,int resultCode,Intent data){
        super.onActivityResult(request,resultCode,data);if(closed)return;
        if(request==IMPORT_TOKEN){if(resultCode!=RESULT_OK||data==null||data.getData()==null){message("已取消文件导入");return;}importToken(data.getData());return;}
        if(request!=AUTHORIZE||!pendingAuth)return;pendingAuth=false;
        if(resultCode!=RESULT_OK||data==null){setBusy(false);message("已取消 Apple Music 授权");return;}
        TokenResult result;try{result=auth.handleTokenResult(data);}catch(Exception invalid){setBusy(false);message("Apple Music 授权结果无法读取，请重试");return;}
        if(result==null||result.isError()){setBusy(false);message(authError(result==null?TokenError.UNKNOWN:result.getError()));return;}
        final long ticket=sequence,expected=expectedGeneration;final AppleMusicSessionStore.Attempt gate=attempt;
        final String user=result.getMusicUserToken(),sf=result.getStorefrontId(),encoded=result.getAppleMusicStorefrontId();message("正在核验 Apple Music 资料库授权…");
        io.execute(()->{try{provider.acceptAuthorization(user,sf,encoded,expected,gate);main.post(()->{if(closed||ticket!=sequence)return;setResult(RESULT_OK);finish();});}catch(ProviderException failure){main.post(()->{if(closed||ticket!=sequence)return;setBusy(false);message(failure.getMessage());});}});
    }
    private void importToken(Uri uri){
        replaceAttempt();final long ticket=sequence,expected=store.generation();final AppleMusicSessionStore.Attempt gate=attempt;setBusy(true);message("正在读取所选开发者 JWT…");
        io.execute(()->{try(InputStream input=getContentResolver().openInputStream(uri)){
            if(input==null)throw new IllegalArgumentException();ByteArrayOutputStream output=new ByteArrayOutputStream();byte[] buffer=new byte[4096];int count;while((count=input.read(buffer))!=-1){if(output.size()+count>49152)throw new IllegalArgumentException();output.write(buffer,0,count);}
            String text=new String(output.toByteArray(),StandardCharsets.UTF_8).trim();if(text.startsWith("\uFEFF"))text=text.substring(1).trim();String token=text.startsWith("{")?new JSONObject(text).optString("developerToken",""):text;
            store.importDeveloperToken(token,expected,gate);main.post(()->{if(closed||ticket!=sequence)return;setBusy(false);message("开发者 JWT 已加密保存。下一步通过 Apple Music 官方授权账户。");});
        }catch(AppleMusicApi.Failure error){main.post(()->{if(closed||ticket!=sequence)return;setBusy(false);message(error.getMessage());});}catch(Exception error){main.post(()->{if(closed||ticket!=sequence)return;setBusy(false);message("无法读取所选 JWT。请选择纯文本 JWT 或含 developerToken 字段的 JSON，勿选择私钥文件。");});}});
    }
    static String authError(TokenError error){if(error==TokenError.USER_CANCELLED)return "已取消 Apple Music 授权";if(error==TokenError.NO_SUBSCRIPTION)return "此账户尚无可用的 Apple Music 订阅";if(error==TokenError.SUBSCRIPTION_EXPIRED)return "Apple Music 订阅已过期，请在官方页面续订后重试";return "Apple Music 暂未完成账户授权，请稍后重试";}
    @Override protected void onSaveInstanceState(Bundle out){out.putBoolean("pendingAuth",pendingAuth);out.putLong("generation",expectedGeneration);super.onSaveInstanceState(out);}
    @Override public void finish(){closed=true;if(attempt!=null)attempt.cancel();super.finish();}
    @Override protected void onDestroy(){closed=true;if(attempt!=null)attempt.cancel();main.removeCallbacksAndMessages(null);io.shutdownNow();super.onDestroy();}
    private int dp(float value){return Math.round(value*getResources().getDisplayMetrics().density);}
}
