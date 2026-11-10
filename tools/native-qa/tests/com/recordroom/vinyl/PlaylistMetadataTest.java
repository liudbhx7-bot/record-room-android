package com.recordroom.vinyl;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.net.Uri;
import android.os.Bundle;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Arrays;
import java.util.List;
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
import org.robolectric.shadows.ShadowContentResolver;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=34,qualifiers="w390dp-h844dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public class PlaylistMetadataTest {
    private ActivityController<Activity> controller;private Activity activity;private LibraryStore store;private AlertDialog dialog;
    private final List<Track> songs=Arrays.asList(song("a"),song("b"),song("c"));
    private static Track song(String id){return new Track(id,"原创歌曲 "+id,"原创音乐人","原创专辑","local","asset:///demo/night-1.wav","",18000,1);}
    @Before public void create(){open();activity.getSharedPreferences("record_room_collections",Context.MODE_PRIVATE).edit().clear().commit();
        ShadowContentResolver.registerProviderInternal("draft-covers",new ContentProvider(){
            public boolean onCreate(){return true;}public String getType(Uri uri){return uri.getPath().endsWith(".pdf")?"application/pdf":"image/png";}
            public Cursor query(Uri u,String[]p,String s,String[]a,String o){return null;}public Uri insert(Uri u,ContentValues v){return null;}public int delete(Uri u,String s,String[]a){return 0;}public int update(Uri u,ContentValues v,String s,String[]a){return 0;}
        });
    }
    private void open(){controller=Robolectric.buildActivity(Activity.class);activity=controller.get();activity.setTheme(android.R.style.Theme_Material_Light_NoActionBar);controller.setup().visible();store=new LibraryStore(activity);}
    @After public void close(){if(dialog!=null)dialog.dismiss();controller.pause().stop().destroy();}
    private static View find(View root,String text){if(text.contentEquals(root.getContentDescription()==null?"":root.getContentDescription()))return root;if(root instanceof android.widget.TextView&&text.contentEquals(((android.widget.TextView)root).getText()))return root;if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){View found=find(((ViewGroup)root).getChildAt(i),text);if(found!=null)return found;}return null;}
    private View find(String text){View view=find(dialog.getWindow().getDecorView(),text);assertNotNull(text,view);return view;}
    private static Intent result(String uri){return new Intent().setData(Uri.parse(uri)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);}
    private void layout(){View view=dialog.getWindow().getDecorView();view.measure(View.MeasureSpec.makeMeasureSpec(390,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(844,View.MeasureSpec.EXACTLY));view.layout(0,0,390,844);Shadows.shadowOf(Looper.getMainLooper()).idle();}

    @Test public void imagePickerResultBeforeReshowKeepsDraftIdentityMetadataAndSelection()throws Exception{
        PlaylistDraftDialog draft=PlaylistDraftDialog.create(activity,store,songs,songs.subList(0,2),()->{});dialog=(AlertDialog)draft.show();layout();
        ((EditText)find("歌单名称")).setText("晚风收件箱");((EditText)find("歌单简介")).setText("把今天想留下的声音，放在这里。");
        find("更换歌单封面").performClick();assertTrue(draft.hasPendingCover());
        org.robolectric.shadows.ShadowActivity.IntentForResult launch=Shadows.shadowOf(activity).getNextStartedActivityForResult();assertEquals(104,launch.requestCode);assertEquals(Intent.ACTION_OPEN_DOCUMENT,launch.intent.getAction());assertEquals("image/*",launch.intent.getType());
        Bundle snapshot=draft.snapshot();dialog.dismiss();controller.pause().stop().destroy();open();
        PlaylistDraftDialog restored=PlaylistDraftDialog.restore(activity,store,songs,snapshot,()->{});assertTrue(restored.hasPendingCover());
        assertTrue(restored.onActivityResult(104,Activity.RESULT_OK,result("content://draft-covers/one.png")));assertFalse(restored.hasPendingCover());
        dialog=(AlertDialog)restored.show();layout();assertEquals("晚风收件箱",((EditText)find("歌单名称")).getText().toString());
        assertEquals("content://draft-covers/one.png",restored.snapshot().getString("cover_uri"));assertTrue(store.playlists().isEmpty());
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();LibraryStore.Playlist saved=store.playlists().get(0);
        assertEquals("晚风收件箱",saved.name);assertEquals("把今天想留下的声音，放在这里。",saved.description);assertEquals("content://draft-covers/one.png",saved.coverUri);assertEquals(2,saved.tracks.size());
        assertTrue(activity.getContentResolver().getPersistedUriPermissions().stream().anyMatch(permission->permission.getUri().equals(Uri.parse(saved.coverUri))&&permission.isReadPermission()));
    }

    @Test public void cancelAndRejectedDocumentNeverMutateSavedPlaylistAndClearCommitsExplicitly()throws Exception{
        LibraryStore.Playlist original=store.create("原创歌单","原简介","content://draft-covers/old.png",songs);
        PlaylistDraftDialog draft=PlaylistDraftDialog.edit(activity,store,songs,original,()->{});dialog=(AlertDialog)draft.show();layout();
        find("更换歌单封面").performClick();draft.onActivityResult(104,Activity.RESULT_OK,result("content://draft-covers/not-an-image.pdf"));assertEquals(original.coverUri,draft.snapshot().getString("cover_uri"));
        find("更换歌单封面").performClick();draft.onActivityResult(104,Activity.RESULT_CANCELED,null);assertEquals(original.coverUri,draft.snapshot().getString("cover_uri"));
        find("清除封面").performClick();((EditText)find("歌单简介")).setText("草稿新简介");Bundle cleared=draft.snapshot();
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();assertEquals(original.coverUri,store.playlists().get(0).coverUri);assertEquals("原简介",store.playlists().get(0).description);
        PlaylistDraftDialog restored=PlaylistDraftDialog.restore(activity,store,songs,cleared,()->{});dialog=(AlertDialog)restored.show();layout();
        restored.onActivityResult(104,Activity.RESULT_OK,result("content://draft-covers/late.png"));assertEquals("",restored.snapshot().getString("cover_uri"));
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();assertEquals("",store.playlists().get(0).coverUri);assertEquals("草稿新简介",store.playlists().get(0).description);
    }

    @Test public void nativeDraftUsesEditableContentAndScrollableSelection()throws Exception{
        PlaylistDraftDialog draft=PlaylistDraftDialog.create(activity,store,songs,songs.subList(0,2),()->{});dialog=(AlertDialog)draft.show();layout();
        ((EditText)find("歌单名称")).setText("晚风收件箱");((EditText)find("歌单简介")).setText("把每个想留下的夜晚，收在这里。");layout();
        assertNotNull(dialog.getListView());assertEquals(3,dialog.getListView().getCount());assertTrue(dialog.getListView().getHeight()>0);
        View picker=find("更换歌单封面");assertTrue(picker.getWidth()>=48);assertTrue(picker.getHeight()>=48);
        String output=System.getProperty("vinyl.output.dir");if(output!=null){File directory=new File(output);directory.mkdirs();Bitmap bitmap=Bitmap.createBitmap(390,844,Bitmap.Config.ARGB_8888);dialog.getWindow().getDecorView().draw(new Canvas(bitmap));try(FileOutputStream file=new FileOutputStream(new File(directory,"playlist-draft-design-api34.png"))){assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,file));}finally{bitmap.recycle();}}
    }
}
