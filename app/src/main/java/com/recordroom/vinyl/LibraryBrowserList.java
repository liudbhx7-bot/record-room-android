package com.recordroom.vinyl;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import java.util.ArrayList;

/** One recycled native viewport for album grids and the full local search result set. */
public final class LibraryBrowserList extends ListView {
    public interface Actions {
        void album(ArrayList<Track> songs,View origin);
        void albumMenu(ArrayList<Track> songs);
        void play(ArrayList<Track> songs,int index);
        void songMenu(Track song);
        void artist(Track seed);
    }
    private static final int LABEL=0,ALBUMS=1,SONG=2,ARTIST=3,EMPTY=4;
    private final ThemeStore themes;
    private final ArrayList<ArrayList<Track>> albums=new ArrayList<>();
    private final ArrayList<Track> songs=new ArrayList<>(),artists=new ArrayList<>();
    private final ArrayList<Entry> entries=new ArrayList<>();
    private final Rows adapter=new Rows();
    private Actions actions;
    private int ink=DesignSystem.INK,columns=3,viewportWidth;
    private boolean searching;
    private long generation;

    public LibraryBrowserList(Context context,ThemeStore themes){
        super(context);this.themes=themes;
        setDivider(null);setDividerHeight(0);setSelector(new ColorDrawable(Color.TRANSPARENT));
        setClipToPadding(false);setPadding(dp(3.5f),dp(2),dp(3.5f),dp(104));
        setVerticalScrollBarEnabled(false);setBackgroundColor(Color.TRANSPARENT);
        setContentDescription("音乐资料库");columns=columnCount();setAdapter(adapter);
    }
    public void setActions(Actions value){actions=value;}
    public void setInkColor(int value){ink=value;adapter.notifyDataSetChanged();}

    public void submit(ArrayList<ArrayList<Track>> albumValues,ArrayList<Track> songValues,ArrayList<Track> artistValues,boolean searching){
        generation++;this.searching=searching;
        setPadding(dp(searching?16:3.5f),dp(searching?8:2),dp(searching?16:3.5f),dp(104));
        albums.clear();songs.clear();artists.clear();
        if(albumValues!=null)for(ArrayList<Track> group:albumValues){
            if(group==null)continue;ArrayList<Track> valid=new ArrayList<>();for(Track t:group)if(t!=null)valid.add(t);
            if(!valid.isEmpty())albums.add(valid);
        }
        if(songValues!=null)for(Track t:songValues)if(t!=null)songs.add(t);
        if(artistValues!=null)for(Track t:artistValues)if(t!=null)artists.add(t);
        rebuildEntries();setSelection(0);
    }

    private int columnCount(){int pixels=viewportWidth>0?viewportWidth:getWidth();int width=pixels>0?Math.round(pixels/getResources().getDisplayMetrics().density):getResources().getConfiguration().screenWidthDp;return width>=600?4:width<300?2:3;}
    @Override protected void onMeasure(int widthSpec,int heightSpec){
        int width=MeasureSpec.getSize(widthSpec);
        if(width>0&&viewportWidth!=width){
            viewportWidth=width;
            if(adapter!=null){int next=columnCount();if(columns!=next){columns=next;generation++;rebuildEntries();}else adapter.notifyDataSetChanged();}
        }
        super.onMeasure(widthSpec,heightSpec);
    }
    @Override protected void onSizeChanged(int w,int h,int oldw,int oldh){
        super.onSizeChanged(w,h,oldw,oldh);
        if(adapter==null)return;
        if(columns!=columnCount()){columns=columnCount();generation++;rebuildEntries();}
        else if(w!=oldw)adapter.notifyDataSetChanged();
    }
    private void rebuildEntries(){
        entries.clear();
        if(!albums.isEmpty()){
            if(searching)entries.add(new Entry(LABEL,-1,"专辑 · "+albums.size()+" 张"));
            for(int i=0;i<albums.size();i+=columns)entries.add(new Entry(ALBUMS,i,null));
        }
        if(searching&&!songs.isEmpty()){
            entries.add(new Entry(LABEL,-1,"歌曲 · "+songs.size()+" 首"));
            for(int i=0;i<songs.size();i++)entries.add(new Entry(SONG,i,null));
        }
        if(searching&&!artists.isEmpty()){
            entries.add(new Entry(LABEL,-1,"音乐人"));
            for(int i=0;i<artists.size();i++)entries.add(new Entry(ARTIST,i,null));
        }
        if(entries.isEmpty())entries.add(new Entry(EMPTY,-1,searching?"没有找到匹配的音乐\n试试专辑名称、歌曲名称或音乐人。":"还没有收藏的专辑\n导入音乐后，专辑会出现在这里。"));
        adapter.notifyDataSetChanged();
    }

