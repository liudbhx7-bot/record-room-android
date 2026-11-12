package com.recordroom.vinyl;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;

/** Stationary moulded chassis and a jacket seated in its rear pocket.
 * Geometry and materials are prepared on resize, never while the platter spins. */
final class FuguangDeck {
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    private final Paint art = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint lettering = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final Path top = new Path(), seam = new Path();
    private final RectF pocket = new RectF(), sleeve = new RectF(), inward = new RectF();
    private final Matrix noiseScale = new Matrix();
    private final Rect sourceWindow = new Rect();
    private int sourceWidth, sourceHeight;
    private static Bitmap grain;
    private static synchronized Bitmap grainTile() {
        if (grain != null) return grain;
        final int side = 92;
        int[] pixels = new int[side * side];
        int seed = 7349;
        for (int i = 0; i < pixels.length; i++) {
            seed ^= seed << 13; seed ^= seed >>> 17; seed ^= seed << 5;
            int light = seed < 0 ? 0x00ffffff : 0;
            pixels[i] = ((3 + ((seed >>> 13) & 15)) << 24) | light;
        }
        grain = Bitmap.createBitmap(pixels,side,side,Bitmap.Config.ARGB_8888);
        return grain;
    }
    private final BitmapShader grainShader = new BitmapShader(grainTile(),Shader.TileMode.REPEAT,Shader.TileMode.REPEAT);
    private Shader upperLight, edgeLight, socket, jacketShading;
    private float u, left, bottom, tilt, headerX, brandY, modelY, brandSize, modelSize;
    private boolean showSleeve, lightCase;
    private int baseColor = 0xff242120;
    private final Typeface brand = Typeface.create("sans-serif",Typeface.NORMAL);
    private final Typeface model = Typeface.create("sans-serif-light",Typeface.NORMAL);

    boolean setColor(int base) {
        if(base==baseColor) return false;
        baseColor = base;
        lightCase = Color.luminance(baseColor) > .34;
        return true;
    }

    void prepare(int width, int height, float cy, float radius, float density) {
        // u is a pixel in the 842-wide concept, scaled to the available machine, not to the screen diagonal.
        float w = width;
        u = Math.min(width / 842f, radius / 344f);
        sourceWidth=sourceHeight=0;
        if (u <= 0) return;
        float deckTop = cy - radius;
        float rear = deckTop + Math.min(radius * .14f, 22*u);
        float xmin = -8*u, xmax = w + 8*u;
        top.reset(); seam.reset();
        seam.moveTo(xmin,rear+.66f*radius);
        seam.cubicTo(w*.10f,rear+.08f*radius,w*.23f,rear+.005f*radius,w*.49f,rear-.115f*radius);
        seam.cubicTo(w*.75f,rear-.24f*radius,w*.91f,rear-.40f*radius,xmax,rear-.20f*radius);
        top.set(seam); top.lineTo(xmax, -1); top.lineTo(xmin,-1); top.close();

        upperLight = new LinearGradient(0,0,w,Math.max(2,cy),
            new int[]{0x06000000,0x0dffffff,0x13ffffff,0x03ffffff},new float[]{0,.33f,.73f,1},Shader.TileMode.CLAMP);
        edgeLight = new LinearGradient(0,0,w,0,new int[]{0x0dffffff,0x4affffff,0x20ffffff,0x0bffffff},null,Shader.TileMode.CLAMP);
        noiseScale.setScale(Math.max(.72f,Math.min(1.45f,density*.72f)),Math.max(.72f,Math.min(1.45f,density*.72f)));
        grainShader.setLocalMatrix(noiseScale);

        headerX = Math.min(w*.070f,26*density);
        brandSize = Math.max(8*density,Math.min(10*density,20*u));
        modelSize = Math.max(14*density,Math.min(19*density,37*u));
        brandY = Math.min(38*u,17*density) + brandSize;
        modelY = brandY + modelSize*1.28f;

        // Allocate the pocket in available space ABOVE the disc. Short viewports
        // keep the artwork recognisable without drawing through transport controls.
        float clearTop = modelY + 10*density;
        bottom = Math.max(0, rear-18*u);
        // On a shorter machine the platter only reaches this height at its centre.
        // Nest the jacket into the empty upper-left shoulder; draw order naturally
        // tucks its lower-right corner *behind* the record, away from the stylus.
        float desiredRise=Math.min(.42f*w,152*density)*.58f;
        boolean shallow=bottom-clearTop < desiredRise;
        if(shallow)bottom+=Math.min(radius*.29f, clearTop+desiredRise-bottom);
        float available = bottom - clearTop;
        if (available<37*density && width>=144*density) {
            // Very shallow landscape: pair the nameplate and the jacket horizontally.
            // The nameplate remains in the left-hand player pane, away from controls.
            headerX=w*.56f; clearTop=7*density; available=bottom-clearTop;
        }
        showSleeve = available >= 16*density && width >= 112*density && height >= 98*density;
        if (!showSleeve) return;
        float sw = Math.min(w*.428f,available*(shallow?1.32f:1.52f));
        float sh = sw*.575f;
        tilt = -10.5f; left = .054f*w;
        sleeve.set(0,-sh,sw,1.5f*u);
        inward.set(sleeve.left+1.6f*u,sleeve.top+2.6f*u,sleeve.right-1.6f*u,sleeve.bottom);
        pocket.set(-sw*.071f,-sw*.065f,sw*1.102f,sw*.020f);
        socket = new LinearGradient(0,pocket.top,0,pocket.bottom,new int[]{0xee060607,0xff0c0b0c,0xff141314},new float[]{0,.55f,1},Shader.TileMode.CLAMP);
        jacketShading = new LinearGradient(0,sleeve.top,0,sleeve.bottom,new int[]{0x14ffffff,0x00ffffff,0x08000000,0x46000000},new float[]{0,.14f,.79f,1},Shader.TileMode.CLAMP);
    }

