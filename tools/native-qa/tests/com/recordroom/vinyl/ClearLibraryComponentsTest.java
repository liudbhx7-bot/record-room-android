package com.recordroom.vinyl;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
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
import org.robolectric.util.ReflectionHelpers;
import org.json.JSONArray;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=34,qualifiers="w320dp-h480dp-mdpi",fontScale=1f)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public class ClearLibraryComponentsTest {
    private ActivityController<Activity> controller;
    private Activity activity;
    private final ArrayList<String> calls=new ArrayList<>();
    private final ArrayList<Track> tracks=new ArrayList<>();
    private final ArrayList<ArrayList<Track>> albums=new ArrayList<>();
    @Before public void setUp(){
        controller=Robolectric.buildActivity(Activity.class).setup().visible();activity=controller.get();
        for(int i=0;i<1200;i++){Track t=new Track("real-"+i,"真实歌曲 "+i,"音乐人","真实专辑 "+i,"local","file:///music/"+i,"",180000,1);tracks.add(t);ArrayList<Track> group=new ArrayList<>();group.add(t);albums.add(group);}
    }
    @After public void tearDown(){controller.pause().stop().destroy();}
    private void layout(View v,int w,int h){v.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));v.layout(0,0,w,h);Shadows.shadowOf(Looper.getMainLooper()).idle();v.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));v.layout(0,0,w,h);}
    private LibraryBrowserList library(){
        LibraryBrowserList v=new LibraryBrowserList(activity,new ThemeStore(activity));
        v.setActions(new LibraryBrowserList.Actions(){
            public void album(ArrayList<Track> songs,View origin){assertTrue(origin instanceof CoverView);calls.add("album:"+songs.get(0).id);}
            public void albumMenu(ArrayList<Track> songs){calls.add("menu:"+songs.get(0).id);}
            public void play(ArrayList<Track> songs,int index){assertEquals(1200,songs.size());calls.add("play:"+songs.get(index).id);}
            public void songMenu(Track song){calls.add("song:"+song.id);}
            public void artist(Track seed){calls.add("artist:"+seed.id);}
        });activity.setContentView(v);return v;
    }
    @Test public void artworkGridKeepsAllRealAlbumsAndRecyclesOnNarrowScreens(){
        LibraryBrowserList v=library();v.submit(albums,new ArrayList<>(),new ArrayList<>(),false);layout(v,320,480);
        assertEquals(400,v.getCount());assertTrue(v.getChildCount()>0&&v.getChildCount()<20);
        View first=v.getAdapter().getView(0,null,v);View last=v.getAdapter().getView(399,first,v);assertSame(first,last);
        ViewGroup row=(ViewGroup)last;assertEquals(3,row.getChildCount());row.getChildAt(2).performClick();row.getChildAt(2).performLongClick();
        assertEquals(java.util.Arrays.asList("album:real-1199","menu:real-1199"),calls);
        v.setSelection(399);layout(v,320,480);assertTrue(v.getLastVisiblePosition()>=399);assertTrue(v.getChildCount()<20);
        layout(v,280,480);assertEquals(600,v.getCount());ViewGroup narrow=(ViewGroup)v.getAdapter().getView(599,null,v);assertEquals(2,narrow.getChildCount());
        layout(v,437,480);ViewGroup wide=(ViewGroup)v.getAdapter().getView(0,null,v);ViewGroup firstCell=(ViewGroup)wide.getChildAt(0);assertTrue("Cover size follows this measurement, not the previous narrow width",firstCell.getChildAt(0).getLayoutParams().height>=138);
    }
    @Test public void searchKeepsFullResultsAndDoesNotUseStaleCallbacks(){
        LibraryBrowserList v=library();v.submit(new ArrayList<>(),tracks,new ArrayList<>(),true);layout(v,320,480);
        assertEquals(1201,v.getCount());View row=v.getAdapter().getView(1,null,v);View last=v.getAdapter().getView(1200,row,v);assertSame(row,last);
        last.performClick();last.performLongClick();assertEquals(java.util.Arrays.asList("play:real-1199","song:real-1199"),calls);
        v.submit(new ArrayList<>(),new ArrayList<>(),new ArrayList<>(),true);last.performClick();assertEquals(2,calls.size());
    }
    @Test public void longShelfRemainsFiniteAndLastAlbumCanOpenThroughKeyboard(){
        AlbumStackView stack=new AlbumStackView(activity);activity.setContentView(stack);stack.setPadding(0,64,0,72);stack.setAlbums(albums);stack.setOnAlbumClickListener(songs->calls.add(songs.get(0).id));layout(stack,320,480);
        stack.setScrollPosition(1199);for(int i=0;i<5;i++)stack.onKeyDown(KeyEvent.KEYCODE_DPAD_DOWN,new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_DOWN));
        stack.performClick();assertEquals(java.util.Collections.singletonList("real-1199"),calls);
        Bitmap canvas=Bitmap.createBitmap(320,480,Bitmap.Config.ARGB_8888);stack.draw(new Canvas(canvas));int drawn=ReflectionHelpers.getField(stack,"drawnCount");assertTrue(drawn>0&&drawn<=18);canvas.recycle();
    }
    @Test public void transparentQueueKeepsRebindingAndAccessibleTouchTargets(){
        QueueListView queue=new QueueListView(activity);queue.setInkColor(Color.WHITE);queue.setActions(new QueueListView.Actions(){public void play(int i){calls.add("play:"+i);}public void menu(int i){calls.add("menu:"+i);}public void move(int from,int to){calls.add("move:"+from+":"+to);}});
        activity.setContentView(queue);queue.submit(tracks,150);layout(queue,180,480);assertTrue(queue.getChildCount()>0&&queue.getChildCount()<20);
        View old=queue.getAdapter().getView(0,null,queue);ViewGroup row=(ViewGroup)queue.getAdapter().getView(150,old,queue);assertSame(old,row);layout(row,180,112);
        row.getChildAt(0).performClick();row.getChildAt(1).performClick();assertEquals(java.util.Arrays.asList("play:150","menu:150"),calls);assertTrue(row.getChildAt(1).getWidth()>=48);assertTrue(row.isSelected());
        queue.submit(tracks,0);row.getChildAt(0).performClick();assertEquals(2,calls.size());
        PlayerControls.Icon mode=new PlayerControls.Icon(activity,"repeat");mode.setGlass(true);mode.setActive(true);layout(mode,48,48);assertTrue(mode.isSelected());assertEquals(48,mode.getMeasuredWidth());
    }
    @Test public void renderClearNativeViewsUsingExistingProjectCatalogue() throws Exception {
        String base=System.getProperty("vinyl.project.dir")+"/app/src/main/assets/";
        JSONArray source=new JSONArray(Files.readString(Path.of(base+"demo/catalog.json")));
        ArrayList<Track> songs=new ArrayList<>();LinkedHashMap<String,ArrayList<Track>> groups=new LinkedHashMap<>();
        for(int i=0;i<source.length();i++){Track t=Track.fromJson(source.getJSONObject(i));t=new Track(t.id,t.title,t.artist,t.album,t.source,t.uri,t.artworkUri.replace("asset:///","file://"+base),t.durationMs,t.trackNumber);songs.add(t);groups.computeIfAbsent(t.albumKey(),k->new ArrayList<>()).add(t);}
        LibraryBrowserList list=library();list.setInkColor(Color.WHITE);list.submit(new ArrayList<>(groups.values()),new ArrayList<>(),new ArrayList<>(),false);layout(list,437,641);CoverView.IO.submit(()->{}).get();Shadows.shadowOf(Looper.getMainLooper()).idle();snapshot(list,437,641,"clear-grid-native.png");
        AlbumStackView stack=new AlbumStackView(activity);activity.setContentView(stack);stack.setInkColor(Color.WHITE);stack.setAlbums(new ArrayList<>(groups.values()));layout(stack,437,603);Thread.sleep(250);Shadows.shadowOf(Looper.getMainLooper()).idle();snapshot(stack,437,603,"clear-shelf-native.png");
        QueueListView queue=new QueueListView(activity);activity.setContentView(queue);queue.setInkColor(Color.WHITE);queue.submit(songs,1);layout(queue,190,486);snapshot(queue,190,486,"clear-queue-native.png");
        LinearLayout dock=new LinearLayout(activity);dock.setOrientation(LinearLayout.VERTICAL);dock.setBackground(PlayerControls.glassCapsule(activity));for(String icon:new String[]{"albums","disc","play","queue"})dock.addView(new PlayerControls.Icon(activity,icon),new LinearLayout.LayoutParams(52,48));layout(dock,52,192);snapshot(dock,52,192,"clear-dock-native.png");
    }
    private void snapshot(View view,int w,int h,String name)throws Exception{layout(view,w,h);Bitmap b=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);Canvas c=new Canvas(b);c.drawColor(0xff344e61);view.draw(c);File out=new File(System.getProperty("vinyl.output.dir"));out.mkdirs();try(FileOutputStream stream=new FileOutputStream(new File(out,name))){b.compress(Bitmap.CompressFormat.PNG,100,stream);}b.recycle();}
}
