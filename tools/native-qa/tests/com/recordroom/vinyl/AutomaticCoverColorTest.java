package com.recordroom.vinyl;
import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.Looper;
import android.view.View;
import android.widget.FrameLayout;
import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk=34,qualifiers="w412dp-h860dp-mdpi") @GraphicsMode(GraphicsMode.Mode.NATIVE) @LooperMode(LooperMode.Mode.PAUSED)
public class AutomaticCoverColorTest {
    ActivityController<Activity> controller;Context context;
    @Before public void setup(){controller=Robolectric.buildActivity(Activity.class).setup().visible();context=controller.get();context.getSharedPreferences("vinyl_appearance",0).edit().clear().commit();}
    @After public void close(){controller.pause().stop().destroy();}
    Bitmap solid(int color){Bitmap b=Bitmap.createBitmap(96,96,Bitmap.Config.ARGB_8888);b.eraseColor(color);return b;}
    @Test public void legacyFixedColorsMigrateToAutomaticOnceAndLaterManualChoiceRemains(){
        context.getSharedPreferences("vinyl_appearance",0).edit().putBoolean("follow_cover_colors",false).putInt("color",0xff382139).putInt("disc",0).putInt("album:a:disc",5).putInt("album:a:color",0xff122334).commit();
        ThemeStore themes=new ThemeStore(context);assertTrue("An upgraded installation defaults to cover matching",themes.followCoverColors());Bitmap cover=solid(0xffdb302a);try{themes.coverPalette("a",AlbumPalette.from(cover));assertNotEquals(0xff122334,themes.background("a"));assertNotEquals(0,themes.recordTint("a"));}finally{cover.recycle();}
        assertEquals(5,themes.disc("a"));assertEquals("The saved manual color is retained for switching back",0xff122334,themes.colorFor("a",true));themes.followCoverColors(false);ThemeStore reopened=new ThemeStore(context);assertFalse("A deliberate manual choice after migration persists",reopened.followCoverColors());assertEquals(0xff122334,reopened.background("a"));assertEquals(0,reopened.recordTint("a"));
    }
    @Test @GraphicsMode(GraphicsMode.Mode.LEGACY) public void hardwareMediaSessionCoversKeepTheirActualRedAndBluePalette(){
        Bitmap red=solid(0xffe02935),blue=solid(0xff2362d8),hwRed=red.copy(Bitmap.Config.HARDWARE,false),hwBlue=blue.copy(Bitmap.Config.HARDWARE,false);
        try{assertEquals(Bitmap.Config.HARDWARE,hwRed.getConfig());AlbumPalette warm=AlbumPalette.from(hwRed),cool=AlbumPalette.from(hwBlue);assertEquals(AlbumPalette.from(red).record,warm.record);assertEquals(AlbumPalette.from(blue).record,cool.record);assertTrue(Color.red(warm.record)>Color.red(cool.record)+60);assertFalse(hwRed.isRecycled());assertFalse(hwBlue.isRecycled());}finally{hwRed.recycle();hwBlue.recycle();red.recycle();blue.recycle();}
    }
    @Test public void appearanceHasExplicitAutomaticSwitchAndRestoresSavedManualColor(){
        ThemeStore themes=new ThemeStore(context);themes.save("album",false,5,0xff293847);Bitmap cover=solid(0xffe02935);themes.coverPalette("album",AlbumPalette.from(cover));int[] changes={0};AppearanceEditor editor=new AppearanceEditor(controller.get(),themes,"album","Album",cover,()->changes[0]++,()->{},()->{});android.app.Dialog dialog=editor.show();
        try{android.widget.Switch toggle=ReflectionHelpers.getField(editor,"automaticColors");assertTrue(toggle.isChecked());assertEquals("背景和唱片随封面配色",toggle.getText().toString());toggle.performClick();assertFalse(themes.followCoverColors());assertEquals(0xff293847,themes.background("album"));toggle.performClick();assertTrue(themes.followCoverColors());assertEquals(AlbumPalette.from(cover).record,themes.recordTint("album"));assertEquals(2,changes[0]);}finally{dialog.dismiss();cover.recycle();}
    }
    @Test public void everyNativeMaterialShowsDifferentCoverHueWithoutChangingCoverPixels()throws Exception{
        ThemeStore themes=new ThemeStore(context);Bitmap red=solid(0xffe02935),blue=solid(0xff2362d8);AlbumPalette warm=AlbumPalette.from(red),cool=AlbumPalette.from(blue);
        FrameLayout host=new FrameLayout(context);TurntableView turntable=new TurntableView(context);turntable.setReferenceLayout(true);turntable.setMotionEnabled(false);turntable.setLighting(0);host.addView(turntable,new FrameLayout.LayoutParams(-1,-1));controller.get().setContentView(host);layout(host);
        try{for(int style=0;style<DiscMaterials.COUNT;style++){
            turntable.setStyle(style);awaitAssets();themes.coverPalette("warm",warm);int warmBase = DesignSystem.deckColor(themes,"warm");host.setBackground(DesignSystem.surface(context,warmBase));turntable.setArtwork(red);turntable.setRecordTint(themes.deckTint("warm"));Bitmap first=render(host);
            themes.coverPalette("cool",cool);int coolBase = DesignSystem.deckColor(themes,"cool");host.setBackground(DesignSystem.surface(context,coolBase));turntable.setArtwork(blue);turntable.setRecordTint(themes.deckTint("cool"));Bitmap second=render(host);
            float cx=ReflectionHelpers.getField(turntable,"cx"),cy=ReflectionHelpers.getField(turntable,"cy"),radius=ReflectionHelpers.getField(turntable,"radius");double[] a=ring(first,cx,cy,radius),b=ring(second,cx,cy,radius);
            System.out.println("AUTO_MATERIAL style="+style+" red_delta="+(a[0]-b[0])+" blue_delta="+(b[2]-a[2]));
            if(style==0||style==5||style==8){write(first,"automatic-red-material-"+style+".png");write(second,"automatic-blue-material-"+style+".png");}
            assertTrue("Red cover must visibly tint actual material "+style,a[0]-b[0]>=32);assertTrue("Blue cover must visibly tint actual material "+style,b[2]-a[2]>=32);
            assertEquals(red.getPixel(48,48),first.getPixel(Math.round(cx-radius*.2f),Math.round(cy)));assertEquals(blue.getPixel(48,48),second.getPixel(Math.round(cx-radius*.2f),Math.round(cy)));assertNotEquals(first.getPixel(12,600),second.getPixel(12,600));first.recycle();second.recycle();
        }}finally{red.recycle();blue.recycle();}
    }
    void layout(View v){v.measure(View.MeasureSpec.makeMeasureSpec(412,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(860,View.MeasureSpec.EXACTLY));v.layout(0,0,412,860);Shadows.shadowOf(Looper.getMainLooper()).idle();}
    void awaitAssets()throws Exception{ExecutorService io=ReflectionHelpers.getStaticField(DiscMaterials.class,"IO");io.submit(()->{}).get(10,TimeUnit.SECONDS);Shadows.shadowOf(Looper.getMainLooper()).idle();}
    Bitmap render(View view){Bitmap image=Bitmap.createBitmap(412,860,Bitmap.Config.ARGB_8888);view.draw(new Canvas(image));return image;}
    double[] ring(Bitmap image,float cx,float cy,float radius){double[] sum=new double[3];int n=0;for(int degree=100;degree<=260;degree+=4){double a=Math.toRadians(degree);int color=image.getPixel(Math.round(cx+(float)Math.cos(a)*radius*.73f),Math.round(cy+(float)Math.sin(a)*radius*.73f));sum[0]+=Color.red(color);sum[1]+=Color.green(color);sum[2]+=Color.blue(color);n++;}for(int i=0;i<3;i++)sum[i]/=n;return sum;}
    void write(Bitmap image,String name)throws Exception{File dir=new File(System.getProperty("vinyl.output.dir"));dir.mkdirs();try(FileOutputStream stream=new FileOutputStream(new File(dir,name))){image.compress(Bitmap.CompressFormat.PNG,100,stream);}}
}
