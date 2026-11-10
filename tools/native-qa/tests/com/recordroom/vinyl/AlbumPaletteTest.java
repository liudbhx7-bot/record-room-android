package com.recordroom.vinyl;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import java.io.File;
import java.io.FileOutputStream;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;

/** Real native bitmap input, including transparent edges and minority-colour noise. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=34)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class AlbumPaletteTest {
    @Test public void redAndBlueCoversProduceDifferentReadableRelatedPalettes() throws Exception {
        Bitmap red = cover(0xffd32732), blue = cover(0xff215fd3), gray = solid(100,100,0xff888888);
        try {
            AlbumPalette warm = AlbumPalette.from(red), cool = AlbumPalette.from(blue);
            assertTrue(Color.red(warm.background) > Color.red(cool.background) + 25);
            assertTrue(Color.blue(cool.background) > Color.blue(warm.background) + 25);
            assertTrue(Color.red(warm.record) > Color.red(cool.record) + 55);
            assertTrue(Color.blue(cool.record) > Color.blue(warm.record) + 55);
            for (AlbumPalette palette : new AlbumPalette[]{warm,cool}) {
                assertTrue("The page remains readable with dark UI ink",contrast(palette.background,0xff29272e)>9);
                assertTrue("Accent text remains legible on its page",contrast(palette.background,palette.accent)>7);
                assertTrue(hsv(palette.record)[1] > hsv(palette.background)[1] + .30);
                assertTrue(hueDistance(hsv(palette.background)[0],hsv(palette.record)[0])<3);
                assertTrue(hueDistance(hsv(palette.record)[0],hsv(palette.accent)[0])<3);
            }
            assertFalse("The caller retains ownership",red.isRecycled());
            assertEquals(0xffd32732,red.getPixel(50,50));
            swatches(new Bitmap[]{red,blue,gray});
        } finally { red.recycle();blue.recycle();gray.recycle(); }
    }

    @Test public void blackWhiteGrayAndLowSaturationCoversStayNeutral() {
        for (int color : new int[]{Color.BLACK,Color.WHITE,0xff777777,0xff929c98,0xff9c9298}) {
            Bitmap bitmap=solid(80,80,color);
            try { assertNeutral(AlbumPalette.from(bitmap)); } finally {bitmap.recycle();}
        }
        Bitmap checker=solid(80,80,Color.BLACK);
        try {
            Canvas canvas=new Canvas(checker);Paint paint=new Paint();paint.setColor(Color.WHITE);
            for(int y=0;y<80;y+=8)for(int x=0;x<80;x+=8)
                if(((x+y)/8)%2==0)canvas.drawRect(x,y,x+8,y+8,paint);
            assertNeutral(AlbumPalette.from(checker));
        } finally {checker.recycle();}
    }

    @Test public void invisibleRgbCannotTintTheOpaqueCoverSubject() {
        Bitmap image=solid(100,100,0x01ff0000), opaque=solid(100,100,0xff215fd3);
        try {
            fill(image,25,25,75,75,0xff215fd3);
            AlbumPalette expected=AlbumPalette.from(opaque), actual=AlbumPalette.from(image);
            sameColors(expected,actual);
            assertTrue("Transparent padding cannot turn blue into red",hsv(actual.record)[0]>200);
            image.eraseColor(Color.TRANSPARENT);
            sameColors(AlbumPalette.from(null),AlbumPalette.from(image));
        } finally {image.recycle();opaque.recycle();}
    }

    @Test public void tinySaturatedMarksDoNotReplaceAnAchromaticOrColoredSubject() {
        Bitmap neutral=solid(100,100,0xff898b89), blue=solid(100,100,0xff3d78c2);
        try {
            fill(neutral,0,0,10,10,Color.MAGENTA);
            assertNeutral(AlbumPalette.from(neutral));
            fill(neutral,0,0,18,100,Color.RED);
            assertNeutral(AlbumPalette.from(neutral));
            AlbumPalette before=AlbumPalette.from(blue);
            fill(blue,0,0,10,10,Color.RED);
            sameColors(before,AlbumPalette.from(blue));
        } finally {neutral.recycle();blue.recycle();}
    }

    @Test public void substantialCoverSubjectBeatsWhitePaddingAndRedHueWrapsCorrectly() {
        Bitmap subject=solid(100,100,Color.WHITE), red=solid(100,100,hsvColor(359,.8f,.8f));
        try {
            fill(subject,20,20,80,80,0xff168638);
            AlbumPalette green=AlbumPalette.from(subject);
            assertTrue("A 36 percent cover subject is sufficient evidence",hsv(green.record)[0]>115);
            assertTrue(hsv(green.record)[0]<150);
            fill(red,50,0,100,100,hsvColor(1,.8f,.8f));
            assertTrue("359 and 1 degree reds are neighbours, not cyan",hueDistance(hsv(AlbumPalette.from(red).record)[0],0)<2);
        } finally {subject.recycle();red.recycle();}
    }

    @Test public void largeAndExtremeAspectBitmapsHaveBoundedDeterministicPixelWork() {
        for(int[] size:new int[][]{{2048,1024},{16384,1},{1,16384},{17,19}}) {
            Bitmap bitmap=solid(size[0],size[1],0xff2569b7);
            try {
                if(size[0]>1)fill(bitmap,0,0,size[0]/3,size[1],0xffd5d5d5);
                else fill(bitmap,0,0,size[0],size[1]/3,0xffd5d5d5);
                AlbumPalette first=AlbumPalette.from(bitmap), again=AlbumPalette.from(bitmap);
                assertTrue(first.sampledPixels>0);
                assertTrue("Pixel reads are bounded regardless of dimensions",first.sampledPixels<=4096);
                if(size[0]*size[1]>4096)assertTrue("Skinny covers still receive a spatial sample",first.sampledPixels>=4000);
                else assertEquals(size[0]*size[1],first.sampledPixels);
                sameColors(first,again);assertEquals(first.sampledPixels,again.sampledPixels);
                assertTrue(hsv(first.record)[0]>200);assertTrue(hsv(first.record)[0]<225);
                System.out.println("AlbumPalette native "+size[0]+"x"+size[1]+" reads="+first.sampledPixels);
            } finally {bitmap.recycle();}
        }
    }

    @Test public void missingRecycledAndTransparentInputsHaveStableSafeFallback() {
        Bitmap empty=solid(32,32,Color.TRANSPARENT), recycled=solid(8,8,Color.RED);
        recycled.recycle();
        try {
            AlbumPalette fallback=AlbumPalette.from(null);
            sameColors(fallback,AlbumPalette.from(recycled));
            sameColors(fallback,AlbumPalette.from(empty));
            assertNeutral(fallback);assertEquals(0,AlbumPalette.from(recycled).sampledPixels);
            assertTrue(contrast(fallback.background,fallback.accent)>7);
        } finally {empty.recycle();}
    }

    private static Bitmap cover(int color) {Bitmap b=solid(100,100,color);fill(b,0,0,25,100,0xffdfdfdf);return b;}
    private static Bitmap solid(int w,int h,int color){Bitmap b=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);b.eraseColor(color);return b;}
    private static void fill(Bitmap b,int left,int top,int right,int bottom,int color){Paint p=new Paint();p.setColor(color);new Canvas(b).drawRect(left,top,right,bottom,p);}
    private static float[] hsv(int color){float[] values=new float[3];Color.colorToHSV(color,values);return values;}
    private static int hsvColor(float h,float s,float v){return Color.HSVToColor(new float[]{h,s,v});}
    private static float hueDistance(float a,float b){float d=Math.abs(a-b);return Math.min(d,360-d);}
    private static void sameColors(AlbumPalette a,AlbumPalette b){assertEquals(a.background,b.background);assertEquals(a.record,b.record);assertEquals(a.accent,b.accent);}
    private static void assertNeutral(AlbumPalette palette){for(int c:new int[]{palette.background,palette.record,palette.accent}){assertEquals(Color.red(c),Color.green(c));assertEquals(Color.red(c),Color.blue(c));}}
    private static double linear(int c){double v=c/255.;return v<=.04045?v/12.92:Math.pow((v+.055)/1.055,2.4);}
    private static double luminance(int c){return .2126*linear(Color.red(c))+.7152*linear(Color.green(c))+.0722*linear(Color.blue(c));}
    private static double contrast(int a,int b){double x=luminance(a),y=luminance(b);return (Math.max(x,y)+.05)/(Math.min(x,y)+.05);}
    private static void swatches(Bitmap[] covers) throws Exception {
        Bitmap result=Bitmap.createBitmap(800,600,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(result);Paint paint=new Paint();
        try {
            for(int row=0;row<covers.length;row++){
                AlbumPalette p=AlbumPalette.from(covers[row]);int y=row*200;
                paint.setColor(p.background);canvas.drawRect(0,y,800,y+200,paint);
                canvas.drawBitmap(covers[row],null,new android.graphics.Rect(20,y+20,180,y+180),null);
                paint.setColor(p.record);canvas.drawCircle(300,y+100,72,paint);
                paint.setColor(p.accent);paint.setTextSize(25);canvas.drawText("Background / record / accent",405,y+95,paint);
                paint.setTextSize(21);canvas.drawText("Native cover palette",405,y+130,paint);
            }
            File output=new File(System.getProperty("vinyl.output.dir"),"album-palette-native-swatches.png");
            try(FileOutputStream stream=new FileOutputStream(output)){assertTrue(result.compress(Bitmap.CompressFormat.PNG,100,stream));}
        } finally {result.recycle();}
    }
}
