package com.recordroom.vinyl;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import static org.junit.Assert.*;

/** Real page and drawable pixels; session availability is an explicit offline platform fixture. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=34,qualifiers="w412dp-h860dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE) @LooperMode(LooperMode.Mode.PAUSED)
public class MusicSourcesThemeTest {
    ActivityController<Activity> owner;Activity activity;MusicSourcesPage page;ExternalMusicBridge bridge;
    final ArrayList<Bitmap> bitmaps=new ArrayList<>();final ArrayList<String> actions=new ArrayList<>();
    final Access access=new Access();
    static final class Access implements ExternalMusicBridge.Access {
        boolean granted,connected,fail;boolean installed=true;
        public boolean hasAccess(){return granted;}public boolean isConnected(){return connected;}
        public boolean isInstalled(String name){return installed;}
        public void start(Runnable changed){}public void stop(){}public void stopSessionWatch(){}
        public List<ExternalMusicBridge.Session> activeSessions(){if(fail)throw new IllegalStateException("Offline system-session error fixture");return Collections.emptyList();}
        public Intent launchIntent(String name){return null;}
    }
    @Before public void setup(){
        owner=Robolectric.buildActivity(Activity.class).setup().visible();activity=owner.get();
        DesignSystem.setAlbumTheme(activity,"red-fixture",0xff59282e,0xffc75059);
        page=new MusicSourcesPage(activity,new MusicSourcesPage.Actions(){
            public void back(){actions.add("back");}public void local(){actions.add("local");}public void netease(){actions.add("netease");}public void authorize(){actions.add("authorize");}
            public void direct(String id){actions.add("direct:"+id);}public void select(String id){actions.add("select:"+id);}public void open(String id){actions.add("open:"+id);}
        });activity.setContentView(page);layout(page,412,860);
        bridge=new ExternalMusicBridge(access,new Handler(Looper.getMainLooper()),page::update);bridge.selectProvider("qishui_music");bridge.start();
    }
    @After public void cleanup(){bridge.close();owner.pause().stop().destroy();for(Bitmap bitmap:bitmaps)if(!bitmap.isRecycled())bitmap.recycle();}
    static View find(View root,String label){
        if(label.contentEquals(root.getContentDescription()==null?"":root.getContentDescription())||root instanceof TextView&&label.contentEquals(((TextView)root).getText()))return root;
        if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){View match=find(((ViewGroup)root).getChildAt(i),label);if(match!=null)return match;}return null;
    }
    static void layout(View view,int width,int height){view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));view.layout(0,0,width,height);}
    Bitmap render(View view,String name)throws Exception{
        Bitmap bitmap=Bitmap.createBitmap(view.getWidth(),view.getHeight(),Bitmap.Config.ARGB_8888);bitmaps.add(bitmap);Canvas canvas=new Canvas(bitmap);canvas.drawColor(DesignSystem.ambientColor(activity));view.draw(canvas);
        if(name!=null){File file=new File(System.getProperty("vinyl.output.dir"),name);file.getParentFile().mkdirs();try(FileOutputStream stream=new FileOutputStream(file)){assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,stream));}}return bitmap;
    }
    Bitmap renderDrawable(Drawable drawable){Bitmap bitmap=Bitmap.createBitmap(120,120,Bitmap.Config.ARGB_8888);bitmaps.add(bitmap);android.graphics.Rect previous=new android.graphics.Rect(drawable.getBounds());try{drawable.setBounds(0,0,120,120);drawable.draw(new Canvas(bitmap));return bitmap;}finally{drawable.setBounds(previous);}}
    @Test public void existingSourcesPageChangesItsRealPixelsWithoutRebuildingOrLosingState()throws Exception{
        Drawable background=page.getBackground();View card=(View)find(page,"唱片机资料库").getParent();Drawable cardFill=card.getBackground();View permission=find(page,"开启播放联动");Drawable buttonFill=permission.getBackground();ScrollView scroll=(ScrollView)page.getChildAt(0);
        Bitmap red=render(page,"music-sources-red-top-api34.png");assertEquals(DesignSystem.ambientColor(activity),red.getPixel(2,400));assertTrue(Color.luminance(red.getPixel(2,400))<.10);
        int redCard=renderDrawable(cardFill).getPixel(60,60),redButton=renderDrawable(buttonFill).getPixel(60,60);
        access.granted=access.connected=true;access.fail=true;bridge.refresh();scroll.scrollTo(0,400);assertEquals(400,scroll.getScrollY());
        String status=bridge.getState().statusMessage;assertNotNull(find(page,status));
        DesignSystem.setAlbumTheme(activity,"blue-fixture",0xff244c67,0xff438fd2);
        assertSame(background,page.getBackground());assertSame(cardFill,card.getBackground());assertSame(buttonFill,permission.getBackground());assertSame(permission,find(page,"管理联动权限"));assertEquals(400,scroll.getScrollY());assertNotNull(find(page,status));
        Bitmap blueScrolled=render(page,"music-sources-blue-error-api34.png");assertEquals(DesignSystem.ambientColor(activity),blueScrolled.getPixel(2,400));assertNotEquals(red.getPixel(2,400),blueScrolled.getPixel(2,400));
        assertNotEquals(redCard,renderDrawable(cardFill).getPixel(60,60));assertNotEquals(redButton,renderDrawable(buttonFill).getPixel(60,60));
        scroll.scrollTo(0,0);render(page,"music-sources-blue-top-api34.png");render(scroll.getChildAt(0),"music-sources-blue-full-api34.png");
    }
    @Test public void everyActionAndPermissionErrorStateSurvivesThemeUpdates(){
        for(String label:new String[]{"关闭音乐来源","切回资料库","网易云音乐","QQ 音乐 · 账号与曲库","Apple Music · 账号与曲库","开启播放联动"}){View control=find(page,label);assertNotNull(label,control);control.performClick();}
        for(ExternalMusicBridge.Provider provider:ExternalMusicBridge.providers()){
            String label=provider.name+" · "+(provider.id.equals("qishui_music")?"正在使用":"联动播放");
            View select=find(page,label);assertNotNull(label,select);select.performClick();
            ViewGroup controls=(ViewGroup)select.getParent();find(controls,"打开官方 App").performClick();
        }
        assertEquals(Arrays.asList("back","local","netease","direct:qq","direct:applemusic","authorize","select:qq_music","open:qq_music","select:qishui_music","open:qishui_music","select:apple_music","open:apple_music","select:netease_music","open:netease_music"),actions);
        assertNotNull(find(page,"先开启播放联动权限"));access.granted=true;access.connected=false;bridge.refresh();assertNotNull(find(page,"已获授权，等待系统连接媒体会话服务"));
        DesignSystem.setAlbumTheme(activity,"gray-fixture",0xff444444,0xff999999);access.connected=true;access.fail=true;bridge.refresh();assertNotNull(find(page,bridge.getState().statusMessage));assertFalse(bridge.getState().connected);
        access.installed=false;bridge.refresh();ViewGroup panel=(ViewGroup)find(page,"汽水音乐").getParent();assertFalse(find(panel,"正在使用").isEnabled());assertNotNull(find(panel,"未检测到汽水音乐，请先安装官方 App"));
    }
    @Test public void darkSurfacesStayFlatAndReadableForColoredNeutralAndLightManualThemes(){
        for(int color:new int[]{0xff59282e,0xff244c67,0xff444444,Color.WHITE}){
            DesignSystem.setAlbumTheme(activity,"contrast-fixture",color,color);
            for(Drawable drawable:new Drawable[]{DesignSystem.ambient(activity),DesignSystem.settingsCard(activity,20),DesignSystem.ambientButton(activity,16)}){
                Bitmap image=renderDrawable(drawable);int fill=image.getPixel(60,60);assertEquals("No metallic diagonal highlight",fill,image.getPixel(25,25));assertEquals(fill,image.getPixel(94,94));
                assertTrue("Primary text keeps WCAG AA contrast",contrast(DesignSystem.onAmbient,fill)>=4.5);
            }
            assertTrue("Descriptions remain readable on cards",contrast(DesignSystem.onAmbientMuted,DesignSystem.settingsCardColor(activity))>=4.5);
        }
    }
    @Test @Config(qualifiers="w320dp-h480dp-mdpi",fontScale=1.2f) public void narrowPageKeepsSansTextAnd48dpActionTargets()throws Exception{
        layout(page,320,480);checkText(page);render(page,"music-sources-narrow-320x480-api34.png");
    }
    private void checkText(View view){if(view instanceof TextView){TextView text=(TextView)view;assertTrue(text.getTextSize()>=14*activity.getResources().getDisplayMetrics().scaledDensity-.1f);assertEquals(Typeface.create("sans-serif",text.getTypeface().getStyle()),text.getTypeface());if(view instanceof Button){assertTrue(text.getWidth()>=48);assertTrue(text.getHeight()>=48);assertTrue(text.getLineCount()*text.getLineHeight()<=text.getHeight());}}if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)checkText(((ViewGroup)view).getChildAt(i));}
    private static double contrast(int text,int fill){double a=Color.luminance(text),b=Color.luminance(fill);return (Math.max(a,b)+.05)/(Math.min(a,b)+.05);}
}
