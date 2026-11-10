package com.recordroom.vinyl;

import android.graphics.Bitmap;

/** Bounded visual identity for covers resent as new Bitmap instances by MediaSession. */
final class ArtworkFingerprint {
    private ArtworkFingerprint(){}
    static long of(Bitmap bitmap){
        if(bitmap==null||bitmap.isRecycled())return 0;
        Bitmap readable=bitmap;
        try{
            if(bitmap.getConfig()==Bitmap.Config.HARDWARE)readable=bitmap.copy(Bitmap.Config.ARGB_8888,false);
            if(readable==null)return 0;
            long hash=0xcbf29ce484222325L;int w=readable.getWidth(),h=readable.getHeight();
            for(int row=0;row<24;row++)for(int col=0;col<24;col++){
                int color=readable.getPixel(Math.min(w-1,(2*col+1)*w/48),Math.min(h-1,(2*row+1)*h/48));
                hash=(hash^color)*0x100000001b3L;
            }
            return hash==0?1:hash;
        }catch(IllegalStateException failure){return 0;}
        finally{if(readable!=null&&readable!=bitmap)readable.recycle();}
    }
}
