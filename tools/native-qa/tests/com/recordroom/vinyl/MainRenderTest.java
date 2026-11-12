package com.recordroom.vinyl;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.app.Dialog;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.android.controller.ServiceController;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=34, qualifiers="w512dp-h726dp-mdpi", fontScale=1f)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class MainRenderTest {
    static final String OUT=System.getProperty("vinyl.output.dir")+"/";
    private static Object field(Object target,String name)throws Exception{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}
    private static void call(Object target,String name,Class<?>[] types,Object... args)throws Exception{Method m=target.getClass().getDeclaredMethod(name,types);m.setAccessible(true);m.invoke(target,args);}
    private static void waitArt()throws Exception{for(int i=0;i<3;i++){Field materials=DiscMaterials.class.getDeclaredField("IO");materials.setAccessible(true);((ExecutorService)materials.get(null)).submit(()->{}).get(10,TimeUnit.SECONDS);CoverView.IO.submit(()->{}).get(10,TimeUnit.SECONDS);Thread.sleep(100);Shadows.shadowOf(Looper.getMainLooper()).idle();}Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1000));}
    private static ImageView image(View v){if(v instanceof ImageView)return (ImageView)v;if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++){ImageView found=image(g.getChildAt(i));if(found!=null)return found;}}return null;}
    private static void shot(View view,int w,int h,String name)throws Exception{
        view.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));view.layout(0,0,w,h);
        Bitmap b=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);Canvas c=new Canvas(b);view.draw(c);
        try(FileOutputStream out=new FileOutputStream(new File(OUT,name))){b.compress(Bitmap.CompressFormat.PNG,100,out);}b.recycle();
        System.out.println("NATIVE_ACTIVITY_RENDER "+name+" "+w+"x"+h);
    }
    private static View described(View view,String description){if(description.contentEquals(view.getContentDescription()==null?"":view.getContentDescription()))return view;if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){View result=described(group.getChildAt(i),description);if(result!=null)return result;}}return null;}
    private static void flag(Object target,String name,boolean value)throws Exception{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);f.setBoolean(target,value);}
    private static void layered(View base,View overlay,int w,int h,String name)throws Exception{for(View v:new View[]{base,overlay}){v.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));v.layout(0,0,w,h);}Bitmap image=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(image);base.draw(canvas);overlay.draw(canvas);try(FileOutputStream out=new FileOutputStream(new File(OUT,name))){image.compress(Bitmap.CompressFormat.PNG,100,out);}image.recycle();System.out.println("NATIVE_LAYERED_RENDER "+name);}
    @Test public void renderActualActivity()throws Exception{
        ServiceController<PlayerService> serviceController=Robolectric.buildService(PlayerService.class).create();
        PlayerService service=serviceController.get();
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).setComponentNameAndServiceForBindService(new ComponentName(RuntimeEnvironment.getApplication(),PlayerService.class),service.onBind(new Intent(RuntimeEnvironment.getApplication(),PlayerService.class)));
        ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup().visible();
        MainActivity activity=controller.get();
        waitArt();
        List<Track> demos=DemoCatalog.load(activity);
        if(demos.isEmpty())throw new IllegalStateException("Real app assets were not loaded");
        service.setQueue(new ArrayList<>(demos),0,false);
        Field player=MainActivity.class.getDeclaredField("player");player.setAccessible(true);player.set(activity,service);
        call(activity,"refresh",new Class<?>[0]);waitArt();
        shot(activity.getWindow().getDecorView(),512,726,"main-portrait-chrome-api34.png");
        call(activity,"showPlayerTools",new Class<?>[0]);
        Dialog drawer=(Dialog)field(activity,"toolDrawer");
        shot(drawer.getWindow().getDecorView(),420,230,"main-thumb-tools-api34.png");
        drawer.dismiss();
        call(activity,"showShelf",new Class<?>[0]);waitArt();
        Dialog shelf=(Dialog)field(activity,"shelfDialog");shot(shelf.getWindow().getDecorView(),512,726,"main-album-shelf-api34.png");
        ArrayList<Track> album=new ArrayList<>();String key=demos.get(0).albumKey();for(Track t:demos)if(t.albumKey().equals(key))album.add(t);
        call(activity,"showAlbum",new Class<?>[]{ArrayList.class},album);waitArt();
        Dialog details=(Dialog)field(activity,"albumDialog");
        ((ExecutorService)field(activity,"io")).submit(()->{}).get(5,TimeUnit.SECONDS);Shadows.shadowOf(Looper.getMainLooper()).idle();Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(900));
        ImageView detailImage=image(details.getWindow().getDecorView());
        if(detailImage==null||detailImage.getDrawable()==null)throw new IllegalStateException("Detail cover drawable missing after IO barrier");
        if(detailImage.getImageAlpha()!=255)throw new IllegalStateException("Detail image alpha must be 255 after animation; check ImageView.setAlpha(int) overload");
        System.out.println("DETAIL_IMAGE drawable="+detailImage.getDrawable().getClass().getName()+" alpha="+detailImage.getAlpha()+" rotationX="+detailImage.getRotationX()+" size="+detailImage.getWidth()+"x"+detailImage.getHeight());
        BitmapDrawable detailBitmap=(BitmapDrawable)detailImage.getDrawable();
        System.out.println("DETAIL_IMAGE bitmap="+detailBitmap.getBitmap().getWidth()+"x"+detailBitmap.getBitmap().getHeight()+" center="+Integer.toHexString(detailBitmap.getBitmap().getPixel(detailBitmap.getBitmap().getWidth()/2,detailBitmap.getBitmap().getHeight()/2))+" bounds="+detailBitmap.getBounds()+" imageMatrix="+detailImage.getImageMatrix()+" viewMatrix="+detailImage.getMatrix()+" imageAlpha="+detailImage.getImageAlpha()+" layer="+detailImage.getLayerType()+" visibility="+detailImage.getVisibility());
        shot(details.getWindow().getDecorView(),512,726,"main-album-detail-api34.png");
        shot(detailImage,286,286,"detail-cover-isolated-api34.png");
        call(activity,"dismissDialogs",new Class<?>[0]);
        call(activity,"showAppearance",new Class<?>[0]);waitArt();Dialog appearance=(Dialog)field(activity,"appearanceDialog");
        layered(activity.getWindow().getDecorView(),appearance.getWindow().getDecorView(),512,726,"main-appearance-api34.png");
        View color=described(appearance.getWindow().getDecorView(),"自定义颜色和封面取色");if(color==null)throw new IllegalStateException("Custom color action missing");color.performClick();waitArt();android.app.AlertDialog colorDialog=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();shot(colorDialog.getWindow().getDecorView(),512,650,"main-color-picker-api34.png");colorDialog.dismiss();
        call(activity,"dismissDialogs",new Class<?>[0]);
        flag(activity,"shelfGrid",true);call(activity,"showShelf",new Class<?>[0]);waitArt();shelf=(Dialog)field(activity,"shelfDialog");shot(shelf.getWindow().getDecorView(),512,726,"main-album-grid-api34.png");
        android.widget.EditText search=(android.widget.EditText)field(activity,"shelfSearch");search.setVisibility(View.VISIBLE);search.setText("夜");waitArt();shot(shelf.getWindow().getDecorView(),512,726,"main-search-results-api34.png");search.setText("");search.setVisibility(View.GONE);
        LibraryStore collections=(LibraryStore)field(activity,"collections");collections.create("慢慢听",album);collections.toggleLike(demos.get(0));collections.remember(demos.get(0));
        if(new LibraryStore(activity).playlists().isEmpty()||new LibraryStore(activity).favorites().isEmpty())throw new IllegalStateException("Collections did not persist");
        flag(activity,"shelfSongs",true);call(activity,"renderShelf",new Class<?>[0]);waitArt();shot(shelf.getWindow().getDecorView(),512,726,"main-playlists-api34.png");call(activity,"dismissDialogs",new Class<?>[0]);
        String before=service.getCurrent().id;service.seekTo(9000);service.moveQueueItem(0,2);if(!service.getCurrent().id.equals(before)||service.getIndex()!=2||service.getPositionMs()!=9000)throw new IllegalStateException("Queue reorder interrupted current song or position");service.removeQueueItem(0);if(!service.getCurrent().id.equals(before)||service.getIndex()!=1)throw new IllegalStateException("Queue removal changed playing identity");service.setQueue(new ArrayList<>(demos),0,false);
        call(activity,"showQueue",new Class<?>[0]);waitArt();Dialog queue=(Dialog)field(activity,"queueDialog");shot(queue.getWindow().getDecorView(),512,726,"main-queue-api34.png");call(activity,"dismissDialogs",new Class<?>[0]);
        call(activity,"showSettings",new Class<?>[0]);waitArt();Dialog settings=(Dialog)field(activity,"settingsDialog");shot(settings.getWindow().getDecorView(),512,726,"main-settings-api34.png");call(activity,"dismissDialogs",new Class<?>[0]);
        RuntimeEnvironment.setQualifiers("w726dp-h512dp-land-mdpi");
        activity.onConfigurationChanged(activity.getResources().getConfiguration());waitArt();
        shot(activity.getWindow().getDecorView(),726,512,"main-landscape-api34.png");
        RuntimeEnvironment.setQualifiers("w480dp-h320dp-land-mdpi");activity.onConfigurationChanged(activity.getResources().getConfiguration());waitArt();
        shot(activity.getWindow().getDecorView(),480,320,"main-small-landscape-api34.png");
        View next=(View)field(activity,"next");int[] coordinates=new int[2];next.getLocationOnScreen(coordinates);Rect visible=new Rect();next.getGlobalVisibleRect(visible);System.out.println("SMALL_LANDSCAPE_NEXT location="+coordinates[0]+","+coordinates[1]+" size="+next.getWidth()+"x"+next.getHeight()+" visible="+visible);if(coordinates[0]<0||coordinates[0]+next.getWidth()>480)throw new IllegalStateException("Next button clips horizontal screen edge");
        controller.pause().stop().destroy();serviceController.destroy();
    }
}
