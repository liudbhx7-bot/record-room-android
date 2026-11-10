package com.recordroom.vinyl;

import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.AbsListView;
import android.widget.ScrollView;
import android.widget.HorizontalScrollView;
import java.util.ArrayList;

/** Compact viewport state. Recycled list children are deliberately not traversed. */
final class ViewScrollState {
    static Bundle capture(View root){Bundle state=new Bundle();capture(root,"v",state);return state;}
    private static void capture(View view,String key,Bundle out){
        if(view instanceof AbsListView){out.putParcelable(key,((AbsListView)view).onSaveInstanceState());return;}
        if(view instanceof AlbumStackView){out.putFloat(key,((AlbumStackView)view).getScrollPosition());return;}
        if(view instanceof ScrollView||view instanceof HorizontalScrollView)out.putIntArray(key,new int[]{view.getScrollX(),view.getScrollY()});
        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)capture(group.getChildAt(i),key+"/"+i,out);}
    }
    static void restore(View root,Bundle state){
        if(state==null)return;
        root.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener(){
            @Override public void onGlobalLayout(){
                if(root.getViewTreeObserver().isAlive())root.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                apply(root,"v",state);
            }
        });
        root.requestLayout();
    }
    private static void apply(View view,String key,Bundle state){
        if(view instanceof AbsListView){android.os.Parcelable saved=state.getParcelable(key);if(saved!=null)((AbsListView)view).onRestoreInstanceState(saved);return;}
        if(view instanceof AlbumStackView){if(state.containsKey(key))((AlbumStackView)view).setScrollPosition(state.getFloat(key));return;}
        if(view instanceof ScrollView||view instanceof HorizontalScrollView){int[] xy=state.getIntArray(key);if(xy!=null&&xy.length==2)view.scrollTo(xy[0],xy[1]);}
        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)apply(group.getChildAt(i),key+"/"+i,state);}
    }
}
