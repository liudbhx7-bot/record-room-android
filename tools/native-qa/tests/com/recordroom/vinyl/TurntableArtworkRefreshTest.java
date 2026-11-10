package com.recordroom.vinyl;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.graphics.Bitmap;
import android.os.Looper;
import android.view.View;
import android.widget.FrameLayout;
import java.lang.reflect.Field;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=34,qualifiers="w440dp-h640dp-mdpi",fontScale=1f)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public class TurntableArtworkRefreshTest {
    ActivityController<Activity> activity;TurntableView view;
    @Before public void setup(){activity=Robolectric.buildActivity(Activity.class);activity.get().setTheme(android.R.style.Theme_Material_Light_NoActionBar);activity.setup().visible();view=new TurntableView(activity.get());FrameLayout root=new FrameLayout(activity.get());root.addView(view,new FrameLayout.LayoutParams(440,640));activity.get().setContentView(root);root.measure(View.MeasureSpec.makeMeasureSpec(440,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(640,View.MeasureSpec.EXACTLY));root.layout(0,0,440,640);Shadows.shadowOf(Looper.getMainLooper()).idle();view.setActive(true);view.setMotionEnabled(true);view.setPlaying(true);}
    @After public void close(){activity.pause().stop().destroy();}
    static Bitmap cover(int color){Bitmap b=Bitmap.createBitmap(32,32,Bitmap.Config.ARGB_8888);b.eraseColor(color);return b;}
    static Object field(Object target,String name)throws Exception{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}
    ValueAnimator begin()throws Exception{view.setArtwork(cover(0xff174553));view.setArtwork(cover(0xffbb5232));ValueAnimator animator=(ValueAnimator)field(view,"artworkAnimator");assertNotNull("Must exercise the real record replacement animator",animator);return animator;}
    @Test public void highResolutionRefreshAfterSwapKeepsTheSameAnimationAndActuallyReachesThePlatter()throws Exception{
        ValueAnimator animator=begin();animator.setCurrentPlayTime(620);assertEquals(true,field(view,"artworkSwitched"));float alpha=(Float)field(view,"artworkAlpha"),arm=(Float)field(view,"armAngle");Bitmap sharp=cover(0xff4a74c2);
        view.updateArtworkInPlace(sharp);assertSame(sharp,field(view,"artwork"));assertSame(animator,field(view,"artworkAnimator"));assertEquals(alpha,(Float)field(view,"artworkAlpha"),0);assertEquals(arm,(Float)field(view,"armAngle"),0);
        animator.end();assertSame(sharp,field(view,"artwork"));assertEquals(false,field(view,"artworkChanging"));assertEquals(true,field(view,"playing"));
    }
    @Test public void refreshBeforeSwapIsDeferredAndRemovalAfterSwapClearsWithoutRestartingNeedleLift()throws Exception{
        ValueAnimator animator=begin();animator.setCurrentPlayTime(180);Bitmap old=(Bitmap)field(view,"artwork"),sharp=cover(0xff51ba7a);
        view.updateArtworkInPlace(sharp);assertSame(old,field(view,"artwork"));assertSame(animator,field(view,"artworkAnimator"));animator.setCurrentPlayTime(620);assertSame(sharp,field(view,"artwork"));
        view.updateArtworkInPlace(null);assertNull(field(view,"artwork"));assertSame(animator,field(view,"artworkAnimator"));animator.end();assertNull(field(view,"artwork"));assertEquals(true,field(view,"playing"));
    }
}
