package com.recordroom.vinyl;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=34,qualifiers="w390dp-h844dp-mdpi",fontScale=1f)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public class ApprovedCoreDesignTest {
    private ActivityController<Activity> controller;private Activity activity;private FrameLayout host;
    @Before public void setup(){controller=Robolectric.buildActivity(Activity.class);activity=controller.get();activity.setTheme(android.R.style.Theme_Material_Light_NoActionBar);controller.setup().visible();host=new FrameLayout(activity);host.setBackgroundColor(DesignSystem.LILAC);activity.setContentView(host);layout(host,390,844);}
    @After public void cleanup(){controller.pause().stop().destroy();}
    @Test public void newDefaultUsesBlueMaterialWhileStoredGlobalAndAlbumChoicesRemainUnchanged(){
        activity.getSharedPreferences("vinyl_appearance",0).edit().clear().commit();ThemeStore theme=new ThemeStore(activity);
        assertEquals(DesignSystem.LILAC,theme.background("new"));assertEquals(5,theme.disc("new"));
        theme.save(null,false,6,0xffde8b7c);theme.save("old-album",true,1,0xff123456);
        ThemeStore restored=new ThemeStore(activity);assertEquals(6,restored.disc("other"));assertEquals(0xffde8b7c,restored.background("other"));assertEquals(1,restored.disc("old-album"));assertEquals(0xff123456,restored.background("old-album"));
        restored.save("new-album",true,8,DesignSystem.ROSE);assertEquals(8,new ThemeStore(activity).disc("new-album"));assertEquals(9,ThemeStore.DISC_NAMES.length);assertEquals("materials/disc-green-marble-v1.png",DiscMaterials.assetPath(6));assertEquals(ApprovedArtwork.SMOKE_DISC,DiscMaterials.assetPath(8));
    }
    @Test public void approvedAssetsAreRealBoundedSharedAndCancelledCallbacksStayCancelled()throws Exception{
        String[] assets={ApprovedArtwork.TONEARM,ApprovedArtwork.SMOKE_DISC,ApprovedArtwork.PAPER_TEXTURE,ApprovedArtwork.SLEEVE,ApprovedArtwork.EMPTY_LIBRARY};
        List<Bitmap> decoded=new ArrayList<>();for(String asset:assets)ApprovedArtwork.load(activity,asset,512,decoded::add);awaitArtwork();assertEquals(5,decoded.size());
        for(Bitmap bitmap:decoded){assertNotNull(bitmap);assertTrue(Math.max(bitmap.getWidth(),bitmap.getHeight())<=512);assertFalse(bitmap.isRecycled());}
        Bitmap[] again={null};ApprovedArtwork.load(activity,ApprovedArtwork.TONEARM,512,b->again[0]=b);int[] cancelled={0};ApprovedArtwork.Request request=ApprovedArtwork.load(activity,ApprovedArtwork.TONEARM,512,b->cancelled[0]++);request.cancel();awaitArtwork();assertSame(decoded.get(0),again[0]);assertEquals(0,cancelled[0]);
        boolean[] delivered={false};ApprovedArtwork.load(activity,"design/not-approved.png",512,b->{assertNull(b);delivered[0]=true;});awaitArtwork();assertTrue(delivered[0]);
        Bitmap cover=Bitmap.createBitmap(32,32,Bitmap.Config.ARGB_8888);cover.eraseColor(Color.RED);Bitmap output=Bitmap.createBitmap(240,240,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(output);canvas.drawColor(DesignSystem.LILAC);ApprovedArtwork.drawSleeve(canvas,decoded.get(3),cover,new RectF(20,20,220,220),new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG));assertEquals(Color.RED,output.getPixel(120,120));assertNotEquals(Color.RED,output.getPixel(35,35));write(output,"approved-sleeve-window-native.png");
    }
    @Test public void actualNewTonearmHeadIsDraggableAndCoverUsesFortyTwoPercent()throws Exception{
        TurntableView view=new TurntableView(activity);host.addView(view,new FrameLayout.LayoutParams(390,405));layout(view,390,405);view.setStyle(8);view.setMotionEnabled(false);view.setActive(false);
        Bitmap cover=Bitmap.createBitmap(40,40,Bitmap.Config.ARGB_8888);cover.eraseColor(Color.RED);view.setArtwork(cover);awaitMaterials();awaitArtwork();assertNotNull(field(view,"tonearmTexture"));
        float cx=number(view,"cx"),cy=number(view,"cy"),r=number(view,"radius"),px=number(view,"pivotX"),py=number(view,"pivotY");
        Bitmap image=Bitmap.createBitmap(390,405,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(image);canvas.drawColor(DesignSystem.LILAC);view.draw(canvas);
        assertEquals(Color.RED,image.getPixel(Math.round(cx-r*.40f),Math.round(cy)));assertNotEquals(Color.RED,image.getPixel(Math.round(cx-r*.44f),Math.round(cy)));write(image,"approved-turntable-smoke-native.png");
        List<String> events=new ArrayList<>();float[] progress={-1};view.setNeedleListener(new TurntableView.NeedleListener(){public void onNeedlePause(){events.add("pause");view.setPlaying(false);}public void onNeedleSeek(float p){events.add("seek");progress[0]=p;}public void onNeedlePlay(){events.add("play");view.setPlaying(true);}});
        // Touch the new source's stylus, beyond the previous vector arm's endpoint.
        float localX=-r*.058f,localY=r*1.58f;long time=SystemClock.uptimeMillis();event(view,time,time,MotionEvent.ACTION_DOWN,px+localX,py+localY);
        double angle=Math.toRadians(23);float endX=px+(float)(localX*Math.cos(angle)-localY*Math.sin(angle)),endY=py+(float)(localX*Math.sin(angle)+localY*Math.cos(angle));
        event(view,time,time+16,MotionEvent.ACTION_MOVE,endX,endY);event(view,time,time+32,MotionEvent.ACTION_UP,endX,endY);assertEquals(Arrays.asList("pause","seek","play"),events);assertEquals(.5f,progress[0],.015f);
        // Missing decoded art still renders the real native fallback instead of crashing.
        Field bitmap=TurntableView.class.getDeclaredField("tonearmTexture");bitmap.setAccessible(true);bitmap.set(view,null);view.draw(new Canvas(Bitmap.createBitmap(390,405,Bitmap.Config.ARGB_8888)));
    }
    @Test public void controlsHaveFortyEightDpTargetsAndReadableDesignTokens()throws Exception{
        PlayerControls.Icon icon=new PlayerControls.Icon(activity,"album");icon.measure(View.MeasureSpec.makeMeasureSpec(200,View.MeasureSpec.AT_MOST),View.MeasureSpec.makeMeasureSpec(200,View.MeasureSpec.AT_MOST));assertEquals(48,icon.getMeasuredWidth());assertEquals(48,icon.getMeasuredHeight());
        assertTrue((luminance(DesignSystem.LILAC)+.05)/(luminance(DesignSystem.MUTED)+.05)>=4.5);
        Bitmap atlas=Bitmap.createBitmap(390,240,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(atlas);canvas.drawColor(DesignSystem.PAPER);Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);String[] names={"play","pause","previous","next","volume","palette","queue","album","share","settings","widget","heart","translate","download","clock","lock","warning","expand","music","info","refresh","trash","grip","search"};for(int i=0;i<names.length;i++)PlayerControls.symbol(canvas,p,names[i],25+(i%8)*47,25+(i/8)*70,24,DesignSystem.INK);write(atlas,"approved-control-icons-native.png");
    }
    private static double luminance(int color){double[] rgb={Color.red(color)/255.0,Color.green(color)/255.0,Color.blue(color)/255.0};for(int i=0;i<3;i++)rgb[i]=rgb[i]<=.04045?rgb[i]/12.92:Math.pow((rgb[i]+.055)/1.055,2.4);return rgb[0]*.2126+rgb[1]*.7152+rgb[2]*.0722;}
    private static Object field(Object target,String name)throws Exception{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}
    private static float number(Object target,String name)throws Exception{return ((Number)field(target,name)).floatValue();}
    private static void awaitArtwork()throws Exception{await(ApprovedArtwork.class);}
    private static void awaitMaterials()throws Exception{await(DiscMaterials.class);}
    private static void await(Class<?> type)throws Exception{Field f=type.getDeclaredField("IO");f.setAccessible(true);((ExecutorService)f.get(null)).submit(()->{}).get(30,TimeUnit.SECONDS);Shadows.shadowOf(Looper.getMainLooper()).idle();}
    private static void layout(View view,int width,int height){view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));view.layout(0,0,width,height);}
    private static void event(View view,long down,long time,int action,float x,float y){MotionEvent e=MotionEvent.obtain(down,time,action,x,y,0);try{assertTrue(view.dispatchTouchEvent(e));}finally{e.recycle();}}
    private static void write(Bitmap bitmap,String name)throws Exception{File dir=new File(System.getProperty("vinyl.output.dir"));assertTrue(dir.isDirectory()||dir.mkdirs());try(FileOutputStream out=new FileOutputStream(new File(dir,name))){assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,out));}}
}
