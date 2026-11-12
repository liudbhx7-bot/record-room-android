package com.recordroom.vinyl;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Bundle;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
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

/** Real native widgets with deterministic protocol fixtures; no live-account acceptance claim. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=34,qualifiers="w412dp-h860dp-mdpi",fontScale=1f)
@GraphicsMode(GraphicsMode.Mode.NATIVE) @LooperMode(LooperMode.Mode.PAUSED)
public class AggregateSearchPageTest {
    ActivityController<Activity> controller;AggregateSearchPage page;AggregateSearch engine;Dialog dialog;
    List<AggregateSearch.Result> played;int playedAt=-1,accounts,closed;CountDownLatch release;
    final AggregateSearchPage.Actions actions=new AggregateSearchPage.Actions(){public void play(List<AggregateSearch.Result> values,int at){played=values;playedAt=at;}public void manageAccounts(){accounts++;}};
    @Before public void setup(){controller=Robolectric.buildActivity(Activity.class);controller.get().setTheme(android.R.style.Theme_Material_Light_NoActionBar);controller.setup().visible();}
    @After public void cleanup(){if(release!=null)release.countDown();if(page!=null)page.close();controller.pause().stop().destroy();Shadows.shadowOf(Looper.getMainLooper()).idle();}
    static Object field(Object value,String name)throws Exception{Field f=value.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(value);}
    void show(Bundle saved,AggregateSearch.Source... sources){engine=new AggregateSearch(Arrays.asList(sources));page=AggregateSearchPage.restore(controller.get(),engine,actions,saved);page.setOnClosed(()->closed++);dialog=page.show();}
    EditText input()throws Exception{return (EditText)field(page,"input");}ListView list()throws Exception{return (ListView)field(page,"list");}
    void query(String value)throws Exception{input().setText(value);Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(351));AggregateSearchTest.await(()->!engine.state().loading);}
    static android.widget.ListAdapter dataRows(ListView list){
        android.widget.ListAdapter adapter=list.getAdapter();
        return adapter instanceof android.widget.HeaderViewListAdapter?((android.widget.HeaderViewListAdapter)adapter).getWrappedAdapter():adapter;
    }
    View row(int index)throws Exception{return dataRows(list()).getView(index,null,list());}
    static String text(View view){StringBuilder result=new StringBuilder();if(view instanceof TextView)result.append(((TextView)view).getText()).append('\n');if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)result.append(text(((ViewGroup)view).getChildAt(i)));return result.toString();}

    @Test public void typingDebouncesAndChangingQueryInvalidatesAlreadyBoundRows()throws Exception{
        List<String> queries=Collections.synchronizedList(new ArrayList<>());
        AggregateSearchTest.Fake provider=new AggregateSearchTest.Fake("qq",(q,o,l)->{queries.add(q);return AggregateSearchTest.page(AggregateSearchTest.track("qq",q,q,"Album",180000));});
        show(null,AggregateSearchTest.source(provider,0));input().setText("first");Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200));input().setText("second");Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(349));assertTrue(queries.isEmpty());
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2));AggregateSearchTest.await(()->!engine.state().loading);assertEquals(Collections.singletonList("second"),queries);View old=row(0);
        input().setText("third");old.performClick();assertNull(played);assertEquals(0,dataRows(list()).getCount());Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(351));AggregateSearchTest.await(()->!engine.state().loading);row(0).performClick();assertEquals("third",played.get(playedAt).displayTrack.title);
    }

    @Test public void visibleResultsHidePlatformLabelsAndErrorsButPlaybackRetainsAlternatives()throws Exception{
        Track qq=AggregateSearchTest.track("qq","q","同一首歌","专辑",180000),net=AggregateSearchTest.track("netease","n","同一首歌","专辑",180000);
        AggregateSearchTest.Fake a=new AggregateSearchTest.Fake("qq",(q,o,l)->AggregateSearchTest.page(qq));AggregateSearchTest.Fake b=new AggregateSearchTest.Fake("netease",(q,o,l)->AggregateSearchTest.page(net));
        AggregateSearchTest.Fake c=new AggregateSearchTest.Fake("applemusic",(q,o,l)->{throw new ProviderException(ProviderException.Kind.NETWORK,"Apple Music private diagnostic");});
        show(null,AggregateSearchTest.source(a,10),AggregateSearchTest.source(b,0),AggregateSearchTest.source(c,2));query("同一首歌");assertEquals(1,dataRows(list()).getCount());
        String visible=text(dialog.getWindow().getDecorView())+text(row(0));for(String label:new String[]{"QQ","qq","网易云","netease","Apple Music","applemusic","private diagnostic","来源"})assertFalse("Unexpected platform label: "+label,visible.contains(label));
        row(0).performClick();assertEquals(0,playedAt);assertEquals(Arrays.asList(net,qq),played.get(0).alternatives);assertTrue(visible.contains("部分结果暂未加载"));
        View account=findText(dialog.getWindow().getDecorView(),"账户");assertNotNull(account);account.performClick();assertEquals(1,accounts);
    }

    @Test public void restorationReloadsPageDepthWithoutStoringTrackOrAccountData()throws Exception{
        ArrayList<Track> tracks=new ArrayList<>();for(int i=0;i<31;i++)tracks.add(AggregateSearchTest.track("qq","q"+i,"Song "+i,"Album",180000));
        AggregateSearchTest.Fake provider=new AggregateSearchTest.Fake("qq",(q,o,l)->{int end=Math.min(tracks.size(),o+l);return new MusicProvider.Page<>(tracks.subList(o,end),tracks.size(),end,end<tracks.size());});
        show(null,AggregateSearchTest.source(provider,0));query("Song");((View)field(page,"more")).performClick();AggregateSearchTest.await(()->!engine.state().loading);assertEquals(31,dataRows(list()).getCount());
        Bundle saved=page.snapshot();assertEquals("Song",saved.getString("query"));assertEquals(31,saved.getInt("loaded_count"));for(String forbidden:new String[]{"tracks","cookies","token","account"})assertFalse(saved.containsKey(forbidden));
        page.close();provider.offsets.clear();show(saved,AggregateSearchTest.source(provider,0));AggregateSearchTest.await(()->!engine.state().loading&&engine.state().results.size()==31);assertEquals(Arrays.asList(0,25),provider.offsets);row(30).performClick();assertEquals("Song 30",played.get(playedAt).displayTrack.title);
    }

    @Test public void recordsOnlyCommittedQueriesAndCanClearHistory()throws Exception{
        AggregateSearchTest.Fake provider=new AggregateSearchTest.Fake("qq",(q,o,l)->AggregateSearchTest.page(AggregateSearchTest.track("qq","id",q,"专辑",180000)));
        show(null,AggregateSearchTest.source(provider,0));
        View header=(View)field(page,"header");
        assertNotNull(findText(header,"浏览曲库"));
        assertNull(findText(header,"最近搜索"));
        query("未提交");query("夜航");
        row(0).performClick();
        input().setText("");
        assertNotNull(findText(header,"最近搜索"));
        assertNotNull(findText(header,"夜航"));
        assertNull(findText(header,"未提交"));
        page.close();show(null,AggregateSearchTest.source(provider,0));header=(View)field(page,"header");
        findText(header,"夜航").performClick();assertEquals("夜航",input().getText().toString());
        input().setText("");findText(header,"清空").performClick();assertNull(findText(header,"最近搜索"));
    }

    @Test public void albumOnlyQueryOpensRealCatalogIdentityWithoutAddingSongResults()throws Exception{
        MusicProvider.Album record=new MusicProvider.Album("remote-id","独立专辑","音乐人","","asset:///demo/sun-v2.png",Collections.emptyList());
        MusicProvider provider=(MusicProvider)java.lang.reflect.Proxy.newProxyInstance(MusicProvider.class.getClassLoader(),new Class<?>[]{MusicProvider.class},(proxy,method,args)->{
            switch(method.getName()){
                case "id":return "qq";
                case "supportsArtistSearch":return false;
                case "supportsAlbumSearch":return true;
                case "searchTracks":return AggregateSearchTest.page();
                case "searchAlbums":return new MusicProvider.Page<>(Collections.singletonList(record),1,1,false);
                default:throw new AssertionError(method.getName());
            }
        });
        engine=new AggregateSearch(Collections.singletonList(new AggregateSearch.Source(provider,()->0,()->true,()->0)));
        final AggregateSearch.AlbumResult[] opened={null};
        page=new AggregateSearchPage(controller.get(),engine,new AggregateSearchPage.Actions(){
            public void catalogAlbum(AggregateSearch.AlbumResult value){opened[0]=value;}
            public void manageAccounts(){}
            public void play(List<AggregateSearch.Result> r,int at){fail();}
        });
        dialog=page.show();query("独立专辑");AggregateSearchTest.await(()->!engine.state().albumLoading);
        assertTrue(engine.state().results.isEmpty());
        SearchArtistEntryTest.find((View)field(page,"header"),"打开专辑 独立专辑，音乐人").performClick();
        assertSame(record,opened[0].album);assertEquals("qq",opened[0].providerId);
    }

    @Test public void recentSleevesAndAlbumTabOpenTheirActualAlbumOnly()throws Exception{
        Track a=new Track("a","夜航","演示音乐人","夜色缓慢降落","QQ音乐","qq://song/a","asset:///demo/night-v2.png",180000,1,"演示音乐人","","","qq","realAlbum","artist");
        Track b=new Track("b","灯火","演示音乐人","夜色缓慢降落","QQ音乐","qq://song/b","asset:///demo/night-v2.png",180000,2,"演示音乐人","","","qq","realAlbum","artist");
        AggregateSearchTest.Fake provider=new AggregateSearchTest.Fake("qq",(q,o,l)->AggregateSearchTest.page(a,b));
        final Track[] opened={null};
        engine=new AggregateSearch(Collections.singletonList(AggregateSearchTest.source(provider,0)));
        page=new AggregateSearchPage(controller.get(),engine,new AggregateSearchPage.Actions(){
            public List<Track> recentTracks(){return Arrays.asList(a,b);}
            public List<Track> libraryTracks(){return Arrays.asList(a,b);}
            public void album(Track track){opened[0]=track;}
            public void manageAccounts(){}
            public void play(List<AggregateSearch.Result> tracks,int index){fail("Album navigation cannot play a song");}
        });
        dialog=page.show();
        View header=(View)field(page,"header");assertNotNull(findText(header,"最近听过的专辑"));
        SearchArtistEntryTest.find(header,"打开专辑 夜色缓慢降落").performClick();
        assertSame(a,opened[0]);
        View content=dialog.findViewById(android.R.id.content);
        content.measure(View.MeasureSpec.makeMeasureSpec(412,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(860,View.MeasureSpec.EXACTLY));content.layout(0,0,412,860);
        CoverView.IO.submit(()->{}).get();Shadows.shadowOf(Looper.getMainLooper()).idle();
        Bitmap image=Bitmap.createBitmap(412,860,Bitmap.Config.ARGB_8888);content.draw(new Canvas(image));
        try(FileOutputStream out=new FileOutputStream(new File(System.getProperty("vinyl.output.dir"),"search-recent-sleeves.png"))){image.compress(Bitmap.CompressFormat.PNG,100,out);}image.recycle();
        query("夜航");findText((View)field(page,"filterBar"),"专辑").performClick();
        assertEquals(1,dataRows(list()).getCount());View albumRow=row(0);
        assertEquals(1,SearchArtistEntryTest.count(albumRow,"打开专辑 夜色缓慢降落，演示音乐人"));
        opened[0]=null;SearchArtistEntryTest.find(albumRow,"打开专辑 夜色缓慢降落，演示音乐人").performClick();
        assertSame(a,opened[0]);assertEquals(3,page.snapshot().getInt("tab"));
    }

    @Test public void dismissRejectsLateResponsesAndClosesExactlyOnce()throws Exception{
        CountDownLatch entered=new CountDownLatch(1);release=new CountDownLatch(1);
        AggregateSearchTest.Fake provider=new AggregateSearchTest.Fake("qq",(q,o,l)->{entered.countDown();AggregateSearchTest.uninterruptibly(release);return AggregateSearchTest.page(AggregateSearchTest.track("qq","late","Late","Album",180000));});
        show(null,AggregateSearchTest.source(provider,0));input().setText("Late");Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(351));assertTrue(entered.await(5,TimeUnit.SECONDS));dialog.dismiss();Shadows.shadowOf(Looper.getMainLooper()).idle();release.countDown();page.close();assertEquals(1,closed);assertFalse(dialog.isShowing());assertNull(played);
    }

    @Test public void rendersNativeUnifiedSearchWithBundledArtwork()throws Exception{
        Track first=new Track("q1","夜航","演示音乐人","夜色缓慢降落","QQ音乐","qq://song/q1","asset:///demo/night-v2.png",180000,1,"演示音乐人","","","qq","album","artist");
        Track second=new Track("q2","海岸线","演示音乐人","深蓝之间","QQ音乐","qq://song/q2","asset:///demo/forest-v2.png",180000,2,"演示音乐人","","","qq","album2","artist");
        AggregateSearchTest.Fake provider=new AggregateSearchTest.Fake("qq",(q,o,l)->AggregateSearchTest.page(first,second));show(null,AggregateSearchTest.source(provider,0));query("夜");
        View content=dialog.findViewById(android.R.id.content);content.measure(View.MeasureSpec.makeMeasureSpec(412,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(860,View.MeasureSpec.EXACTLY));content.layout(0,0,412,860);Shadows.shadowOf(Looper.getMainLooper()).idle();
        CoverView.IO.submit(()->{}).get(10,TimeUnit.SECONDS);Shadows.shadowOf(Looper.getMainLooper()).idle();
        int visibleSongs=0;
        for(int i=0;i<list().getChildCount();i++){
            View leading=((ViewGroup)list().getChildAt(i)).getChildAt(0);
            if(leading instanceof CoverView){assertNotNull("Demo cover must decode",((CoverView)leading).getDrawable());visibleSongs++;}
        }
        assertTrue("The album display leaves space for song results",visibleSongs>0);
        Bitmap image=Bitmap.createBitmap(412,860,Bitmap.Config.ARGB_8888);content.draw(new Canvas(image));
        File out=new File(System.getProperty("vinyl.output.dir"),"aggregate-search-412x860-fixture.png");out.getParentFile().mkdirs();try(FileOutputStream stream=new FileOutputStream(out)){assertTrue(image.compress(Bitmap.CompressFormat.PNG,100,stream));}image.recycle();assertTrue(out.length()>3000);
    }
    static View findText(View view,String label){if(view instanceof TextView&&label.contentEquals(((TextView)view).getText()))return view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){View found=findText(((ViewGroup)view).getChildAt(i),label);if(found!=null)return found;}return null;}
}
