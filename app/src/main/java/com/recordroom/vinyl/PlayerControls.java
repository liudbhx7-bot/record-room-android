package com.recordroom.vinyl;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.View;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;

/** Small vector controls keep the player independent of font glyph availability. */
final class PlayerControls {
    private PlayerControls() { }
    /** Translucent native capsule; the host supplies the live page underneath. */
    static Drawable glassCapsule(Context context) {
        return new LiquidGlass(context,27);
    }
    static Drawable glassPanel(Context context,float radius){return new LiquidGlass(context,radius);}
    private static final class LiquidGlass extends Drawable {
        final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);final RectF r=new RectF();final float radius;final Context context;int opacity=255;
        LiquidGlass(Context context,float radius){this.context=context.getApplicationContext();this.radius=DesignSystem.dp(context,radius);DesignSystem.registerLive(this);}
        @Override public void draw(Canvas c){r.set(getBounds());drawGlass(c,p,r,Math.min(radius,r.width()/2),opacity);}
        @Override public void setAlpha(int a){opacity=a;invalidateSelf();}@Override public void setColorFilter(android.graphics.ColorFilter f){p.setColorFilter(f);invalidateSelf();}@Override public int getOpacity(){return android.graphics.PixelFormat.TRANSLUCENT;}
    }
    private static void drawGlass(Canvas c,Paint p,RectF r,float radius,int opacity){
        p.setStyle(Paint.Style.FILL);p.setAlpha(opacity);
        p.setShader(new LinearGradient(r.left,r.top,r.right,r.bottom,new int[]{0x42ffffff,0x12e8f1f8,0x243c5669,0x36ffffff},new float[]{0,.36f,.72f,1},Shader.TileMode.CLAMP));c.drawRoundRect(r,radius,radius,p);p.setShader(null);
        p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(Math.max(1,Math.min(2,r.width()*.012f)));p.setShader(new LinearGradient(r.left,r.top,r.right,r.bottom,new int[]{0xc9ffffff,0x22ffffff,0x67ffffff},null,Shader.TileMode.CLAMP));RectF edge=new RectF(r);edge.inset(.7f,.7f);c.drawRoundRect(edge,radius,radius,p);p.setShader(null);
        p.setColor(0x21041018);p.setStrokeWidth(1.2f);edge.inset(1.3f,1.3f);c.drawRoundRect(edge,Math.max(0,radius-1),Math.max(0,radius-1),p);
        p.setStyle(Paint.Style.FILL);p.setAlpha(255);
    }
    static void symbol(Canvas c, Paint p, String name, float x, float y, float size, int ink) {
        c.save(); c.translate(x,y); c.scale(size/24f,size/24f);
        p.setShader(null); p.setColor(ink); p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(1.6f); p.setStrokeCap(Paint.Cap.ROUND); p.setStrokeJoin(Paint.Join.ROUND);
        Path path=new Path();
        switch(name) {
            case "play":path.moveTo(9,5);path.lineTo(19,12);path.lineTo(9,19);path.close();c.drawPath(path,p);break;
            case "pause":c.drawLine(8,5,8,19,p);c.drawLine(16,5,16,19,p);break;
            case "previous":c.drawLine(5,5,5,19,p);path.moveTo(19,5);path.lineTo(9,12);path.lineTo(19,19);path.close();c.drawPath(path,p);break;
            case "next":c.drawLine(19,5,19,19,p);path.moveTo(5,5);path.lineTo(15,12);path.lineTo(5,19);path.close();c.drawPath(path,p);break;
            case "shelf":
            case "albums": c.drawLine(3,3,3,21,p);c.drawLine(7,3,7,21,p);c.drawLine(11,3,11,21,p);
                path.moveTo(15,3);path.lineTo(21,6);path.lineTo(21,18);path.lineTo(15,21);path.close();c.drawPath(path,p);break;
            case "lyrics":
                c.drawRoundRect(3,3,21,18,3,3,p);path.moveTo(7,18);path.lineTo(7,21);path.lineTo(11,18);c.drawPath(path,p);
                c.drawLine(7,8,17,8,p);c.drawLine(7,12,14,12,p);break;
            case "queue": c.drawLine(3,4,21,4,p);c.drawLine(3,9,16,9,p);c.drawLine(3,14,11,14,p);c.drawLine(3,19,11,19,p);
                path.moveTo(17,13);path.lineTo(21,16);path.lineTo(17,19);c.drawPath(path,p);break;
            case "back":path.moveTo(14,5);path.lineTo(7,12);path.lineTo(14,19);c.drawPath(path,p);break;
            case "close":c.drawLine(6,6,18,18,p);c.drawLine(18,6,6,18,p);break;
            case "search":c.drawCircle(10.5f,10.5f,6,p);c.drawLine(15,15,21,21,p);break;
            case "plus":case "add":c.drawLine(12,4,12,20,p);c.drawLine(4,12,20,12,p);break;
            case "more":p.setStyle(Paint.Style.FILL);for(int i=0;i<3;i++)c.drawCircle(5+i*7,12,1.5f,p);break;
            case "settings":for(int i=0;i<6;i++){double a=Math.PI*i/3-Math.PI/2;float px=12+(float)Math.cos(a)*9,py=12+(float)Math.sin(a)*9;if(i==0)path.moveTo(px,py);else path.lineTo(px,py);}path.close();c.drawPath(path,p);c.drawCircle(12,12,3,p);break;
            case "disc":c.drawCircle(12,12,9,p);c.drawCircle(12,12,4,p);c.drawCircle(12,12,1,p);break;
            case "shuffle":
                path.moveTo(3,6);path.lineTo(6,6);path.cubicTo(10,6,13,18,17,18);path.lineTo(21,18);c.drawPath(path,p);
                path.reset();path.moveTo(3,18);path.lineTo(6,18);path.cubicTo(8,18,9,15,10.5f,12.5f);c.drawPath(path,p);
                path.reset();path.moveTo(13,8.5f);path.cubicTo(14.5f,6,15.5f,6,18,6);path.lineTo(21,6);c.drawPath(path,p);
                path.reset();path.moveTo(18,3);path.lineTo(21,6);path.lineTo(18,9);path.moveTo(18,15);path.lineTo(21,18);path.lineTo(18,21);c.drawPath(path,p);break;
            case "repeat":
            case "repeatone":
                path.moveTo(5,10);path.lineTo(5,8);path.quadTo(5,5,8,5);path.lineTo(20,5);path.moveTo(17,2);path.lineTo(20,5);path.lineTo(17,8);
                path.moveTo(19,14);path.lineTo(19,16);path.quadTo(19,19,16,19);path.lineTo(4,19);path.moveTo(7,16);path.lineTo(4,19);path.lineTo(7,22);c.drawPath(path,p);
                if(name.equals("repeatone")){path.reset();path.moveTo(10.5f,10.5f);path.lineTo(12.5f,9);path.lineTo(12.5f,15);path.moveTo(10.5f,15);path.lineTo(14.5f,15);c.drawPath(path,p);}break;
            case "grid":
                c.drawRoundRect(3,3,21,21,1.6f,1.6f,p);c.drawLine(12,3,12,21,p);c.drawLine(3,12,21,12,p);break;
            case "sort":
                c.drawLine(3,5,13,5,p);c.drawLine(3,11,10,11,p);c.drawLine(3,17,7,17,p);c.drawLine(18,4,18,20,p);
                path.moveTo(14,16);path.lineTo(18,20);path.lineTo(22,16);c.drawPath(path,p);break;
            case "heart":
            case "heartfilled":
                path.moveTo(12,21);path.cubicTo(10,19,3,14,3,8);path.cubicTo(3,3,9,1.8f,12,6);
                path.cubicTo(15,1.8f,21,3,21,8);path.cubicTo(21,14,14,19,12,21);path.close();
                if(name.equals("heartfilled"))p.setStyle(Paint.Style.FILL);c.drawPath(path,p);break;
            case "share":
                path.moveTo(7,10);path.lineTo(4,10);path.lineTo(4,21);path.lineTo(20,21);path.lineTo(20,10);path.lineTo(17,10);
                path.moveTo(12,15);path.lineTo(12,2);path.moveTo(7,7);path.lineTo(12,2);path.lineTo(17,7);c.drawPath(path,p);break;
            case "user":case "artist":
                c.drawCircle(12,7,4,p);path.moveTo(4,21);path.lineTo(4,19);path.cubicTo(4,12,20,12,20,19);path.lineTo(20,21);c.drawPath(path,p);break;
            case "chevronDown":
            case "down":path.moveTo(5,9);path.lineTo(12,16);path.lineTo(19,9);c.drawPath(path,p);break;
            case "up":path.moveTo(5,15);path.lineTo(12,8);path.lineTo(19,15);c.drawPath(path,p);break;
            case "check":path.moveTo(4,12);path.lineTo(9,17);path.lineTo(20,6);c.drawPath(path,p);break;
            case "sun":
                c.drawCircle(12,12,4.5f,p);for(int i=0;i<8;i++){double a=Math.PI*i/4;float x1=12+(float)Math.cos(a)*7.5f,y1=12+(float)Math.sin(a)*7.5f;float x2=12+(float)Math.cos(a)*10.5f,y2=12+(float)Math.sin(a)*10.5f;c.drawLine(x1,y1,x2,y2,p);}break;
            case "palette":
                path.moveTo(21,13);path.cubicTo(24,5,16,1,10,3);path.cubicTo(3,4,0,10,3,16);path.cubicTo(5,21,11,24,14,20);
                path.cubicTo(16,17,11,15,15,13);path.cubicTo(17,12,20,15,21,13);path.close();c.drawPath(path,p);
                p.setStyle(Paint.Style.FILL);c.drawCircle(7,10,1.3f,p);c.drawCircle(10,6.5f,1.3f,p);c.drawCircle(15,6.5f,1.3f,p);c.drawCircle(6.5f,15,1.3f,p);break;
            case "moon":case "sleep":
                path.moveTo(20.8f,15.3f);path.cubicTo(17,17.3f,11,15.2f,9,11);path.cubicTo(7.3f,7.5f,8.3f,4.3f,10,2.6f);
                path.cubicTo(4.5f,3.8f,1.7f,9,3.2f,14.6f);path.cubicTo(5,22,15.8f,24,20.8f,15.3f);path.close();c.drawPath(path,p);break;
            case "edit":
                path.moveTo(4,16);path.lineTo(16.5f,3.5f);path.quadTo(17.5f,2.5f,18.5f,3.5f);path.lineTo(21,6);path.quadTo(21.5f,6.5f,20.5f,7.5f);
                path.lineTo(8,20);path.lineTo(3,21);path.close();path.moveTo(14,6);path.lineTo(18,10);path.moveTo(4,16);path.lineTo(8,20);c.drawPath(path,p);break;
            case "volume":
                path.moveTo(3,9);path.lineTo(7,9);path.lineTo(12,5);path.lineTo(12,19);path.lineTo(7,15);path.lineTo(3,15);path.close();c.drawPath(path,p);
                c.drawArc(10,7,20,17,-55,110,false,p);c.drawArc(8,3,24,21,-55,110,false,p);break;
            case "album":c.drawRect(4,4,20,20,p);c.drawCircle(12,12,5,p);c.drawCircle(12,12,1,p);break;
            case "chevron":path.moveTo(9,5);path.lineTo(16,12);path.lineTo(9,19);c.drawPath(path,p);break;
            case "grip":c.drawLine(7,8,17,8,p);c.drawLine(7,12,17,12,p);c.drawLine(7,16,17,16,p);break;
            case "trash":c.drawLine(3,6,21,6,p);path.moveTo(9,6);path.lineTo(9,3);path.lineTo(15,3);path.lineTo(15,6);path.moveTo(6,6);path.lineTo(7,21);path.lineTo(17,21);path.lineTo(18,6);path.moveTo(10,10);path.lineTo(10,17);path.moveTo(14,10);path.lineTo(14,17);c.drawPath(path,p);break;
            case "clock":c.drawCircle(12,12,9,p);path.moveTo(12,6);path.lineTo(12,12);path.lineTo(16,14);c.drawPath(path,p);break;
            case "download":path.moveTo(12,3);path.lineTo(12,15);path.moveTo(7,10);path.lineTo(12,15);path.lineTo(17,10);path.moveTo(4,17);path.lineTo(4,21);path.lineTo(20,21);path.lineTo(20,17);c.drawPath(path,p);break;
            case "lock":c.drawRoundRect(5,10,19,21,2,2,p);path.moveTo(8,10);path.lineTo(8,7);path.cubicTo(8,1.5f,16,1.5f,16,7);path.lineTo(16,10);path.moveTo(12,14);path.lineTo(12,17);c.drawPath(path,p);break;
            case "refresh":c.drawArc(4,4,20,20,205,155,false,p);c.drawArc(4,4,20,20,25,155,false,p);path.moveTo(3,3);path.lineTo(3,8);path.lineTo(8,8);path.moveTo(21,21);path.lineTo(21,16);path.lineTo(16,16);c.drawPath(path,p);break;
            case "warning":path.moveTo(12,3);path.lineTo(22,21);path.lineTo(2,21);path.close();path.moveTo(12,9);path.lineTo(12,14);c.drawPath(path,p);c.drawCircle(12,17,.6f,p);break;
            case "expand":path.moveTo(4,9);path.lineTo(4,4);path.lineTo(9,4);path.moveTo(15,4);path.lineTo(20,4);path.lineTo(20,9);path.moveTo(20,15);path.lineTo(20,20);path.lineTo(15,20);path.moveTo(9,20);path.lineTo(4,20);path.lineTo(4,15);c.drawPath(path,p);break;
            case "widget":c.drawRoundRect(3,3,11,11,2,2,p);c.drawRoundRect(14,3,21,21,2,2,p);c.drawRoundRect(3,14,11,21,2,2,p);break;
            case "music":path.moveTo(9,18);path.lineTo(9,6);path.lineTo(20,3);path.lineTo(20,15);path.moveTo(9,9);path.lineTo(20,6);c.drawPath(path,p);c.drawOval(3,16,9,20,p);c.drawOval(14,13,20,17,p);break;
            case "translate":c.drawLine(3,6,14,6,p);c.drawLine(9,3,9,6,p);path.moveTo(6,6);path.cubicTo(6,11,10,14,13,15);path.moveTo(12,6);path.cubicTo(11,11,7,15,3,17);path.moveTo(14,20);path.lineTo(18,9);path.lineTo(22,20);path.moveTo(15,17);path.lineTo(21,17);c.drawPath(path,p);break;
            case "info":c.drawCircle(12,12,9,p);c.drawLine(12,11,12,17,p);c.drawCircle(12,7,.6f,p);break;
            case "signal":for(int i=0;i<4;i++)c.drawLine(5+i*5,18,5+i*5,15-i*4,p);break;
            case "wifi":c.drawArc(0,5,24,25,225,90,false,p);c.drawArc(4,10,20,24,225,90,false,p);c.drawArc(8,15,16,23,225,90,false,p);c.drawCircle(12,20,.5f,p);break;
            default:c.drawCircle(12,12,7,p);
        }
        p.setStyle(Paint.Style.FILL);c.restore();
    }
    static final class Icon extends View {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
        private String symbol; private int ink=0xff20262b; private boolean explicitInk; private Bitmap artwork; private BitmapShader shader; private boolean glass,active;
        Icon(Context c,String symbol){super(c);this.symbol=symbol;setClickable(true);setFocusable(true);setMinimumWidth(dp(48));setMinimumHeight(dp(48));}
        void setSymbol(String value){if(!value.equals(symbol)){symbol=value;invalidate();}}
        void setInk(int value){ink=value;explicitInk=true;invalidate();}
        void setGlass(boolean value){glass=value;invalidate();}
        void setActive(boolean value){active=value;setSelected(value);invalidate();}
        void setArtwork(Bitmap value){artwork=value;shader=value==null?null:new BitmapShader(value,Shader.TileMode.CLAMP,Shader.TileMode.CLAMP);invalidate();}
        @Override protected void onMeasure(int widthSpec,int heightSpec){setMeasuredDimension(resolveSize(getSuggestedMinimumWidth(),widthSpec),resolveSize(getSuggestedMinimumHeight(),heightSpec));}
        @Override protected void onDraw(Canvas c){super.onDraw(c);float s=Math.min(getWidth(),getHeight()),x=getWidth()/2f,y=getHeight()/2f;
            paint.setShader(null);paint.setStyle(Paint.Style.FILL);paint.setAlpha(isEnabled()?255:110);
            if(glass){
                float radius=Math.min(dp(21),s*.44f);
                drawGlass(c,paint,new RectF(x-radius,y-radius,x+radius,y+radius),radius,isEnabled()?255:110);
                if(active){paint.setColor(0xff071015);c.drawCircle(x,y,Math.max(0,radius-dp(4)),paint);}
            }
            if(isPressed()||isFocused()){paint.setColor(isFocused()?0x33ffffff:0x1c000000);c.drawCircle(x,y,s*.44f,paint);}
            if(shader!=null&&artwork!=null&&!artwork.isRecycled()){
                paint.setColor(0x16818b95);c.drawCircle(x,y,s*.41f,paint);
                float diameter=s*.56f,scale=diameter/Math.min(artwork.getWidth(),artwork.getHeight());Matrix m=new Matrix();m.setScale(scale,scale);m.postTranslate((getWidth()-artwork.getWidth()*scale)/2,(getHeight()-artwork.getHeight()*scale)/2);shader.setLocalMatrix(m);
                paint.setAlpha(255);paint.setShader(shader);c.drawCircle(x,y,diameter/2,paint);paint.setShader(null);
            }else {float glyph=Math.min(dp(24),s*.56f);symbol(c,paint,symbol,x-glyph/2,y-glyph/2,glyph,glass?(active?0xffffffff:glassInk()):(!explicitInk&&getParent() instanceof View&&((View)getParent()).getBackground() instanceof LiquidGlass?glassInk():ink));}
            paint.setAlpha(255);
        }
        private int glassInk(){int color=DesignSystem.currentStage(getContext());return .2126*Color.red(color)+.7152*Color.green(color)+.0722*Color.blue(color)>155?0xff17232c:0xfff8fbff;}
        @Override protected void drawableStateChanged(){super.drawableStateChanged();invalidate();}
        @Override public CharSequence getAccessibilityClassName(){return android.widget.Button.class.getName();}
        private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    }
    static final class Hardware extends View {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF key=new RectF(),inner=new RectF();
        private final Path transport=new Path();
        private final String name;
        private String displaySymbol;
        private int ink=Color.WHITE;
        Hardware(Context c,String name){super(c);this.name=name;displaySymbol=name;setClickable(true);setFocusable(true);setMinimumWidth(dp(48));setMinimumHeight(dp(62));}
        void setInk(int color){ink=color;invalidate();}
        void setSymbol(String value){if(value!=null&&!value.equals(displaySymbol)){displaySymbol=value;invalidate();}}
        @Override protected void onMeasure(int widthSpec,int heightSpec){setMeasuredDimension(resolveSize(getSuggestedMinimumWidth(),widthSpec),resolveSize(getSuggestedMinimumHeight(),heightSpec));}
        @Override protected void onDraw(Canvas c){
            super.onDraw(c);
            float x=getWidth()/2f,travel=isPressed()?dp(1.8f):0;
            float width=Math.min(dp(57),getWidth()-dp(3)),top=dp(3)+travel,height=dp(28),radius=dp(16);
            key.set(x-width/2,top,x+width/2,top+height);
            paint.setShader(null);paint.setStyle(Paint.Style.FILL);paint.setColor(isPressed()?0x300b1115:0x600b1115);
            paint.setShadowLayer(dp(isPressed()?2:4),0,dp(isPressed()?1:3),isPressed()?0x380b1115:0x660b1115);
            c.drawRoundRect(key,radius,radius,paint);paint.clearShadowLayer();
            paint.setShader(new LinearGradient(key.left,key.top,key.left,key.bottom,0xff292e31,0xff1e262b,Shader.TileMode.CLAMP));
            c.drawRoundRect(key,radius,radius,paint);paint.setShader(null);
            paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(Math.max(1,dp(.6f)));paint.setColor(0x22757b7e);c.drawRoundRect(key,radius,radius,paint);
            inner.set(key.left+dp(1.5f),key.top+dp(2),key.right-dp(1.5f),key.bottom-dp(1.5f));
            paint.setStyle(Paint.Style.FILL);paint.setColor(isPressed()?0xff202020:0xff252525);c.drawRoundRect(inner,dp(14),dp(14),paint);
            paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(Math.max(1,dp(.8f)));paint.setColor(0xff0f1113);c.drawRoundRect(inner,dp(14),dp(14),paint);
            paint.setStyle(Paint.Style.FILL);paint.setColor(ink);
            if(name.equals("play")){
                paint.setTypeface(Typeface.create("sans-serif-light",Typeface.NORMAL));paint.setTextSize(9.5f*getResources().getDisplayMetrics().scaledDensity);paint.setTextAlign(Paint.Align.CENTER);
                Paint.FontMetrics metrics=paint.getFontMetrics();float baseline=dp(44)-(metrics.ascent+metrics.descent)/2;
                c.drawText("pause".equals(displaySymbol)?"PAUSE":"PLAY",x,baseline,paint);
            }else{
                c.save();c.translate(x-dp(8),dp(38));c.scale(dp(16)/18f,dp(12)/14f);transport.reset();
                if(name.equals("previous")){c.drawRect(2,1,5,13,paint);transport.moveTo(5,7);transport.lineTo(17,1);transport.lineTo(17,13);}
                else{c.drawRect(13,1,16,13,paint);transport.moveTo(1,1);transport.lineTo(13,7);transport.lineTo(1,13);}
                transport.close();c.drawPath(transport,paint);c.restore();
            }
            if(isFocused()){paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(1));paint.setColor((ink&0x00ffffff)|0x99000000);c.drawRoundRect(1,1,getWidth()-1,dp(58),dp(8),dp(8),paint);paint.setStyle(Paint.Style.FILL);}
        }
        @Override protected void drawableStateChanged(){super.drawableStateChanged();invalidate();}
        @Override public CharSequence getAccessibilityClassName(){return android.widget.Button.class.getName();}
        private int dp(float n){return DesignSystem.dp(getContext(),n);}
    }

}
