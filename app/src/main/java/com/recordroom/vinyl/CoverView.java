package com.recordroom.vinyl;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;
import java.lang.ref.WeakReference;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Asynchronous small album-cover view used by grids, playlists and the queue. */
final class CoverView extends ImageView {
    static final ExecutorService IO=Executors.newSingleThreadExecutor();
    private static final Handler MAIN=new Handler(Looper.getMainLooper());
    private static final LruCache<String,Bitmap> CACHE=new LruCache<String,Bitmap>(12*1024*1024){protected int sizeOf(String key,Bitmap value){return value.getAllocationByteCount();}};
    private int generation,requestedEdge;
    private String requestedUri="";
    private boolean loading;
    CoverView(Context c){super(c);setScaleType(ScaleType.CENTER_CROP);setBackgroundColor(0xffb8acba);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
    void clear(){generation++;requestedUri="";loading=false;setImageDrawable(null);}
    void setTrack(Track track,ThemeStore themes,int edge){String override=themes.artwork(track.albumKey());setSource(override.isEmpty()?track.artworkUri:override,edge);}
    void setSource(String uri,int edge){
        String value=uri==null?"":uri;
        if(value.isEmpty()){clear();return;}
        if(value.equals(requestedUri)&&edge==requestedEdge&&(loading||getDrawable() instanceof android.graphics.drawable.BitmapDrawable&&((android.graphics.drawable.BitmapDrawable)getDrawable()).getBitmap()!=null))return;
        requestedUri=value;requestedEdge=edge;loadRequested();
    }
    private void loadRequested(){
        int token=++generation;String uri=requestedUri,key=uri+"|"+requestedEdge;int edge=requestedEdge;
        Bitmap cached=CACHE.get(key);if(cached!=null){loading=false;setImageBitmap(cached);return;}
        loading=true;setImageDrawable(null);Context app=getContext().getApplicationContext();WeakReference<CoverView> target=new WeakReference<>(this);
        IO.execute(()->{
            // A queued request may already have been decoded by another view using this cover.
            Bitmap bitmap=CACHE.get(key);if(bitmap==null){bitmap=LocalMusicImporter.loadArtwork(app,uri,edge);if(bitmap!=null)CACHE.put(key,bitmap);}final Bitmap result=bitmap;
            MAIN.post(()->{CoverView view=target.get();if(view!=null&&view.generation==token){view.loading=false;if(result==null)view.setImageDrawable(null);else view.setImageBitmap(result);}});
        });
    }
    @Override protected void onAttachedToWindow(){super.onAttachedToWindow();if(!requestedUri.isEmpty()&&!loading&&getDrawable()==null)loadRequested();}
    @Override protected void onDetachedFromWindow(){generation++;loading=false;super.onDetachedFromWindow();}

}
