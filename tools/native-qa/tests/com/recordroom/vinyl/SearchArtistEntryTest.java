package com.recordroom.vinyl;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
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

@RunWith(RobolectricTestRunner.class) @Config(sdk=34,qualifiers="w412dp-h860dp-mdpi",fontScale=1f)
@GraphicsMode(GraphicsMode.Mode.NATIVE) @LooperMode(LooperMode.Mode.PAUSED)
public class SearchArtistEntryTest {
    ActivityController<Activity> controller;AggregateSearchPage page;AggregateSearch engine;Dialog dialog;
    AggregateSearch.ArtistResult opened;String openedName;int plays;
    @Before public void setup(){controller=Robolectric.buildActivity(Activity.class);controller.get().setTheme(android.R.style.Theme_Material_Light_NoActionBar);controller.setup().visible();}
    @After public void cleanup(){if(page!=null)page.close();controller.pause().stop().destroy();Shadows.shadowOf(Looper.getMainLooper()).idle();}
    static Track track(String provider,String id,String name,String artistId){return new Track(id,"测试歌曲 "+id,name,"测试专辑",provider,provider+"://song/"+id,"asset:///demo/night-v2.png",180000,1,name,"","",provider,"album",artistId);}
    void show(Track... tracks){AggregateSearchTest.Fake provider=new AggregateSearchTest.Fake("qq",(q,o,l)->AggregateSearchTest.page(tracks));showSources(AggregateSearchTest.source(provider,0));}
    void showSources(AggregateSearch.Source... sources){showRestored(null,sources);}
    void showRestored(android.os.Bundle saved,AggregateSearch.Source... sources){engine=new AggregateSearch(Arrays.asList(sources));page=AggregateSearchPage.restore(controller.get(),engine,new AggregateSearchPage.Actions(){public void play(List<AggregateSearch.Result> r,int at){plays++;}public void manageAccounts(){}public void artist(AggregateSearch.ArtistResult result){opened=result;openedName=result.name;}},saved);dialog=page.show();}
    void query(String value)throws Exception{((EditText)AggregateSearchPageTest.field(page,"input")).setText(value);Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(351));AggregateSearchTest.await(()->!engine.state().loading&&!engine.state().artistLoading);}
    static View find(View view,String description){if(description.contentEquals(view.getContentDescription()==null?"":view.getContentDescription()))return view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){View hit=find(((ViewGroup)view).getChildAt(i),description);if(hit!=null)return hit;}return null;}
    View card(String name){return find(dialog.getWindow().getDecorView(),"打开"+name+"的歌手主页");}
    @Test public void singerQueryOffersRealArtistDestinationAndDoesNotStartPlayback()throws Exception{
        MusicProvider.Artist singer=RealArtistSearchTest.artist("realSingerMid","刘德华");RealArtistSearchTest.Source source=new RealArtistSearchTest.Source("qq",(q,o,l)->AggregateSearchTest.page(),(q,o,l)->RealArtistSearchTest.page(singer));showSources(source.lane());query("刘德华");assertEquals("已找到音乐人，可直接查看主页",((TextView)AggregateSearchPageTest.field(page,"status")).getText().toString());View card=card("刘德华");assertNotNull("Search must expose singer homepage",card);card.performClick();assertSame(singer,opened.artist);assertEquals("qq",opened.providerId);assertEquals("刘德华",openedName);assertEquals(0,plays);assertTrue(dialog.isShowing());assertEquals("刘德华",page.snapshot().getString("query"));
        ((EditText)AggregateSearchPageTest.field(page,"input")).setText("新查询");opened=null;card.performClick();assertNull("Stale artist cards cannot navigate after query changes",opened);
    }
    @Test public void collaboratorAndUnknownIdsCannotOpenTheWrongArtistsPage()throws Exception{
        show(track("qq","a","甲 / 刘德华","firstSingerOnly"),track("qq","b","刘德华",""));query("刘德华");assertNull(card("刘德华"));assertNull(card("甲 / 刘德华"));
    }
    @Test public void sameNamesAcrossSourcesStaySeparateAndNavigateTheirOwnRealIds()throws Exception{
        MusicProvider.Artist qq=RealArtistSearchTest.artist("qqSinger","同名歌手"),net=RealArtistSearchTest.artist("12345","同名歌手");
        RealArtistSearchTest.Source a=new RealArtistSearchTest.Source("qq",(q,o,l)->AggregateSearchTest.page(),(q,o,l)->RealArtistSearchTest.page(qq));RealArtistSearchTest.Source b=new RealArtistSearchTest.Source("netease",(q,o,l)->AggregateSearchTest.page(),(q,o,l)->RealArtistSearchTest.page(net));showSources(a.lane(),b.lane());query("同名歌手");assertEquals(2,count(dialog.getWindow().getDecorView(),"打开同名歌手的歌手主页"));ViewGroup cards=(ViewGroup)AggregateSearchPageTest.field(page,"artistCards");cards.getChildAt(0).performClick();assertSame(qq,opened.artist);assertEquals("qq",opened.providerId);cards.getChildAt(1).performClick();assertSame(net,opened.artist);assertEquals("netease",opened.providerId);
    }
    @Test public void missingPreferredArtworkUsesSameVersionAlternativeAndRecyclingClearsIt()throws Exception{
        Track preferred=new Track("q","同版本歌曲","歌手","同专辑","QQ音乐","qq://song/q","",180000,1,"歌手","","","qq","album","artist");
        Track alternate=new Track("n","同版本歌曲","歌手","同专辑","网易云","netease://song/n","asset:///demo/night-v2.png",180000,1,"歌手","","","netease","album","artist");
        Track empty=new Track("e","无封面歌曲","歌手","另一个专辑","QQ音乐","qq://song/e","",180000,1,"歌手","","","qq","album2","artist");
        AggregateSearchTest.Fake a=new AggregateSearchTest.Fake("qq",(q,o,l)->AggregateSearchTest.page(preferred,empty));AggregateSearchTest.Fake b=new AggregateSearchTest.Fake("netease",(q,o,l)->AggregateSearchTest.page(alternate));showSources(AggregateSearchTest.source(a,0),AggregateSearchTest.source(b,10));query("歌曲");
        android.widget.ListView list=(android.widget.ListView)AggregateSearchPageTest.field(page,"list");assertEquals(2,list.getCount());ViewGroup cell=(ViewGroup)list.getAdapter().getView(0,null,list);CoverView cover=(CoverView)cell.getChildAt(0);CoverView.IO.submit(()->{}).get();Shadows.shadowOf(Looper.getMainLooper()).idle();assertNotNull("A real same-version cover must replace the empty preferred image",cover.getDrawable());assertSame(preferred,engine.state().results.get(0).displayTrack);
        assertSame(cell,list.getAdapter().getView(1,cell,list));assertNull("Recycled rows cannot keep a different song's artwork",cover.getDrawable());
    }
    @Test public void artistAvatarUsesRealArtistUrlAndEmptyPortraitKeepsInitials()throws Exception{
        MusicProvider.Artist with=RealArtistSearchTest.artist("real-artist","真实头像"),empty=new MusicProvider.Artist("empty","首字回退","","",java.util.Collections.emptyList(),java.util.Collections.emptyList());RealArtistSearchTest.Source source=new RealArtistSearchTest.Source("qq",(q,o,l)->AggregateSearchTest.page(),(q,o,l)->RealArtistSearchTest.page(with,empty));showSources(source.lane());query("歌手");View content=dialog.findViewById(android.R.id.content);Bitmap shot=render(content,"search-real-artist-portrait-412x860.png");shot.recycle();ViewGroup cards=(ViewGroup)AggregateSearchPageTest.field(page,"artistCards");CoverView avatar=(CoverView)find(cards.getChildAt(0),"音乐人头像");assertNotNull(avatar);assertNotNull("The actual artist image must decode",avatar.getDrawable());assertEquals(with.avatarUrl,AggregateSearchPageTest.field(avatar,"requestedUri"));assertNull(find(cards.getChildAt(1),"音乐人头像"));assertNotNull(AggregateSearchPageTest.findText(cards.getChildAt(1),"首"));
    }
    @Test public void artistResultsRemainNavigableWhenSongRequestFails()throws Exception{
        RealArtistSearchTest.Source source=new RealArtistSearchTest.Source("qq",(q,o,l)->{throw new ProviderException(ProviderException.Kind.NETWORK,"song fixture failure");},(q,o,l)->RealArtistSearchTest.page(RealArtistSearchTest.artist("a","音乐人")));showSources(source.lane());query("音乐人");assertEquals("歌曲暂未加载，已找到的音乐人可查看主页",((TextView)AggregateSearchPageTest.field(page,"status")).getText().toString());assertNotNull(card("音乐人"));assertTrue(((View)AggregateSearchPageTest.field(page,"retry")).getVisibility()==View.VISIBLE);card("音乐人").performClick();assertEquals("a",opened.artist.id);assertEquals(0,plays);
    }
    @Test public void artistPaginationRestoresDepthAndLoadsOnlyNearbyPortraits()throws Exception{
        java.util.ArrayList<MusicProvider.Artist> entries=new java.util.ArrayList<>();for(int i=0;i<27;i++)entries.add(RealArtistSearchTest.artist("a"+i,"艺人"+i));RealArtistSearchTest.Source source=new RealArtistSearchTest.Source("qq",(q,o,l)->AggregateSearchTest.page(),(q,o,l)->{int end=Math.min(entries.size(),o+l);return new MusicProvider.Page<>(entries.subList(o,end),entries.size(),end,end<entries.size());});showSources(source.lane());query("艺人");((View)AggregateSearchPageTest.field(page,"artistMore")).performClick();AggregateSearchTest.await(()->!engine.state().artistLoading);assertEquals(27,engine.state().artists.size());Bitmap shot=render(dialog.findViewById(android.R.id.content),"search-real-artist-pagination-412x860.png");shot.recycle();ViewGroup cards=(ViewGroup)AggregateSearchPageTest.field(page,"artistCards");int loaded=0;for(int i=0;i<cards.getChildCount();i++)if(find(cards.getChildAt(i),"音乐人头像")!=null)loaded++;assertTrue("Off-screen artist artwork must not flood the song cover worker",loaded<=3);android.widget.HorizontalScrollView scroll=(android.widget.HorizontalScrollView)AggregateSearchPageTest.field(page,"artistScroll");scroll.scrollTo(180,0);android.os.Bundle saved=page.snapshot();assertEquals(27,saved.getInt("artist_loaded_count"));assertEquals(180,saved.getInt("artist_scroll"));page.close();source.artistOffsets.clear();showRestored(saved,source.lane());AggregateSearchTest.await(()->!engine.state().artistLoading&&engine.state().artists.size()==27);assertEquals(Arrays.asList(0,25),source.artistOffsets);Bitmap restored=render(dialog.findViewById(android.R.id.content),"search-real-artist-restored-412x860.png");restored.recycle();assertEquals(180,((android.widget.HorizontalScrollView)AggregateSearchPageTest.field(page,"artistScroll")).getScrollX());((EditText)AggregateSearchPageTest.field(page,"input")).setText("另一查询");assertEquals(0,((android.widget.HorizontalScrollView)AggregateSearchPageTest.field(page,"artistScroll")).getScrollX());
    }
    @Test public void mainArtistCallbackAndRecreationPreserveCatalogIdentityWithoutFakeSong()throws Exception{
        android.app.Application app=org.robolectric.RuntimeEnvironment.getApplication();MusicProvider.Artist identity=RealArtistSearchTest.artist("6412","真实目录歌手");
        MusicProvider provider=(MusicProvider)java.lang.reflect.Proxy.newProxyInstance(MusicProvider.class.getClassLoader(),new Class<?>[]{MusicProvider.class},(p,m,args)->{switch(m.getName()){case "id":return "netease";case "artist":assertEquals(identity.id,args[0]);return identity;case "artistAlbums":return new MusicProvider.Page<MusicProvider.Album>(java.util.Collections.emptyList(),0,0,false);case "supportsArtistSearch":return true;case "searchArtists":return RealArtistSearchTest.page(identity);case "searchTracks":return AggregateSearchTest.page();default:throw new AssertionError("Unexpected catalog call "+m.getName());}});
        OnlineMusic.useProviderForTests(app,provider,new ProviderSession(){public String readCookies(){return "";}public void writeCookies(String value){}public void clear(){}public long generation(){return 0;}});
        org.robolectric.android.controller.ServiceController<PlayerService> service=Robolectric.buildService(PlayerService.class).create();Shadows.shadowOf(app).setComponentNameAndServiceForBindService(new android.content.ComponentName(app,PlayerService.class),service.get().onBind(new android.content.Intent(app,PlayerService.class)));
        ActivityController<MainActivity> mainOwner=null;
        try{
            mainOwner=Robolectric.buildActivity(MainActivity.class).setup().visible();MainActivity main=mainOwner.get();drainMain(main);int librarySize=((List<?>)UiTheme073Test.field(main,"library")).size(),queueSize=service.get().getQueueSize();
            invokeMain(main,"showCatalogSearch",new Class<?>[]{String.class},"");AggregateSearchPage realSearch=(AggregateSearchPage)UiTheme073Test.field(main,"aggregatePage");AggregateSearchPage.Actions actualActions=(AggregateSearchPage.Actions)AggregateSearchPageTest.field(realSearch,"actions");actualActions.artist(new AggregateSearch.ArtistResult("netease",identity));
            android.widget.FrameLayout artistPage=(android.widget.FrameLayout)UiTheme073Test.field(main,"artistPage");assertNull("Catalog navigation must not invent a seed track",UiTheme073Test.field(artistPage,"seed"));assertEquals("6412",UiTheme073Test.field(artistPage,"remoteId"));assertEquals("netease",UiTheme073Test.field(artistPage,"providerId"));ArtistPage.selectChapter(artistPage,2);assertEquals(librarySize,((List<?>)UiTheme073Test.field(main,"library")).size());assertEquals(queueSize,service.get().getQueueSize());
            android.os.Bundle saved=new android.os.Bundle();invokeMain(main,"onSaveInstanceState",new Class<?>[]{android.os.Bundle.class},saved);java.util.ArrayList<android.os.Bundle> pages=saved.getParcelableArrayList("navigation_pages");android.os.Bundle artistState=pages.get(pages.size()-1);assertEquals("6412",artistState.getString("catalog_artist_id"));assertEquals("netease",artistState.getString("catalog_provider"));assertFalse(artistState.containsKey("seed"));
            mainOwner.pause().stop().destroy();mainOwner=Robolectric.buildActivity(MainActivity.class).create(saved).start().resume().visible();main=mainOwner.get();drainMain(main);android.widget.FrameLayout restored=(android.widget.FrameLayout)UiTheme073Test.field(main,"artistPage");assertNotNull(restored);assertNull(UiTheme073Test.field(restored,"seed"));assertEquals("6412",UiTheme073Test.field(restored,"remoteId"));assertEquals(2,ArtistPage.chapter(restored));assertTrue(((Dialog)UiTheme073Test.field(main,"artistDialog")).isShowing());assertNotNull(UiTheme073Test.field(main,"aggregatePage"));
        }finally{if(mainOwner!=null)mainOwner.pause().stop().destroy();service.destroy();OnlineMusic.useProviderForTests(app,null,null);}
    }
    static void invokeMain(MainActivity activity,String name,Class<?>[] types,Object... args)throws Exception{java.lang.reflect.Method method=MainActivity.class.getDeclaredMethod(name,types);method.setAccessible(true);method.invoke(activity,args);}
    static void drainMain(MainActivity activity)throws Exception{for(int i=0;i<3;i++){((java.util.concurrent.ExecutorService)UiTheme073Test.field(activity,"io")).submit(()->{}).get(5,java.util.concurrent.TimeUnit.SECONDS);Shadows.shadowOf(Looper.getMainLooper()).idle();}}
    static int count(View view,String d){int result=d.contentEquals(view.getContentDescription()==null?"":view.getContentDescription())?1:0;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)result+=count(((ViewGroup)view).getChildAt(i),d);return result;}
    @Test public void searchUsesSettingsDarkPaletteAndRetintsWithoutReplacingControls()throws Exception{
        Activity activity=controller.get();DesignSystem.setAlbumTheme(activity,"red",0xff793e42,0xffce777b);RealArtistSearchTest.Source source=new RealArtistSearchTest.Source("qq",(q,o,l)->AggregateSearchTest.page(track("qq","q","演示音乐人","fixtureSinger")),(q,o,l)->RealArtistSearchTest.page(RealArtistSearchTest.artist("fixtureSinger","演示音乐人")));showSources(source.lane());query("演示音乐人");View content=dialog.findViewById(android.R.id.content);View input=(View)AggregateSearchPageTest.field(page,"input");Bitmap red=render(content,"search-artist-red-412x860.png");assertTrue("Search should share soft dark settings background",Color.red(red.getPixel(2,500))<150);TextView title=(TextView)AggregateSearchPageTest.findText(content,"搜索音乐");assertTrue(Color.red(title.getCurrentTextColor())>220);
        DesignSystem.setAlbumTheme(activity,"blue",0xff254a81,0xff699ddd);Bitmap blue=render(content,"search-artist-blue-412x860.png");assertSame(input,AggregateSearchPageTest.field(page,"input"));assertNotEquals(red.getPixel(2,500),blue.getPixel(2,500));assertTrue(Color.blue(blue.getPixel(2,500))>Color.red(blue.getPixel(2,500)));red.recycle();blue.recycle();
    }
    Bitmap render(View content,String name)throws Exception{content.measure(View.MeasureSpec.makeMeasureSpec(412,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(860,View.MeasureSpec.EXACTLY));content.layout(0,0,412,860);Shadows.shadowOf(Looper.getMainLooper()).idle();CoverView.IO.submit(()->{}).get();Shadows.shadowOf(Looper.getMainLooper()).idle();Bitmap b=Bitmap.createBitmap(412,860,Bitmap.Config.ARGB_8888);content.draw(new Canvas(b));File out=new File(System.getProperty("vinyl.output.dir"),name);out.getParentFile().mkdirs();try(FileOutputStream f=new FileOutputStream(out)){b.compress(Bitmap.CompressFormat.PNG,100,f);}return b;}
}
