package com.recordroom.vinyl;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.Application;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowActivity;
import org.robolectric.shadows.ShadowAlertDialog;
import static org.junit.Assert.*;

/** Real native theme/interaction evidence; coloured squares are explicit palette fixtures. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=34,qualifiers="w412dp-h860dp-mdpi",fontScale=1f)
@GraphicsMode(GraphicsMode.Mode.NATIVE) @LooperMode(LooperMode.Mode.PAUSED)
public class UiTheme073Test {
    ActivityController<MainActivity> controller;ServiceController<PlayerService> serviceController;
    MainActivity activity;PlayerService service;final List<Bitmap> images=new ArrayList<>();
    @After public void cleanup(){if(controller!=null)controller.pause().stop().destroy();if(serviceController!=null)serviceController.destroy();Shadows.shadowOf(Looper.getMainLooper()).idle();for(Bitmap b:images)if(!b.isRecycled())b.recycle();OnlineMusic.useProviderForTests(RuntimeEnvironment.getApplication(),null,null);}
    static Object field(Object target,String name)throws Exception{for(Class<?> type=target.getClass();type!=null;type=type.getSuperclass())try{Field f=type.getDeclaredField(name);f.setAccessible(true);return f.get(target);}catch(NoSuchFieldException ignored){}throw new NoSuchFieldException(name);}
    void call(String name,Class<?>[] types,Object... args)throws Exception{Method m=MainActivity.class.getDeclaredMethod(name,types);m.setAccessible(true);m.invoke(activity,args);}
    void startMain()throws Exception{
        Application app=RuntimeEnvironment.getApplication();app.getSharedPreferences(PlayerService.PREFS,0).edit().clear().commit();new ThemeStore(app).needleSound(false);
        serviceController=Robolectric.buildService(PlayerService.class).create();service=serviceController.get();
        Shadows.shadowOf(app).setComponentNameAndServiceForBindService(new ComponentName(app,PlayerService.class),service.onBind(new Intent(app,PlayerService.class)));
        controller=Robolectric.buildActivity(MainActivity.class).setup().visible();activity=controller.get();drain();
        assertNotNull(service.getCurrent());
    }
    void drain()throws Exception{for(int i=0;i<3;i++){((ExecutorService)field(activity,"io")).submit(()->{}).get(10,TimeUnit.SECONDS);CoverView.IO.submit(()->{}).get(10,TimeUnit.SECONDS);Shadows.shadowOf(Looper.getMainLooper()).idle();}}
    static View described(View view,String label){if(label.contentEquals(view.getContentDescription()==null?"":view.getContentDescription()))return view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){View match=described(((ViewGroup)view).getChildAt(i),label);if(match!=null)return match;}return null;}
    static View text(View view,String label){if(view instanceof TextView&&label.contentEquals(((TextView)view).getText()))return view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){View match=text(((ViewGroup)view).getChildAt(i),label);if(match!=null)return match;}return null;}
    static <T> T first(View view,Class<T> type){if(type.isInstance(view))return type.cast(view);if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){T match=first(((ViewGroup)view).getChildAt(i),type);if(match!=null)return match;}return null;}
    static void layout(View view,int w,int h){view.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));view.layout(0,0,w,h);}
    Bitmap render(View view,int w,int h,String name)throws Exception{layout(view,w,h);Bitmap bitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);images.add(bitmap);view.draw(new Canvas(bitmap));if(name!=null)save(bitmap,name);return bitmap;}
    void save(Bitmap bitmap,String name)throws Exception{File out=new File(System.getProperty("vinyl.output.dir"),name);out.getParentFile().mkdirs();try(FileOutputStream stream=new FileOutputStream(out)){assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,stream));}}
    Bitmap solid(int color){Bitmap b=Bitmap.createBitmap(80,80,Bitmap.Config.ARGB_8888);b.eraseColor(color);images.add(b);return b;}
    void palette(Bitmap bitmap)throws Exception{ThemeStore themes=(ThemeStore)field(activity,"themes");themes.followCoverColors(true);themes.coverPalette(service.getCurrent().albumKey(),AlbumPalette.from(bitmap));Field current=MainActivity.class.getDeclaredField("currentArtwork");current.setAccessible(true);current.set(activity,bitmap);call("applyAppearance",new Class<?>[]{boolean.class},false);Shadows.shadowOf(Looper.getMainLooper()).idle();}
    SettingsPage settings()throws Exception{call("showSettings",new Class<?>[0]);SettingsPage page=(SettingsPage)field(activity,"settingsPage");layout(page,412,860);return page;}

    @Test public void groupedActionsAndAllSwitchesCallTheirRealHandlersAndPersist(){
        Application context=RuntimeEnvironment.getApplication();ThemeStore themes=new ThemeStore(context);List<String> destinations=new ArrayList<>();int[] changes={0};
        SettingsPage page=new SettingsPage(context,themes,new SettingsPage.Actions(){public void open(String value){destinations.add(value);}public void changed(){changes[0]++;}});
        String[][] rows={{"关闭设置","back"},{"导入本地音乐","import"},{"音乐账户与联动","accounts"},{"搜索音乐","search"},{"唱片材质与背景","appearance"},{"桌面小组件","widgets"},{"播放模式","mode"},{"播放音量","volume"},{"睡眠定时","sleep"},{"操作指南","help"},{"开源许可","licenses"}};
        for(String[] row:rows){View control=described(page,row[0]);assertNotNull(row[0],control);assertTrue(control.performClick());assertEquals(row[1],destinations.get(destinations.size()-1));}
        String[] toggles={"随封面自动配色","唱片与唱臂动画","播放时自动收起工具","保持屏幕常亮","落针音效"};boolean[] before={themes.followCoverColors(),themes.motion(),themes.autoHide(),themes.keepAwake(),themes.needleSound()};
        for(String name:toggles){Switch control=(Switch)described(page,name);assertNotNull(control);((View)control.getParent()).performClick();}
        ThemeStore persisted=new ThemeStore(context);boolean[] after={persisted.followCoverColors(),persisted.motion(),persisted.autoHide(),persisted.keepAwake(),persisted.needleSound()};for(int i=0;i<before.length;i++)assertEquals(toggles[i],!before[i],after[i]);assertEquals(4,changes[0]);
        for(String heading:new String[]{"音乐与连接","唱机外观","聆听体验","关于唱片机"})assertNotNull(text(page,heading));
    }

    @Test public void thumbDrawerOpensAggregateSearchDirectlyWithNoSourceChooser()throws Exception{
        startMain();
        View main=activity.getWindow().getDecorView();
        assertNull(described(main,"搜索在线曲库"));
        View trigger=described(main,"打开播放工具");
        assertNotNull(trigger);
        trigger.performClick();
        Dialog drawer=(Dialog)field(activity,"toolDrawer");
        assertTrue(drawer.isShowing());
        View panel=drawer.getWindow().getDecorView();
        for(String action:new String[]{"搜索","资料库","队列","歌词","外观","设置","查看音质与输出","更多歌曲操作","收起工具"})assertNotNull(action,described(panel,action));
        View search=described(panel,"搜索");
        search.performClick();AggregateSearchPage aggregate=(AggregateSearchPage)field(activity,"aggregatePage");assertNotNull(aggregate);assertNull("Search must not insert a platform chooser",field(activity,"sourcesDialog"));Dialog dialog=(Dialog)field(aggregate,"dialog");assertTrue(dialog.isShowing());assertNotNull(text(dialog.getWindow().getDecorView(),"搜索音乐"));assertFalse(((AggregateSearch)field(aggregate,"engine")).state().loading);
    }

    @Test public void mainSettingsRoutesFilePickerWidgetModeAndSleepControls()throws Exception{
        startMain();SettingsPage page=settings();described(page,"导入本地音乐").performClick();ShadowActivity.IntentForResult file=Shadows.shadowOf(activity).getNextStartedActivityForResult();assertNotNull(file);assertEquals(Intent.ACTION_OPEN_DOCUMENT,file.intent.getAction());assertEquals("*/*",file.intent.getType());assertTrue(file.intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE,false));
        described(page,"桌面小组件").performClick();Intent widget=Shadows.shadowOf(activity).getNextStartedActivity();assertNotNull(widget);assertEquals(WidgetAppearanceActivity.class.getName(),widget.getComponent().getClassName());
        described(page,"播放模式").performClick();AlertDialog mode=ShadowAlertDialog.getLatestAlertDialog();assertTrue(mode.isShowing());mode.getListView().performItemClick(null,PlayerService.MODE_SHUFFLE,PlayerService.MODE_SHUFFLE);assertEquals(PlayerService.MODE_SHUFFLE,service.getPlayMode());
        described(page,"睡眠定时").performClick();@SuppressWarnings("unchecked")List<Dialog> dialogs=(List<Dialog>)field(activity,"dialogs");Dialog timer=dialogs.get(dialogs.size()-1);View fifteen=text(timer.getWindow().getDecorView(),"15 分钟");assertNotNull(fifteen);fifteen.performClick();assertTrue(service.getSleepRemainingMs()>890000&&service.getSleepRemainingMs()<=900000);
        described(page,"音乐账户与联动").performClick();Dialog sources=(Dialog)field(activity,"sourcesDialog");assertNotNull(sources);assertTrue(sources.isShowing());assertNotNull(text(sources.getWindow().getDecorView(),"汽水音乐"));
    }

    @Test public void liveCoverThemeUpdatesOpenSettingsAndSearchWithoutRebuildOrScrollLoss()throws Exception{
        startMain();Bitmap red=solid(0xffb53530),blue=solid(0xff286dc4);palette(red);SettingsPage settings=settings();
        Bitmap redShot=render(settings,412,860,"settings-red-412x860-fixture.png");Drawable originalBackground=settings.getBackground();ScrollView scroll=first(settings,ScrollView.class);assertNotNull(scroll);scroll.scrollTo(0,420);assertEquals(420,scroll.getScrollY());View savedRow=described(settings,"播放音量");
        call("showCatalogSearch",new Class<?>[]{String.class},"");AggregateSearchPage search=(AggregateSearchPage)field(activity,"aggregatePage");Dialog searchDialog=(Dialog)field(search,"dialog");View searchContent=searchDialog.findViewById(android.R.id.content);View searchPage=((ViewGroup)searchContent).getChildAt(0);Drawable originalPaper=searchPage.getBackground();
        Bitmap searchRed=render(searchContent,412,860,"search-red-412x860-fixture.png");render(settings,412,860,"settings-red-scrolled-412x860-fixture.png");int beforeScroll=scroll.getScrollY();
        palette(blue);assertSame(settings,field(activity,"settingsPage"));assertSame(search,field(activity,"aggregatePage"));assertSame(savedRow,described(settings,"播放音量"));assertSame(originalBackground,settings.getBackground());assertSame(originalPaper,searchPage.getBackground());assertEquals(beforeScroll,scroll.getScrollY());
        render(settings,412,860,"settings-blue-scrolled-412x860-fixture.png");Bitmap searchBlue=render(searchContent,412,860,"search-blue-412x860-fixture.png");assertNotEquals(searchRed.getPixel(3,400),searchBlue.getPixel(3,400));
        scroll.scrollTo(0,0);Bitmap blueShot=render(settings,412,860,"settings-blue-412x860-fixture.png");int redColor=redShot.getPixel(2,500),blueColor=blueShot.getPixel(2,500);assertTrue(Color.red(redColor)>Color.blue(redColor));assertTrue(Color.blue(blueColor)>Color.red(blueColor));assertNotEquals(redColor,blueColor);
    }

    @Test public void sourcesAndSearchMatchSettingsSurfaceAndSystemBars()throws Exception{
        startMain();palette(solid(0xffa52b32));SettingsPage settings=settings();
        Bitmap settingsShot=render(settings,412,860,null);int expected=settingsShot.getPixel(2,400);
        call("showMusicSources",new Class<?>[0]);Dialog sources=(Dialog)field(activity,"sourcesDialog");
        Bitmap sourceShot=render(sources.findViewById(android.R.id.content),412,860,null);
        assertEquals("Sources must use the same soft dark surface as Settings",expected,sourceShot.getPixel(2,400));
        assertEquals(0,sources.getWindow().getDecorView().getSystemUiVisibility()&View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        assertEquals(expected,sources.getWindow().getNavigationBarColor());
        call("showCatalogSearch",new Class<?>[]{String.class},"");AggregateSearchPage search=(AggregateSearchPage)field(activity,"aggregatePage");Dialog searchDialog=(Dialog)field(search,"dialog");
        Bitmap searchShot=render(searchDialog.findViewById(android.R.id.content),412,860,null);
        assertEquals("Search must use the same soft dark surface as Settings",expected,searchShot.getPixel(2,400));
        palette(solid(0xff266db4));int changed=render(settings,412,860,null).getPixel(2,400);
        assertNotEquals(expected,changed);assertEquals(changed,render(sources.findViewById(android.R.id.content),412,860,null).getPixel(2,400));
        assertEquals(changed,render(searchDialog.findViewById(android.R.id.content),412,860,null).getPixel(2,400));
        assertEquals(changed,sources.getWindow().getNavigationBarColor());assertEquals(changed,searchDialog.getWindow().getNavigationBarColor());
    }

    @Test public void nestedAccountCatalogsKeepSameDarkThemeAfterCoverChange()throws Exception{
        startMain();OnlineMusic.useProviderForTests(activity,new FullCatalogEntryTest.Catalog(),new FullCatalogEntryTest.Store());
        palette(solid(0xffa52b32));int expected=render(settings(),412,860,null).getPixel(2,400);
        call("showProvider",new Class<?>[]{String.class,android.os.Bundle.class},"netease",null);
        ProviderLibraryPage provider=(ProviderLibraryPage)((java.util.Map<?,?>)field(activity,"providerPages")).get("netease");
        ((ExecutorService)field(provider,"worker")).submit(()->{}).get(10,TimeUnit.SECONDS);Shadows.shadowOf(Looper.getMainLooper()).idle();
        Dialog providerDialog=(Dialog)field(provider,"dialog");View providerContent=providerDialog.findViewById(android.R.id.content);
        assertEquals(expected,render(providerContent,412,860,"account-soft-red-fixture.png").getPixel(2,400));
        call("showNetease",new Class<?>[0]);NeteasePage net=(NeteasePage)field(activity,"neteasePage");
        ((ExecutorService)field(net,"worker")).submit(()->{}).get(10,TimeUnit.SECONDS);Shadows.shadowOf(Looper.getMainLooper()).idle();
        Dialog netDialog=(Dialog)field(net,"dialog");View netContent=netDialog.findViewById(android.R.id.content);
        assertEquals(expected,render(netContent,412,860,"netease-soft-red-fixture.png").getPixel(2,400));
        palette(solid(0xff266db4));int changed=DesignSystem.ambientColor(activity);
        assertNotEquals(expected,changed);assertEquals(changed,render(providerContent,412,860,"account-soft-blue-fixture.png").getPixel(2,400));
        assertEquals(changed,render(netContent,412,860,"netease-soft-blue-fixture.png").getPixel(2,400));
        assertEquals(changed,providerDialog.getWindow().getNavigationBarColor());assertEquals(changed,netDialog.getWindow().getNavigationBarColor());
        assertEquals(0,providerDialog.getWindow().getDecorView().getSystemUiVisibility());assertEquals(0,netDialog.getWindow().getDecorView().getSystemUiVisibility());
    }

    @Test public void glassIconsAndCapsulesRetainRealTransparencyAndUnderlyingHue()throws Exception{
        Application context=RuntimeEnvironment.getApplication();DesignSystem.setAlbumTheme(context,"fixture",0xff283d58,0xff5581bb);
        PlayerControls.Icon icon=new PlayerControls.Icon(context,"search");icon.setGlass(true);Bitmap glass=render(icon,48,48,null);int alpha=Color.alpha(glass.getPixel(24,8));assertTrue("Glass fill is translucent",alpha>0&&alpha<180);assertEquals(0,Color.alpha(glass.getPixel(0,0)));
        icon.setEnabled(false);Bitmap disabled=render(icon,48,48,null);assertTrue(Color.alpha(disabled.getPixel(24,8))<alpha);icon.setEnabled(true);
        Drawable capsule=PlayerControls.glassCapsule(context);capsule.setBounds(0,0,48,180);Bitmap transparent=Bitmap.createBitmap(48,180,Bitmap.Config.ARGB_8888);images.add(transparent);capsule.draw(new Canvas(transparent));int centerAlpha=Color.alpha(transparent.getPixel(24,90));assertTrue(centerAlpha>0&&centerAlpha<180);assertEquals(0,Color.alpha(transparent.getPixel(0,0)));assertEquals(android.graphics.PixelFormat.TRANSLUCENT,capsule.getOpacity());
        Bitmap overview=Bitmap.createBitmap(412,210,Bitmap.Config.ARGB_8888);images.add(overview);Canvas canvas=new Canvas(overview);android.graphics.Paint paint=new android.graphics.Paint();int[] colors={0xff663b40,0xff294c72};String[] names={"search","settings","more","albums","queue"};
        for(int band=0;band<2;band++){paint.setColor(colors[band]);canvas.drawRect(0,band*105,412,(band+1)*105,paint);for(int i=0;i<names.length;i++){PlayerControls.Icon glyph=new PlayerControls.Icon(context,names[i]);glyph.setGlass(true);layout(glyph,48,48);canvas.save();canvas.translate(18+i*63,band*105+25);glyph.draw(canvas);canvas.restore();}capsule.setBounds(0,0,48,82);canvas.save();canvas.translate(350,band*105+12);capsule.draw(canvas);canvas.restore();}
        assertNotEquals(overview.getPixel(374,50),overview.getPixel(374,155));save(overview,"liquid-glass-red-blue-native.png");
    }
}
