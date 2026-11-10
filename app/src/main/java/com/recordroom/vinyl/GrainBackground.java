package com.recordroom.vinyl;

import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.concurrent.Executors;

/** A bounded, asynchronously decoded neutral grain, shared by themed surfaces. */
final class GrainBackground extends Drawable {
    private static volatile Bitmap tile;
    private static boolean loading;
    private static final ArrayList<WeakReference<GrainBackground>> waiting=new ArrayList<>();
    private final int color;
    private final Paint base=new Paint();
    private final Paint grain=new Paint(Paint.ANTI_ALIAS_FLAG);
    private BitmapShader shader;
    GrainBackground(Context context,int color){
        this.color=color;
        base.setColor(color);
        grain.setAlpha(28);
        grain.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.OVERLAY));
        if(tile==null)synchronized(waiting){
            waiting.add(new WeakReference<>(this));
            if(!loading){loading=true;Context app=context.getApplicationContext();
                java.util.concurrent.ExecutorService worker=Executors.newSingleThreadExecutor();
                worker.execute(()->{
                    try(InputStream in=app.getAssets().open("materials/background-fine-grain-v1.png")){
                        BitmapFactory.Options options=new BitmapFactory.Options();options.inSampleSize=4;
                        tile=BitmapFactory.decodeStream(in,null,options);
                    }catch(Exception ignored){}finally{worker.shutdown();}
                    new Handler(Looper.getMainLooper()).post(()->{synchronized(waiting){
                        for(WeakReference<GrainBackground> ref:waiting){GrainBackground d=ref.get();if(d!=null)d.invalidateSelf();}
                        waiting.clear();loading=false;
                    }});
                });
            }
        }
    }
    @Override public void draw(Canvas canvas){
        canvas.drawRect(getBounds(),base);
        Bitmap bitmap=tile;
        if(bitmap!=null){if(shader==null){shader=new BitmapShader(bitmap,Shader.TileMode.REPEAT,Shader.TileMode.REPEAT);grain.setShader(shader);}canvas.drawRect(getBounds(),grain);}
    }
    @Override public void setAlpha(int alpha){base.setAlpha(Color.alpha(color)*alpha/255);grain.setAlpha(28*alpha/255);invalidateSelf();}
    @Override public void setColorFilter(ColorFilter filter){base.setColorFilter(filter);grain.setColorFilter(filter);invalidateSelf();}
    @Override public int getOpacity(){return base.getAlpha()==255?PixelFormat.OPAQUE:PixelFormat.TRANSLUCENT;}
}
