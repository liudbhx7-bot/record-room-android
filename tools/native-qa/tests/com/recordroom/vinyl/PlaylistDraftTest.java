package com.recordroom.vinyl;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.os.Bundle;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ListView;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=34)
@LooperMode(LooperMode.Mode.PAUSED)
public class PlaylistDraftTest {
    private ActivityController<Activity> controller;
    private Activity activity;
    private LibraryStore store;
    private List<Track> library;
    private AlertDialog dialog;
    @Before public void setup(){
        RuntimeEnvironment.getApplication().getSharedPreferences("record_room_collections",Context.MODE_PRIVATE).edit().clear().commit();
        openActivity();library=Arrays.asList(track("a"),track("b"),track("c"));
    }
    @After public void teardown(){if(dialog!=null)dialog.dismiss();idle();if(controller!=null)controller.pause().stop().destroy();}
    private void openActivity(){controller=Robolectric.buildActivity(Activity.class);activity=controller.get();activity.setTheme(android.R.style.Theme_Material_Light_NoActionBar);controller.setup().visible();store=new LibraryStore(activity);}
    private void recreate(){if(dialog!=null)dialog.dismiss();idle();controller.pause().stop().destroy();openActivity();dialog=null;}

    @Test public void createDraftKeepsNameAndIdentitiesAcrossRecreationButCancelWritesNothing(){
        PlaylistDraftDialog draft=PlaylistDraftDialog.create(activity,store,library,library.subList(0,2),()->fail("Cancel cannot complete"));
        dialog=(AlertDialog)draft.show();input(dialog).setText("  周末夜晚  ");Bundle state=draft.snapshot();
        assertEquals("",state.getString("description"));assertEquals("",state.getString("cover_uri"));assertFalse(state.getBoolean("pending_cover"));assertEquals("  周末夜晚  ",state.getString("name"));
        assertEquals(Arrays.asList(LibraryStore.identity(library.get(0)),LibraryStore.identity(library.get(1))),state.getStringArrayList("selected_ids"));
        recreate();PlaylistDraftDialog restored=PlaylistDraftDialog.restore(activity,store,library,state,()->fail("Cancel cannot complete"));
        assertNotNull(restored);dialog=(AlertDialog)restored.show();assertEquals("  周末夜晚  ",input(dialog).getText().toString());
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();idle();assertTrue(store.playlists().isEmpty());
    }

    @Test public void renameValidatesAndCommitsOnceWithoutChangingSongs(){
        LibraryStore.Playlist playlist=store.create("旧名字",library.subList(0,2));int[] saved={0};
        PlaylistDraftDialog draft=PlaylistDraftDialog.rename(activity,store,library,playlist,()->saved[0]++);
        dialog=(AlertDialog)draft.show();input(dialog).setText("旋转后的名字");Bundle state=draft.snapshot();recreate();
        PlaylistDraftDialog restored=PlaylistDraftDialog.restore(activity,store,library,state,()->saved[0]++);assertNotNull(restored);
        dialog=(AlertDialog)restored.show();assertEquals("旋转后的名字",input(dialog).getText().toString());
        input(dialog).setText("   ");dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();assertTrue(dialog.isShowing());
        assertNotNull(input(dialog).getError());assertEquals("旧名字",store.playlists().get(0).name);assertEquals(0,saved[0]);
        input(dialog).setText("新名字");dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();idle();
        LibraryStore.Playlist result=store.playlists().get(0);assertEquals(playlist.id,result.id);assertEquals("新名字",result.name);
        assertEquals(2,result.tracks.size());assertEquals("a",result.tracks.get(0).id);assertEquals("b",result.tracks.get(1).id);assertEquals(1,saved[0]);
    }

    @Test public void editedSelectionSurvivesRecreationAndCancellationBeforeExplicitCommit(){
        LibraryStore.Playlist playlist=store.create("选曲",Collections.singletonList(library.get(0)));
        PlaylistDraftDialog draft=PlaylistDraftDialog.edit(activity,store,library,playlist,()->{});dialog=(AlertDialog)draft.show();
        layoutDialog();assertTrue("Initial selected rows must be bound before input",dialog.getListView().isItemChecked(0));
        ListView list=dialog.getListView();list.performItemClick(null,0,list.getAdapter().getItemId(0));list.performItemClick(null,2,list.getAdapter().getItemId(2));
        Bundle state=draft.snapshot();assertEquals(Collections.singletonList(LibraryStore.identity(library.get(2))),state.getStringArrayList("selected_ids"));
        recreate();PlaylistDraftDialog restored=PlaylistDraftDialog.restore(activity,store,library,state,()->{});assertNotNull(restored);dialog=(AlertDialog)restored.show();
        layoutDialog();
        assertFalse(dialog.getListView().isItemChecked(0));assertTrue(dialog.getListView().isItemChecked(2));
        dialog.cancel();idle();assertEquals("a",store.playlists().get(0).tracks.get(0).id);
        restored=PlaylistDraftDialog.restore(activity,store,library,state,()->{});dialog=(AlertDialog)restored.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();idle();
        assertEquals(1,store.playlists().get(0).tracks.size());assertEquals("c",store.playlists().get(0).tracks.get(0).id);
    }

    @Test public void unresolvedSelectionCannotBeSilentlyDroppedAndMissingPlaylistIsNotRecreated(){
        PlaylistDraftDialog draft=PlaylistDraftDialog.create(activity,store,library,Collections.singletonList(track("missing")),()->{});
        dialog=(AlertDialog)draft.show();input(dialog).setText("保留草稿");Bundle state=draft.snapshot();recreate();
        PlaylistDraftDialog restored=PlaylistDraftDialog.restore(activity,store,library,state,()->{});assertNotNull(restored);dialog=(AlertDialog)restored.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();assertTrue(dialog.isShowing());assertTrue(store.playlists().isEmpty());
        assertTrue(input(dialog).getError().toString().contains("尚未载入"));assertEquals(state.getStringArrayList("selected_ids"),restored.snapshot().getStringArrayList("selected_ids"));
        dialog.dismiss();idle();
        LibraryStore.Playlist playlist=store.create("待删除",library);state=PlaylistDraftDialog.edit(activity,store,library,playlist,()->{}).snapshot();
        store.delete(playlist.id);assertNull(PlaylistDraftDialog.restore(activity,store,library,state,()->fail("Deleted playlist cannot complete")));
        state.putString("mode","invalid");assertNull(PlaylistDraftDialog.restore(activity,store,library,state,()->{}));
    }

    private static Track track(String id){return new Track(id,"歌曲"+id,"音乐人","专辑","测试","asset:///demo/night-1.wav","",18000,1);}
    private static EditText input(AlertDialog d){return findInput(d.getWindow().getDecorView());}
    private void layoutDialog(){View decor=dialog.getWindow().getDecorView();decor.measure(View.MeasureSpec.makeMeasureSpec(480,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(640,View.MeasureSpec.EXACTLY));decor.layout(0,0,480,640);idle();}
    private static EditText findInput(View view){if(view instanceof EditText)return (EditText)view;if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){EditText found=findInput(group.getChildAt(i));if(found!=null)return found;}}return null;}
    private static void idle(){Shadows.shadowOf(Looper.getMainLooper()).idle();}
}