    void draw(Canvas c, Bitmap cover) {
        if (u <= 0) return;
        // Translucent specular variation preserves the user's underlying manual finish.
        p.setStyle(Paint.Style.STROKE); p.setStrokeCap(Paint.Cap.ROUND);
        int back = c.save(); c.translate(0,8*u);
        p.setStrokeWidth(23*u); p.setColor(0x13000000); c.drawPath(seam,p);
        p.setStrokeWidth(14*u); p.setColor(0x20000000); c.drawPath(seam,p);
        p.setStrokeWidth(7*u); p.setColor(0x39000000); c.drawPath(seam,p); c.restoreToCount(back);
        p.setStrokeWidth(7*u);p.setColor(0x6b070605);c.drawPath(seam,p);
        p.setStyle(Paint.Style.FILL);p.setColor(0xffffffff);p.setShader(upperLight);c.drawPath(top,p);p.setShader(null);
        p.setShader(grainShader);p.setAlpha(118);c.drawPath(top,p);p.setShader(null);p.setAlpha(255);
        p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(Math.max(.65f,1.7f*u));p.setShader(edgeLight);c.drawPath(seam,p);p.setShader(null);p.setStyle(Paint.Style.FILL);
        if (showSleeve) drawJacket(c,cover);
        lettering.setTypeface(brand);lettering.setTextSize(brandSize);lettering.setColor(lightCase?0xff413d39:0xffcfccc7);
        c.drawText("RECORD ROOM",headerX,brandY,lettering);
        lettering.setTypeface(model);lettering.setTextSize(modelSize);lettering.setColor(lightCase?0xff211f1d:0xfff1eee8);
        c.drawText("浮光 01",headerX,modelY,lettering);
    }

    private void drawJacket(Canvas c,Bitmap cover) {
        int saved = c.save(); c.translate(left,bottom); c.rotate(tilt);
        float sr = pocket.height()*.48f;
        // Light catches the lip at the front of a deep, milled opening.
        p.setColor(0x1bffffff);c.drawRoundRect(pocket.left-1.4f*u,pocket.top+u,pocket.right+1.4f*u,pocket.bottom+2*u,sr,sr,p);
        p.setColor(0xffffffff);p.setShader(socket);c.drawRoundRect(pocket,sr,sr,p);p.setShader(null);
        int shadow=c.save();c.translate(4*u,3*u);p.setColor(0x32000000);c.drawRoundRect(sleeve,2*u,2*u,p);c.restoreToCount(shadow);
        // A small shear straightens the vertical edges of the inserted jacket.
        c.skew(-.145f,0);
        p.setColor(0xff564c44);c.drawRoundRect(sleeve.left-1.5f*u,sleeve.top-3*u,sleeve.right+1.6f*u,sleeve.bottom,2*u,2*u,p);
        p.setColor(0xffb6a997);c.drawRoundRect(sleeve.left-1.2f*u,sleeve.top-1.7f*u,sleeve.right+u,sleeve.bottom,1.8f*u,1.8f*u,p);
        p.setColor(0xffcabbaa);c.drawRoundRect(sleeve,1.7f*u,1.7f*u,p);
        if(cover!=null && !cover.isRecycled()) {
            // Only the portion above the slot is visible. Crop the sheet at its native
            // aspect so faces and round artwork remain in proportion instead of flattening.
            int bw=cover.getWidth(),bh=cover.getHeight();
            if (sourceWidth!=bw || sourceHeight!=bh) {
                float scale=Math.max(inward.width()/bw,inward.height()/bh);
                int showW=Math.min(bw,Math.max(1,Math.round(inward.width()/scale)));
                int showH=Math.min(bh,Math.max(1,Math.round(inward.height()/scale)));
                int x=(bw-showW)/2,y=Math.round((bh-showH)*.38f);
                sourceWindow.set(x,y,x+showW,y+showH);sourceWidth=bw;sourceHeight=bh;
            }
            c.drawBitmap(cover,sourceWindow,inward,art);
        } else {
            // A pressed charcoal blank jacket when no cover exists, never a fake album.
            p.setColor(0xff43403e);c.drawRect(inward,p);
            p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1.2f*u);p.setColor(0xff655e55);
            float x=inward.centerX(),y=inward.centerY();int el=c.save();c.scale(1f,.64f,x,y);c.drawCircle(x,y,inward.width()*.157f,p);c.drawCircle(x,y,inward.width()*.041f,p);c.restoreToCount(el);p.setStyle(Paint.Style.FILL);
        }
        p.setColor(0xffffffff);p.setShader(jacketShading);c.drawRect(inward,p);p.setShader(null);
        p.setShader(grainShader);p.setAlpha(62);c.drawRect(inward,p);p.setAlpha(255);p.setShader(null);
        p.setColor(0x6bfff4db);p.setStrokeWidth(1.2f*u);
        c.drawLine(sleeve.left+1.5f*u,sleeve.top,sleeve.right-1.8f*u,sleeve.top,p);
        c.restoreToCount(saved);
    }
}
