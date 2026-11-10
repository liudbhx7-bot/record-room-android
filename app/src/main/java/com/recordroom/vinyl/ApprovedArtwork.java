package com.recordroom.vinyl;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Approved individual artwork, decoded off-thread and shared without recycling published bitmaps. */
public final class ApprovedArtwork {
    public static final String TONEARM="design/tonearm-graphite-v1.png";
    public static final String SMOKE_DISC="design/disc-smoke-lilac-v1.png";
    public static final String PAPER_TEXTURE="design/paper-ivory-fiber-v1.png";
    public static final String SLEEVE="design/sleeve-ivory-window-v1.png";
    public static final String EMPTY_LIBRARY="design/library-empty-v1.png";
    public static final String GRAIN="materials/background-fine-grain-v1.png";
    private static final Handler MAIN=new Handler(Looper.getMainLooper());
    private static final Object LOCK=new Object();
    private static final LruCache<String,Bitmap> CACHE=new LruCache<String,Bitmap>(12*1024*1024){
        @Override protected int sizeOf(String key,Bitmap value){return value.getAllocationByteCount();}
    };
    private static final HashMap<String,ArrayList<Request>> PENDING=new HashMap<>();
    private static final ExecutorService IO=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"vinyl-approved-artwork");t.setDaemon(true);return t;});
    public interface Callback { void onLoaded(Bitmap bitmap); }
    public static final class Request {
        private volatile Callback callback;
        Request(Callback callback){this.callback=callback;}
        public void cancel(){callback=null;}
        private void deliver(Bitmap value){MAIN.post(()->{Callback listener=callback;callback=null;if(listener!=null)listener.onLoaded(value);});}
    }
    private ApprovedArtwork(){}
    private static boolean approved(String name){return TONEARM.equals(name)||SMOKE_DISC.equals(name)||PAPER_TEXTURE.equals(name)||SLEEVE.equals(name)||EMPTY_LIBRARY.equals(name)||GRAIN.equals(name);}
    private static int edge(int value){return Math.min(1280,Math.max(128,((Math.max(1,value)+127)/128)*128));}
    public static Bitmap cached(String asset,int maxEdge){synchronized(LOCK){return CACHE.get(asset+":"+edge(maxEdge));}}
    public static Request load(Context context,String asset,int maxEdge,Callback callback){
        Request request=new Request(callback);
        if(!approved(asset)){request.deliver(null);return request;}
        final int size=edge(maxEdge);final String key=asset+":"+size;Bitmap ready;boolean schedule=false;
        synchronized(LOCK){ready=CACHE.get(key);if(ready==null){ArrayList<Request> waiting=PENDING.get(key);if(waiting==null){waiting=new ArrayList<>();PENDING.put(key,waiting);schedule=true;}waiting.add(request);}}
        if(ready!=null)request.deliver(ready);
        if(schedule){Context app=context.getApplicationContext();IO.execute(()->{
            Bitmap bitmap;synchronized(LOCK){bitmap=CACHE.get(key);}if(bitmap==null)bitmap=decode(app,asset,size);ArrayList<Request> waiting;
            synchronized(LOCK){if(bitmap!=null)CACHE.put(key,bitmap);waiting=PENDING.remove(key);}
            if(waiting!=null)for(Request item:waiting)item.deliver(bitmap);
        });}
        return request;
    }
    /** Export workers may synchronously read the same bounded cache; never call this on the UI thread. */
    public static Bitmap loadBlocking(Context context,String asset,int maxEdge){
        if(Looper.myLooper()==Looper.getMainLooper())throw new IllegalStateException("Use ApprovedArtwork.load on the UI thread");
        if(!approved(asset))return null;int size=edge(maxEdge);String key=asset+":"+size;
        synchronized(LOCK){Bitmap ready=CACHE.get(key);if(ready!=null)return ready;}
        // One decoder also serves exports; never hold the cache lock across disk IO.
        try{return IO.submit(()->{synchronized(LOCK){Bitmap ready=CACHE.get(key);if(ready!=null)return ready;}Bitmap decoded=decode(context.getApplicationContext(),asset,size);if(decoded!=null)synchronized(LOCK){CACHE.put(key,decoded);}return decoded;}).get();}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();return null;}
        catch(java.util.concurrent.ExecutionException failed){return null;}
    }
    private static Bitmap decode(Context context,String asset,int maxEdge){
        Bitmap bitmap=null;
        try{
            BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;
            try(InputStream input=context.getAssets().open(asset)){BitmapFactory.decodeStream(input,null,bounds);}
            if(bounds.outWidth<=0||bounds.outHeight<=0)return null;
            BitmapFactory.Options options=new BitmapFactory.Options();options.inPreferredConfig=Bitmap.Config.ARGB_8888;
            options.inSampleSize=1;while(Math.max(bounds.outWidth,bounds.outHeight)/(options.inSampleSize*2)>=maxEdge)options.inSampleSize*=2;
            try(InputStream input=context.getAssets().open(asset)){bitmap=BitmapFactory.decodeStream(input,null,options);}
            if(bitmap!=null&&Math.max(bitmap.getWidth(),bitmap.getHeight())>maxEdge){float ratio=maxEdge/(float)Math.max(bitmap.getWidth(),bitmap.getHeight());Bitmap scaled=Bitmap.createScaledBitmap(bitmap,Math.max(1,Math.round(bitmap.getWidth()*ratio)),Math.max(1,Math.round(bitmap.getHeight()*ratio)),true);if(scaled!=bitmap)bitmap.recycle();bitmap=scaled;}
        }catch(java.io.IOException|RuntimeException|OutOfMemoryError ignored){if(bitmap!=null&&!bitmap.isRecycled())bitmap.recycle();return null;}
        return bitmap;
    }
    /** Exact native crop excludes transparent/generated fringes, without modifying the source PNG. */
    public static void drawCircle(Canvas canvas,Bitmap bitmap,float cx,float cy,float radius,float sourceRadius,Paint paint){
        if(bitmap==null||bitmap.isRecycled()||radius<=0)return;
        BitmapShader shader=new BitmapShader(bitmap,Shader.TileMode.CLAMP,Shader.TileMode.CLAMP);Matrix matrix=new Matrix();
        float scale=radius/(Math.min(bitmap.getWidth(),bitmap.getHeight())*Math.max(.1f,sourceRadius));matrix.setScale(scale,scale);matrix.postTranslate(cx-bitmap.getWidth()*scale/2,cy-bitmap.getHeight()*scale/2);shader.setLocalMatrix(matrix);
        Shader previous=paint.getShader();paint.setShader(shader);canvas.drawCircle(cx,cy,radius,paint);paint.setShader(previous);
    }
    /** The paper border and its circular window use native geometry, not generated alpha fringes. */
    public static void drawSleeve(Canvas canvas,Bitmap sleeve,Bitmap artwork,RectF bounds,Paint paint){
        if(bounds.width()<=0||bounds.height()<=0)return;
        float cx=bounds.centerX(),cy=bounds.centerY(),radius=Math.min(bounds.width(),bounds.height())*.205f;
        int save=canvas.save();canvas.clipRect(bounds);
        if(artwork!=null)drawCircle(canvas,artwork,cx,cy,radius+.5f,.5f,paint);
        Path border=new Path();border.setFillType(Path.FillType.EVEN_ODD);border.addRect(bounds,Path.Direction.CW);border.addCircle(cx,cy,radius,Path.Direction.CW);canvas.clipPath(border);
        if(sleeve!=null&&!sleeve.isRecycled()){
            android.graphics.Rect source=new android.graphics.Rect(Math.round(sleeve.getWidth()*.065f),Math.round(sleeve.getHeight()*.078f),Math.round(sleeve.getWidth()*.935f),Math.round(sleeve.getHeight()*.937f));
            canvas.drawBitmap(sleeve,source,bounds,paint);
        }else{int previous=paint.getColor();paint.setColor(DesignSystem.PAPER);canvas.drawRect(bounds,paint);paint.setColor(previous);}
        canvas.restoreToCount(save);
    }
}
