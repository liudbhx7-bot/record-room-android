package com.recordroom.vinyl;

import android.graphics.Bitmap;
import android.graphics.Color;

/** Small, deterministic cover palette. Call on the artwork decoder's worker thread. */
public final class AlbumPalette {
    public final int background;
    public final int record;
    public final int accent;
    public final int deckLight;
    // Actual pixel reads, retained for bounded-work verification without bitmap mocks.
    final int sampledPixels;
    private static final int MAX_SAMPLES = 4096;
    private static final int BINS = 24;
    private static final double MIN_COLOR_SHARE = .22;

    private AlbumPalette(int background, int record, int accent, int sampledPixels) {
        this(background, record, accent, sampledPixels, record);
    }
    private AlbumPalette(int background, int record, int accent, int sampledPixels, int deckLight) {
        this.deckLight = deckLight;
        this.background = background;
        this.record = record;
        this.accent = accent;
        this.sampledPixels = sampledPixels;
    }

    /** Never mutates, retains or recycles the caller; hardware covers need a temporary software readback. */
    public static AlbumPalette from(Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) return fallback(0);
        int reads = 0;
        try {
            // Hardware bitmaps cannot be read with getPixel; the decoder normally supplies software.
            if (bitmap.getConfig() == Bitmap.Config.HARDWARE) return fromHardware(bitmap);
            int width = bitmap.getWidth(), height = bitmap.getHeight();
            if (width <= 0 || height <= 0) return fallback(0);
            int columns = Math.min(width, Math.max(1,
                    (int) Math.round(Math.sqrt(MAX_SAMPLES * (double) width / height))));
            columns = Math.min(columns, MAX_SAMPLES);
            int rows = Math.min(height, Math.max(1, MAX_SAMPLES / columns));
            columns = Math.min(width, MAX_SAMPLES / rows);
            double[] weights = new double[BINS], saturation = new double[BINS];
            double[] value = new double[BINS], hueX = new double[BINS], hueY = new double[BINS];
            // Issued by the same bounded pixel pass: dark covers often carry their light
            // colour in a window, sign or reflection rather than in the largest shadow.
            double[] lightScore=new double[BINS], lightArea=new double[BINS], lx=new double[BINS], ly=new double[BINS];
            double total = 0, brightness = 0;
            float[] hsv = new float[3];
            for (int row = 0; row < rows; row++) {
                int y = (int) ((2L * row + 1) * height / (2L * rows));
                for (int column = 0; column < columns; column++) {
                    int x = (int) ((2L * column + 1) * width / (2L * columns));
                    reads++;
                    int color = bitmap.getPixel(x, y), alpha = Color.alpha(color);
                    // Near-transparent edge RGB is not visible cover content.
                    if (alpha < 32) continue;
                    double weight = alpha / 255.0;
                    total += weight;
                    brightness += weight * (.2126 * Color.red(color)
                            + .7152 * Color.green(color) + .0722 * Color.blue(color)) / 255;
                    Color.colorToHSV(color, hsv);
                    if (hsv[1] <= .18f || hsv[2] <= .12f) continue;
                    int bin = Math.min(BINS - 1, (int) (hsv[0] * BINS / 360));
                    double angle = Math.toRadians(hsv[0]);
                    weights[bin] += weight;
                    saturation[bin] += weight * hsv[1];
                    value[bin] += weight * hsv[2];
                    if (hsv[1] >= .22f && hsv[2] >= .32f) {
                        double v=hsv[2], luma=(.2126*Color.red(color)+.7152*Color.green(color)+.0722*Color.blue(color))/255;
                        double score=weight*v*v*v*hsv[1]*(.3+.7*luma);
                        lightScore[bin]+=score; lightArea[bin]+=weight;
                        lx[bin]+=score*Math.cos(angle); ly[bin]+=score*Math.sin(angle);
                    }
                    hueX[bin] += weight * Math.cos(angle);
                    hueY[bin] += weight * Math.sin(angle);
                }
            }
            if (total == 0) return fallback(reads);
            // Neighbouring bins form one colour family; red wraps across 360/0 degrees.
            // Area, not saturation, decides the winner, so a bright speck cannot take over.
            int best = 0;
            double bestWeight = 0;
            for (int bin = 0; bin < BINS; bin++) {
                double weight = 0;
                for (int delta = -1; delta <= 1; delta++) weight += weights[(bin + delta + BINS) % BINS];
                if (weight > bestWeight) { bestWeight = weight; best = bin; }
            }
            if (bestWeight < total * MIN_COLOR_SHARE) return neutral(brightness / total, reads);
            double sx = 0, sy = 0, sat = 0, val = 0;
            for (int delta = -1; delta <= 1; delta++) {
                int bin = (best + delta + BINS) % BINS;
                sx += hueX[bin]; sy += hueY[bin]; sat += saturation[bin]; val += value[bin];
            }
            float hue = (float) ((Math.toDegrees(Math.atan2(sy, sx)) + 360) % 360);
            float sourceSaturation = (float) (sat / bestWeight);
            float sourceValue = (float) (val / bestWeight);
            int recordColor=hsv(hue, .18f + .34f * sourceSaturation, .82f + .16f * sourceValue);
            double top=0; int lamp=recordColor;
            for(int i=0;i<BINS;i++) {
                int a=(i+BINS-1)%BINS,b=(i+1)%BINS;
                if(lightArea[a]+lightArea[i]+lightArea[b]<total*.055)continue; // ignore dust, text and tiny logos
                double score=lightScore[a]+lightScore[i]+lightScore[b];
                if(score<=top)continue;
                top=score;
                float lightHue=(float)((Math.toDegrees(Math.atan2(ly[a]+ly[i]+ly[b],lx[a]+lx[i]+lx[b]))+360)%360);
                lamp=hsv(lightHue,.48f+.32f*sourceSaturation,.94f);
            }
            return new AlbumPalette(
                    hsv(hue, .08f + .09f * sourceSaturation, .97f),
                    recordColor,
                    hsv(hue, .20f + .35f * sourceSaturation, .22f + .10f * sourceValue), reads, lamp);
        } catch (IllegalStateException unavailable) {
            // A shared cover may have been recycled between the check and a pixel read.
            return fallback(reads);
        }
    }

    private static AlbumPalette fromHardware(Bitmap source) {
        Bitmap readable=null;
        try {
            readable=source.copy(Bitmap.Config.ARGB_8888,false);
            return readable==null?fallback(0):from(readable);
        } catch (IllegalStateException | IllegalArgumentException | OutOfMemoryError unavailable) {
            return fallback(0);
        } finally { if(readable!=null&&readable!=source)readable.recycle(); }
    }

    private static AlbumPalette neutral(double brightness, int reads) {
        return new AlbumPalette(gray(230 + 12 * brightness), gray(180 + 32 * brightness),
                gray(45 + 20 * brightness), reads);
    }
    private static AlbumPalette fallback(int reads) {
        return new AlbumPalette(0xffededed, 0xff777777, 0xff3d3d3d, reads);
    }
    private static int gray(double value) {
        int component = (int) Math.round(value);
        return Color.rgb(component, component, component);
    }
    private static int hsv(float hue, float saturation, float value) {
        return Color.HSVToColor(new float[]{hue, saturation, value});
    }
}
