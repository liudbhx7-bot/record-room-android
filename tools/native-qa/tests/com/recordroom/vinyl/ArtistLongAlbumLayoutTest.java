package com.recordroom.vinyl;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.os.Looper;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=34,qualifiers="w412dp-h860dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE) @LooperMode(LooperMode.Mode.PAUSED)
public class ArtistLongAlbumLayoutTest {
    static final String TITLE="MULTIVERSE OF IPS 35TH ANNIVERSARY - 刘德华";
    @Test public void portraitTwoLineTitleAndAllActionsFitTheMeasuredInfoPanel()throws Exception{check(412,860,false,"portrait");}
    @Test @Config(qualifiers="w320dp-h720dp-mdpi",fontScale=1.3f)
    public void narrowLargeTextKeepsTwoLinesAndFullActionTargets()throws Exception{check(320,720,false,"narrow-large-text");}
    @Test @Config(qualifiers="w726dp-h320dp-mdpi")
    public void compactLandscapeKeepsOneEllipsizedTitleAndFullActions()throws Exception{check(726,320,true,"compact");}
    @Test @Config(qualifiers="w900dp-h420dp-mdpi")
    public void wideLandscapeKeepsShelfAndInfoWithinThePage()throws Exception{check(900,420,true,"wide");}
    private void check(int width,int height,boolean compact,String label)throws Exception{
        org.robolectric.android.controller.ActivityController<Activity> owner=Robolectric.buildActivity(Activity.class).setup().visible();
        try{
            Track track=new Track("long-album","测试曲目","刘德华",TITLE,"本地测试","asset:///demo/night-1.wav","asset:///demo/night-v2.png",180000,1);
            FrameLayout page=ArtistPage.create(owner.get(),Collections.singletonList(track),track,new ArtistPage.Actions(){public void back(){}public void play(ArrayList<Track> tracks,int index,boolean shuffle){}public void album(ArrayList<Track> tracks){}public void share(String title,String subtitle,ArrayList<Track> tracks){}});
            owner.get().setContentView(page);ArtistPage.selectChapter(page,2);Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(350));layout(page,width,height);
            Object browser=ReflectionHelpers.getField(page,"albumBrowser");AlbumStackView stack=ReflectionHelpers.getField(browser,"stack");ExecutorService loader=ReflectionHelpers.getField(stack,"artExecutor");loader.submit(()->{}).get(10,TimeUnit.SECONDS);Shadows.shadowOf(Looper.getMainLooper()).idle();layout(page,width,height);
            android.util.LruCache<?,?> covers=ReflectionHelpers.getField(stack,"covers");assertEquals("Render a decoded native cover",1,covers.snapshot().size());
            draw(page,width,height,"artist-long-title-"+label+"-closed.png");assertInfo(browser,compact,page);
            stack.openSelectedSleeve();ValueAnimator animation=ReflectionHelpers.getField(stack,"sleeveAnimator");if(animation!=null)animation.end();Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(800));layout(page,width,height);assertTrue(stack.isSleeveOpen());
            draw(page,width,height,"artist-long-title-"+label+"-open.png");assertInfo(browser,compact,page);assertEquals(View.VISIBLE,((View)ReflectionHelpers.getField(browser,"playAlbum")).getVisibility());
        }finally{owner.pause().stop().destroy();Shadows.shadowOf(Looper.getMainLooper()).idle();}
    }
    private void assertInfo(Object browser,boolean compact,FrameLayout page){
        View info=ReflectionHelpers.getField(browser,"info");TextView title=ReflectionHelpers.getField(browser,"name");assertEquals(TITLE,title.getText().toString());assertEquals(compact?1:2,title.getLineCount());
        for(String key:new String[]{"name","meta","previousAlbum","openAlbum","playAlbum","nextAlbum"}){
            View child=ReflectionHelpers.getField(browser,key);if(child.getVisibility()!=View.VISIBLE)continue;
            Rect bounds=new Rect(0,0,child.getWidth(),child.getHeight());((android.view.ViewGroup)info).offsetDescendantRectToMyCoords(child,bounds);
            assertTrue(key+" top clipped: "+bounds,bounds.top>=0);assertTrue(key+" bottom clipped: "+bounds,bounds.bottom<=info.getHeight());assertTrue(key+" horizontal overflow: "+bounds,bounds.left>=0&&bounds.right<=info.getWidth());
            if(key.endsWith("Album")){assertEquals(key+" label remains one readable line",1,((TextView)child).getLineCount());assertTrue(key+" 48dp target",child.getHeight()>=48&&child.getWidth()>=48);Rect visible=new Rect();assertTrue(child.getGlobalVisibleRect(visible));assertEquals(child.getHeight(),visible.height());}
        }
        Rect infoBounds=new Rect(0,0,info.getWidth(),info.getHeight());page.offsetDescendantRectToMyCoords(info,infoBounds);assertTrue(infoBounds.right<=page.getWidth()&&infoBounds.bottom<=page.getHeight());
    }
    private void layout(View page,int width,int height){page.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));page.layout(0,0,width,height);}
    private void draw(View page,int width,int height,String name)throws Exception{Bitmap bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);page.draw(new Canvas(bitmap));File dir=new File(System.getProperty("vinyl.output.dir"));dir.mkdirs();try(FileOutputStream out=new FileOutputStream(new File(dir,name))){bitmap.compress(Bitmap.CompressFormat.PNG,100,out);}finally{bitmap.recycle();}}
}
