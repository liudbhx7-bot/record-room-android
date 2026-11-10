package com.recordroom.vinyl;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=34,qualifiers="w320dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class DesignComponentTest {
    private static Track song(String id){return new Track(id,"原创歌曲 "+id,"原创音乐人","原创专辑","local","asset:///demo/night-1.wav","",18000,1);}
    private static Object field(Object owner,String name)throws Exception{Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(owner);}
    private static void set(Object owner,String name,Object value)throws Exception{Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);f.set(owner,value);}
    private static Bitmap solid(int color){Bitmap image=Bitmap.createBitmap(64,64,Bitmap.Config.ARGB_8888);image.eraseColor(color);return image;}

    @Test public void albumOverflowTargetMeetsTouchSizeAndRebindingUsesLatestSong(){
        ActivityController<Activity> controller=Robolectric.buildActivity(Activity.class).setup();
        try{
            ArrayList<String> events=new ArrayList<>();AlbumSongAdapter adapter=new AlbumSongAdapter(controller.get(),Arrays.asList(song("a"),song("b")),new AlbumSongAdapter.Actions(){public void play(int at){events.add("play:"+at);}public void menu(Track track){events.add("menu:"+track.id);}});
            View row=adapter.getView(0,null,new FrameLayout(controller.get()));row=adapter.getView(1,row,new FrameLayout(controller.get()));
            row.measure(View.MeasureSpec.makeMeasureSpec(320,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));row.layout(0,0,320,row.getMeasuredHeight());
            View menu=((ViewGroup)row).getChildAt(2);assertTrue(menu.getWidth()>=48);assertTrue(menu.getHeight()>=48);
            assertEquals("歌曲操作：原创歌曲 b",menu.getContentDescription());menu.performClick();row.performClick();
            assertEquals(Arrays.asList("menu:b","play:1"),events);
        }finally{controller.pause().stop().destroy();}
    }

    @Test public void exportedSleeveHasRealCircularCoverWindowAndTracksNewArtwork()throws Exception{
        ActivityController<Activity> controller=Robolectric.buildActivity(Activity.class).setup();
        ShareStudio studio=new ShareStudio(controller.get(),"原创唱片","原创测试",Collections.singletonList(song("a")),DesignSystem.LILAC,0);
        try{
            Object bundle=field(studio,"artwork");set(bundle,"paper",solid(DesignSystem.PAPER));set(bundle,"sleeve",solid(Color.BLUE));set(bundle,"disc",solid(0xffb0b0b0));
            @SuppressWarnings("unchecked") ArrayList<Bitmap> covers=(ArrayList<Bitmap>)field(bundle,"covers");covers.add(solid(Color.RED));
            Method snapshot=ShareStudio.class.getDeclaredMethod("renderSnapshot");snapshot.setAccessible(true);
            Integer previousDiscPixel=null;
            for(int expected:new int[]{Color.RED,Color.GREEN}){
                covers.set(0,solid(expected));set(bundle,"recordTint",expected);Object card=snapshot.invoke(studio);
                Method export=ShareStudio.class.getDeclaredMethod("writeExport",android.content.Context.class,card.getClass());export.setAccessible(true);
                File png=(File)export.invoke(null,controller.get(),card);Bitmap image=BitmapFactory.decodeFile(png.getAbsolutePath());
                try{assertEquals(1600,image.getWidth());assertEquals(2000,image.getHeight());
                    assertEquals("Paper must not cover the real album in the center window",expected,image.getPixel(584,736));
                    assertEquals("Approved sleeve surface is painted outside its circular window",Color.BLUE,image.getPixel(887,709));
                    int discPixel=image.getPixel(1500,650);if(previousDiscPixel!=null)assertNotEquals("The exported disc must actually follow its new tint",previousDiscPixel.intValue(),discPixel);previousDiscPixel=discPixel;
                }finally{image.recycle();png.delete();}
            }
        }finally{studio.close();controller.pause().stop().destroy();}
    }
}
