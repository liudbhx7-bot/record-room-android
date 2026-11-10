package com.recordroom.vinyl;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.ScrollView;
import java.io.File;
import java.io.FileOutputStream;
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
import org.robolectric.shadows.ShadowAlertDialog;
import static org.junit.Assert.*;

/** Checks the real appearance flow, persisted theme state, and constrained native layout. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=34, qualifiers="w480dp-h320dp-land-mdpi", fontScale=1f)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public class AppearanceInteractionTest {
    private static final String ALBUM="appearance-test-album";
    private static final int BEFORE=0xff789088, PREVIEW=0xffa45d93;
    private ActivityController<Activity> controller;
    private Activity activity;
    private ThemeStore themes;
    private Dialog parent;
    private AlertDialog color;
    private Bitmap cover;

    @Before public void openEditor() {
        controller=Robolectric.buildActivity(Activity.class);
        activity=controller.get();
        activity.setTheme(android.R.style.Theme_Material_Light_NoActionBar);
        controller.setup().visible();
        themes=new ThemeStore(activity);
        themes.save("",false,2,0xffd7867e);
        themes.save(ALBUM,true,4,BEFORE);
        cover=Bitmap.createBitmap(32,32,Bitmap.Config.ARGB_8888);
        cover.eraseColor(0xff536e8a);
        parent=new AppearanceEditor(activity,themes,ALBUM,"Test Album",cover,()->{},()->{},()->{}).show();
        idle();
        openColor();
    }

    @After public void closeEditor() {
        if(color!=null)color.dismiss();
        if(parent!=null)parent.dismiss();
        idle();
        if(controller!=null)controller.pause().stop().destroy();
        if(cover!=null)cover.recycle();
    }

    @Test public void parentDismissRollsBackUnsavedPreviewAndPreservesScope() {
        picker().setColor(PREVIEW);
        assertEquals("A color drag previews immediately",PREVIEW,themes.background(ALBUM));
        assertEquals("Album preview must not overwrite the global theme",0xffd7867e,themes.background(""));
        parent.dismiss();idle();
        assertFalse(color.isShowing());
        assertEquals("Configuration teardown must roll back an unsaved preview",BEFORE,new ThemeStore(activity).background(ALBUM));
        assertEquals(4,themes.disc(ALBUM));
        assertTrue(themes.hasAlbum(ALBUM));
    }

    @Test public void invalidHexStaysOpenCancelRollsBackAndSavePersists() {
        picker().setColor(PREVIEW);
        EditText hex=hex();hex.setText("invalid");
        color.getButton(AlertDialog.BUTTON_POSITIVE).performClick();idle();
        assertTrue("Invalid color must leave the editor open",color.isShowing());
        assertNotNull(hex.getError());
        color.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();idle();
        assertEquals(BEFORE,new ThemeStore(activity).background(ALBUM));

        openColor();picker().setColor(PREVIEW);color.cancel();idle();
        assertEquals("Back/outside cancellation must also restore the prior color",BEFORE,themes.background(ALBUM));

        openColor();hex().setText("#123456");
        color.getButton(AlertDialog.BUTTON_POSITIVE).performClick();idle();
        assertFalse(color.isShowing());
        parent.dismiss();idle();
        assertEquals("Explicit Save survives parent dismissal",0xff123456,new ThemeStore(activity).background(ALBUM));
        assertEquals(0xffd7867e,themes.background(""));
    }

    @Test public void shortLandscapeAndReducedViewportKeepControlsReachable() throws Exception {
        assertEquals(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
                color.getWindow().getAttributes().softInputMode & WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST);
        View decor=color.getWindow().getDecorView();
        EditText hex=hex();
        assertTrue("Color content must be inside a scrollable viewport",hex.getParent().getParent() instanceof ScrollView);
        ScrollView scroll=(ScrollView)hex.getParent().getParent();
        for(int height:new int[]{320,240}) {
            layout(decor,480,height);idle();layout(decor,480,height);
            assertTrue("The dialog must allocate space for its content",scroll.getHeight()>0);
            assertTrue("Content overflows a short viewport and must scroll",scroll.getChildAt(0).getHeight()>scroll.getHeight());
            View artwork=described(decor,"专辑封面取色区");
            assertNotNull(artwork);
            scroll.fullScroll(View.FOCUS_DOWN);
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(600));
            layout(decor,480,height);
            render(decor,480,height,"appearance-sampler-after-scroll-"+height+"-api34.png");
            assertVisible(artwork,height,"Album color sampling surface");
            hex.requestFocus();
            hex.requestRectangleOnScreen(new Rect(0,0,hex.getWidth(),hex.getHeight()),true);idle();
            assertVisible(hex,height,"HEX input");
            assertVisible(color.getButton(AlertDialog.BUTTON_POSITIVE),height,"Save action");
            render(decor,480,height,"appearance-color-landscape-"+height+"-api34.png");
        }
    }

    private void openColor() {
        View custom=described(parent.getWindow().getDecorView(),"自定义颜色和封面取色");
        assertNotNull(custom);custom.performClick();idle();
        color=ShadowAlertDialog.getLatestAlertDialog();assertNotNull(color);assertTrue(color.isShowing());
    }
    private EditText hex(){return (EditText)described(color.getWindow().getDecorView(),"十六进制颜色");}
    private ColorPickerView picker(){return findPicker(color.getWindow().getDecorView());}
    private static ColorPickerView findPicker(View view){
        if(view instanceof ColorPickerView)return (ColorPickerView)view;
        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){ColorPickerView found=findPicker(group.getChildAt(i));if(found!=null)return found;}}
        return null;
    }
    private static View described(View view,String description){
        if(description.contentEquals(view.getContentDescription()==null?"":view.getContentDescription()))return view;
        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){View found=described(group.getChildAt(i),description);if(found!=null)return found;}}
        return null;
    }
    private static void idle(){Shadows.shadowOf(Looper.getMainLooper()).idle();}
    private static void layout(View view,int width,int height){view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));view.layout(0,0,width,height);}
    private static void assertVisible(View view,int viewportHeight,String label){
        Rect visible=new Rect();assertTrue(label+" must be visible",view.getGlobalVisibleRect(visible));
        assertTrue(label+" must be within the window",visible.top>=0&&visible.bottom<=viewportHeight);
        assertTrue(label+" must expose an actionable area",visible.height()>=Math.min(32,view.getHeight()));
    }
    private static void render(View view,int width,int height,String name)throws Exception{
        String path=System.getProperty("vinyl.output.dir");assertNotNull(path);
        File output=new File(path);assertTrue(output.isDirectory()||output.mkdirs());
        Bitmap bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);view.draw(new Canvas(bitmap));
        try(FileOutputStream stream=new FileOutputStream(new File(output,name))){assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,stream));}finally{bitmap.recycle();}
    }
}
