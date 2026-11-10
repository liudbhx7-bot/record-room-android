package com.recordroom.vinyl;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.MotionEvent;
import android.view.View;

/** Native saturation/value field and hue strip, with real-time callbacks. */
final class ColorPickerView extends View {
    interface Listener { void changed(int color); }
    private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float[] hsv={12,.45f,.87f};
    private final RectF field=new RectF(), hue=new RectF();
    private Listener listener;
    private int drag;
    ColorPickerView(Context c,int color){super(c);Color.colorToHSV(color,hsv);setMinimumHeight(dp(220));setFocusable(true);setContentDescription("颜色选择器，拖动上方区域选择明暗和饱和度，拖动下方色带选择色相");}
    void setListener(Listener l){listener=l;}
    int color(){return Color.HSVToColor(hsv);}
    void setColor(int value){Color.colorToHSV(value,hsv);invalidate();if(listener!=null)listener.changed(color());}
    @Override protected void onSizeChanged(int w,int h,int ow,int oh){field.set(dp(8),dp(8),w-dp(8),h-dp(50));hue.set(dp(8),h-dp(31),w-dp(8),h-dp(9));}
    @Override protected void onDraw(Canvas c){super.onDraw(c);
        p.setShader(new LinearGradient(field.left,0,field.right,0,Color.WHITE,Color.HSVToColor(new float[]{hsv[0],1,1}),Shader.TileMode.CLAMP));c.drawRoundRect(field,dp(12),dp(12),p);
        p.setShader(new LinearGradient(0,field.top,0,field.bottom,0x00000000,Color.BLACK,Shader.TileMode.CLAMP));c.drawRoundRect(field,dp(12),dp(12),p);
        p.setShader(new LinearGradient(hue.left,0,hue.right,0,new int[]{Color.RED,Color.YELLOW,Color.GREEN,Color.CYAN,Color.BLUE,Color.MAGENTA,Color.RED},null,Shader.TileMode.CLAMP));c.drawRoundRect(hue,dp(10),dp(10),p);p.setShader(null);
        circle(c,field.left+hsv[1]*field.width(),field.top+(1-hsv[2])*field.height(),dp(7));circle(c,hue.left+hsv[0]/360f*hue.width(),hue.centerY(),dp(10));
    }
    private void circle(Canvas c,float x,float y,float radius){p.setStyle(Paint.Style.STROKE);p.setColor(0x88000000);p.setStrokeWidth(dp(4));c.drawCircle(x,y,radius,p);p.setColor(Color.WHITE);p.setStrokeWidth(dp(2));c.drawCircle(x,y,radius,p);p.setStyle(Paint.Style.FILL);}
    @Override public boolean onTouchEvent(MotionEvent e){if(e.getActionMasked()==MotionEvent.ACTION_DOWN){drag=e.getY()>field.bottom?2:1;getParent().requestDisallowInterceptTouchEvent(true);}else if(e.getActionMasked()==MotionEvent.ACTION_CANCEL){drag=0;getParent().requestDisallowInterceptTouchEvent(false);return true;}
        if(drag==1){hsv[1]=unit((e.getX()-field.left)/field.width());hsv[2]=1-unit((e.getY()-field.top)/field.height());}else if(drag==2)hsv[0]=359.9f*unit((e.getX()-hue.left)/hue.width());
        invalidate();if(listener!=null)listener.changed(color());if(e.getActionMasked()==MotionEvent.ACTION_UP){drag=0;getParent().requestDisallowInterceptTouchEvent(false);performClick();}return true;
    }
    @Override public boolean performClick(){super.performClick();return true;}
    private float unit(float x){return Math.max(0,Math.min(1,x));}
    private int dp(float n){return Math.round(n*getResources().getDisplayMetrics().density);}
}
