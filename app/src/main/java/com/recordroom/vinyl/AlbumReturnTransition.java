package com.recordroom.vinyl;

import android.animation.ValueAnimator;
import android.app.Dialog;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;

/** The shelf remains underneath the detail page, at its original scroll position. */
final class AlbumReturnTransition implements Runnable {
    private final Dialog dialog;
    private final View page,cover;
    private final RectF origin;
    private final float tilt;
    private boolean returning;
    AlbumReturnTransition(Dialog dialog,View page,View cover,RectF origin,float tilt) {
        this.dialog=dialog;this.page=page;this.cover=cover;
        this.origin=origin==null?null:new RectF(origin);this.tilt=tilt;
    }
    @Override public void run() {
        if(returning||!dialog.isShowing())return;
        returning=true;cover.animate().cancel();
        if(!ValueAnimator.areAnimatorsEnabled()||cover.getWidth()==0){dialog.dismiss();return;}
        Rect visible=new Rect();
        if(cover.getGlobalVisibleRect(visible)&&visible.height()>cover.getHeight()/2) {
            float rotation=Math.max(-72,Math.min(72,tilt));
            float x=0,y=0,sx=.88f,sy=.88f;
            if(origin!=null&&!origin.isEmpty()) {
                int[] location=new int[2];cover.getLocationOnScreen(location);
                x=origin.centerX()-location[0]-cover.getWidth()/2f;
                y=origin.centerY()-location[1]-cover.getHeight()/2f;
                sx=Math.max(.2f,Math.min(2f,origin.width()/cover.getWidth()));
                sy=Math.max(.2f,Math.min(2f,origin.height()/(cover.getHeight()*(float)Math.cos(Math.toRadians(rotation)))));
            } else rotation=48;
            cover.animate().translationX(x).translationY(y).scaleX(sx).scaleY(sy).rotationX(rotation).alpha(.25f).setDuration(280).setInterpolator(new AccelerateDecelerateInterpolator()).start();
        }
        page.animate().alpha(0f).setDuration(280).withEndAction(dialog::dismiss).start();
    }
}
