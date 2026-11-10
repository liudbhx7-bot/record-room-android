package com.recordroom.vinyl;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.os.Looper;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;

/** Network-origin and native async binding contract, with deterministic cached image bytes. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=34) @LooperMode(LooperMode.Mode.PAUSED) @GraphicsMode(GraphicsMode.Mode.NATIVE)
public class RemoteCoverPipelineTest {
 ActivityController<Activity> owner;
 @Before public void setup(){owner=Robolectric.buildActivity(Activity.class).setup().visible();}
 @After public void close(){owner.pause().stop().destroy();Shadows.shadowOf(Looper.getMainLooper()).idle();}
 @Test public void actualQqSearchArtworkReachesTheNativeCoverInsteadOfRemainingASolidSquare()throws Exception{
  QQProvider provider=new QQProvider(new QQProviderTest.Session(),new QQProviderTest.Http((url,body,cookies)->json("{\"code\":0,\"req\":{\"code\":0,\"data\":{\"meta\":{\"sum\":1},\"body\":{\"item_song\":[{\"mid\":\"SongFixture\",\"name\":\"歌曲\",\"interval\":180,\"singer\":[{\"name\":\"刘德华\"}],\"album\":{\"mid\":\"004Z85XP1c25b7\",\"name\":\"公开专辑\"}}]}}}}")),new QQProviderTest.Audio());
  Track track=provider.searchTracks("刘德华",0,25).items.get(0);assertTrue(track.artworkUri.startsWith("https://y.qq.com/music/photo_new/"));
  seed(track.artworkUri,Color.RED,Color.BLUE);CoverView cover=new CoverView(owner.get());owner.get().setContentView(cover);cover.setSource(track.artworkUri,61);drain();
  assertNotNull("Real QQ search URI must pass the artwork boundary and decode, not stay at the placeholder",cover.getDrawable());Bitmap bitmap=((BitmapDrawable)cover.getDrawable()).getBitmap();assertNotNull("QQ artwork was rejected before decode; a null BitmapDrawable is still a solid placeholder",bitmap);assertNotEquals(bitmap.getPixel(0,0),bitmap.getPixel(bitmap.getWidth()-1,bitmap.getHeight()-1));
 }
 @Test public void detachedAndReattachedCoverResumesTheSamePendingImage()throws Exception{
  String uri="https://p1.music.126.net/fixture-reattach-cover.png";seed(uri,Color.GREEN,Color.BLUE);CoverView cover=new CoverView(owner.get());owner.get().setContentView(cover);
  CountDownLatch started=new CountDownLatch(1),release=new CountDownLatch(1);CoverView.IO.submit(()->{started.countDown();try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}});assertTrue(started.await(3,TimeUnit.SECONDS));
  try{cover.setSource(uri,62);owner.get().setContentView(new android.widget.FrameLayout(owner.get()));owner.get().setContentView(cover);}finally{release.countDown();}drain();assertNotNull("A temporary detach must not leave an attached view permanently blank",cover.getDrawable());
 }
 @Test public void qqPhotoRuleDoesNotAdmitOtherQqPagesOrLookalikeOrigins()throws Exception{
  assertEquals("y.qq.com",RemoteArtworkCache.validatedUrl("https://y.qq.com/music/photo_new/T001R500x500M000Singer01.jpg").getHost());
  for(String uri:new String[]{"https://y.qq.com/n/ryqq/profile","https://y.qq.com/music/photo_new/../private.jpg","https://y.qq.com.evil.example/music/photo_new/cover.jpg","https://user:secret@y.qq.com/music/photo_new/cover.jpg","https://y.qq.com:444/music/photo_new/cover.jpg"})try{RemoteArtworkCache.validatedUrl(uri);fail("Unexpected artwork origin: "+uri);}catch(IOException expected){}
 }
 @Test public void rebindAndClearStillRejectThePreviousPendingCover()throws Exception{
  String old="https://p1.music.126.net/fixture-old-cover.png",fresh="https://p1.music.126.net/fixture-new-cover.png";seed(old,Color.RED,Color.BLUE);seed(fresh,Color.GREEN,Color.YELLOW);CoverView cover=new CoverView(owner.get());owner.get().setContentView(cover);cover.setSource(fresh,64);drain();
  CountDownLatch started=new CountDownLatch(1),release=new CountDownLatch(1);CoverView.IO.submit(()->{started.countDown();try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}});assertTrue(started.await(3,TimeUnit.SECONDS));
  try{cover.setSource(old,63);cover.setSource(fresh,64);}finally{release.countDown();}drain();Bitmap current=((BitmapDrawable)cover.getDrawable()).getBitmap();assertEquals(Color.GREEN,current.getPixel(0,0));
  cover.setSource(old,65);cover.clear();owner.get().setContentView(new android.widget.FrameLayout(owner.get()));owner.get().setContentView(cover);drain();assertNull("Clearing a recycled cover must also forget its reattach request",cover.getDrawable());
 }
 static JSONObject json(String value){try{return new JSONObject(value);}catch(JSONException e){throw new AssertionError(e);}}
 static void seed(String uri,int first,int second)throws Exception{
  byte[] hash=MessageDigest.getInstance("SHA-256").digest(uri.getBytes(StandardCharsets.UTF_8));StringBuilder key=new StringBuilder();for(byte b:hash)key.append(String.format(java.util.Locale.ROOT,"%02x",b&255));File root=new File(RuntimeEnvironment.getApplication().getCacheDir(),"public-music-artwork");root.mkdirs();Bitmap bitmap=Bitmap.createBitmap(8,8,Bitmap.Config.ARGB_8888);bitmap.eraseColor(first);for(int y=4;y<8;y++)for(int x=4;x<8;x++)bitmap.setPixel(x,y,second);try(FileOutputStream out=new FileOutputStream(new File(root,key+".image"))){assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,out));}bitmap.recycle();
 }
 static void drain()throws Exception{for(int i=0;i<2;i++){CoverView.IO.submit(()->{}).get(8,TimeUnit.SECONDS);Shadows.shadowOf(Looper.getMainLooper()).idle();}}
}
