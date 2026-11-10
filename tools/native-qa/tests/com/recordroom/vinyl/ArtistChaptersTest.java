package com.recordroom.vinyl;

import android.app.Activity;
import android.animation.ValueAnimator;
import org.robolectric.util.ReflectionHelpers;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Bundle;
import android.os.Looper;
import android.os.Parcel;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ListView;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
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

/** Real native Views and callbacks. Remote fixtures are explicit and never claim live-provider acceptance. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=34,qualifiers="w412dp-h860dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public class ArtistChaptersTest {
    ActivityController<Activity> controller;
    FrameLayout page;
    final ArrayList<String> events=new ArrayList<>();
    ArrayList<Track> played,opened;int playedAt=-1;
    final ArtistPage.Actions actions=new ArtistPage.Actions(){
        public void back(){events.add("back");}
        public void play(ArrayList<Track> songs,int index,boolean shuffle){played=songs;playedAt=index;events.add("play:"+index+":"+shuffle);}
        public void album(ArrayList<Track> songs){opened=songs;events.add("album");}
        public void share(String title,String subtitle,ArrayList<Track> songs){events.add("share:"+title);}
    };
    @Before public void setup(){controller=Robolectric.buildActivity(Activity.class).setup().visible();}
    @After public void close(){ReflectionHelpers.callStaticMethod(ValueAnimator.class,"setDurationScale",ReflectionHelpers.ClassParameter.from(float.class,1f));controller.pause().stop().destroy();OnlineMusic.useProviderForTests(controller.get(),null,null);}
    static Track local(String id,String title,String artist,String album,String albumArtist){return new Track(id,title,artist,album,"local","asset:///demo/night-1.wav","asset:///demo/night-v2.png",180000,1,albumArtist,"","2024");}
    static Object field(Object owner,String name)throws Exception{Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(owner);}
    private void show(List<Track> tracks,Track seed){page=ArtistPage.create(controller.get(),tracks,seed,actions);controller.get().setContentView(page);layout(412,860);}
    private void layout(int w,int h){for(int i=0;i<2;i++){page.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));page.layout(0,0,w,h);Shadows.shadowOf(Looper.getMainLooper()).idle();}page.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));page.layout(0,0,w,h);}
    static View find(View view,String name){if(name.contentEquals(view.getContentDescription()==null?"":view.getContentDescription())||(view instanceof TextView&&name.contentEquals(((TextView)view).getText())))return view;if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){View result=find(group.getChildAt(i),name);if(result!=null)return result;}}return null;}

    private void openSelectedAlbum(){
        View button=find(page,"打开专辑");
        if(button!=null){button.performClick();Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(1000));}
        View tracks=find(page,"查看曲目");assertNotNull(tracks);tracks.performClick();
    }
    @Test public void sleeveOpensBeforeTracksAndNeverStartsPlayback()throws Exception{
        Track seed=local("s","Song","Alice","Actual Album","Alice");show(Collections.singletonList(seed),seed);ArtistPage.selectChapter(page,2);layout(412,860);
        View open=find(page,"打开专辑");assertNotNull(open);open.performClick();assertNull(opened);assertNull(played);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(1000));
        assertNotNull(find(page,"查看曲目"));assertNull(opened);assertNull(played);
        Bundle saved=ArtistPage.saveState(page);assertTrue(saved.getBoolean("albums_expanded"));
        ArtistPage.selectChapter(page,1);ArtistPage.selectChapter(page,2);layout(412,860);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(1000));
        assertNotNull(find(page,"查看曲目"));find(page,"查看曲目").performClick();assertEquals(Collections.singletonList(seed),opened);assertNull(played);
        find(page,"收起唱片").performClick();Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(1000));assertNotNull(find(page,"打开专辑"));
    }
    @Test public void allArtistChaptersFollowCurrentCoverThemeAndRemoveSharing()throws Exception{
        Track seed=local("s","Song","Alice","Actual Album","Alice");show(Collections.singletonList(seed),seed);
        android.view.Window window=controller.get().getWindow();ArtistPage.bindWindow(page,window);
        int mask=View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        for(int chapter=0;chapter<3;chapter++){
            ArtistPage.selectChapter(page,chapter);assertEquals(DesignSystem.currentStage(controller.get()),window.getNavigationBarColor());
            assertEquals(android.graphics.Color.luminance(DesignSystem.currentStage(controller.get()))>.18?mask:0,window.getDecorView().getSystemUiVisibility()&mask);
            assertNull(find(page,"分享音乐人"));
        }
        assertFalse(window.isNavigationBarContrastEnforced());
    }
    @Test public void chapterTransitionsAnimateIncomingAndOutgoingViewsAndRespectReducedMotion()throws Exception{
        Track seed=local("s","Song","Alice","Actual Album","Alice");show(Collections.singletonList(seed),seed);
        ArtistPage.selectChapter(page,1);ValueAnimator transition=(ValueAnimator)field(page,"chapterAnimator");assertNotNull(transition);
        transition.setCurrentPlayTime(120);View body=(View)field(page,"body");assertTrue(body.getAlpha()>0&&body.getAlpha()<1);assertNotNull(field(page,"chapterOverlay"));
        ArtistPage.selectChapter(page,2);ValueAnimator next=(ValueAnimator)field(page,"chapterAnimator");assertNotNull(next);assertNotSame(transition,next);next.end();
        assertNull(field(page,"chapterOverlay"));assertEquals(1,body.getAlpha(),.001f);assertEquals(0,body.getTranslationX(),.001f);
        ReflectionHelpers.callStaticMethod(ValueAnimator.class,"setDurationScale",ReflectionHelpers.ClassParameter.from(float.class,0f));ArtistPage.selectChapter(page,0);
        assertNull(field(page,"chapterAnimator"));assertNull(field(page,"chapterOverlay"));assertEquals(1,body.getAlpha(),.001f);
    }
    @Test public void artistAlbumArrowsStayReachableAndWrapWithoutOpening()throws Exception{
        Track first=local("s1","Song 1","Alice","First album","Alice"),second=local("s2","Song 2","Alice","Second album","Alice");show(Arrays.asList(first,second),first);ArtistPage.selectChapter(page,2);layout(412,860);
        Object browser=field(page,"albumBrowser");AlbumStackView shelf=(AlbumStackView)field(browser,"stack");assertTrue(shelf.isLooping());
        View previous=find(page,"上一张专辑"),next=find(page,"下一张专辑");assertTrue(previous.isEnabled());assertTrue(next.isEnabled());previous.performClick();assertEquals(1,shelf.getSelectedIndex());next.performClick();assertEquals(0,shelf.getSelectedIndex());assertNull(opened);assertNull(played);
        assertTrue("Standing sleeves should use most of the shelf width",(float)field(shelf,"cardWidth")>=shelf.getWidth()*.80f);
    }
    @Test public void liveCoverThemeRefreshPreservesSelectedAlbumAndOpenIntent()throws Exception{
        Track first=local("s1","Song 1","Alice","First album","Alice"),second=local("s2","Song 2","Alice","Second album","Alice");show(Arrays.asList(first,second),first);ArtistPage.selectChapter(page,2);layout(412,860);
        AlbumStackView shelf=(AlbumStackView)field(field(page,"albumBrowser"),"stack");shelf.setScrollPosition(1);shelf.openSelectedSleeve();((ValueAnimator)field(shelf,"sleeveAnimator")).end();
        Bundle before=ArtistPage.saveState(page);DesignSystem.setAlbumTheme(controller.get(),"different-playing-album",0xff59262d,0xffca405b);ArtistPage.refreshTheme(page);layout(412,860);
        Bundle after=ArtistPage.saveState(page);assertEquals(before.getString("album_key"),after.getString("album_key"));assertTrue(after.getBoolean("albums_expanded"));assertEquals(2,ArtistPage.chapter(page));assertEquals(0xff59262d,(int)field(page,"stage"));
        assertNull(played);assertNull(opened);
    }
    @Test public void onlyActualArtistTracksAndArtworkEnterThePage(){
        Track a=local("a","Artist song","Alice","Actual Album","Alice"),b=local("b","Different artist song","Bob","Another Album","Bob");
        ArtistLibraryData data=new ArtistLibraryData("Alice","","",Arrays.asList(a,b,a),a,null);
        assertEquals(Collections.singletonList(a),data.songs);assertEquals(1,data.albums.size());assertEquals(a.artworkUri,data.albums.get(0).artwork);assertEquals("",data.portrait);
        MusicProvider.Artist wrong=new MusicProvider.Artist("wrong","陈奕迅","","https://example.invalid/eason.jpg",Collections.singletonList(b),Collections.emptyList());
        ArtistLibraryData guarded=new ArtistLibraryData("Alice","netease","requested",Collections.singletonList(a),a,wrong);
        assertEquals("",guarded.portrait);assertEquals(Collections.singletonList(a),guarded.songs);assertFalse(guarded.providerSongs);
    }
    @Test public void oneAlbumGroupsAllRealSongsAndThreePagesHaveNoLegacyCategories(){
        Track first=local("a","First","Alice","One Album","Alice"),second=local("b","Second","Alice","One Album","Alice");
        ArtistLibraryData data=new ArtistLibraryData("Alice","","",Arrays.asList(first,second,first),first,null);
        assertEquals(1,data.albums.size());assertEquals(Arrays.asList(first,second),data.albums.get(0).tracks);assertEquals(Arrays.asList(first,second),data.recommended);
        show(Arrays.asList(first,second),first);find(page,"播放推荐").performClick();assertEquals(Arrays.asList(first,second),played);
        ArtistPage.selectChapter(page,8);layout(412,860);assertEquals(2,ArtistPage.chapter(page));assertNotNull(find(page,"ALBUMS"));assertFalse(find(page,"下一章节").isEnabled());
    }
    @Test @Config(sdk=34,qualifiers="w320dp-h480dp-mdpi") public void recycledSongRowsPlayTheCorrectRealQueueAndIndex()throws Exception{
        ArrayList<Track> tracks=new ArrayList<>();for(int i=0;i<1200;i++)tracks.add(local("t"+i,"Actual song "+i,"Alice","Album "+i,"Alice"));
        ArrayList<Track> input=new ArrayList<>(tracks);input.add(local("other","Other","Bob","Other","Bob"));show(input,tracks.get(0));ArtistPage.selectChapter(page,1);layout(320,480);
        ListView list=(ListView)find(page,"音乐人资料库");assertNotNull(list);assertEquals(1201,list.getCount());assertTrue(list.getChildCount()<20);
        View row=list.getAdapter().getView(1,null,list);View rebound=list.getAdapter().getView(1200,row,list);assertSame(row,rebound);assertNotNull(find(rebound,"播放 Actual song 1199"));rebound.performClick();assertEquals(tracks,played);assertEquals(1199,playedAt);assertSame(tracks.get(1199),played.get(playedAt));
        list.setSelection(1200);layout(320,480);find(list,"播放 Actual song 1199").performClick();assertEquals(1199,playedAt);assertTrue(list.getChildCount()<20);
    }
    @Test public void chapterBoundsAndParcelStateSurviveRecreation()throws Exception{
        Track seed=local("s","Song","Alice","Actual Album","Alice");show(Collections.singletonList(seed),seed);
        ArtistPage.selectChapter(page,-20);assertEquals(0,ArtistPage.chapter(page));assertFalse(find(page,"上一章节").isEnabled());
        ArtistPage.selectChapter(page,50);assertEquals(2,ArtistPage.chapter(page));page.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_PAGE_DOWN));assertEquals(2,ArtistPage.chapter(page));assertFalse(find(page,"下一章节").isEnabled());
        page.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_PAGE_UP));assertEquals(1,ArtistPage.chapter(page));Bundle state=ArtistPage.saveState(page);Parcel parcel=Parcel.obtain();Bundle restored;try{parcel.writeBundle(state);parcel.setDataPosition(0);restored=parcel.readBundle(getClass().getClassLoader());}finally{parcel.recycle();}
        FrameLayout fresh=ArtistPage.create(controller.get(),Collections.singletonList(seed),seed,actions);ArtistPage.restoreState(fresh,restored);controller.get().setContentView(fresh);page=fresh;layout(726,512);assertEquals(1,ArtistPage.chapter(page));assertNotNull(find(page,"TOP SONGS"));assertNotNull(find(page,"播放 Song"));
    }
    @Test public void playbackRailUsesOnlyTheHostsCurrentStateAcrossChapters()throws Exception{
        Track seed=local("s","Artist seed","Alice","Artist album","Alice");show(Collections.singletonList(seed),seed);
        PlayerControls.Icon play=(PlayerControls.Icon)field(page,"railPlay"),cover=(PlayerControls.Icon)field(page,"railCover");
        Bitmap current=Bitmap.createBitmap(8,8,Bitmap.Config.ARGB_8888);current.eraseColor(0xff934a25);
        try{
            ArtistPage.updatePlayback(page,true,current,true);
            assertEquals("pause",field(play,"symbol"));assertEquals("暂停",play.getContentDescription());assertSame(current,field(cover,"artwork"));assertTrue(play.isEnabled());
            ArtistPage.selectChapter(page,2);layout(412,860);
            assertEquals("Chapter changes cannot replace current cover with the artist seed",current,field(cover,"artwork"));assertEquals("pause",field(play,"symbol"));
            ArtistPage.updatePlayback(page,false,null,false);
            assertEquals("play",field(play,"symbol"));assertEquals("播放",play.getContentDescription());assertNull(field(cover,"artwork"));assertFalse(play.isEnabled());
        }finally{current.recycle();}
    }
    @Test public void shortLandscapeKeepsNavigationInsideInsetsWith48dpTargets()throws Exception{
        Track seed=local("s","Song","Alice","Actual Album","Alice");
        ArtistPage.Navigation navigation=new ArtistPage.Navigation(){
            public void back(){events.add("back");}public void play(ArrayList<Track> songs,int index,boolean shuffle){}
            public void album(ArrayList<Track> songs){}public void share(String title,String subtitle,ArrayList<Track> songs){}
            public void library(){events.add("library");}public void current(){events.add("current");}
            public void queue(){events.add("queue");}public void toggle(){events.add("toggle");}
        };
        page=ArtistPage.create(controller.get(),Collections.singletonList(seed),seed,navigation);controller.get().setContentView(page);
        page.setPadding(12,24,12,24);ArtistPage.updatePlayback(page,false,null,true);
        for(int height:new int[]{320,360,412}){
            layout(726,height);ArrayList<android.graphics.Rect> bounds=new ArrayList<>();
            for(String label:new String[]{"返回","更多音乐人操作","音乐资料库","返回当前播放","播放队列"}){
                View button=find(page,label);assertNotNull(button);assertVisibleTarget(button,height,bounds);
            }
            assertVisibleTarget((View)field(page,"railPlay"),height,bounds);
        }
        find(page,"音乐资料库").performClick();find(page,"返回当前播放").performClick();find(page,"播放队列").performClick();((View)field(page,"railPlay")).performClick();
        assertEquals(Arrays.asList("library","current","queue","toggle"),events);
    }
    private void assertVisibleTarget(View target,int height,ArrayList<android.graphics.Rect> previous){
        assertTrue(target.getWidth()>=48);assertTrue(target.getHeight()>=48);
        android.graphics.Rect r=new android.graphics.Rect(0,0,target.getWidth(),target.getHeight());page.offsetDescendantRectToMyCoords(target,r);
        assertTrue("Control clipped by screen edge: "+target.getContentDescription()+" "+r,r.left>=12&&r.right<=714&&r.top>=24&&r.bottom<=height-24);
        for(android.graphics.Rect other:previous)assertFalse("Navigation targets overlap",android.graphics.Rect.intersects(r,other));previous.add(r);
    }
    @Test @Config(sdk=34,qualifiers="w726dp-h320dp-mdpi") public void shortLandscapeAlbumOpenIsVisibleWith48dpTarget()throws Exception{
        Track seed=local("s","Song","Alice","Actual Album","Alice");show(Collections.singletonList(seed),seed);page.setPadding(12,24,12,24);
        ArtistPage.selectChapter(page,ArtistLibraryData.ALBUMS);layout(726,320);
        View open=find(page,"打开专辑");assertNotNull(open);android.graphics.Rect visible=new android.graphics.Rect();assertTrue(open.getGlobalVisibleRect(visible));assertEquals(48,visible.height());
        openSelectedAlbum();assertEquals(Collections.singletonList(seed),opened);
        File out=new File(System.getProperty("vinyl.output.dir",System.getProperty("java.io.tmpdir")));out.mkdirs();
        Bitmap bitmap=Bitmap.createBitmap(726,320,Bitmap.Config.ARGB_8888);try{page.draw(new Canvas(bitmap));try(FileOutputStream stream=new FileOutputStream(new File(out,"artist-short-landscape-controls.png"))){assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,stream));}}finally{bitmap.recycle();}
    }
    @Test public void albumCallbacksKeepRealCompilationTracksWithoutLeakingIntoArtistSongs(){
        Track artist=local("a","Artist song","Alice","Compilation","Various Artists"),other=local("b","Other song","Bob","Compilation","Various Artists");
        ArtistLibraryData data=new ArtistLibraryData("Alice","","",Arrays.asList(artist,other),artist,null);
        assertEquals(1,data.albums.size());assertEquals(Arrays.asList(artist,other),data.albums.get(0).tracks);assertEquals(Collections.singletonList(artist),data.songs);
        show(Arrays.asList(artist,other),artist);ArtistPage.selectChapter(page,2);layout(412,860);openSelectedAlbum();assertEquals(Arrays.asList(artist,other),opened);assertSame(artist,opened.get(0));
    }
    @Test public void remoteArtistAndCompleteAlbumAreFetchedOffMainThread()throws Exception{
        FakeProvider provider=new FakeProvider();MemorySession session=new MemorySession();OnlineMusic.useProviderForTests(controller.get(),provider,session);show(Collections.singletonList(provider.first),provider.first);drain();
        ArtistLibraryData data=(ArtistLibraryData)field(page,"data");assertEquals(2,data.songs.size());assertEquals(provider.avatar,data.portrait);assertFalse(provider.calledOnMain);
        ArtistPage.selectChapter(page,2);layout(412,860);View album=find(page,"打开专辑");assertNotNull(album);openSelectedAlbum();drain();assertEquals(1,provider.albumCalls);assertEquals(Arrays.asList(provider.first,provider.second),opened);assertSame(provider.second,opened.get(1));
        ArtistPage.selectChapter(page,1);layout(412,860);find(page,"播放 Remote second").performClick();assertEquals(1,playedAt);assertSame(provider.second,played.get(playedAt));
    }
    @Test public void wrongRemoteIdentityNeverReplacesTheSelectedArtist()throws Exception{
        FakeProvider provider=new FakeProvider();provider.wrongArtist=true;OnlineMusic.useProviderForTests(controller.get(),provider,new MemorySession());show(Collections.singletonList(provider.first),provider.first);drain();ArtistLibraryData data=(ArtistLibraryData)field(page,"data");assertEquals("",data.portrait);assertEquals(1,data.songs.size());assertTrue((Boolean)field(page,"failed"));assertNull(opened);
    }
    @Test public void nativeThreeChapterRendersKeepAllControlsAndNoPaywall()throws Exception{
        ArrayList<Track> tracks=new ArrayList<>();tracks.add(local("a","Real track","Alice","Actual Album","Alice"));tracks.add(local("s","Real single","Alice","Release - Single","Alice"));tracks.add(local("l","Live track","Alice","Concert (Live)","Alice"));tracks.add(local("c","Compilation track","Alice","Greatest Hits","Alice"));tracks.add(local("f","Featured track","Alice","Collaborator Album","Another Artist"));
        new LibraryStore(controller.get()).create("Real saved list",Collections.singletonList(tracks.get(0)));show(tracks,tracks.get(0));
        File out=new File(System.getProperty("vinyl.output.dir",System.getProperty("java.io.tmpdir")));out.mkdirs();
        for(int chapter=0;chapter<3;chapter++){ArtistPage.selectChapter(page,chapter);layout(412,860);Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(320));Bitmap image=Bitmap.createBitmap(412,860,Bitmap.Config.ARGB_8888);try{page.draw(new Canvas(image));awaitCovers();page.draw(new Canvas(image));try(FileOutputStream stream=new FileOutputStream(new File(out,"artist-native-chapter-"+(chapter+1)+".png"))){assertTrue(image.compress(Bitmap.CompressFormat.PNG,100,stream));}}finally{image.recycle();}assertNotNull(find(page,"返回"));assertNull(find(page,"分享音乐人"));assertNull(find(page,"继续"));assertNull(find(page,"获取谜底黑胶全部功能"));}
    }
    @Test public void paginatedCatalogDeduplicatesCachedAlbumsAndOpensSelectedCompleteAlbum()throws Exception{
        PagingProvider provider=new PagingProvider();OnlineMusic.useProviderForTests(controller.get(),provider,new MemorySession());show(Collections.singletonList(provider.first),provider.first);drain();
        ArtistLibraryData data=(ArtistLibraryData)field(page,"data");assertEquals(Arrays.asList(0,2),provider.offsets);assertEquals(3,data.albums.size());assertTrue((Boolean)field(page,"catalogComplete"));assertFalse(provider.calledOnMain);
        find(page,"播放推荐").performClick();assertEquals(Arrays.asList(provider.first,provider.second),played);
        ArtistPage.selectChapter(page,2);layout(412,860);find(page,"下一张专辑").performClick();find(page,"下一张专辑").performClick();openSelectedAlbum();drain();assertEquals("third-album",provider.lastAlbum);assertEquals(Arrays.asList(provider.first,provider.second),opened);
        Bundle saved=ArtistPage.saveState(page);assertTrue(saved.getString("album_key").endsWith("third-album"));ArtistPage.selectChapter(page,1);ArtistPage.selectChapter(page,2);layout(412,860);assertNotNull(find(page,"Third Album"));
    }
    @Test public void failedLaterPageRetainsAlbumsAndRetryContinuesAtFailedOffset()throws Exception{
        PagingProvider provider=new PagingProvider();provider.failSecond=true;OnlineMusic.useProviderForTests(controller.get(),provider,new MemorySession());show(Collections.singletonList(provider.first),provider.first);drain();
        assertTrue((Boolean)field(page,"catalogFailed"));assertFalse((Boolean)field(page,"catalogComplete"));assertEquals(2,((ArtistLibraryData)field(page,"data")).albums.size());
        TextView status=(TextView)field(page,"status");assertTrue(status.getText().toString().contains("后续读取失败"));assertFalse(status.getText().toString().contains("全部专辑已载入"));status.performClick();drain();
        assertEquals(Arrays.asList(0,2,2),provider.offsets);assertEquals(3,((ArtistLibraryData)field(page,"data")).albums.size());assertTrue((Boolean)field(page,"catalogComplete"));assertFalse((Boolean)field(page,"catalogFailed"));
    }
    @Test public void qishuiCachedArtistUsesOnlyCachedDataAndNoDirectCatalog()throws Exception{
        Track seed=new Track("q","Cached song","Cached Artist","Cached Album","汽水音乐","qishui://song/q","",180000,1,"Cached Artist","","2024","qishui","album","artist");
        show(Collections.singletonList(seed),seed);drain();assertEquals("",field(page,"remoteId"));assertEquals("",field(page,"providerId"));assertNull(field(page,"remote"));assertFalse((Boolean)field(page,"catalogLoading"));assertEquals(Collections.singletonList(seed),((ArtistLibraryData)field(page,"data")).songs);
    }
    @Test public void detachedPageIgnoresLateCompleteAlbumCallback()throws Exception{
        java.util.concurrent.CountDownLatch entered=new java.util.concurrent.CountDownLatch(1),release=new java.util.concurrent.CountDownLatch(1);
        FakeProvider provider=new FakeProvider(){@Override public Album album(String id){entered.countDown();boolean done=false;while(!done)try{release.await();done=true;}catch(InterruptedException ignored){}return super.album(id);}};
        OnlineMusic.useProviderForTests(controller.get(),provider,new MemorySession());show(Collections.singletonList(provider.first),provider.first);drain();ArtistPage.selectChapter(page,2);layout(412,860);ExecutorService worker=(ExecutorService)field(page,"worker");openSelectedAlbum();
        try{assertTrue(entered.await(10,TimeUnit.SECONDS));controller.get().setContentView(new FrameLayout(controller.get()));}finally{release.countDown();}
        assertTrue(worker.awaitTermination(10,TimeUnit.SECONDS));Shadows.shadowOf(Looper.getMainLooper()).idle();assertNull(opened);assertNull(played);
    }
    @Test public void accountInvalidationRemovesQqAndAppleSeedAndAlbumsBeforeAnyNewRequest()throws Exception{
        for(String provider:new String[]{"qq","applemusic"}){
            Track seed=new Track("remote","Old account song","Alice","Private album",provider,provider+"://song/old","",180000,1,"Alice","","",provider,"old-album","old-artist");Track local=local("local","Local song","Alice","Local album","Alice");
            page=ArtistPage.create(controller.get(),Arrays.asList(seed,local),seed,actions);ArtistPage.selectChapter(page,2);ArtistPage.invalidateProvider(page,provider);controller.get().setContentView(page);layout(412,860);drain();
            ArtistLibraryData data=(ArtistLibraryData)field(page,"data");assertEquals(Collections.singletonList(local),data.songs);assertEquals(1,data.albums.size());assertEquals("Local album",data.albums.get(0).name);assertTrue((Boolean)field(page,"remoteDisabled"));assertFalse((Boolean)field(page,"loading"));assertFalse((Boolean)field(page,"catalogLoading"));assertFalse((Boolean)field(page,"albumLoading"));openSelectedAlbum();assertEquals(Collections.singletonList(local),opened);opened=null;
        }
    }
    @Test public void verifiedProviderAliasesAreAcceptedWithoutPermittingUnrelatedCatalogIds(){
        assertTrue(ArtistPage.resolvedIdentity("qq","22276","004Z85XP1c25b7",true));assertTrue(ArtistPage.resolvedIdentity("qq","004Z85XP1c25b7_1","004Z85XP1c25b7",true));
        assertTrue(ArtistPage.resolvedIdentity("applemusic","l.oldLibraryAlbum","123456",true));assertTrue(ArtistPage.resolvedIdentity("applemusic","r.libraryArtist","654321",false));
        assertFalse(ArtistPage.resolvedIdentity("qq","004Z85XP1c25b7","Different00000",true));assertFalse(ArtistPage.resolvedIdentity("qq","22276","Bad",true));assertFalse(ArtistPage.resolvedIdentity("qq","22276","004Z85XP1c25b7",false));assertFalse(ArtistPage.resolvedIdentity("applemusic","123456","99999",true));assertFalse(ArtistPage.resolvedIdentity("netease","123456","99999",true));
    }
    @Test public void truncatedFinalCatalogPageCannotClaimCompletenessAndCanRetry()throws Exception{
        PagingProvider provider=new PagingProvider();provider.truncateSecond=true;OnlineMusic.useProviderForTests(controller.get(),provider,new MemorySession());show(Collections.singletonList(provider.first),provider.first);drain();assertFalse((Boolean)field(page,"catalogComplete"));assertTrue((Boolean)field(page,"catalogFailed"));assertEquals(2,((ArtistLibraryData)field(page,"data")).albums.size());((View)field(page,"status")).performClick();drain();assertEquals(Arrays.asList(0,2,2),provider.offsets);assertTrue((Boolean)field(page,"catalogComplete"));
    }
    @Test public void duplicateAcrossCatalogPagesIsRejectedInsteadOfSilentlyDeduplicatingToComplete()throws Exception{
        PagingProvider provider=new PagingProvider();provider.duplicateSecond=true;OnlineMusic.useProviderForTests(controller.get(),provider,new MemorySession());show(Collections.singletonList(provider.first),provider.first);drain();assertFalse((Boolean)field(page,"catalogComplete"));assertTrue((Boolean)field(page,"catalogFailed"));((View)field(page,"status")).performClick();drain();assertTrue((Boolean)field(page,"catalogComplete"));assertEquals(3,((ArtistLibraryData)field(page,"data")).albums.size());
    }
    @Test public void changingCatalogTotalRestartsFromPageOneAndOnlyThenReportsComplete()throws Exception{
        PagingProvider provider=new PagingProvider();provider.changeTotal=true;OnlineMusic.useProviderForTests(controller.get(),provider,new MemorySession());show(Collections.singletonList(provider.first),provider.first);drain();assertTrue((Boolean)field(page,"catalogRestartRequired"));assertFalse((Boolean)field(page,"catalogComplete"));((View)field(page,"status")).performClick();drain();assertEquals(Arrays.asList(0,2,0,2),provider.offsets);assertTrue((Boolean)field(page,"catalogComplete"));assertEquals(4,((ArtistLibraryData)field(page,"data")).albums.size());
    }
    private static final class PagingProvider extends FakeProvider {
        final ArrayList<Integer> offsets=new ArrayList<>();boolean failSecond,truncateSecond,duplicateSecond,changeTotal,newCatalog;String lastAlbum="";
        @Override public Page<Album> artistAlbums(String id,int offset,int limit)throws ProviderException{
            check();offsets.add(offset);if(offset==2&&changeTotal){changeTotal=false;newCatalog=true;}if(offset==2&&failSecond){failSecond=false;throw new ProviderException(ProviderException.Kind.NETWORK,"Fixture interrupted page");}
            if(offset==2&&truncateSecond){truncateSecond=false;return new Page<>(Collections.emptyList(),3,2,false);}
            if(offset==2&&duplicateSecond){duplicateSecond=false;return new Page<>(Collections.singletonList(new Album("second-album","Second Album","Online Artist",id,first.artworkUri,Collections.emptyList())),3,3,false);}
            if(offset==0)return new Page<>(Arrays.asList(new Album("remote-album","Remote Album","Online Artist",id,first.artworkUri,Collections.emptyList()),new Album("second-album","Second Album","Online Artist",id,first.artworkUri,Collections.emptyList())),newCatalog?4:3,2,true);
            if(newCatalog)return new Page<>(Arrays.asList(new Album("third-album","Third Album","Online Artist",id,first.artworkUri,Collections.emptyList()),new Album("fourth-album","Fourth Album","Online Artist",id,first.artworkUri,Collections.emptyList())),4,4,false);
            return new Page<>(Collections.singletonList(new Album("third-album","Third Album","Online Artist",id,first.artworkUri,Collections.emptyList())),3,3,false);
        }
        @Override public Album album(String id){lastAlbum=id;return super.album(id);}
    }
    private void drain()throws Exception{for(int i=0;i<8;i++){((ExecutorService)field(page,"worker")).submit(()->{}).get(10,TimeUnit.SECONDS);Shadows.shadowOf(Looper.getMainLooper()).idle();}layout(412,860);}
    private void awaitCovers()throws Exception{Object loader=field(page,"covers");Map<?,?> pending=(Map<?,?>)field(loader,"waiters");long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);while(!pending.isEmpty()&&System.nanoTime()<until){Shadows.shadowOf(Looper.getMainLooper()).idle();Thread.sleep(15);}Shadows.shadowOf(Looper.getMainLooper()).idle();assertTrue(pending.isEmpty());}
    private static final class MemorySession implements ProviderSession{long generation;public String readCookies(){return "";}public void writeCookies(String value){}public void clear(){generation++;}public long generation(){return generation;}}
    private static class FakeProvider implements MusicProvider {
        final Track first=remote("1","Remote first"),second=remote("2","Remote second");final String avatar="asset:///demo/forest-v2.png";boolean calledOnMain,wrongArtist;int albumCalls;
        static Track remote(String id,String title){return new Track(id,title,"Online Artist","Remote Album","网易云音乐","netease://song/"+id,"asset:///demo/night-v2.png",190000,Integer.parseInt(id),"Online Artist","","2024","netease","remote-album","artist-id");}
        void check(){calledOnMain|=Looper.myLooper()==Looper.getMainLooper();}
        public String id(){return "netease";}
        public Artist artist(String id){check();return new Artist(wrongArtist?"other-id":id,"Online Artist","Provider biography",avatar,Arrays.asList(first,second),Collections.singletonList(new Album("remote-album","Remote Album","Online Artist","artist-id",first.artworkUri,Collections.emptyList())));}
        public Album album(String id){check();albumCalls++;return new Album(id,"Remote Album","Online Artist","artist-id",first.artworkUri,Arrays.asList(first,second));}
        public QrLogin beginQrLogin(){throw new UnsupportedOperationException();}public LoginPoll pollQrLogin(String key){throw new UnsupportedOperationException();}public Account account(){throw new UnsupportedOperationException();}public List<Playlist> playlists(String userId){throw new UnsupportedOperationException();}public List<Track> likedTracks(String userId){throw new UnsupportedOperationException();}public List<Track> playlistTracks(String playlistId){throw new UnsupportedOperationException();}public Page<Album> searchAlbums(String query,int offset,int limit){throw new UnsupportedOperationException();}public PlaybackResource resolve(Track track){throw new UnsupportedOperationException();}public void logout(){}
    }
}
