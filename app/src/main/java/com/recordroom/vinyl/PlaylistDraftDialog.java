package com.recordroom.vinyl;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;

/** A playlist edit is a draft until an explicit valid Save/Create action. */
final class PlaylistDraftDialog {
    public static final int REQUEST_COVER=104;
    private static final String CREATE="create", RENAME="rename", EDIT="edit";
    private final Activity activity;
    private final LibraryStore store;
    private final List<Track> library;
    private final Runnable completion;
    private final String mode, playlistId;
    private final LinkedHashSet<String> selected=new LinkedHashSet<>();
    private final LinkedHashMap<String,Track> initialTracks=new LinkedHashMap<>();
    private String draftName,draftDescription="",coverUri="";
    private AlertDialog dialog;
    private EditText nameInput,descriptionInput;
    private CoverView coverPreview;
    private TextView coverPlaceholder;
    private Button clearCover;
    private boolean committed,pendingCover;

    private PlaylistDraftDialog(Activity activity,LibraryStore store,List<Track> library,
                                String mode,String playlistId,String name,List<Track> initial,Runnable completion){
        this.activity=activity;this.store=store;this.library=library;this.mode=mode;
        this.playlistId=playlistId==null?"":playlistId;draftName=name==null?"":name;
        this.completion=completion;
        if(initial!=null)for(Track track:initial)if(track!=null){String key=LibraryStore.identity(track);selected.add(key);initialTracks.put(key,track);}
    }

    static PlaylistDraftDialog create(Activity activity,LibraryStore store,List<Track> library,List<Track> initial,Runnable completion){
        return new PlaylistDraftDialog(activity,store,library,CREATE,"","",initial,completion);
    }
    static PlaylistDraftDialog rename(Activity activity,LibraryStore store,List<Track> library,LibraryStore.Playlist playlist,Runnable completion){
        return existing(activity,store,library,playlist,RENAME,completion);
    }
    static PlaylistDraftDialog edit(Activity activity,LibraryStore store,List<Track> library,LibraryStore.Playlist playlist,Runnable completion){
        return existing(activity,store,library,playlist,EDIT,completion);
    }
    private static PlaylistDraftDialog existing(Activity activity,LibraryStore store,List<Track> library,LibraryStore.Playlist playlist,String mode,Runnable completion){
        PlaylistDraftDialog result=new PlaylistDraftDialog(activity,store,library,mode,playlist.id,playlist.name,playlist.tracks,completion);result.draftDescription=playlist.description;result.coverUri=playlist.coverUri;return result;
    }

    /** Call after the library has loaded. Missing selected IDs remain in the draft. */
    static PlaylistDraftDialog restore(Activity activity,LibraryStore store,List<Track> library,Bundle state,Runnable completion){
        if(state==null)return null;
        String mode=state.getString("mode",""),id=state.getString("playlist_id","");
        if(!CREATE.equals(mode)&&!RENAME.equals(mode)&&!EDIT.equals(mode))return null;
        if(!CREATE.equals(mode)){
            boolean exists=false;for(LibraryStore.Playlist p:store.playlists())if(p.id.equals(id))exists=true;
            if(!exists)return null;
        }
        PlaylistDraftDialog result=new PlaylistDraftDialog(activity,store,library,mode,id,state.getString("name",""),null,completion);
        LibraryStore.Playlist current=result.findPlaylist();
        result.draftDescription=state.getString("description",current==null?"":current.description);result.coverUri=state.getString("cover_uri",current==null?"":current.coverUri);result.pendingCover=state.getBoolean("pending_cover");
        ArrayList<String> identities=state.getStringArrayList("selected_ids");
        if(identities!=null)for(String identity:identities)if(identity!=null&&!identity.isEmpty())result.selected.add(identity);
        return result;
    }

    Bundle snapshot(){
        Bundle state=new Bundle();state.putString("mode",mode);state.putString("playlist_id",playlistId);
        state.putString("name",nameInput==null?draftName:nameInput.getText().toString());
        state.putString("description",descriptionInput==null?draftDescription:descriptionInput.getText().toString());state.putString("cover_uri",coverUri);state.putBoolean("pending_cover",pendingCover);
        state.putStringArrayList("selected_ids",new ArrayList<>(selected));return state;
    }

