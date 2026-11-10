package com.recordroom.vinyl;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=34)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class WidgetPaletteTest {
    private static Bitmap solid(int color){Bitmap image=Bitmap.createBitmap(64,64,Bitmap.Config.ARGB_8888);image.eraseColor(color);return image;}
    private static VinylWidgetProvider.Options mode(int mode){return new VinylWidgetProvider.Options(VinylWidgetProvider.TURNTABLE,mode,0xffc58d78,false);}
    private static VinylWidgetProvider.Snapshot state(Bitmap cover,Bitmap disc,int tint,AlbumPalette palette){return new VinylWidgetProvider.Snapshot(null,false,cover,disc,0xffe5d1c8,2,tint,palette,0,Collections.emptyList(),0,"test");}

    @Test public void cachedPaletteIsReusedButNewCoverPixelsInvalidateIt(){
        Bitmap cover=solid(0xffc53e39);
        AlbumPalette first=VinylWidgetProvider.paletteFor("original-cover",cover);
        assertSame("Unchanged cover must not be sampled repeatedly",first,VinylWidgetProvider.paletteFor("original-cover",cover));
        cover.eraseColor(0xff356db5);
        AlbumPalette second=VinylWidgetProvider.paletteFor("original-cover",cover);
        assertNotSame(first,second);assertNotEquals(first.record,second.record);
        Bitmap replacement=solid(0xff56915c);
        assertNotSame(second,VinylWidgetProvider.paletteFor("original-cover",replacement));
        assertNull(VinylWidgetProvider.paletteFor("missing",null));cover.recycle();replacement.recycle();
    }

    @Test public void actualSnapshotFollowsCoverAndHonorsFixedAndManualModes()throws Exception{
        Context context=RuntimeEnvironment.getApplication();
        context.getSharedPreferences("vinyl_appearance",Context.MODE_PRIVATE).edit().clear().commit();
        ThemeStore themes=new ThemeStore(context);themes.save(null,false,2,0xffb7a4c6);themes.followCoverColors(true);
        Track current=DemoCatalog.load(context).get(0);
        ExecutorService worker=Executors.newSingleThreadExecutor();
        try{
            VinylWidgetProvider.Snapshot automatic=worker.submit(()->VinylWidgetProvider.snapshot(context,current,false,false)).get(20,TimeUnit.SECONDS);
            assertNotNull(automatic.cover);assertNotNull(automatic.artworkPalette);
            assertEquals(automatic.artworkPalette.background,automatic.color);assertEquals(automatic.artworkPalette.record,automatic.recordTint);
            assertEquals(automatic.artworkPalette.background,VinylWidgetProvider.backgroundColor(automatic,mode(VinylWidgetProvider.COLOR_THEME)));
            assertEquals(0xffc58d78,VinylWidgetProvider.backgroundColor(automatic,mode(VinylWidgetProvider.COLOR_FIXED)));
            assertEquals(0,VinylWidgetProvider.recordColor(automatic,mode(VinylWidgetProvider.COLOR_FIXED)));
            themes.followCoverColors(false);
            VinylWidgetProvider.Snapshot manual=worker.submit(()->VinylWidgetProvider.snapshot(context,current,false,false)).get(20,TimeUnit.SECONDS);
            assertEquals(0xffb7a4c6,manual.color);assertEquals(0,manual.recordTint);
            assertEquals(manual.artworkPalette.background,VinylWidgetProvider.backgroundColor(manual,mode(VinylWidgetProvider.COLOR_ARTWORK)));
            assertEquals(manual.artworkPalette.record,VinylWidgetProvider.recordColor(manual,mode(VinylWidgetProvider.COLOR_ARTWORK)));
            VinylWidgetProvider.Snapshot empty=worker.submit(()->VinylWidgetProvider.snapshot(context,null,false,false)).get(20,TimeUnit.SECONDS);
            assertNull(empty.artworkPalette);assertEquals(0xffb7a4c6,VinylWidgetProvider.backgroundColor(empty,mode(VinylWidgetProvider.COLOR_ARTWORK)));
        }finally{worker.shutdownNow();}
    }

    @Test public void nativeDiscTintChangesGroovesWithoutRecoloringAlbumArtwork()throws Exception{
        Context context=RuntimeEnvironment.getApplication();Bitmap cover=solid(0xffda4428),disc=solid(0xffb0b0b0);
        VinylWidgetProvider.Snapshot red=state(cover,disc,0xffa04444,AlbumPalette.from(cover));
        VinylWidgetProvider.Snapshot blue=state(cover,disc,0xff3e65a8,red.artworkPalette);
        Bitmap first=VinylWidgetProvider.render(context,red,mode(VinylWidgetProvider.COLOR_THEME),320,190);
        Bitmap second=VinylWidgetProvider.render(context,blue,mode(VinylWidgetProvider.COLOR_THEME),320,190);
        // Both points are on the disc: the first outside the label, the second inside it away from the spindle.
        assertNotEquals(first.getPixel(45,110),second.getPixel(45,110));
        assertEquals(0xffda4428,first.getPixel(125,125));assertEquals(first.getPixel(125,125),second.getPixel(125,125));
        Bitmap fixed=VinylWidgetProvider.render(context,red,mode(VinylWidgetProvider.COLOR_FIXED),320,190);
        Bitmap fixedAgain=VinylWidgetProvider.render(context,blue,mode(VinylWidgetProvider.COLOR_FIXED),320,190);
        assertEquals(fixed.getPixel(45,110),fixedAgain.getPixel(45,110));
        String output=System.getProperty("vinyl.output.dir");if(output!=null){File dir=new File(output);dir.mkdirs();try(FileOutputStream file=new FileOutputStream(new File(dir,"widget-cover-palette-api34.png"))){assertTrue(first.compress(Bitmap.CompressFormat.PNG,100,file));}}
        first.recycle();second.recycle();fixed.recycle();fixedAgain.recycle();cover.recycle();disc.recycle();
    }
}
