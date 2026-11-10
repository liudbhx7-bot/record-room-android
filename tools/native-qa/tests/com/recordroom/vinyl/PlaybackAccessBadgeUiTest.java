package com.recordroom.vinyl;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
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

/** Access tags describe returned catalog requirements, not the current account's entitlement. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=34,qualifiers="w412dp-h860dp-mdpi",fontScale=1f)
@GraphicsMode(GraphicsMode.Mode.NATIVE) @LooperMode(LooperMode.Mode.PAUSED)
public class PlaybackAccessBadgeUiTest {
    ActivityController<Activity> controller;AggregateSearchPage aggregate;AggregateSearch engine;ProviderLibraryPage providerPage;Dialog dialog;int plays;
    @Before public void setup(){controller=Robolectric.buildActivity(Activity.class);controller.get().setTheme(android.R.style.Theme_Material_NoActionBar);controller.setup().visible();DesignSystem.setAlbumTheme(controller.get(),"blue",0xff264b78,0xff7ca4ce);}
    @After public void cleanup(){if(aggregate!=null)aggregate.close();if(providerPage!=null)providerPage.close();controller.pause().stop().destroy();Shadows.shadowOf(Looper.getMainLooper()).idle();}
    static Track song(String p,String id,String title,Track.PlaybackAccess access){return new Track(id,title,"演示歌手","演示专辑",p,p+"://song/"+id,"asset:///demo/night-v2.png",180000,1,"演示歌手","","",p,"album","artist").withPlaybackAccess(access);}
    void showAggregate(Track[] qq,Track[] net)throws Exception{
        AggregateSearchTest.Fake a=new AggregateSearchTest.Fake("qq",(q,o,l)->AggregateSearchTest.page(qq));AggregateSearchTest.Fake b=new AggregateSearchTest.Fake("netease",(q,o,l)->AggregateSearchTest.page(net));
        engine=new AggregateSearch(Arrays.asList(AggregateSearchTest.source(a,0),AggregateSearchTest.source(b,10)));aggregate=new AggregateSearchPage(controller.get(),engine,new AggregateSearchPage.Actions(){public void play(List<AggregateSearch.Result> r,int at){plays++;}public void manageAccounts(){}});dialog=aggregate.show();((EditText)AggregateSearchPageTest.field(aggregate,"input")).setText("歌曲");Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(351));AggregateSearchTest.await(()->!engine.state().loading);
    }
    ListView list(Object p)throws Exception{return (ListView)AggregateSearchPageTest.field(p,"list");}
    View row(Object p,int at,View reused)throws Exception{ListView list=list(p);return list.getAdapter().getView(at,reused,list);}
    static TextView badge(View row,String label){View found=AggregateSearchPageTest.findText(row,label);assertNotNull("Missing before-play catalog label: "+label,found);assertEquals(View.VISIBLE,found.getVisibility());return (TextView)found;}
    @Test public void aggregateShowsVipAndPurchaseBeforePlaybackAndNeverLabelsUnknownFree()throws Exception{
        showAggregate(new Track[]{song("qq","v","VIP歌曲",Track.PlaybackAccess.VIP),song("qq","p","购买歌曲",Track.PlaybackAccess.PURCHASE),song("qq","s","普通歌曲",Track.PlaybackAccess.STANDARD),song("qq","u","未知歌曲",Track.PlaybackAccess.UNKNOWN)},new Track[0]);
        View vip=row(aggregate,0,null);badge(vip,"需 VIP");assertTrue(vip.getContentDescription().toString().contains("需 VIP"));assertEquals(0,plays);vip.performClick();assertEquals("Tags must not block an entitled user from trying playback",1,plays);
        View purchase=row(aggregate,1,null);badge(purchase,"需单独购买");assertTrue(purchase.getContentDescription().toString().contains("需单独购买"));
        for(int at:new int[]{2,3}){String text=AggregateSearchPageTest.text(row(aggregate,at,null));assertFalse(text.contains("需 VIP"));assertFalse(text.contains("需单独购买"));assertFalse(text.contains("免费"));}
    }
    @Test public void recycledAggregateRowClearsVipTextAndAccessibility()throws Exception{
        showAggregate(new Track[]{song("qq","v","VIP歌曲",Track.PlaybackAccess.VIP),song("qq","u","未知歌曲",Track.PlaybackAccess.UNKNOWN)},new Track[0]);View vip=row(aggregate,0,null);TextView oldBadge=badge(vip,"需 VIP");View reused=row(aggregate,1,vip);assertSame(vip,reused);assertEquals(View.GONE,oldBadge.getVisibility());assertEquals("",oldBadge.getText().toString());assertFalse(reused.getContentDescription().toString().contains("VIP"));
    }
    @Test public void mergedVersionsUsePartialLabelWithoutRevealingPlatformNames()throws Exception{
        showAggregate(new Track[]{song("qq","v","同版本歌曲",Track.PlaybackAccess.VIP)},new Track[]{song("netease","n","同版本歌曲",Track.PlaybackAccess.UNKNOWN)});assertEquals(1,list(aggregate).getCount());View row=row(aggregate,0,null);badge(row,"部分版本需 VIP");String text=AggregateSearchPageTest.text(row);assertFalse(text.contains("QQ"));assertFalse(text.contains("网易云"));assertFalse(text.contains("免费"));
    }
    void showProvider(Track... songs)throws Exception{
        Provider stub=new Provider();providerPage=new ProviderLibraryPage(controller.get(),stub,"测试曲库",(q,o,l)->AggregateSearchTest.page(songs),new ProviderLibraryPage.Actions(){public void openTracks(List<Track> t,String h){}public void play(List<Track> t,int at){plays++;}public void officialLogin(){}public void onAccount(MusicProvider.Account a){}public void onLogout(){}});dialog=providerPage.show();for(int i=0;i<3;i++){((ExecutorService)AggregateSearchPageTest.field(providerPage,"worker")).submit(()->{}).get(5,TimeUnit.SECONDS);Shadows.shadowOf(Looper.getMainLooper()).idle();}
    }
    @Test public void providerRowsShowRequirementsAndRecyclingToUnknownOrHeadingClearsThem()throws Exception{
        showProvider(song("qq","v","VIP歌曲",Track.PlaybackAccess.VIP),song("qq","p","购买歌曲",Track.PlaybackAccess.PURCHASE),song("qq","u","未知歌曲",Track.PlaybackAccess.UNKNOWN));
        View vip=row(providerPage,1,null);TextView tag=badge(vip,"需 VIP");assertTrue(vip.getContentDescription().toString().contains("需 VIP"));assertEquals(0,plays);vip.performClick();assertEquals(1,plays);badge(row(providerPage,2,null),"需单独购买");assertSame(vip,row(providerPage,3,vip));assertEquals(View.GONE,tag.getVisibility());assertEquals("",tag.getText().toString());assertFalse(vip.getContentDescription().toString().contains("VIP"));assertSame(vip,row(providerPage,0,vip));assertEquals(View.GONE,tag.getVisibility());
    }
    @Test @Config(qualifiers="w320dp-h720dp-mdpi",fontScale=1.3f) public void longTitlesAndAccessTextRemainVisibleOnNarrowScreens()throws Exception{
        showAggregate(new Track[]{song("qq","v","测试歌曲：这是长歌名以及一个很长的现场演唱会版本",Track.PlaybackAccess.VIP),song("qq","p","测试歌曲：另一张单独购买的数字唱片",Track.PlaybackAccess.PURCHASE)},new Track[0]);View content=dialog.findViewById(android.R.id.content);render(content,320,720,"search-vip-320x720-large-font.png");View row=list(aggregate).getChildAt(0);TextView tag=badge(row,"需 VIP");assertTrue(tag.getWidth()>0);assertTrue(tag.getHeight()>0);assertTrue(tag.getLayout().getLineCount()<=2);assertTrue(tag.getBottom()<=((View)tag.getParent()).getHeight());
        DesignSystem.setAlbumTheme(controller.get(),"red",0xff784049,0xffd5a2ac);render(content,320,720,"search-vip-red-320x720-large-font.png");
    }
    void render(View content,int w,int h,String name)throws Exception{content.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));content.layout(0,0,w,h);CoverView.IO.submit(()->{}).get(5,TimeUnit.SECONDS);Shadows.shadowOf(Looper.getMainLooper()).idle();Bitmap bitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);content.draw(new Canvas(bitmap));File out=new File(System.getProperty("vinyl.output.dir"),name);out.getParentFile().mkdirs();try(FileOutputStream f=new FileOutputStream(out)){bitmap.compress(Bitmap.CompressFormat.PNG,100,f);}bitmap.recycle();}
    static final class Provider implements MusicProvider {
        public String id(){return "qq";}public boolean supportsAccount(){return false;}public Account account(){throw new UnsupportedOperationException();}public QrLogin beginQrLogin(){throw new UnsupportedOperationException();}public LoginPoll pollQrLogin(String key){throw new UnsupportedOperationException();}public List<Playlist> playlists(String id){throw new UnsupportedOperationException();}public List<Track> likedTracks(String id){throw new UnsupportedOperationException();}public List<Track> playlistTracks(String id){throw new UnsupportedOperationException();}public Page<Album> searchAlbums(String q,int o,int l){throw new UnsupportedOperationException();}public Album album(String id){throw new UnsupportedOperationException();}public Artist artist(String id){throw new UnsupportedOperationException();}public PlaybackResource resolve(Track t){throw new UnsupportedOperationException();}public void logout(){}
    }
}