    private final class Rows extends BaseAdapter {
        @Override public int getCount(){return entries.size();}
        @Override public Object getItem(int position){return entries.get(position);}
        @Override public long getItemId(int position){return position;}
        @Override public int getViewTypeCount(){return 5;}
        @Override public int getItemViewType(int position){return entries.get(position).type;}
        @Override public boolean areAllItemsEnabled(){return false;}
        @Override public boolean isEnabled(int position){int type=entries.get(position).type;return type!=LABEL&&type!=EMPTY;}
        @Override public View getView(int position,View recycled,ViewGroup parent){
            Entry entry=entries.get(position);
            if(entry.type==EMPTY){EmptyRow row=recycled instanceof EmptyRow?(EmptyRow)recycled:new EmptyRow();row.label.setText(entry.label);row.label.setTextColor(ink);return row;}
            if(entry.type==LABEL){
                TextView label=recycled instanceof TextView?(TextView)recycled:text(18);
                label.setText(entry.label);label.setTextColor(ink);label.setTypeface(null,Typeface.BOLD);
                label.setPadding(0,dp(12),0,dp(14));label.setLayoutParams(new AbsListView.LayoutParams(-1,-2));return label;
            }
            if(entry.type==ALBUMS){
                AlbumRow row=recycled instanceof AlbumRow?(AlbumRow)recycled:new AlbumRow();row.bind(entry.index);return row;
            }
            if(entry.type==SONG){
                SongRow row=recycled instanceof SongRow?(SongRow)recycled:new SongRow();row.bind(entry.index);return row;
            }
            ArtistRow row=recycled instanceof ArtistRow?(ArtistRow)recycled:new ArtistRow();row.bind(entry.index);return row;
        }
    }

