package com.recordroom.vinyl;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.inputmethod.EditorInfo;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.HorizontalScrollView;
import android.widget.FrameLayout;
import android.widget.ListView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import android.content.SharedPreferences;
import android.view.inputmethod.InputMethodManager;
import org.json.JSONArray;

/** A single native search surface. Provider identities are retained only inside playback alternatives. */
public final class AggregateSearchPage {
    public interface Actions {
        void play(List<AggregateSearch.Result> results,int index);
        void manageAccounts();
        default List<Track> libraryTracks() { return Collections.emptyList(); }
        default List<Track> recentTracks() { return Collections.emptyList(); }
        default void catalogAlbum(AggregateSearch.AlbumResult value) {}
        default void album(Track track) {}
        default void libraryArtist(Track track) {}
        default void libraryPlay(List<Track> tracks,int index) {}
        default void artist(AggregateSearch.ArtistResult result) {}
    }
    private final Activity activity;
    private final AggregateSearch engine;
    private final Actions actions;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Rows adapter=new Rows();
    private final ArrayList<AggregateSearch.Result> results=new ArrayList<>();
    private final ArrayList<AggregateSearch.ArtistResult> artists=new ArrayList<>();
    private final ArrayList<ArtistPortrait> artistPortraits=new ArrayList<>();
    private HorizontalScrollView artistScroll;private LinearLayout artistCards,artistHeader;private TextView songHeading,artistStatus;private Button artistMore,artistRetry;private String artistSignature="";
    private static final String[] FILTERS={"综合","歌曲","音乐人","专辑"};
    private final ArrayList<SearchSleeves.Jacket> albums=new ArrayList<>();
    private final ArrayList<Track> localRows=new ArrayList<>();
    private final ArrayList<String> history=new ArrayList<>();
    private final SharedPreferences historyStore;
    private final SearchSleeves sleeves;
    private final ThemeStore themes;
    private LinearLayout filterBar, header, display, home, footer;
    private Button clear;
    private int tab,restoreAlbumTarget,restoreAlbumRequests;
    private String browse="";
    private Dialog dialog;private EditText input;private ListView list;private TextView status;private Button more,retry;
    private Runnable onClosed;private boolean closed;private String query="";
    private int restoreTarget,restoreRequests,restoreArtistTarget,restoreArtistRequests,restoreArtistScroll;private Bundle viewport;
    private final Runnable debounce=this::submitQuery;
    public AggregateSearchPage(Activity activity,AggregateSearch engine,Actions actions){this.activity=activity;this.engine=engine;this.actions=actions;
        themes=new ThemeStore(activity);sleeves=new SearchSleeves(activity);
        historyStore=activity.getSharedPreferences("vinyl_search",Activity.MODE_PRIVATE);
        try {
            JSONArray stored=new JSONArray(historyStore.getString("recent_queries","[]"));
            for(int i=0;i<Math.min(8,stored.length());i++){
                String term=stored.optString(i,"").trim();
                if(!term.isEmpty())history.add(term);
            }
        } catch(org.json.JSONException ignored) {}
    }
    public static AggregateSearchPage restore(Activity activity,AggregateSearch engine,Actions actions,Bundle saved){
        AggregateSearchPage page=new AggregateSearchPage(activity,engine,actions);
        if(saved!=null){page.restoreAlbumTarget=Math.min(1000,Math.max(0,saved.getInt("album_loaded_count")));page.tab=Math.max(0,Math.min(3,saved.getInt("tab")));page.browse=saved.getString("browse","");page.query=saved.getString("query","");page.restoreTarget=Math.min(1000,Math.max(0,saved.getInt("loaded_count")));page.viewport=saved.getBundle("viewport");page.restoreArtistTarget=Math.min(1000,Math.max(0,saved.getInt("artist_loaded_count")));page.restoreArtistScroll=Math.max(0,saved.getInt("artist_scroll"));}
        return page;
    }
    public Bundle snapshot(){Bundle saved=new Bundle();saved.putString("query",query);saved.putInt("tab",tab);saved.putInt("album_loaded_count",Math.max(restoreAlbumTarget,engine.state().albums.size()));saved.putString("browse",browse);saved.putInt("loaded_count",Math.max(restoreTarget,results.size()));saved.putBundle("viewport",list==null?viewport:ViewScrollState.capture(list));saved.putInt("artist_loaded_count",Math.max(restoreArtistTarget,artists.size()));saved.putInt("artist_scroll",artistScroll==null?restoreArtistScroll:artistScroll.getScrollX());return saved;}
    public void setOnClosed(Runnable callback){onClosed=callback;}
    public Dialog show(){
        if(closed)throw new IllegalStateException("Search page is closed");if(dialog!=null&&dialog.isShowing())return dialog;
        dialog=new Dialog(activity,android.R.style.Theme_Material_NoActionBar);
        LinearLayout page=column();page.setFocusableInTouchMode(true);page.setBackground(DesignSystem.ambient(activity));
        page.setOnApplyWindowInsetsListener((v,insets)->{v.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());return insets;});
        LinearLayout top=new LinearLayout(activity);top.setGravity(Gravity.CENTER_VERTICAL);top.setPadding(dp(12),dp(8),dp(18),dp(8));
        Button back=button("‹",this::back);back.setTextSize(28);back.setContentDescription("关闭搜索");top.addView(back,new LinearLayout.LayoutParams(dp(48),dp(48)));
        TextView heading=text("搜索音乐",23);heading.setPadding(dp(10),0,0,0);heading.setTypeface(Typeface.create("serif",Typeface.BOLD));top.addView(heading,new LinearLayout.LayoutParams(0,-2,1));
        top.addView(button("账户",actions::manageAccounts),new LinearLayout.LayoutParams(dp(62),dp(48)));page.addView(top);
        LinearLayout field=new LinearLayout(activity);field.setGravity(Gravity.CENTER_VERTICAL);
        field.setBackground(DesignSystem.settingsCard(activity,14));
        LinearLayout.LayoutParams searchParams=new LinearLayout.LayoutParams(-1,dp(50));searchParams.setMargins(dp(18),dp(5),dp(18),dp(7));page.addView(field,searchParams);
        PlayerControls.Icon lens=new PlayerControls.Icon(activity,"search");lens.setInk(DesignSystem.onAmbientMuted);
        lens.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);lens.setFocusable(false);
        lens.setOnClickListener(v->{input.requestFocus();((InputMethodManager)activity.getSystemService(Activity.INPUT_METHOD_SERVICE)).showSoftInput(input,InputMethodManager.SHOW_IMPLICIT);});
        field.addView(lens,new LinearLayout.LayoutParams(dp(46),dp(48)));
        input=new EditText(activity);input.setSingleLine(true);input.setTextSize(16);input.setTextColor(DesignSystem.onAmbient);input.setHintTextColor(DesignSystem.onAmbientMuted);
        input.setHint("歌曲、音乐人或专辑");input.setContentDescription("搜索歌曲、音乐人或专辑");input.setImeOptions(EditorInfo.IME_ACTION_SEARCH);input.setPadding(0,0,0,0);input.setBackgroundColor(Color.TRANSPARENT);
        field.addView(input,new LinearLayout.LayoutParams(0,-1,1));input.setText(query);
        clear=button("×",()->{input.setText("");input.requestFocus();});clear.setTextSize(22);clear.setBackgroundColor(Color.TRANSPARENT);clear.setContentDescription("清空输入");field.addView(clear,new LinearLayout.LayoutParams(dp(48),-1));
        filterBar=new LinearLayout(activity);filterBar.setPadding(dp(18),0,dp(18),dp(3));page.addView(filterBar);
        for(int i=0;i<FILTERS.length;i++){
            final int target=i;
            Button option=button(FILTERS[i],()->{tab=target;viewport=null;render(engine.state());list.setSelection(0);});
            filterBar.addView(option,new LinearLayout.LayoutParams(0,dp(44),1));
        }
        header=column();home=column();header.addView(home);
        status=text("",12);status.setTextColor(DesignSystem.onAmbientMuted);status.setPadding(0,dp(6),0,dp(8));header.addView(status);
        artistHeader=new LinearLayout(activity);artistHeader.setGravity(Gravity.CENTER_VERTICAL);artistHeader.setPadding(0,0,0,dp(2));artistStatus=text("音乐人",13);artistStatus.setTextColor(DesignSystem.onAmbientMuted);artistHeader.addView(artistStatus,new LinearLayout.LayoutParams(0,-2,1));
        artistMore=button("更多音乐人",engine::loadMoreArtists);artistMore.setTextSize(12);artistHeader.addView(artistMore,new LinearLayout.LayoutParams(-2,dp(38)));artistRetry=button("重试音乐人",engine::retryArtists);artistRetry.setTextSize(12);artistHeader.addView(artistRetry,new LinearLayout.LayoutParams(-2,dp(38)));header.addView(artistHeader);artistHeader.setVisibility(View.GONE);
        artistScroll=new HorizontalScrollView(activity);artistScroll.setHorizontalScrollBarEnabled(false);artistScroll.setClipToPadding(false);artistScroll.setPadding(0,0,0,dp(3));
        artistCards=new LinearLayout(activity);artistScroll.addView(artistCards);artistScroll.getViewTreeObserver().addOnScrollChangedListener(this::loadVisibleArtistPortraits);artistScroll.addOnLayoutChangeListener((v,l,t,r,b,oldL,oldT,oldR,oldB)->loadVisibleArtistPortraits());header.addView(artistScroll,new LinearLayout.LayoutParams(-1,dp(72)));artistScroll.setVisibility(View.GONE);
        display=column();header.addView(display);
        songHeading=text("歌曲",17);songHeading.setTextColor(DesignSystem.onAmbientMuted);songHeading.setPadding(0,dp(8),0,dp(4));header.addView(songHeading);songHeading.setVisibility(View.GONE);
        list=new ListView(activity);list.setContentDescription("音乐搜索");list.setDivider(null);list.setVerticalScrollBarEnabled(false);list.setSelector(new android.graphics.drawable.ColorDrawable(0x14736578));
        list.setPadding(dp(18),0,dp(18),dp(12));list.setClipToPadding(false);
        list.addHeaderView(header,null,false);
        footer=new LinearLayout(activity);footer.setPadding(0,dp(6),0,dp(10));more=button("加载更多",()->{if(tab==3)engine.loadMoreAlbums();else engine.loadMore();});retry=button("重试未完成的搜索",()->{if(tab==3)engine.retryAlbums();else engine.retryFailed();});
        footer.addView(more,new LinearLayout.LayoutParams(0,dp(48),1));footer.addView(retry,new LinearLayout.LayoutParams(0,dp(48),1));list.addFooterView(footer,null,false);
        list.setAdapter(adapter);page.addView(list,new LinearLayout.LayoutParams(-1,0,1));
        input.addTextChangedListener(new TextWatcher(){
            public void beforeTextChanged(CharSequence s,int start,int count,int after){}
            public void afterTextChanged(Editable value){}
            public void onTextChanged(CharSequence value,int start,int before,int count){
                if(closed)return;
                query=value.toString().trim();browse="";tab=0;restoreTarget=restoreRequests=restoreAlbumTarget=restoreAlbumRequests=restoreArtistTarget=restoreArtistRequests=restoreArtistScroll=0;artistSignature="";artistScroll.scrollTo(0,0);
                viewport=null;main.removeCallbacks(debounce);engine.search("");list.setSelection(0);
                if(!query.isEmpty()){status.setText("准备搜索…");main.postDelayed(debounce,350);}
            }
        });
        input.setOnEditorActionListener((v,action,event)->{
            if(action!=EditorInfo.IME_ACTION_SEARCH)return false;
            rememberQuery();hideKeyboard();main.removeCallbacks(debounce);engine.search(query);return true;
        });
        dialog.setOnKeyListener((d,key,event)->{
            if(key==android.view.KeyEvent.KEYCODE_BACK&&!browse.isEmpty()){
                if(event.getAction()==android.view.KeyEvent.ACTION_UP)back();
                return true;
            }
            return false;
        });
        dialog.setContentView(page);dialog.setOnDismissListener(d->close());dialog.show();Window window=dialog.getWindow();if(window!=null){window.setLayout(-1,-1);window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE|android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);refreshTheme();}page.requestApplyInsets();
        engine.setListener(this::render);engine.search(query);page.requestFocus();return dialog;
    }
    public void refreshTheme(){updateTabs();if(dialog==null||dialog.getWindow()==null)return;Window w=dialog.getWindow();w.setStatusBarColor(DesignSystem.ambientColor(activity));w.setNavigationBarColor(DesignSystem.ambientColor(activity));w.getDecorView().setSystemUiVisibility(0);}
    public void refresh(){if(active()){main.removeCallbacks(debounce);engine.search(query);}}
    private void submitQuery(){if(active())engine.search(query);}
    private boolean active(){return !closed&&!activity.isFinishing()&&!activity.isDestroyed();}
    private void render(AggregateSearch.State state){
        if(!active()||list==null)return;
        Bundle held=results.isEmpty()?null:ViewScrollState.capture(list);results.clear();results.addAll(state.results);
        albums.clear();localRows.clear();home.removeAllViews();display.removeAllViews();
        boolean browsing=query.isEmpty();
        filterBar.setVisibility(browsing?View.GONE:View.VISIBLE);clear.setVisibility(input.length()==0?View.GONE:View.VISIBLE);
        updateTabs();
        renderArtists(state);
        status.setVisibility(browsing?View.GONE:View.VISIBLE);
        if(browsing)renderHome();
        else if(results.isEmpty()&&!artists.isEmpty())status.setText(state.loading?"已找到音乐人，歌曲仍在搜索中":state.failedCount>0?"歌曲暂未加载，已找到的音乐人可查看主页":"已找到音乐人，可直接查看主页");
        else if(results.isEmpty())status.setText(state.loading?"正在搜索…":state.failedCount>0?"暂时无法搜索，请重试或检查账户设置。":"没有找到相关歌曲，试试其他关键词。");
        else status.setText("找到 "+results.size()+" 首"+(state.loading?" · 继续搜索中":state.failedCount>0?" · 部分结果暂未加载":""));
        boolean canMore=tab==3?state.albumHasMore:state.hasMore, canRetry=tab==3?state.albumFailed>0:state.failedCount>0;
        more.setVisibility(canMore?View.VISIBLE:View.GONE);more.setEnabled(canMore);
        retry.setVisibility(canRetry?View.VISIBLE:View.GONE);
        if(!browsing){
            collectAlbums(state);
            if(tab==0&&!albums.isEmpty()){
                section(display,"专辑",albums.size()>2||state.albumHasMore?"查看全部":null,()->{tab=3;render(engine.state());list.setSelection(0);});
                display.addView(sleeves.pair(albums,0,this::openJacket));
            }
            songHeading.setVisibility(tab<=1&&!results.isEmpty()?View.VISIBLE:View.GONE);
            if(!albums.isEmpty()&&results.isEmpty()&&artists.isEmpty()&&tab==0)status.setText("找到 "+albums.size()+" 张专辑");
            if(tab==3)status.setText(state.albumLoading?"正在搜索专辑…":albums.isEmpty()&&state.albumFailed==0?"没有找到相关专辑":"专辑 · "+albums.size()+" 张"+(state.albumFailed>0?" · 部分结果暂未加载":""));
            if(tab==2)status.setVisibility(View.GONE);
        }else songHeading.setVisibility(View.GONE);
        footer.setVisibility(!browsing&&tab!=2&&(canMore||canRetry)?View.VISIBLE:View.GONE);
        adapter.notifyDataSetChanged();
        if(held!=null)ViewScrollState.restore(list,held);
        if(state.query.equals(query)&&!state.albumLoading&&restoreAlbumTarget>0){
            if(state.albumHasMore&&state.albums.size()<restoreAlbumTarget&&restoreAlbumRequests++<40)engine.loadMoreAlbums();else restoreAlbumTarget=0;
        }
        if(state.query.equals(query)&&!state.artistLoading&&state.artistHasMore&&artists.size()<restoreArtistTarget&&restoreArtistRequests++<40)engine.loadMoreArtists();
        else if(state.query.equals(query)&&!state.artistLoading&&restoreArtistTarget>0){artistScroll.post(()->{if(active())artistScroll.scrollTo(restoreArtistScroll,0);});restoreArtistTarget=0;}
        if(state.query.equals(query)&&!state.loading&&state.hasMore&&results.size()<restoreTarget&&restoreRequests++<40){engine.loadMore();return;}
        if(state.query.equals(query)&&!state.loading&&viewport!=null){ViewScrollState.restore(list,viewport);viewport=null;restoreTarget=0;}
    }
    private void renderArtists(AggregateSearch.State state){
        List<AggregateSearch.ArtistResult> matches=state.artists;artists.clear();artists.addAll(matches);
        boolean hasSection=!query.isEmpty()&&(tab==0||tab==2)&&(state.artistLoading||state.artistFailedCount>0||state.artistSearchedCount>0||!artists.isEmpty());
        artistHeader.setVisibility(hasSection?View.VISIBLE:View.GONE);artistScroll.setVisibility(hasSection&&!artists.isEmpty()?View.VISIBLE:View.GONE);songHeading.setVisibility(hasSection&&!results.isEmpty()?View.VISIBLE:View.GONE);
        artistStatus.setText(artists.isEmpty()?(state.artistLoading?"正在搜索音乐人…":state.artistFailedCount>0?"音乐人暂未加载":"没有找到相关音乐人"):"音乐人 · "+artists.size()+(state.artistLoading?" · 加载中":state.artistFailedCount>0?" · 部分暂未加载":""));
        artistMore.setVisibility(state.artistHasMore?View.VISIBLE:View.GONE);artistMore.setEnabled(!state.artistLoading);artistRetry.setVisibility(state.artistFailedCount>0?View.VISIBLE:View.GONE);artistRetry.setEnabled(!state.artistLoading);
        StringBuilder signature=new StringBuilder(query);for(AggregateSearch.ArtistResult artist:artists)signature.append('\u001e').append(artist.key).append('|').append(artist.name).append('|').append(artist.artist.avatarUrl).append('|').append(artist.artist.description);
        if(signature.toString().equals(artistSignature))return;artistSignature=signature.toString();int heldScroll=artistScroll.getScrollX();artistCards.removeAllViews();artistPortraits.clear();
        int width=Math.max(240,Math.round(activity.getResources().getDisplayMetrics().widthPixels/activity.getResources().getDisplayMetrics().density)-40);
        for(AggregateSearch.ArtistResult artist:artists){
            LinearLayout card=new LinearLayout(activity);card.setGravity(Gravity.CENTER_VERTICAL);card.setPadding(0,dp(4),dp(10),dp(4));
            ArtistPortrait portrait=new ArtistPortrait(artist);artistPortraits.add(portrait);
            card.addView(portrait,new LinearLayout.LayoutParams(dp(54),dp(54)));
            LinearLayout names=column();names.setPadding(dp(14),0,dp(8),0);TextView name=text(artist.name,19);name.setTypeface(Typeface.create("sans-serif",Typeface.BOLD));name.setSingleLine(true);name.setEllipsize(TextUtils.TruncateAt.END);names.addView(name);
            String description=artist.artist.description;TextView hint=text(description==null||description.isEmpty()?"音乐人 · 查看主页":description,12);hint.setMaxLines(1);hint.setEllipsize(TextUtils.TruncateAt.END);hint.setTextColor(DesignSystem.onAmbientMuted);hint.setPadding(0,dp(5),0,0);names.addView(hint);card.addView(names,new LinearLayout.LayoutParams(0,-2,1));TextView arrow=text("›",28);card.addView(arrow);
            card.setContentDescription("打开"+artist.name+"的歌手主页");card.setClickable(true);card.setFocusable(true);String boundQuery=query,key=artist.key;
            card.setOnClickListener(v->{if(!active()||!query.equals(boundQuery))return;for(AggregateSearch.ArtistResult current:artists)if(current.key.equals(key)){android.view.inputmethod.InputMethodManager ime=(android.view.inputmethod.InputMethodManager)activity.getSystemService(Activity.INPUT_METHOD_SERVICE);if(ime!=null)ime.hideSoftInputFromWindow(input.getWindowToken(),0);rememberQuery();actions.artist(current);return;}});
            LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(dp(artists.size()==1?width:Math.min(width-20,316)),dp(64));params.setMargins(0,0,dp(10),0);artistCards.addView(card,params);
        }
        artistScroll.post(()->{if(active()){artistScroll.scrollTo(heldScroll,0);loadVisibleArtistPortraits();}});
    }
    private void loadVisibleArtistPortraits(){
        if(!active()||artistScroll==null||artistScroll.getWidth()==0)return;int left=artistScroll.getScrollX()-dp(80),right=left+artistScroll.getWidth()+dp(160);
        for(ArtistPortrait portrait:artistPortraits){View card=(View)portrait.getParent();if(card!=null&&card.getRight()>left&&card.getLeft()<right)portrait.load();}
    }
    private final class ArtistPortrait extends FrameLayout {
        final String uri;boolean requested;
        ArtistPortrait(AggregateSearch.ArtistResult artist){super(activity);uri=artist.artist.avatarUrl;setBackground(DesignSystem.rounded(activity,0x12ffffff,30));setClipToOutline(true);setOutlineProvider(android.view.ViewOutlineProvider.BACKGROUND);
            TextView mark=text(artist.name.substring(0,artist.name.offsetByCodePoints(0,1)),23);mark.setGravity(Gravity.CENTER);addView(mark,new FrameLayout.LayoutParams(-1,-1));}
        void load(){if(requested||uri==null||uri.isEmpty())return;requested=true;CoverView avatar=new CoverView(activity);avatar.setBackgroundColor(Color.TRANSPARENT);avatar.setSource(uri,160);avatar.setContentDescription("音乐人头像");addView(avatar,new FrameLayout.LayoutParams(-1,-1));}
    }
    public void close(){if(closed)return;closed=true;main.removeCallbacksAndMessages(null);engine.close();if(dialog!=null&&dialog.isShowing())dialog.dismiss();Runnable callback=onClosed;onClosed=null;if(callback!=null)callback.run();}
    private void updateTabs(){
        if(filterBar==null)return;
        for(int i=0;i<filterBar.getChildCount();i++){
            Button button=(Button)filterBar.getChildAt(i);
            button.setSelected(i==tab);
            button.setTextColor(i==tab?DesignSystem.onAmbient:DesignSystem.onAmbientMuted);
            button.setBackground(i==tab?DesignSystem.ambientButton(activity,12):null);
        }
    }

    private void back(){
        if(!browse.isEmpty()){browse="";render(engine.state());list.setSelection(0);return;}
        close();
    }

    private void hideKeyboard(){
        InputMethodManager ime=(InputMethodManager)activity.getSystemService(Activity.INPUT_METHOD_SERVICE);
        if(ime!=null)ime.hideSoftInputFromWindow(input.getWindowToken(),0);
    }

    private void rememberQuery(){
        String term=query.trim();
        if(term.isEmpty()||term.length()>180)return;
        history.removeIf(old->old.equalsIgnoreCase(term));
        history.add(0,term);
        while(history.size()>8)history.remove(history.size()-1);
        historyStore.edit().putString("recent_queries",new JSONArray(history).toString()).apply();
    }

    private void openAlbum(Track track){if(active()){rememberQuery();hideKeyboard();actions.album(track);}}

    private static boolean hasAlbum(Track track){
        return !"未分类专辑".equals(track.album)&&(!OnlineMusic.isRemote(track)||!track.albumId.isEmpty());
    }

    private void openJacket(SearchSleeves.Jacket jacket){
        if(!active()||!albums.stream().anyMatch(item->item.key.equals(jacket.key)))return;
        if(jacket.seed!=null){openAlbum(jacket.seed);return;}
        rememberQuery();hideKeyboard();actions.catalogAlbum(jacket.result);
    }

    private void collectAlbums(AggregateSearch.State state){
        LinkedHashMap<String,SearchSleeves.Jacket> unique=new LinkedHashMap<>();
        for(AggregateSearch.AlbumResult result:state.albums){
            SearchSleeves.Jacket jacket=new SearchSleeves.Jacket(result);unique.putIfAbsent(jacket.key,jacket);
        }
        for(AggregateSearch.Result result:results){
            Track seed=null;
            for(Track track:result.alternatives)if(hasAlbum(track)){
                if(seed==null)seed=track;
                if(!track.artworkUri.isEmpty()){seed=track;break;}
            }
            if(seed!=null)unique.putIfAbsent(seed.albumKey(),new SearchSleeves.Jacket(seed));
        }
        albums.addAll(unique.values());
    }

    private void section(LinearLayout root,String name,String action,Runnable onClick){
        LinearLayout row=new LinearLayout(activity);row.setGravity(Gravity.CENTER_VERTICAL);
        TextView title=text(name,17);title.setTypeface(Typeface.create("serif",Typeface.BOLD));title.setPadding(0,dp(12),0,dp(8));row.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        if(action!=null){Button link=button(action,onClick);link.setTextSize(12);link.setTextColor(DesignSystem.onAmbientMuted);link.setBackgroundColor(Color.TRANSPARENT);row.addView(link,new LinearLayout.LayoutParams(-2,dp(44)));}
        root.addView(row);
    }

    private void renderHome(){
        if(!browse.isEmpty()){
            section(home,"曲库 · "+browse,"返回",this::back);
            LinkedHashMap<String,Track> unique=new LinkedHashMap<>();
            for(Track track:actions.libraryTracks()){
                if("歌曲".equals(browse))localRows.add(track);
                else if("专辑".equals(browse)&&hasAlbum(track))unique.putIfAbsent(track.albumKey(),track);
                else if("音乐人".equals(browse))unique.putIfAbsent(track.artist,track);
            }
            if("专辑".equals(browse))for(Track track:unique.values())albums.add(new SearchSleeves.Jacket(track));
            if("音乐人".equals(browse))localRows.addAll(unique.values());
            if(albums.isEmpty()&&localRows.isEmpty())home.addView(text("曲库中还没有"+browse,14));
            return;
        }
        List<Track> recent=actions.recentTracks();
        for(Track t:recent){if(localRows.size()==8)break;localRows.add(t);}
        LinkedHashMap<String,Track> jackets=new LinkedHashMap<>();
        for(Track t:recent){if(hasAlbum(t))jackets.putIfAbsent(t.albumKey(),t);if(jackets.size()==3)break;}
        if(!jackets.isEmpty()){
            section(home,"最近听过的专辑",null,null);
            home.addView(sleeves.spread(new ArrayList<>(jackets.values()),this::openAlbum));
        }
        if(!history.isEmpty()){
            section(home,"最近搜索","清空",()->{history.clear();historyStore.edit().remove("recent_queries").apply();render(engine.state());});
            HorizontalScrollView scroll=new HorizontalScrollView(activity);scroll.setHorizontalScrollBarEnabled(false);
            LinearLayout chips=new LinearLayout(activity);
            for(String term:history){
                Button chip=button(term,()->{input.setText(term);input.setSelection(input.length());hideKeyboard();});
                chip.setSingleLine(true);chip.setMaxWidth(dp(200));chip.setEllipsize(TextUtils.TruncateAt.END);
                LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-2,dp(44));params.rightMargin=dp(7);chips.addView(chip,params);
            }
            scroll.addView(chips);home.addView(scroll);
        }
        if(localRows.isEmpty()){
            section(home,"浏览曲库",null,null);
            for(String category:new String[]{"音乐人","专辑","歌曲"}){
                Button item=button(category+"     ›",()->{browse=category;hideKeyboard();render(engine.state());list.setSelection(0);});
                item.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);item.setPadding(dp(16),0,dp(16),0);
                LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,dp(52));params.bottomMargin=dp(6);home.addView(item,params);
            }
        }else section(home,"最近播放",null,null);
    }

    private boolean albumRows(){return query.isEmpty()?"专辑".equals(browse):tab==3;}
    private final class Rows extends BaseAdapter {
        public int getCount(){if(albumRows())return (albums.size()+1)/2;return query.isEmpty()?localRows.size():tab<=1?results.size():0;}
        public int getViewTypeCount(){return 3;}
        public int getItemViewType(int at){return albumRows()?1:"音乐人".equals(browse)?2:0;}
        public Object getItem(int at){return albumRows()?albums.get(at*2):query.isEmpty()?localRows.get(at):results.get(at);}
        public long getItemId(int at){return at;}
        public View getView(int at,View recycled,ViewGroup parent){
            if(albumRows())return sleeves.pair(albums,at*2,AggregateSearchPage.this::openJacket);
            if("音乐人".equals(browse)){
                Track track=localRows.get(at);
                TextView name=recycled instanceof TextView?(TextView)recycled:text("",17);
                name.setText(track.artist+"    ›");name.setPadding(dp(8),dp(14),dp(8),dp(14));name.setFocusable(true);
                name.setOnClickListener(v->actions.libraryArtist(track));return name;
            }
            Cell cell=recycled instanceof Cell?(Cell)recycled:new Cell();
            if(query.isEmpty()){
                Track track=localRows.get(at);cell.bindTrack(track);
                cell.setOnClickListener(v->{int current=localRows.indexOf(track);if(active()&&query.isEmpty()&&current>=0)actions.libraryPlay(new ArrayList<>(localRows),current);});
            }else cell.bind(results.get(at));
            return cell;
        }
    }
    private final class Cell extends LinearLayout {
        final CoverView cover;final TextView title,detail,time;final PlaybackAccessBadge access;
        Cell(){super(activity);setGravity(Gravity.CENTER_VERTICAL);setPadding(0,dp(7),0,dp(7));setMinimumHeight(dp(64));
            cover=new CoverView(activity);addView(cover,new LinearLayout.LayoutParams(dp(46),dp(46)));LinearLayout text=column();text.setPadding(dp(14),0,0,0);title=AggregateSearchPage.this.text("",16);title.setTypeface(Typeface.create("sans-serif",Typeface.BOLD));title.setMaxLines(2);title.setEllipsize(TextUtils.TruncateAt.END);detail=AggregateSearchPage.this.text("",12);detail.setTextColor(DesignSystem.onAmbientMuted);detail.setPadding(0,dp(5),0,0);detail.setMaxLines(2);detail.setEllipsize(TextUtils.TruncateAt.END);text.addView(title);text.addView(detail);access=new PlaybackAccessBadge(activity);text.addView(access);addView(text,new LinearLayout.LayoutParams(0,-2,1));time=AggregateSearchPage.this.text("",12);time.setTextColor(DesignSystem.onAmbientMuted);time.setPadding(dp(6),0,0,0);addView(time);}
        void bindTrack(Track track){title.setText(track.title);detail.setText(track.artist+("未分类专辑".equals(track.album)?"":" · "+track.album));
            cover.setTrack(track,themes,160);access.bind(track.accessLabel());time.setText(duration(track));setContentDescription(track.title+"，"+track.artist);setFocusable(true);}
        String duration(Track track){return track.durationMs<=0?"":String.format(Locale.ROOT,"%d:%02d",track.durationMs/60000,track.durationMs/1000%60);}

        void bind(AggregateSearch.Result result){Track track=result.displayTrack;time.setText(duration(track));title.setText(track.title);detail.setText(track.artist+(track.album.isEmpty()||"未分类专辑".equals(track.album)?"":" · "+track.album));String artwork=track.artworkUri;if(artwork.isEmpty())for(Track candidate:result.alternatives)if(!candidate.artworkUri.isEmpty()){artwork=candidate.artworkUri;break;}if(artwork.isEmpty())cover.clear();else cover.setSource(artwork,160);String accessLabel=result.accessLabel();access.bind(accessLabel);setContentDescription(track.title+"，"+track.artist+(accessLabel.isEmpty()?"":"，"+accessLabel));setClickable(true);setFocusable(true);
            String boundQuery=query,key=result.key;setOnClickListener(v->{if(!active()||!query.equals(boundQuery))return;for(int i=0;i<results.size();i++)if(results.get(i).key.equals(key)){rememberQuery();hideKeyboard();actions.play(new ArrayList<>(results),i);return;}});}
    }
    private LinearLayout column(){LinearLayout view=new LinearLayout(activity);view.setOrientation(LinearLayout.VERTICAL);return view;}
    private TextView text(String value,int size){TextView view=new TextView(activity);view.setText(value);view.setTextSize(size);view.setTypeface(Typeface.create("sans-serif",Typeface.NORMAL));view.setTextColor(DesignSystem.onAmbient);return view;}
    private Button button(String value,Runnable action){Button view=new Button(activity);view.setAllCaps(false);view.setTypeface(Typeface.create("sans-serif",Typeface.NORMAL));view.setText(value);view.setTextColor(DesignSystem.onAmbient);view.setTextSize(14);view.setMinWidth(0);view.setMinimumWidth(0);view.setBackground(DesignSystem.ambientButton(activity,22));view.setOnClickListener(v->{if(active())action.run();});return view;}
    private int dp(float value){return DesignSystem.dp(activity,value);}
}
