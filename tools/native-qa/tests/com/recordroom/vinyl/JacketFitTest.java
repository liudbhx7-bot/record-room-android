package com.recordroom.vinyl;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

/** Corner marks must remain on screen and outside the spinning platter. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=34)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class JacketFitTest {
    @Test public void completePortraitSquareAndLandscapeArtworkSurvivesInTallAndShortDecks() {
        FuguangDeck deck = new FuguangDeck();
        // Portrait, compact landscape column, and unfolded listening area.
        int[][] layouts = {{390,770,182,336,171},{260,235,118,139,81},{410,455,185,268,157}};
        int[][] covers = {{160,160},{130,240},{240,130}};
        for (int[] layout : layouts) {
            int width=layout[0], height=layout[1], cx=layout[2], cy=layout[3], radius=layout[4];
            deck.prepare(width,height,cx,cy,radius,1.025f,1f);
            for (int[] size : covers) {
                Bitmap cover = markedCover(size[0],size[1]);
                Bitmap display = Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(display);
                deck.draw(canvas,cover);
                Paint disc = new Paint();
                disc.setColor(Color.BLACK);
                canvas.drawCircle(cx,cy,radius*1.025f,disc);
                RectF bounds = ReflectionHelpers.getField(deck,"artworkBounds");
                assertEquals((float)cover.getWidth()/cover.getHeight(),bounds.width()/bounds.height(),.001);
                int[] counts = new int[4];
                for (int y=0;y<height;y++) for (int x=0;x<width;x++) {
                    int pixel=display.getPixel(x,y),r=Color.red(pixel),g=Color.green(pixel),b=Color.blue(pixel);
                    if(r>145 && g<80 && b<80) counts[0]++;
                    if(g>145 && r<80 && b<80) counts[1]++;
                    if(b>145 && r<80 && g<80) counts[2]++;
                    if(r>145 && g>145 && b<80) counts[3]++;
                }
                for (int n:counts) assertTrue("A marked corner was clipped: "+width+" × "+height,n>=6);
                cover.recycle();
                display.recycle();
            }
        }
    }

    private static Bitmap markedCover(int width,int height) {
        Bitmap b=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);
        Canvas c=new Canvas(b);
        c.drawColor(Color.DKGRAY);
        Paint p=new Paint();
        int side=Math.min(width,height)/5;
        p.setColor(Color.RED); c.drawRect(0,0,side,side,p);
        p.setColor(Color.GREEN); c.drawRect(width-side,0,width,side,p);
        p.setColor(Color.BLUE); c.drawRect(0,height-side,side,height,p);
        p.setColor(Color.YELLOW); c.drawRect(width-side,height-side,width,height,p);
        return b;
    }
}