    private final class AlbumRow extends LinearLayout {
        final ArrayList<AlbumCell> cells=new ArrayList<>();
        AlbumRow(){super(LibraryBrowserList.this.getContext());setOrientation(HORIZONTAL);setGravity(Gravity.TOP);setPadding(0,0,0,dp(3.5f));setLayoutParams(new AbsListView.LayoutParams(-1,-2));}
        void bind(int first){
            if(cells.size()!=columns){removeAllViews();cells.clear();for(int i=0;i<columns;i++){AlbumCell cell=new AlbumCell();LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1);if(i>0)lp.leftMargin=dp(searching?10:3.5f);addView(cell,lp);cells.add(cell);}}
            int width=viewportWidth>0?viewportWidth:getResources().getDisplayMetrics().widthPixels;
            int gap=dp(searching?10:3.5f);
            int edge=Math.max(dp(48),(width-LibraryBrowserList.this.getPaddingLeft()-LibraryBrowserList.this.getPaddingRight()-gap*(columns-1))/columns);
            setPadding(0,0,0,dp(searching?18:3.5f));
            for(int i=0;i<cells.size();i++){LinearLayout.LayoutParams lp=(LinearLayout.LayoutParams)cells.get(i).getLayoutParams();lp.leftMargin=i==0?0:gap;cells.get(i).setLayoutParams(lp);cells.get(i).bind(first+i,edge);}
        }
    }
    private final class AlbumCell extends LinearLayout {
        final CoverView cover;final TextView name,artist;ArrayList<Track> group;long bound;
        AlbumCell(){super(LibraryBrowserList.this.getContext());setOrientation(VERTICAL);setFocusable(true);
            setForeground(new RippleDrawable(ColorStateList.valueOf(0x38ffffff),null,new ColorDrawable(Color.WHITE)));
            cover=new CoverView(getContext());cover.setBackgroundColor(0xff687d8a);addView(cover,new LinearLayout.LayoutParams(-1,dp(80)));
            name=text(14);name.setMaxLines(2);name.setEllipsize(TextUtils.TruncateAt.END);name.setPadding(0,dp(8),0,0);addView(name);
            artist=text(12);artist.setAlpha(.78f);artist.setSingleLine(true);artist.setEllipsize(TextUtils.TruncateAt.END);addView(artist);
            setOnClickListener(v->{if(bound==generation&&group!=null&&actions!=null)actions.album(new ArrayList<>(group),cover);});
            setOnLongClickListener(v->{if(bound!=generation||group==null||actions==null)return false;actions.albumMenu(new ArrayList<>(group));return true;});
        }
        void bind(int index,int edge){
            bound=generation;cover.getLayoutParams().height=edge;name.setVisibility(searching?VISIBLE:GONE);artist.setVisibility(searching?VISIBLE:GONE);
            if(index>=albums.size()){group=null;cover.clear();setVisibility(INVISIBLE);setContentDescription(null);return;}
            setVisibility(VISIBLE);group=albums.get(index);Track first=group.get(0);cover.setTrack(first,themes,Math.min(640,Math.max(320,edge)));
            name.setText(first.album);name.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));artist.setText(first.albumArtist);name.setTextColor(ink);artist.setTextColor(ink);
            setContentDescription(first.album+"，"+first.albumArtist);
        }
    }
    private final class SongRow extends LinearLayout {
        final CoverView cover;final TextView name,artist;int index;long bound;
        SongRow(){super(LibraryBrowserList.this.getContext());setGravity(Gravity.CENTER_VERTICAL);setPadding(dp(3),dp(8),dp(12),dp(8));setFocusable(true);setLayoutParams(new AbsListView.LayoutParams(-1,-2));
            cover=new CoverView(getContext());addView(cover,new LinearLayout.LayoutParams(dp(46),dp(46)));
            LinearLayout labels=new LinearLayout(getContext());labels.setOrientation(VERTICAL);labels.setPadding(dp(12),0,0,0);
            name=text(15);name.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));name.setMaxLines(2);name.setEllipsize(TextUtils.TruncateAt.END);labels.addView(name);
            artist=text(13);artist.setAlpha(.78f);artist.setSingleLine(true);artist.setEllipsize(TextUtils.TruncateAt.END);labels.addView(artist);addView(labels,new LinearLayout.LayoutParams(0,-2,1));
            setOnClickListener(v->{if(bound==generation&&actions!=null&&index<songs.size())actions.play(new ArrayList<>(songs),index);});
            setOnLongClickListener(v->{if(bound!=generation||actions==null||index>=songs.size())return false;actions.songMenu(songs.get(index));return true;});
        }
        void bind(int index){this.index=index;bound=generation;Track t=songs.get(index);name.setText(t.title);artist.setText(t.artist);name.setTextColor(ink);artist.setTextColor(ink);cover.setTrack(t,themes,120);setContentDescription(t.title+"，"+t.artist);}
    }
    private final class ArtistRow extends TextView {
        int index;long bound;
        ArtistRow(){super(LibraryBrowserList.this.getContext());setTextSize(14);setGravity(Gravity.START|Gravity.CENTER_VERTICAL);setMinHeight(dp(52));setPadding(dp(8),dp(10),dp(8),dp(10));setFocusable(true);setLayoutParams(new AbsListView.LayoutParams(-1,-2));setOnClickListener(v->{if(bound==generation&&actions!=null&&index<artists.size())actions.artist(artists.get(index));});}
        void bind(int index){this.index=index;bound=generation;Track seed=artists.get(index);setText(seed.artist);setTextColor(ink);setContentDescription("查看音乐人 "+seed.artist);}
    }
    private final class EmptyRow extends LinearLayout {
        final TextView label;
        EmptyRow(){super(LibraryBrowserList.this.getContext());setOrientation(VERTICAL);setGravity(Gravity.CENTER);setPadding(dp(16),dp(24),dp(16),dp(24));
            PlayerControls.Icon illustration=new PlayerControls.Icon(getContext(),"albums");illustration.setInk(ink);illustration.setClickable(false);illustration.setFocusable(false);illustration.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            addView(illustration,new LinearLayout.LayoutParams(dp(64),dp(72)));label=text(16);label.setGravity(Gravity.CENTER);label.setLineSpacing(dp(6),1);addView(label,new LinearLayout.LayoutParams(-1,-2));}
    }
    private static final class Entry {final int type,index;final String label;Entry(int type,int index,String label){this.type=type;this.index=index;this.label=label;}}
    private TextView text(int size){TextView t=new TextView(getContext());t.setTextSize(size);return t;}
    private int dp(float value){return Math.round(value*getResources().getDisplayMetrics().density);}
}
