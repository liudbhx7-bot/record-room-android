package com.recordroom.vinyl;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.RectF;
import android.os.Looper;
import android.os.SystemClock;
import android.util.LruCache;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.Button;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
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

/** Native Canvas evidence, using the app's real demo catalog and decoded cover assets. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=34, qualifiers="w412dp-h800dp-mdpi", fontScale=1f)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public class UprightAlbumRenderTest {
    private ActivityController<Activity> controller;
    private Activity activity;
    private FrameLayout host;

    @Before public void setup() {
        controller=Robolectric.buildActivity(Activity.class);
        activity=controller.get();
        activity.setTheme(android.R.style.Theme_Material_Light_NoActionBar);
        controller.setup().visible();
        host=new FrameLayout(activity);
        activity.setContentView(host);
    }

    @After public void teardown() {
        if(controller!=null)controller.pause().stop().destroy();
    }

    @Test public void portraitRendersRealSleevesThroughBothAnimationStages() throws Exception {
        renderStages(412,500,"portrait");
    }

    @Test public void landscapeRendersRealSleevesThroughBothAnimationStages() throws Exception {
        renderStages(900,420,"landscape");
    }

    @Test public void nineEntryDisplayFixtureKeepsTheSelectedSleeveInFront() throws Exception {
        AlbumStackView shelf=shelf(900,420);
        ArrayList<ArrayList<Track>> albums=demoAlbums();
        ArrayList<AlbumStackView.Entry> fixture=new ArrayList<>();
        // Explicit display-density fixture: three actual demo covers repeated, not an online catalog.
        for(int i=0;i<9;i++) {
            ArrayList<Track> tracks=albums.get(i%albums.size());
            Track first=tracks.get(0);
            fixture.add(new AlbumStackView.Entry("display-fixture-"+i,first.album,first.artist,first.artworkUri,tracks));
        }
        shelf.setEntries(fixture);
        shelf.setScrollPosition(4);
        ((ExecutorService)field(shelf,"artExecutor")).submit(()->{}).get(30,TimeUnit.SECONDS);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        LruCache<?,?> covers=(LruCache<?,?>)field(shelf,"covers");
        assertEquals("All nine fixture sleeves must use decoded demo artwork",9L,
                covers.snapshot().keySet().stream().filter(key->key.toString().startsWith("display-fixture-")).count());
        renderStages(shelf,"landscape-nine-entry-fixture");
        assertEquals(4,shelf.getSelectedIndex());
    }

    @Test public void completeArtistPageKeepsOpenSleeveAndActionsVisibleInBothOrientations() throws Exception {
        ArrayList<Track> fixture=new ArrayList<>();
        // This UI fixture assigns the three genuine demo releases to one explicitly named test artist.
        // Audio URIs, track titles, album titles and all cover pixels remain the real bundled assets.
        String artist="封套架验收 · 本地演示";
        for(ArrayList<Track> album:demoAlbums())for(Track track:album)
            fixture.add(new Track(track.id,track.title,artist,track.album,track.source,track.uri,
                    track.artworkUri,track.durationMs,track.trackNumber,artist));
        int[] opened={0};
        ArtistPage.Actions actions=new ArtistPage.Actions(){
            public void back(){}
            public void play(ArrayList<Track> tracks,int index,boolean shuffle){}
            public void album(ArrayList<Track> tracks){opened[0]++;}
            public void share(String title,String subtitle,ArrayList<Track> tracks){}
        };
        for(int[] size:new int[][]{{412,860},{726,512}}) {
            FrameLayout page=ArtistPage.create(activity,fixture,fixture.get(0),actions);
            activity.setContentView(page);
            for(int chapter=0;chapter<3;chapter++){
                ArtistPage.selectChapter(page,chapter);
                for(int i=0;i<2;i++){layout(page,size[0],size[1]);Shadows.shadowOf(Looper.getMainLooper()).idle();}
                Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(320));draw(page,null);
                Object coverLoader=field(page,"covers");ExecutorService loader=(ExecutorService)field(coverLoader,"worker");
                for(int wait=0;wait<40&&!((java.util.Map<?,?>)field(coverLoader,"waiters")).isEmpty();wait++){loader.submit(()->{}).get(30,TimeUnit.SECONDS);Thread.sleep(5);Shadows.shadowOf(Looper.getMainLooper()).idle();}
                assertTrue("Gallery waits for every real hero cover",((java.util.Map<?,?>)field(coverLoader,"waiters")).isEmpty());
                layout(page,size[0],size[1]);if(chapter<2)draw(page,"artist-chapter-"+(chapter+1)+"-"+size[0]+"x"+size[1]+"-demo-fixture.png");
            }
            Object browser=field(page,"albumBrowser");
            AlbumStackView shelf=(AlbumStackView)field(browser,"stack");
            assertEquals(3,shelf.getAlbumCount());
            shelf.setScrollPosition(1);
            ((ExecutorService)field(shelf,"artExecutor")).submit(()->{}).get(30,TimeUnit.SECONDS);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals(3,((LruCache<?,?>)field(shelf,"covers")).snapshot().size());
            layout(page,size[0],size[1]);draw(page,"artist-closed-sleeve-"+size[0]+"x"+size[1]+"-demo-fixture.png");
            Button open=(Button)field(browser,"openAlbum");
            assertTrue(open.isEnabled());
            open.performClick();
            assertEquals("Page control must unpack before navigating",0,opened[0]);
            assertTrue("Track entry must be usable immediately while the sleeve is moving",open.isEnabled());
            assertEquals("查看曲目",open.getText().toString());
            Button play=(Button)field(browser,"playAlbum");assertTrue(play.isEnabled());assertEquals(View.VISIBLE,play.getVisibility());
            animator(shelf).setCurrentPlayTime(200);layout(page,size[0],size[1]);draw(page,"artist-immediate-actions-"+size[0]+"x"+size[1]+"-demo-fixture.png");
            animator(shelf).end();
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(800));
            shelf.computeScroll();
            layout(page,size[0],size[1]);
            assertTrue(shelf.isSleeveOpen());
            assertTrue(open.isEnabled());
            assertEquals("查看曲目",open.getText().toString());
            for(String name:new String[]{"openAlbum","playAlbum","expand","previousAlbum","nextAlbum"}) {
                View control=(View)field(browser,name);
                android.graphics.Rect bounds=new android.graphics.Rect(0,0,control.getWidth(),control.getHeight());
                page.offsetDescendantRectToMyCoords(control,bounds);
                assertTrue("Album control must retain a visible 48dp target: "+name,
                        control.getVisibility()==View.VISIBLE&&bounds.height()>=48&&bounds.width()>=48
                                &&bounds.left>=0&&bounds.top>=0&&bounds.right<=size[0]&&bounds.bottom<=size[1]);
            }
            draw(page,"artist-open-sleeve-"+size[0]+"x"+size[1]+"-demo-fixture.png");
        }
    }

    @Test public void firstTapUnpacksAndOnlyASecondCompletedTapOpensTheAlbum() throws Exception {
        AlbumStackView shelf=shelf(412,500);
        int[] opened={-1};
        shelf.setOnAlbumIndexClickListener(index->opened[0]=index);
        draw(shelf,null);
        tapSelected(shelf);
        assertEquals("Unpacking the sleeve must not navigate away",-1,opened[0]);
        ValueAnimator animation=animator(shelf);
        animation.setCurrentPlayTime(300);
        assertFalse(shelf.isSleeveOpen());
        shelf.performClick();
        assertEquals("A tap while the sleeve is moving cannot open a track list",-1,opened[0]);
        animation.end();
        // The sleeve animator and its independent centering scroller both finish in real time.
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(800));
        shelf.computeScroll();
        assertTrue(shelf.isSleeveOpen());
        draw(shelf,null);
        tapSelected(shelf);
        assertEquals("The opened track list belongs to the selected real album",1,opened[0]);
    }

    @Test public void horizontalBrowseClosesTheSleeveWithoutOpeningTracksAndCanReachBothEnds() throws Exception {
        AlbumStackView shelf=shelf(412,500);
        int[] opened={0};
        shelf.setOnAlbumIndexClickListener(index->opened[0]++);
        shelf.openSelectedSleeve();
        animator(shelf).end();
        draw(shelf,null);
        long down=SystemClock.uptimeMillis();
        event(shelf,down,down,MotionEvent.ACTION_DOWN,310,250);
        event(shelf,down,down+80,MotionEvent.ACTION_MOVE,190,250);
        event(shelf,down,down+160,MotionEvent.ACTION_MOVE,60,250);
        event(shelf,down,down+190,MotionEvent.ACTION_UP,60,250);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(900));
        shelf.computeScroll();
        assertTrue("A horizontal swipe must browse later sleeves",shelf.getSelectedIndex()>1);
        assertFalse("Browsing another album must close the previous sleeve",shelf.isSleeveOpen());
        assertEquals("Dragging is never a track-list click",0,opened[0]);
        shelf.setScrollPosition(0);
        assertEquals(0,shelf.getSelectedIndex());
        assertFalse(shelf.selectAlbum(-1));
        shelf.setScrollPosition(shelf.getAlbumCount()-1);
        assertEquals(2,shelf.getSelectedIndex());
        assertFalse(shelf.selectAlbum(1));
    }

    private void renderStages(int width,int height,String size) throws Exception {
        AlbumStackView shelf=shelf(width,height);
        assertEquals(3,shelf.getAlbumCount());
        assertEquals(1,shelf.getSelectedIndex());
        renderStages(shelf,size);
    }

    private void renderStages(AlbumStackView shelf,String size) throws Exception {
        assertFalse(shelf.isSleeveOpen());
        draw(shelf,"upright-"+size+"-1-closed.png");
        shelf.openSelectedSleeve();
        ValueAnimator animation=animator(shelf);
        animation.setCurrentPlayTime(280);
        float turning=shelf.getSleeveOpenProgress();
        assertTrue(turning>0&&turning<1);
        draw(shelf,"upright-"+size+"-2-turning.png");
        animation.setCurrentPlayTime(600);
        assertTrue(shelf.getSleeveOpenProgress()>turning);
        assertFalse(shelf.isSleeveOpen());
        draw(shelf,"upright-"+size+"-3-extracting.png");
        animation.end();
        assertTrue(shelf.isSleeveOpen());
        assertEquals(1f,shelf.getSleeveOpenProgress(),.001f);
        draw(shelf,"upright-"+size+"-4-open.png");
        RectF selected=selectedBounds(shelf);
        assertTrue("Selected sleeve must remain inside the viewport",selected.left>=0&&selected.top>=0&&selected.right<=shelf.getWidth()&&selected.bottom<=shelf.getHeight());
    }

    private AlbumStackView shelf(int width,int height) throws Exception {
        AlbumStackView shelf=new AlbumStackView(activity);
        shelf.setUpright(true);
        shelf.setInkColor(0xfff0ede7);
        host.addView(shelf,new FrameLayout.LayoutParams(width,height));
        layout(host,width,height);
        layout(shelf,width,height);
        shelf.setAlbums(demoAlbums());
        shelf.setScrollPosition(1);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        ExecutorService executor=(ExecutorService)field(shelf,"artExecutor");
        assertNotNull("The actual view must be attached before loading cover assets",executor);
        executor.submit(()->{}).get(30,TimeUnit.SECONDS);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        LruCache<?,?> covers=(LruCache<?,?>)field(shelf,"covers");
        assertEquals("All three images must be decoded; placeholder sleeves are not render evidence",3,covers.snapshot().size());
        return shelf;
    }

    private ArrayList<ArrayList<Track>> demoAlbums() throws Exception {
        JSONArray catalog;
        try(InputStream stream=activity.getAssets().open("demo/catalog.json")) {
            catalog=new JSONArray(new String(stream.readAllBytes(),StandardCharsets.UTF_8));
        }
        LinkedHashMap<String,ArrayList<Track>> albums=new LinkedHashMap<>();
        for(int i=0;i<catalog.length();i++) {
            Track track=Track.fromJson(catalog.getJSONObject(i));
            albums.computeIfAbsent(track.albumKey(),key->new ArrayList<>()).add(track);
        }
        return new ArrayList<>(albums.values());
    }

    private static ValueAnimator animator(AlbumStackView shelf) throws Exception {
        ValueAnimator animator=(ValueAnimator)field(shelf,"sleeveAnimator");
        assertNotNull("Use the real production animator, not a synthetic screenshot state",animator);
        return animator;
    }

    private static RectF selectedBounds(AlbumStackView shelf) throws Exception {
        Object[] cards=(Object[])field(shelf,"drawn");
        int count=(Integer)field(shelf,"drawnCount");
        for(int i=0;i<count;i++)if((Integer)field(cards[i],"index")==shelf.getSelectedIndex()) {
            float[] quad=(float[])field(cards[i],"quad");
            RectF bounds=new RectF(quad[0],quad[1],quad[0],quad[1]);
            for(int n=2;n<quad.length;n+=2)bounds.union(quad[n],quad[n+1]);
            return bounds;
        }
        throw new AssertionError("The selected sleeve was not drawn");
    }

    private static void tapSelected(AlbumStackView shelf) throws Exception {
        RectF bounds=selectedBounds(shelf);
        long down=SystemClock.uptimeMillis();
        event(shelf,down,down,MotionEvent.ACTION_DOWN,bounds.centerX(),bounds.centerY());
        event(shelf,down,down+32,MotionEvent.ACTION_UP,bounds.centerX(),bounds.centerY());
    }

    private static void event(View view,long down,long at,int action,float x,float y) {
        MotionEvent event=MotionEvent.obtain(down,at,action,x,y,0);
        try { assertTrue(view.dispatchTouchEvent(event)); } finally { event.recycle(); }
    }

    private static Object field(Object target,String name) throws Exception {
        Field field=target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void layout(View view,int width,int height) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));
        view.layout(0,0,width,height);
    }

    private static void draw(View view,String name) throws Exception {
        Bitmap bitmap=Bitmap.createBitmap(view.getWidth(),view.getHeight(),Bitmap.Config.ARGB_8888);
        Canvas canvas=new Canvas(bitmap);
        canvas.drawColor(0xff25262c);
        view.draw(canvas);
        try {
            if(name==null)return;
            File directory=new File(System.getProperty("vinyl.output.dir"));
            assertTrue(directory.isDirectory()||directory.mkdirs());
            try(FileOutputStream stream=new FileOutputStream(new File(directory,name))) {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,stream));
            }
        } finally { bitmap.recycle(); }
    }
}
