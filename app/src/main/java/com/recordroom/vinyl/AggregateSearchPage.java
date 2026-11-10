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

/** A single native search surface. Provider identities are retained only inside playback alternatives. */
public final class AggregateSearchPage {
    public interface Actions {
        void play(List<AggregateSearch.Result> results,int index);
        void manageAccounts();
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
    private Dialog dialog;private EditText input;private ListView list;private TextView status;private Button more,retry;
    private Runnable onClosed;private boolean closed,suppressInput;private String query="";
    private int restoreTarget,restoreRequests,restoreArtistTarget,restoreArtistRequests,restoreArtistScroll;private Bundle viewport;
    private final Runnable debounce=this::submitQuery;
    public AggregateSearchPage(Activity activity,AggregateSearch engine,Actions actions){this.activity=activity;this.engine=engine;this.actions=actions;}
    public static AggregateSearchPage restore(Activity activity,AggregateSearch engine,Actions actions,Bundle saved){
        AggregateSearchPage page=new AggregateSearchPage(activity,engine,actions);
        if(saved!=null){page.query=saved.getString("query","");page.restoreTarget=Math.min(1000,Math.max(0,saved.getInt("loaded_count")));page.viewport=saved.getBundle("viewport");page.restoreArtistTarget=Math.min(1000,Math.max(0,saved.getInt("artist_loaded_count")));page.restoreArtistScroll=Math.max(0,saved.getInt("artist_scroll"));}
        return page;
    }
    public Bundle snapshot(){Bundle saved=new Bundle();saved.putString("query",query);saved.putInt("loaded_count",Math.max(restoreTarget,results.size()));saved.putBundle("viewport",list==null?viewport:ViewScrollState.capture(list));saved.putInt("artist_loaded_count",Math.max(restoreArtistTarget,artists.size()));saved.putInt("artist_scroll",artistScroll==null?restoreArtistScroll:artistScroll.getScrollX());return saved;}
    public void setOnClosed(Runnable callback){onClosed=callback;}
    public Dialog show(){
        if(closed)throw new IllegalStateException("Search page is closed");if(dialog!=null&&dialog.isShowing())return dialog;
        dialog=new Dialog(activity,android.R.style.Theme_Material_NoActionBar);
        LinearLayout page=column();page.setBackground(DesignSystem.ambient(activity));
        page.setOnApplyWindowInsetsListener((v,insets)->{v.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());return insets;});
        LinearLayout top=new LinearLayout(activity);top.setGravity(Gravity.CENTER_VERTICAL);top.setPadding(dp(12),dp(8),dp(18),dp(8));
        Button back=button("‹",this::close);back.setTextSize(28);back.setContentDescription("关闭搜索");top.addView(back,new LinearLayout.LayoutParams(dp(48),dp(48)));
        TextView heading=text("搜索音乐",28);heading.setTypeface(Typeface.create("sans-serif",Typeface.BOLD));top.addView(heading,new LinearLayout.LayoutParams(0,-2,1));
        top.addView(button("账户",actions::manageAccounts),new LinearLayout.LayoutParams(dp(64),dp(48)));page.addView(top);
        input=new EditText(activity);input.setSingleLine(true);input.setTextSize(16);input.setTypeface(Typeface.create("sans-serif",Typeface.NORMAL));input.setTextColor(DesignSystem.onAmbient);input.setHintTextColor(DesignSystem.onAmbientMuted);input.setHint("歌曲名或音乐人");input.setContentDescription("搜索歌曲和音乐人");input.setImeOptions(EditorInfo.IME_ACTION_SEARCH);input.setPadding(dp(18),0,dp(18),0);input.setBackground(DesignSystem.settingsCard(activity,22));
        LinearLayout.LayoutParams searchParams=new LinearLayout.LayoutParams(-1,dp(54));searchParams.setMargins(dp(20),dp(8),dp(20),dp(12));page.addView(input,searchParams);input.setText(query);
        status=text("搜索歌曲，也可直达音乐人主页",13);status.setTextColor(DesignSystem.onAmbientMuted);status.setPadding(dp(24),dp(4),dp(24),dp(12));page.addView(status);
        artistHeader=new LinearLayout(activity);artistHeader.setGravity(Gravity.CENTER_VERTICAL);artistHeader.setPadding(dp(24),0,dp(20),dp(6));artistStatus=text("音乐人",13);artistStatus.setTextColor(DesignSystem.onAmbientMuted);artistHeader.addView(artistStatus,new LinearLayout.LayoutParams(0,-2,1));
        artistMore=button("更多音乐人",engine::loadMoreArtists);artistMore.setTextSize(12);artistHeader.addView(artistMore,new LinearLayout.LayoutParams(-2,dp(38)));artistRetry=button("重试音乐人",engine::retryArtists);artistRetry.setTextSize(12);artistHeader.addView(artistRetry,new LinearLayout.LayoutParams(-2,dp(38)));page.addView(artistHeader);artistHeader.setVisibility(View.GONE);
        artistScroll=new HorizontalScrollView(activity);artistScroll.setHorizontalScrollBarEnabled(false);artistScroll.setClipToPadding(false);artistScroll.setPadding(dp(20),0,dp(20),dp(14));
        artistCards=new LinearLayout(activity);artistScroll.addView(artistCards);artistScroll.getViewTreeObserver().addOnScrollChangedListener(this::loadVisibleArtistPortraits);artistScroll.addOnLayoutChangeListener((v,l,t,r,b,oldL,oldT,oldR,oldB)->loadVisibleArtistPortraits());page.addView(artistScroll,new LinearLayout.LayoutParams(-1,dp(108)));artistScroll.setVisibility(View.GONE);
        songHeading=text("歌曲",13);songHeading.setTextColor(DesignSystem.onAmbientMuted);songHeading.setPadding(dp(24),0,dp(24),dp(8));page.addView(songHeading);songHeading.setVisibility(View.GONE);
        list=new ListView(activity);list.setContentDescription("歌曲搜索结果");list.setDivider(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));list.setDividerHeight(dp(8));list.setSelector(new android.graphics.drawable.ColorDrawable(0x14736578));list.setPadding(dp(20),0,dp(20),dp(20));list.setClipToPadding(false);list.setAdapter(adapter);page.addView(list,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout bottom=new LinearLayout(activity);bottom.setPadding(dp(20),dp(8),dp(20),dp(18));
        more=button("加载更多",engine::loadMore);retry=button("重试未完成的搜索",engine::retryFailed);bottom.addView(more,new LinearLayout.LayoutParams(0,dp(48),1));bottom.addView(retry,new LinearLayout.LayoutParams(0,dp(48),1));page.addView(bottom);
        input.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void afterTextChanged(Editable value){}public void onTextChanged(CharSequence value,int start,int before,int count){if(suppressInput||closed)return;query=value.toString().trim();restoreTarget=restoreRequests=restoreArtistTarget=restoreArtistRequests=restoreArtistScroll=0;artistSignature="";artistScroll.scrollTo(0,0);viewport=null;main.removeCallbacks(debounce);engine.search("");if(!query.isEmpty()){status.setText("准备搜索…");main.postDelayed(debounce,350);}}});
        input.setOnEditorActionListener((v,action,event)->{if(action!=EditorInfo.IME_ACTION_SEARCH)return false;main.removeCallbacks(debounce);engine.search(query);return true;});
        dialog.setContentView(page);dialog.setOnDismissListener(d->close());dialog.show();Window window=dialog.getWindow();if(window!=null){window.setLayout(-1,-1);refreshTheme();}page.requestApplyInsets();
        engine.setListener(this::render);engine.search(query);input.requestFocus();return dialog;
    }
    public void refreshTheme(){if(dialog==null||dialog.getWindow()==null)return;Window w=dialog.getWindow();w.setStatusBarColor(DesignSystem.ambientColor(activity));w.setNavigationBarColor(DesignSystem.ambientColor(activity));w.getDecorView().setSystemUiVisibility(0);}
    public void refresh(){if(active()){main.removeCallbacks(debounce);engine.search(query);}}
    private void submitQuery(){if(active())engine.search(query);}
    private boolean active(){return !closed&&!activity.isFinishing()&&!activity.isDestroyed();}
    private void render(AggregateSearch.State state){
        if(!active()||list==null)return;
        Bundle held=results.isEmpty()?null:ViewScrollState.capture(list);results.clear();results.addAll(state.results);adapter.notifyDataSetChanged();
        renderArtists(state);
        if(query.isEmpty())status.setText("搜索歌曲，也可直达音乐人主页");
        else if(results.isEmpty()&&!artists.isEmpty())status.setText(state.loading?"已找到音乐人，歌曲仍在搜索中":state.failedCount>0?"歌曲暂未加载，已找到的音乐人可查看主页":"已找到音乐人，可直接查看主页");
        else if(results.isEmpty())status.setText(state.loading?"正在搜索…":state.failedCount>0?"暂时无法搜索，请重试或检查账户设置。":"没有找到相关歌曲，试试其他关键词。");
        else status.setText("找到 "+results.size()+" 首"+(state.loading?" · 继续搜索中":state.failedCount>0?" · 部分结果暂未加载":""));
        more.setVisibility(state.hasMore?View.VISIBLE:View.GONE);more.setEnabled(state.hasMore);
        retry.setVisibility(state.failedCount>0?View.VISIBLE:View.GONE);
        if(held!=null)ViewScrollState.restore(list,held);
        if(state.query.equals(query)&&!state.artistLoading&&state.artistHasMore&&artists.size()<restoreArtistTarget&&restoreArtistRequests++<40)engine.loadMoreArtists();
        else if(state.query.equals(query)&&!state.artistLoading&&restoreArtistTarget>0){artistScroll.post(()->{if(active())artistScroll.scrollTo(restoreArtistScroll,0);});restoreArtistTarget=0;}
        if(state.query.equals(query)&&!state.loading&&state.hasMore&&results.size()<restoreTarget&&restoreRequests++<40){engine.loadMore();return;}
        if(state.query.equals(query)&&!state.loading&&viewport!=null){ViewScrollState.restore(list,viewport);viewport=null;restoreTarget=0;}
    }
    private void renderArtists(AggregateSearch.State state){
        List<AggregateSearch.ArtistResult> matches=state.artists;artists.clear();artists.addAll(matches);
        boolean hasSection=!query.isEmpty()&&(state.artistLoading||state.artistFailedCount>0||state.artistSearchedCount>0||!artists.isEmpty());
        artistHeader.setVisibility(hasSection?View.VISIBLE:View.GONE);artistScroll.setVisibility(artists.isEmpty()?View.GONE:View.VISIBLE);songHeading.setVisibility(hasSection&&!results.isEmpty()?View.VISIBLE:View.GONE);
        artistStatus.setText(artists.isEmpty()?(state.artistLoading?"正在搜索音乐人…":state.artistFailedCount>0?"音乐人暂未加载":"没有找到相关音乐人"):"音乐人 · "+artists.size()+(state.artistLoading?" · 加载中":state.artistFailedCount>0?" · 部分暂未加载":""));
        artistMore.setVisibility(state.artistHasMore?View.VISIBLE:View.GONE);artistMore.setEnabled(!state.artistLoading);artistRetry.setVisibility(state.artistFailedCount>0?View.VISIBLE:View.GONE);artistRetry.setEnabled(!state.artistLoading);
        StringBuilder signature=new StringBuilder(query);for(AggregateSearch.ArtistResult artist:artists)signature.append('\u001e').append(artist.key).append('|').append(artist.name).append('|').append(artist.artist.avatarUrl).append('|').append(artist.artist.description);
        if(signature.toString().equals(artistSignature))return;artistSignature=signature.toString();int heldScroll=artistScroll.getScrollX();artistCards.removeAllViews();artistPortraits.clear();
        int width=Math.max(240,Math.round(activity.getResources().getDisplayMetrics().widthPixels/activity.getResources().getDisplayMetrics().density)-40);
        for(AggregateSearch.ArtistResult artist:artists){
            LinearLayout card=new LinearLayout(activity);card.setGravity(Gravity.CENTER_VERTICAL);card.setPadding(dp(18),dp(12),dp(18),dp(12));card.setBackground(DesignSystem.settingsCard(activity,22));
            ArtistPortrait portrait=new ArtistPortrait(artist);artistPortraits.add(portrait);
            card.addView(portrait,new LinearLayout.LayoutParams(dp(54),dp(54)));
            LinearLayout names=column();names.setPadding(dp(14),0,dp(8),0);TextView name=text(artist.name,19);name.setTypeface(Typeface.create("sans-serif",Typeface.BOLD));name.setSingleLine(true);name.setEllipsize(TextUtils.TruncateAt.END);names.addView(name);
            String description=artist.artist.description;TextView hint=text(description==null||description.isEmpty()?"音乐人 · 查看主页":description,12);hint.setMaxLines(1);hint.setEllipsize(TextUtils.TruncateAt.END);hint.setTextColor(DesignSystem.onAmbientMuted);hint.setPadding(0,dp(5),0,0);names.addView(hint);card.addView(names,new LinearLayout.LayoutParams(0,-2,1));TextView arrow=text("›",28);card.addView(arrow);
            card.setContentDescription("打开"+artist.name+"的歌手主页");card.setClickable(true);card.setFocusable(true);String boundQuery=query,key=artist.key;
            card.setOnClickListener(v->{if(!active()||!query.equals(boundQuery))return;for(AggregateSearch.ArtistResult current:artists)if(current.key.equals(key)){android.view.inputmethod.InputMethodManager ime=(android.view.inputmethod.InputMethodManager)activity.getSystemService(Activity.INPUT_METHOD_SERVICE);if(ime!=null)ime.hideSoftInputFromWindow(input.getWindowToken(),0);actions.artist(current);return;}});
            LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(dp(artists.size()==1?width:Math.min(width-20,316)),dp(90));params.setMargins(0,0,dp(10),0);artistCards.addView(card,params);
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
    private final class Rows extends BaseAdapter {
        public int getCount(){return results.size();}public Object getItem(int at){return results.get(at);}public long getItemId(int at){return results.get(at).key.hashCode();}
        public View getView(int at,View recycled,ViewGroup parent){Cell cell=recycled instanceof Cell?(Cell)recycled:new Cell();cell.bind(results.get(at));return cell;}
    }
    private final class Cell extends LinearLayout {
        final CoverView cover;final TextView title,detail;final PlaybackAccessBadge access;
        Cell(){super(activity);setGravity(Gravity.CENTER_VERTICAL);setPadding(dp(12),dp(12),dp(12),dp(12));setMinimumHeight(dp(82));setBackground(DesignSystem.settingsCard(activity,18));
            cover=new CoverView(activity);addView(cover,new LinearLayout.LayoutParams(dp(54),dp(54)));LinearLayout text=column();text.setPadding(dp(14),0,0,0);title=AggregateSearchPage.this.text("",16);title.setTypeface(Typeface.create("sans-serif",Typeface.BOLD));title.setMaxLines(2);title.setEllipsize(TextUtils.TruncateAt.END);detail=AggregateSearchPage.this.text("",12);detail.setTextColor(DesignSystem.onAmbientMuted);detail.setPadding(0,dp(5),0,0);detail.setMaxLines(2);detail.setEllipsize(TextUtils.TruncateAt.END);text.addView(title);text.addView(detail);access=new PlaybackAccessBadge(activity);text.addView(access);addView(text,new LinearLayout.LayoutParams(0,-2,1));}
        void bind(AggregateSearch.Result result){Track track=result.displayTrack;title.setText(track.title);detail.setText(track.artist+(track.album.isEmpty()||"未分类专辑".equals(track.album)?"":" · "+track.album));String artwork=track.artworkUri;if(artwork.isEmpty())for(Track candidate:result.alternatives)if(!candidate.artworkUri.isEmpty()){artwork=candidate.artworkUri;break;}if(artwork.isEmpty())cover.clear();else cover.setSource(artwork,160);String accessLabel=result.accessLabel();access.bind(accessLabel);setContentDescription(track.title+"，"+track.artist+(accessLabel.isEmpty()?"":"，"+accessLabel));setClickable(true);setFocusable(true);
            String boundQuery=query,key=result.key;setOnClickListener(v->{if(!active()||!query.equals(boundQuery))return;for(int i=0;i<results.size();i++)if(results.get(i).key.equals(key)){actions.play(new ArrayList<>(results),i);return;}});}
    }
    private LinearLayout column(){LinearLayout view=new LinearLayout(activity);view.setOrientation(LinearLayout.VERTICAL);return view;}
    private TextView text(String value,int size){TextView view=new TextView(activity);view.setText(value);view.setTextSize(size);view.setTypeface(Typeface.create("sans-serif",Typeface.NORMAL));view.setTextColor(DesignSystem.onAmbient);return view;}
    private Button button(String value,Runnable action){Button view=new Button(activity);view.setAllCaps(false);view.setTypeface(Typeface.create("sans-serif",Typeface.NORMAL));view.setText(value);view.setTextColor(DesignSystem.onAmbient);view.setTextSize(14);view.setMinWidth(0);view.setMinimumWidth(0);view.setBackground(DesignSystem.ambientButton(activity,22));view.setOnClickListener(v->{if(active())action.run();});return view;}
    private int dp(float value){return DesignSystem.dp(activity,value);}
}