    /** Root may register its own OnDismissListener on the returned Dialog. */
    Dialog show(){
        if(dialog!=null){if(!dialog.isShowing()&&!committed)dialog.show();return dialog;}
        AlertDialog.Builder builder=new AlertDialog.Builder(activity).setNegativeButton("取消",(d,which)->pendingCover=false);
        if(!RENAME.equals(mode)){
            LinkedHashMap<String,Track> choices=new LinkedHashMap<>(initialTracks);
            LibraryStore.Playlist current=findPlaylist();
            if(current!=null)for(Track track:current.tracks)choices.put(LibraryStore.identity(track),track);
            if(library!=null)for(Track track:library)if(track!=null)choices.put(LibraryStore.identity(track),track);
            ArrayList<String> identities=new ArrayList<>(choices.keySet());
            String[] labels=new String[identities.size()];boolean[] checked=new boolean[identities.size()];
            for(int i=0;i<identities.size();i++){Track t=choices.get(identities.get(i));labels[i]=t.title+" · "+t.artist;checked[i]=selected.contains(identities.get(i));}
            builder.setMultiChoiceItems(labels,checked,(d,which,value)->{String identity=identities.get(which);if(value)selected.add(identity);else selected.remove(identity);});
        }
        nameInput=input("歌单名称",true);nameInput.setText(draftName);nameInput.setSelection(nameInput.length());
        if(RENAME.equals(mode)){builder.setTitle("重命名歌单");builder.setView(nameInput);}
        else builder.setCustomTitle(editor());
        builder.setPositiveButton(CREATE.equals(mode)?"创建":"保存",null);
        dialog=builder.create();dialog.show();dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->commit());
        if(dialog.getListView()!=null){
            android.widget.ListView choices=dialog.getListView();
            choices.setOnHierarchyChangeListener(new android.view.ViewGroup.OnHierarchyChangeListener(){
                @Override public void onChildViewAdded(View parent,View child){tintChoice(child);}
                @Override public void onChildViewRemoved(View parent,View child){}
            });
            for(int i=0;i<choices.getChildCount();i++)tintChoice(choices.getChildAt(i));
        }
        if(dialog.getWindow()!=null){dialog.getWindow().setBackgroundDrawable(DesignSystem.paper(activity));if(!RENAME.equals(mode))dialog.getWindow().setLayout(-1,-1);}
        for(int which:new int[]{AlertDialog.BUTTON_POSITIVE,AlertDialog.BUTTON_NEGATIVE}){
            android.widget.Button button=dialog.getButton(which);button.setMinHeight(dp(48));button.setMinimumWidth(dp(64));button.setTextColor(DesignSystem.ACCENT);button.setAllCaps(false);
        }
        return dialog;
    }

    private EditText input(String label,boolean singleLine){
        EditText value=new EditText(activity);value.setSingleLine(singleLine);value.setHint(label);value.setContentDescription(label);value.setTextColor(DesignSystem.INK);value.setHintTextColor(DesignSystem.MUTED);value.setTextSize(16);value.setMinHeight(dp(56));value.setPadding(dp(16),dp(12),dp(16),dp(12));value.setBackgroundTintList(android.content.res.ColorStateList.valueOf(DesignSystem.ACCENT));if(android.os.Build.VERSION.SDK_INT>=29&&value.getTextCursorDrawable()!=null)value.getTextCursorDrawable().setTint(DesignSystem.ACCENT);return value;
    }
    private void tintChoice(View view){
        android.content.res.ColorStateList tint=android.content.res.ColorStateList.valueOf(DesignSystem.ACCENT);
        if(view instanceof android.widget.CheckedTextView){android.widget.CheckedTextView choice=(android.widget.CheckedTextView)view;choice.setTextColor(DesignSystem.INK);choice.setCheckMarkTintList(tint);choice.setCompoundDrawableTintList(tint);}
        if(view instanceof android.widget.CompoundButton)((android.widget.CompoundButton)view).setButtonTintList(tint);
        if(view instanceof android.view.ViewGroup){android.view.ViewGroup group=(android.view.ViewGroup)view;for(int i=0;i<group.getChildCount();i++)tintChoice(group.getChildAt(i));}
    }
    private View editor(){
        ScrollView scroll=new ScrollView(activity){@Override protected void onMeasure(int widthSpec,int heightSpec){int available=Math.max(160,Math.round(getResources().getConfiguration().screenHeightDp*.52f));super.onMeasure(widthSpec,View.MeasureSpec.makeMeasureSpec(dp(available),View.MeasureSpec.AT_MOST));}};
        LinearLayout form=new LinearLayout(activity);form.setOrientation(LinearLayout.VERTICAL);form.setPadding(dp(16),dp(12),dp(16),dp(8));scroll.addView(form);
        TextView heading=new TextView(activity);heading.setText(CREATE.equals(mode)?"新建歌单":"编辑歌单");heading.setTextSize(24);heading.setTextColor(DesignSystem.INK);heading.setGravity(Gravity.CENTER);heading.setPadding(0,0,0,dp(16));form.addView(heading);
        FrameLayout artwork=new FrameLayout(activity);artwork.setBackground(DesignSystem.rounded(activity,DesignSystem.LILAC,8));artwork.setContentDescription("更换歌单封面");artwork.setOnClickListener(v->pickCover());artwork.setFocusable(true);
        coverPreview=new CoverView(activity);coverPreview.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);artwork.addView(coverPreview,new FrameLayout.LayoutParams(-1,-1));
        coverPlaceholder=new TextView(activity);coverPlaceholder.setText("＋\n更换封面");coverPlaceholder.setGravity(Gravity.CENTER);coverPlaceholder.setTextColor(DesignSystem.ACCENT);coverPlaceholder.setTextSize(16);coverPlaceholder.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);artwork.addView(coverPlaceholder,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout.LayoutParams art=new LinearLayout.LayoutParams(dp(112),dp(112));art.gravity=Gravity.CENTER_HORIZONTAL;form.addView(artwork,art);
        clearCover=new Button(activity);clearCover.setText("清除封面");clearCover.setAllCaps(false);clearCover.setTextColor(DesignSystem.ACCENT);clearCover.setBackgroundColor(android.graphics.Color.TRANSPARENT);clearCover.setOnClickListener(v->{coverUri="";updateCover();});LinearLayout.LayoutParams clear=new LinearLayout.LayoutParams(-1,dp(48));form.addView(clearCover,clear);
        form.addView(nameInput,new LinearLayout.LayoutParams(-1,-2));
        descriptionInput=input("写一点介绍（最多2000字）",false);descriptionInput.setContentDescription("歌单简介");descriptionInput.setMaxLines(4);descriptionInput.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(2000)});descriptionInput.setText(draftDescription);form.addView(descriptionInput,new LinearLayout.LayoutParams(-1,-2));
        TextView songs=new TextView(activity);songs.setText("添加歌曲 · 勾选后保存");songs.setTextColor(DesignSystem.MUTED);songs.setTextSize(14);songs.setPadding(dp(16),dp(16),dp(16),dp(8));form.addView(songs);updateCover();return scroll;
    }
    private void updateCover(){if(coverPreview==null)return;if(coverUri.isEmpty())coverPreview.clear();else coverPreview.setSource(coverUri,320);coverPlaceholder.setVisibility(coverUri.isEmpty()?View.VISIBLE:View.GONE);clearCover.setEnabled(!coverUri.isEmpty());}
    private void pickCover(){
        if(committed||pendingCover)return;
        Intent picker=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        pendingCover=true;try{activity.startActivityForResult(picker,REQUEST_COVER);}catch(RuntimeException failure){pendingCover=false;showError("无法打开图片选择器，请重试");}
    }
    boolean hasPendingCover(){return pendingCover&&!committed;}
    boolean onActivityResult(int request,int result,Intent data){
        if(request!=REQUEST_COVER)return false;
        if(!pendingCover||committed)return true;pendingCover=false;
        if(result!=Activity.RESULT_OK||data==null||data.getData()==null)return true;
        Uri uri=data.getData();
        try{
            String type=activity.getContentResolver().getType(uri);
            if(!"content".equalsIgnoreCase(uri.getScheme())||type==null||!type.startsWith("image/")){showError("请选择可读取的图片文件");return true;}
            activity.getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);
            coverUri=uri.toString();updateCover();
        }catch(RuntimeException failure){showError("无法保留这张图片的读取权限，请重新选择");}
        return true;
    }

    private void commit(){
        if(committed)return;
        if(!CREATE.equals(mode)&&findPlaylist()==null){showError("这个歌单已不存在，草稿未保存");return;}
        {
            draftName=nameInput.getText().toString();
            if(draftName.trim().isEmpty()){nameInput.setError("给歌单起个名字");return;}
            if(draftName.trim().length()>80){nameInput.setError("歌单名称最多 80 个字符");return;}
        }
        ArrayList<Track> tracks=new ArrayList<>();
        if(!RENAME.equals(mode)){
            LinkedHashMap<String,Track> known=new LinkedHashMap<>(initialTracks);
            for(LibraryStore.Playlist p:store.playlists())for(Track t:p.tracks)known.put(LibraryStore.identity(t),t);
            if(library!=null)for(Track t:library)if(t!=null)known.put(LibraryStore.identity(t),t);
            int missing=0;for(String id:selected){Track track=known.get(id);if(track==null)missing++;else tracks.add(track);}
            if(missing>0){showError("有 "+missing+" 首歌曲尚未载入，草稿已保留，请稍后重试");return;}
        }
        if(descriptionInput!=null)draftDescription=descriptionInput.getText().toString();
        if(CREATE.equals(mode))store.create(draftName,draftDescription,coverUri,tracks);
        else if(RENAME.equals(mode))store.rename(playlistId,draftName);
        else store.update(playlistId,draftName,draftDescription,coverUri,tracks);
        committed=true;dialog.dismiss();if(completion!=null)completion.run();
    }

    private LibraryStore.Playlist findPlaylist(){for(LibraryStore.Playlist p:store.playlists())if(p.id.equals(playlistId))return p;return null;}
    private void showError(String message){if(nameInput!=null)nameInput.setError(message);Toast.makeText(activity,message,Toast.LENGTH_LONG).show();}
    private int dp(int value){return Math.round(value*activity.getResources().getDisplayMetrics().density);}
}
