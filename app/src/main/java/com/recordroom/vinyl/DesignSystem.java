package com.recordroom.vinyl;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import java.lang.ref.WeakReference;

/** Approved v1 native design tokens. This contains no account or demonstration content. */
public final class DesignSystem {
    public static final int INK=0xff24282c,MUTED=0xff566571,PAPER=0xfff6f4f0,LILAC=0xffe2e8eb,ACCENT=0xff52788e;
    public static final int ROSE=0xffdcb8b0;
    public static final int onAmbient=0xfff3f5f5,onAmbientMuted=0xffc7cfd3;
    public static final float RECORD_LABEL_RATIO=.42f;
    private DesignSystem(){}
    public static int dp(Context context,float value){return Math.round(value*context.getResources().getDisplayMetrics().density);}
    public static Drawable paper(Context context){return new LiveSurface(context,0,0);}
    public static Drawable lilac(Context context){return new Surface(context,LILAC,ApprovedArtwork.GRAIN,18);}
    public static Drawable surface(Context context,int color){return new Surface(context,color,ApprovedArtwork.GRAIN,18);}
    /** Quiet directional light; all artwork and content stay sharp. */
    static int stageColor(ThemeStore themes,String album){
        if(!themes.followCoverColors())return themes.background(album);
        int tint=themes.recordTint(album);if(android.graphics.Color.alpha(tint)==0)return 0xff30465c;
        float[] hsv=new float[3];android.graphics.Color.colorToHSV(tint,hsv);hsv[1]=hsv[1]<.05f?0:Math.min(.50f,Math.max(.18f,hsv[1]*.74f));hsv[2]=.26f;
        // Reserve separation for the record, including monochrome covers and the soft stage light.
        double recordLuminance=android.graphics.Color.luminance(tint);int stage=android.graphics.Color.HSVToColor(hsv);
        while(hsv[2]>.08f&&(recordLuminance+.05)/(android.graphics.Color.luminance(stage)+.05)<3.4){hsv[2]-=.01f;stage=android.graphics.Color.HSVToColor(hsv);}
        if(themes.disc(album)==0){
            // Black vinyl absorbs the tint in its photographed grooves; compare its material,
            // not the brighter input pigment, when reserving a darker backdrop.
            double materialLuminance=android.graphics.Color.luminance(DiscMaterials.tintedColor(0,tint));
            while(hsv[2]>.01f&&(materialLuminance+.05)/(android.graphics.Color.luminance(stage)+.05)<2){hsv[2]-=.01f;stage=android.graphics.Color.HSVToColor(hsv);}
        }
        return stage;
    }
    public static Drawable stage(Context context,int color){return new Stage(color);}
    private static final java.util.ArrayList<WeakReference<Drawable>> liveSurfaces=new java.util.ArrayList<>();
    private static android.content.SharedPreferences palette(Context c){return c.getApplicationContext().getSharedPreferences("vinyl_appearance",Context.MODE_PRIVATE);}
    static int currentStage(Context c){return palette(c).getInt("ui_stage",0xff30465c);}
    static int currentTint(Context c){return palette(c).getInt("ui_tint",0xff829cba);}
    static int mix(int a,int b,float t){return android.graphics.Color.rgb(Math.round(android.graphics.Color.red(a)*(1-t)+android.graphics.Color.red(b)*t),Math.round(android.graphics.Color.green(a)*(1-t)+android.graphics.Color.green(b)*t),Math.round(android.graphics.Color.blue(a)*(1-t)+android.graphics.Color.blue(b)*t));}
    static int pageColor(Context c){return mix(currentTint(c),0xfffafbfc,.87f);}
    static int pageSurface(Context c){return mix(currentTint(c),0xffffffff,.93f);}
    static int pageAccent(Context c){return mix(currentStage(c),0xff17222b,.14f);}
    static Drawable pageCard(Context c,float radius){return new LiveSurface(c,1,radius);}
    static Drawable pageButton(Context c,float radius){return new LiveSurface(c,2,radius);}
    static int ambientColor(Context c){
        int stage=currentStage(c);
        // A neutral anchor retains the cover hue instead of turning muted red covers blue.
        int anchor=0xff151515;
        int color=mix(stage,anchor,.62f);
        // Keep fixed light text readable even when the user chooses a very light manual background.
        return android.graphics.Color.luminance(color)>.075?mix(color,anchor,.5f):color;
    }
    static int settingsCardColor(Context c){return mix(ambientColor(c),0xffffffff,.065f);}
    static Drawable settingsCard(Context c,float radius){return new LiveSurface(c,5,radius);}
    static Drawable ambientButton(Context c,float radius){
        return new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x20ffffff),new LiveSurface(c,6,radius),rounded(c,0xffffffff,radius));
    }
    static Drawable liveStage(Context c){return new LiveSurface(c,4,0);}
    static Drawable ambient(Context c){return new LiveSurface(c,3,0);}
    static void setAlbumTheme(Context c,String key,int stage,int tint){
        if(android.graphics.Color.alpha(tint)==0)tint=stage;
        android.content.SharedPreferences p=palette(c);if(p.getInt("ui_stage",0)==stage&&p.getInt("ui_tint",0)==tint)return;
        p.edit().putInt("ui_stage",stage).putInt("ui_tint",tint).apply();
        for(int i=liveSurfaces.size()-1;i>=0;i--){Drawable d=liveSurfaces.get(i).get();if(d==null)liveSurfaces.remove(i);else d.invalidateSelf();}
    }
    static void registerLive(Drawable d){for(int i=liveSurfaces.size()-1;i>=0;i--)if(liveSurfaces.get(i).get()==null)liveSurfaces.remove(i);liveSurfaces.add(new WeakReference<>(d));}
    private static final class LiveSurface extends Drawable {
        final Context context;final int mode;final float radius;final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);int opacity=255;
        LiveSurface(Context c,int mode,float radius){context=c.getApplicationContext();this.mode=mode;this.radius=dp(c,radius);registerLive(this);}
        @Override public void draw(Canvas canvas){android.graphics.Rect b=getBounds();int base=mode==6?mix(settingsCardColor(context),0xffffffff,.06f):mode==5?settingsCardColor(context):mode==4?currentStage(context):mode==3?ambientColor(context):mode==1?pageSurface(context):mode==2?mix(currentTint(context),0xffffffff,.77f):pageColor(context);p.setAlpha(opacity);
            if(mode==3||mode==5||mode==6){p.setShader(null);p.setColor(base);p.setAlpha(opacity);canvas.drawRoundRect(new android.graphics.RectF(b),radius,radius,p);return;}
            p.setShader(new android.graphics.LinearGradient(b.left,b.top,b.right,b.bottom,new int[]{mix(base,0xffffffff,mode==3?.07f:.06f),base,mix(base,mode==3?0xff071219:currentTint(context),mode==3?.35f:.04f)},null,Shader.TileMode.CLAMP));
            canvas.drawRoundRect(new android.graphics.RectF(b),radius,radius,p);p.setShader(null);
            if(mode==3){p.setShader(new android.graphics.RadialGradient(b.right,b.top,Math.max(1,b.height()*.8f),new int[]{0x18ffffff,0x00ffffff},null,Shader.TileMode.CLAMP));canvas.drawRect(b,p);p.setShader(null);}
        }
        @Override public void setAlpha(int a){opacity=a;invalidateSelf();}@Override public void setColorFilter(ColorFilter f){p.setColorFilter(f);invalidateSelf();}@Override public int getOpacity(){return opacity==255?PixelFormat.OPAQUE:PixelFormat.TRANSLUCENT;}
    }
    private static final class Stage extends Drawable {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);private final int color;private int alpha=255;
        Stage(int color){this.color=color;}
        private int blend(int target,float amount){return android.graphics.Color.rgb((int)(android.graphics.Color.red(color)*(1-amount)+android.graphics.Color.red(target)*amount),(int)(android.graphics.Color.green(color)*(1-amount)+android.graphics.Color.green(target)*amount),(int)(android.graphics.Color.blue(color)*(1-amount)+android.graphics.Color.blue(target)*amount));}
        @Override public void draw(Canvas canvas){
            android.graphics.Rect r=getBounds();float w=Math.max(1,r.width()),h=Math.max(1,r.height());
            paint.setAlpha(alpha);paint.setShader(new android.graphics.LinearGradient(r.left,r.top,r.right,r.bottom,new int[]{blend(0xffffffff,.045f),color,blend(0xff000000,.16f)},null,Shader.TileMode.CLAMP));canvas.drawRect(r,paint);
            paint.setShader(new android.graphics.RadialGradient(r.left+w*.98f,r.top+h*.76f,w*.80f,new int[]{0x08ffffff,0x00ffffff},null,Shader.TileMode.CLAMP));canvas.drawRect(r,paint);paint.setShader(null);
        }
        @Override public void setAlpha(int value){alpha=value;invalidateSelf();}@Override public void setColorFilter(ColorFilter filter){paint.setColorFilter(filter);invalidateSelf();}@Override public int getOpacity(){return alpha==255?PixelFormat.OPAQUE:PixelFormat.TRANSLUCENT;}
    }
    public static GradientDrawable rounded(Context context,int color,float radius){GradientDrawable drawable=new GradientDrawable();drawable.setColor(color);drawable.setCornerRadius(dp(context,radius));return drawable;}
    private static final class Surface extends Drawable {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
        private final int color,textureAlpha,tileSize;private int opacity=255;private BitmapShader texture;
        Surface(Context context,int color,String asset,int alpha){
            this.color=color;textureAlpha=alpha;tileSize=dp(context,320);
            WeakReference<Surface> owner=new WeakReference<>(this);
            ApprovedArtwork.load(context,asset,512,bitmap->{Surface view=owner.get();if(view==null||bitmap==null)return;view.texture=new BitmapShader(bitmap,Shader.TileMode.REPEAT,Shader.TileMode.REPEAT);Matrix m=new Matrix();float scale=view.tileSize/(float)bitmap.getWidth();m.setScale(scale,scale);view.texture.setLocalMatrix(m);view.invalidateSelf();});
        }
        @Override public void draw(Canvas canvas){paint.setShader(null);paint.setColor(color);paint.setAlpha(opacity);canvas.drawRect(getBounds(),paint);if(texture!=null){paint.setShader(texture);paint.setAlpha(textureAlpha*opacity/255);canvas.drawRect(getBounds(),paint);paint.setShader(null);}}
        @Override public void setAlpha(int value){opacity=Math.max(0,Math.min(255,value));invalidateSelf();}
        @Override public void setColorFilter(ColorFilter filter){paint.setColorFilter(filter);invalidateSelf();}
        @Override public int getOpacity(){return opacity==255?PixelFormat.OPAQUE:PixelFormat.TRANSLUCENT;}
    }
}
