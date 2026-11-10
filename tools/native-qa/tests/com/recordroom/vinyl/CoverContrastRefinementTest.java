package com.recordroom.vinyl;
import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.android.controller.ActivityController;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk=34) @GraphicsMode(GraphicsMode.Mode.NATIVE)
public class CoverContrastRefinementTest {
 ActivityController<Activity> owner;Activity context;
 @Before public void start(){owner=Robolectric.buildActivity(Activity.class).setup();context=owner.get();context.getSharedPreferences("vinyl_appearance",0).edit().clear().commit();}
 @After public void stop(){owner.pause().stop().destroy();}
 static double contrast(int a,int b){double x=Color.luminance(a),y=Color.luminance(b);return (Math.max(x,y)+.05)/(Math.min(x,y)+.05);}
 @Test public void blackWhiteAndSaturatedCoversKeepDiscSeparationAndReadableFlatSurfaces(){
  ThemeStore themes=new ThemeStore(context);
  for(int c:new int[]{Color.BLACK,Color.WHITE,0xff777777,Color.RED,Color.GREEN,Color.BLUE,Color.MAGENTA,Color.YELLOW}){
   Bitmap cover=Bitmap.createBitmap(64,64,Bitmap.Config.ARGB_8888);cover.eraseColor(c);AlbumPalette palette=AlbumPalette.from(cover);cover.recycle();themes.coverPalette("test",palette);int stage=DesignSystem.stageColor(themes,"test");
   assertTrue("Disc/stage separation for "+Integer.toHexString(c),contrast(palette.record,stage)>=3);
   DesignSystem.setAlbumTheme(context,"test",stage,palette.record);assertTrue(contrast(DesignSystem.onAmbient,DesignSystem.settingsCardColor(context))>=7);assertTrue(contrast(DesignSystem.onAmbientMuted,DesignSystem.settingsCardColor(context))>=4.5);
   if(Color.red(c)==Color.green(c)&&Color.red(c)==Color.blue(c)){int ambient=DesignSystem.ambientColor(context);assertEquals(Color.red(ambient),Color.green(ambient));assertEquals(Color.red(ambient),Color.blue(ambient));Drawable backdrop=DesignSystem.stage(context,stage);Bitmap pixels=Bitmap.createBitmap(100,100,Bitmap.Config.ARGB_8888);backdrop.setBounds(0,0,100,100);backdrop.draw(new Canvas(pixels));int top=pixels.getPixel(10,10);assertEquals(Color.red(top),Color.green(top));assertEquals(Color.red(top),Color.blue(top));pixels.recycle();}
  }
 }
 @Test @Config(qualifiers="w412dp-h860dp-mdpi") @LooperMode(LooperMode.Mode.PAUSED)
 public void realNativeMaterialsKeepNeutralAndVividRecordsVisibleAndEmitComparisonFrames()throws Exception{
  owner.visible();ThemeStore themes=new ThemeStore(context);android.widget.FrameLayout host=new android.widget.FrameLayout(context);TurntableView turntable=new TurntableView(context);turntable.setReferenceLayout(true);turntable.setMotionEnabled(false);turntable.setLighting(0);host.addView(turntable,new android.widget.FrameLayout.LayoutParams(-1,-1));context.setContentView(host);
  host.measure(android.view.View.MeasureSpec.makeMeasureSpec(412,1073741824),android.view.View.MeasureSpec.makeMeasureSpec(860,1073741824));host.layout(0,0,412,860);
  java.util.concurrent.ExecutorService assets=org.robolectric.util.ReflectionHelpers.getStaticField(DiscMaterials.class,"IO");
  org.json.JSONArray report=new org.json.JSONArray();String[] names={"black","white","red","blue"};int[] colors={Color.BLACK,Color.WHITE,0xffff0000,0xff0000ff};
  for(int i=0;i<colors.length;i++){
   Bitmap cover=Bitmap.createBitmap(128,128,Bitmap.Config.ARGB_8888);cover.eraseColor(colors[i]);AlbumPalette palette=AlbumPalette.from(cover);themes.coverPalette("fixture",palette);int stage=DesignSystem.stageColor(themes,"fixture");host.setBackground(DesignSystem.stage(context,stage));DesignSystem.setAlbumTheme(context,"fixture",stage,palette.record);turntable.setArtwork(cover);turntable.setRecordTint(palette.record);
   for(int material:new int[]{0,5,8}){
    themes.save("fixture",false,material,0xff222222);stage=DesignSystem.stageColor(themes,"fixture");host.setBackground(DesignSystem.stage(context,stage));DesignSystem.setAlbumTheme(context,"fixture",stage,palette.record);turntable.setStyle(material);assets.submit(()->{}).get(10,java.util.concurrent.TimeUnit.SECONDS);Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();Bitmap image=Bitmap.createBitmap(412,860,Bitmap.Config.ARGB_8888);host.draw(new Canvas(image));
    float cx=org.robolectric.util.ReflectionHelpers.getField(turntable,"cx"),cy=org.robolectric.util.ReflectionHelpers.getField(turntable,"cy"),r=org.robolectric.util.ReflectionHelpers.getField(turntable,"radius");double ring=0;int n=0;
    for(int degrees=105;degrees<=255;degrees+=5){double a=Math.toRadians(degrees);int pixel=image.getPixel(Math.round(cx+(float)Math.cos(a)*r*.74f),Math.round(cy+(float)Math.sin(a)*r*.74f));ring+=Color.luminance(pixel);n++;}
    double separation=(ring/n+.05)/(Color.luminance(image.getPixel(12,600))+.05);assertTrue("Actual textured record must remain distinguishable: "+names[i]+"/"+material,separation>=1.5);
    report.put(new org.json.JSONObject().put("cover",names[i]).put("material",material).put("record_stage_palette_contrast",contrast(palette.record,stage)).put("rendered_ring_background_contrast",separation).put("primary_text_contrast",contrast(DesignSystem.onAmbient,DesignSystem.settingsCardColor(context))).put("secondary_text_contrast",contrast(DesignSystem.onAmbientMuted,DesignSystem.settingsCardColor(context))));
    java.io.File dir=new java.io.File(System.getProperty("vinyl.output.dir"));dir.mkdirs();try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(dir,"cover-refined-"+names[i]+"-material-"+material+".png"))){image.compress(Bitmap.CompressFormat.PNG,100,out);}image.recycle();
   }
   cover.recycle();
  }
  java.nio.file.Files.writeString(new java.io.File(System.getProperty("vinyl.output.dir"),"cover-contrast-report.json").toPath(),new org.json.JSONObject().put("native_render",true).put("physical_device_test",false).put("fixture_covers",true).put("cases",report).toString(2));
 }

}
