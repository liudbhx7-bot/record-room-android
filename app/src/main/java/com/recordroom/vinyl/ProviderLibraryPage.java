package com.recordroom.vinyl;

import android.app.Activity;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
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
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Native account/library browsing. Authentication and playback remain provider/host responsibilities. */
public final class ProviderLibraryPage {
    public interface Actions {
        void openTracks(List<Track> tracks,String heading);
        void play(List<Track> tracks,int index);
        void officialLogin();
        void onAccount(MusicProvider.Account account);
        void onLogout();
    }
    /** Optional song search, deliberately independent of provider-specific implementations. */
    public interface Search {
        MusicProvider.Page<Track> search(String query,int offset,int limit) throws ProviderException;
    }
    private interface Work<T> { T run() throws Exception; }
    private interface Result<T> { void accept(T value); }
    private static final int PAGE_SIZE=30;
    private final Activity activity;
    private final MusicProvider provider;
    private final String name;
    private final Search songSearch;
    private final Actions actions;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"vinyl-provider-library");t.setDaemon(true);return t;});
    private final ArrayList<Row> rows=new ArrayList<>();
    private final ArrayList<Track> results=new ArrayList<>();
    private final Rows adapter=new Rows();
    private ArrayList<MusicProvider.Playlist> playlists=new ArrayList<>();
    private MusicProvider.Account account;
    private Dialog dialog;
    private ListView list;
    private EditText search;
    private Future<?> request;
    private Runnable onClosed;
    private long generation;
    private boolean busy,closed,suppressSearch,hasMore,pendingLiked,focusSearch,playlistsLoaded;
    private int nextOffset,restoreOffset;
    private String query="",pendingPlaylist="",pendingHeading="";
    private Bundle pendingScroll;
    private final Runnable searchDelay=()->searchSongs(false);

    public ProviderLibraryPage(Activity activity,MusicProvider provider,String name,Search search,Actions actions){
        if(activity==null||provider==null||actions==null)throw new IllegalArgumentException("Activity, provider and actions are required");
        this.activity=activity;this.provider=provider;this.name=name==null?provider.id():name;this.songSearch=search;this.actions=actions;
    }
    public static ProviderLibraryPage restore(Activity activity,MusicProvider provider,String name,Search search,Actions actions,Bundle state){
        ProviderLibraryPage page=new ProviderLibraryPage(activity,provider,name,search,actions);
        if(state!=null){page.focusSearch=state.getBoolean("focus_search");page.query=search==null?"":state.getString("query","");page.restoreOffset=Math.max(0,state.getInt("search_offset"));page.pendingScroll=state.getBundle("viewport");page.pendingPlaylist=state.getString("pending_playlist","");page.pendingHeading=state.getString("pending_heading","");page.pendingLiked=state.getBoolean("pending_liked");}
        return page;
    }
    public Bundle snapshot(){
        Bundle state=new Bundle();state.putString("query",query);state.putInt("search_offset",Math.max(nextOffset,restoreOffset));
        state.putString("pending_playlist",pendingPlaylist);state.putString("pending_heading",pendingHeading);state.putBoolean("pending_liked",pendingLiked);
        state.putBundle("viewport",pendingScroll!=null?pendingScroll:list==null?null:ViewScrollState.capture(list));
        // Credentials, tokens, signed playback URLs and full collections never enter saved instance state.
        return state;
    }
    public void setOnClosed(Runnable callback){onClosed=callback;}
    public Dialog show(){
        if(closed)throw new IllegalStateException("Provider page is closed");
        if(dialog!=null&&dialog.isShowing())return dialog;
        dialog=new Dialog(activity,android.R.style.Theme_Material_Light_NoActionBar){@Override public void onBackPressed(){back();}};
        LinearLayout page=column();page.setBackground(DesignSystem.ambient(activity));
        page.setOnApplyWindowInsetsListener((v,insets)->{v.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());return insets;});
        LinearLayout bar=new LinearLayout(activity);bar.setGravity(Gravity.CENTER_VERTICAL);bar.setPadding(dp(10),0,dp(12),0);
        Button back=button("‹",this::back);back.setTextSize(28);back.setContentDescription("返回"+name+"上层");bar.addView(back,new LinearLayout.LayoutParams(dp(48),dp(52)));
        TextView heading=text(name,20,DesignSystem.onAmbient);heading.setTypeface(Typeface.create("sans-serif",Typeface.BOLD));bar.addView(heading,new LinearLayout.LayoutParams(0,-2,1));bar.addView(button("刷新",this::reload),new LinearLayout.LayoutParams(dp(64),dp(48)));page.addView(bar);
        search=new EditText(activity);search.setTextSize(15);search.setTextColor(DesignSystem.onAmbient);search.setHintTextColor(DesignSystem.onAmbientMuted);search.setSingleLine(true);search.setHint("搜索"+name+"全曲库歌曲");search.setContentDescription("搜索"+name+"歌曲");search.setImeOptions(EditorInfo.IME_ACTION_SEARCH);search.setPadding(dp(16),0,dp(16),0);
        search.setTypeface(Typeface.create("sans-serif",Typeface.NORMAL));search.setBackground(DesignSystem.settingsCard(activity,20));LinearLayout.LayoutParams input=new LinearLayout.LayoutParams(-1,dp(48));input.setMargins(dp(18),dp(8),dp(18),dp(10));page.addView(search,input);search.setVisibility(songSearch==null?View.GONE:View.VISIBLE);setSearchText(query);
        search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void afterTextChanged(Editable value){}public void onTextChanged(CharSequence value,int start,int before,int count){if(suppressSearch||closed)return;query=value.toString().trim();restoreOffset=0;nextOffset=0;results.clear();pendingScroll=null;pendingPlaylist="";pendingLiked=false;invalidateRequest();main.removeCallbacks(searchDelay);main.postDelayed(searchDelay,350);}});
        search.setOnEditorActionListener((v,action,event)->{if(action!=EditorInfo.IME_ACTION_SEARCH)return false;main.removeCallbacks(searchDelay);searchSongs(false);return true;});
        list=new ListView(activity);list.setDivider(null);list.setDividerHeight(0);list.setSelector(new ColorDrawable(0x14736578));list.setClipToPadding(false);list.setPadding(dp(18),dp(6),dp(18),dp(28));list.setContentDescription(name+"音乐内容");list.setAdapter(adapter);page.addView(list,new LinearLayout.LayoutParams(-1,0,1));
        dialog.setContentView(page);dialog.setOnDismissListener(d->close());dialog.show();Window window=dialog.getWindow();if(window!=null){window.setLayout(-1,-1);refreshTheme();}page.requestApplyInsets();reload();if(focusSearch){search.requestFocus();search.post(()->{if(!closed)((android.view.inputmethod.InputMethodManager)activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)).showSoftInput(search,android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);});}return dialog;
    }
    public void refreshAccount(){query="";results.clear();nextOffset=restoreOffset=0;pendingPlaylist="";pendingLiked=false;pendingScroll=null;setSearchText("");if(dialog!=null&&!closed)loadHome();}
    public void close(){
        if(closed)return;closed=true;invalidateRequest();main.removeCallbacksAndMessages(null);worker.shutdownNow();
        if(dialog!=null&&dialog.isShowing())dialog.dismiss();Runnable callback=onClosed;onClosed=null;if(callback!=null)callback.run();
    }
    private void back(){if(!query.isEmpty()){refreshAccount();return;}close();}
    private void reload(){if(closed)return;if(query.isEmpty())loadHome();else searchSongs(false);}
    private void setSearchText(String value){if(search!=null){suppressSearch=true;search.setText(value);suppressSearch=false;}}
    private void invalidateRequest(){generation++;if(request!=null)request.cancel(true);request=null;busy=false;}
    private boolean active(long token){return !closed&&generation==token&&!activity.isFinishing()&&!activity.isDestroyed();}
    private <T>void run(Work<T> work,Result<T> result,Runnable retry){
        invalidateRequest();main.removeCallbacks(searchDelay);final long token=generation;busy=true;
        request=worker.submit(()->{try{T value=work.run();main.post(()->{if(active(token)){busy=false;request=null;result.accept(value);}});}catch(Exception failure){main.post(()->{if(active(token)){busy=false;request=null;showError(failure,retry);}});}});
    }
    private void loading(String label){rows.clear();rows.add(Row.note(label,""));update(false);}
    private void loadHome(){
        if(!provider.supportsAccount()){pendingPlaylist="";pendingLiked=false;searchSongs(false);return;}
        loading("正在读取"+name+"账户…");
        run(()->{
            MusicProvider.Account value=provider.account();if(value==null)throw invalid("账号信息为空");return value;
        },value->{account=value;playlists.clear();playlistsLoaded=false;actions.onAccount(value);if(value.loggedIn)loadPlaylists(value);else{pendingPlaylist="";pendingLiked=false;renderHome();}},this::loadHome);
    }
    private void loadPlaylists(MusicProvider.Account identity){
        loading("正在读取"+name+"歌单…");
        run(()->{List<MusicProvider.Playlist> values=provider.playlists(identity.id);if(values==null)throw invalid("歌单接口没有返回列表，未将其显示为曲库为空");return values;},values->{playlists.clear();for(MusicProvider.Playlist p:values)if(p!=null)playlists.add(p);playlistsLoaded=true;renderHome();continueCollection();},()->loadPlaylists(identity));
    }
    private void renderHome(){
        if(!provider.supportsAccount()){renderSearch();return;}
        rows.clear();
        if(account==null||!account.loggedIn){boolean apple="applemusic".equals(provider.id());rows.add(Row.note("连接你的"+name,apple?"先完成开发者配置，再通过 Apple 官方授权读取你的资料库与歌单。":"搜索全曲库歌曲；登录后同步歌单，并按你的账号权益播放。"));rows.add(Row.action(apple?"配置并授权 Apple Music":"官方登录","在官方页面完成账号授权与验证",this::officialLogin));update(true);return;}
        rows.add(Row.image(account.nickname==null||account.nickname.isEmpty()?name+"账户":account.nickname,"已登录 · "+name,account.avatarUrl,null));
        if(!"applemusic".equals(provider.id()))rows.add(Row.action("喜欢的歌曲","读取账户返回的喜欢列表",()->openCollection("","喜欢的歌曲",true)));
        rows.add(Row.note("本人歌单",""));int owned=0;
        for(MusicProvider.Playlist p:playlists)if(p.owned){owned++;rows.add(Row.image(p.name,p.trackCount<0?"打开查看歌曲":p.trackCount+" 首歌曲",p.coverUrl,()->openCollection(p.id,p.name,false)));}
        if(!playlistsLoaded)rows.add(Row.note("歌单尚未读取","可重试读取，或先打开喜欢的歌曲。"));
        else if(owned==0)rows.add(Row.note("暂无本人歌单","该账户暂未返回创建的歌单。"));
        int collected=0;for(MusicProvider.Playlist p:playlists)if(!p.owned)collected++;
        if(collected>0){rows.add(Row.note("收藏歌单",""));for(MusicProvider.Playlist p:playlists)if(!p.owned)rows.add(Row.image(p.name,p.trackCount<0?"打开查看歌曲":p.trackCount+" 首歌曲",p.coverUrl,()->openCollection(p.id,p.name,false)));}
        rows.add(Row.action("切换账号","在官方页面重新验证账号",this::officialLogin));rows.add(Row.action("退出"+name+"账户","清除本应用保存的该来源登录凭据",this::logout));update(true);
    }
    private void officialLogin(){if(!provider.supportsAccount())return;invalidateRequest();main.removeCallbacks(searchDelay);actions.officialLogin();}
    private void continueCollection(){if(!pendingPlaylist.isEmpty()||pendingLiked)openCollection(pendingPlaylist,pendingHeading,pendingLiked);}
    private void openCollection(String playlistId,String title,boolean liked){
        if(account==null||!account.loggedIn){loadHome();return;}
        if(pendingScroll==null&&list!=null)pendingScroll=ViewScrollState.capture(list);
        pendingPlaylist=playlistId;pendingHeading=title;pendingLiked=liked;String userId=account.id;loading("正在读取“"+title+"”的曲目…");
        run(()->clean(liked?provider.likedTracks(userId):provider.playlistTracks(playlistId)),tracks->{pendingPlaylist="";pendingHeading="";pendingLiked=false;renderHome();if(tracks.isEmpty()){rows.add(0,Row.note(title+"暂无可显示的歌曲",""));update(false);}else actions.openTracks(tracks,title);},()->openCollection(playlistId,title,liked));
    }
    private void searchSongs(boolean more){
        if(closed)return;if(songSearch==null){if(provider.supportsAccount())loadHome();else loading("当前来源暂未提供公开搜索");return;}if(query.isEmpty()&&provider.supportsAccount()){loadHome();return;}
        String requested=query;int offset=more?nextOffset:0,target=more?0:restoreOffset;
        if(!more){results.clear();nextOffset=0;loading(query.isEmpty()?"正在读取"+name+"热门歌曲…":"正在搜索“"+query+"”…");}else pendingScroll=ViewScrollState.capture(list);
        run(()->{
            ArrayList<Track> collected=new ArrayList<>();MusicProvider.Page<Track> page;int cursor=offset,iterations=0;
            do{if(Thread.currentThread().isInterrupted())throw new InterruptedException();page=songSearch.search(requested,cursor,PAGE_SIZE);if(page==null)throw invalid("搜索结果为空");collected.addAll(clean(page.items));if(page.hasMore&&page.nextOffset<=cursor)throw invalid("搜索分页未前进，请重试");cursor=page.nextOffset;if(++iterations>1000)throw invalid("搜索分页过多，请缩小搜索范围");}while(page.hasMore&&cursor<target);
            return new MusicProvider.Page<>(collected,page.total,cursor,page.hasMore);
        },page->{if(!requested.equals(query))return;HashSet<String> seen=new HashSet<>();for(Track t:results)seen.add(LibraryStore.identity(t));for(Track t:page.items)if(seen.add(LibraryStore.identity(t)))results.add(t);nextOffset=page.nextOffset;hasMore=page.hasMore;restoreOffset=0;renderSearch();},()->searchSongs(more));
    }
    private void renderSearch(){
        rows.clear();rows.add(Row.note(query.isEmpty()&&!provider.supportsAccount()?"热门推荐":"歌曲搜索","已加载 "+results.size()+" 首"));
        for(int i=0;i<results.size();i++){Track track=results.get(i);final int at=i;rows.add(Row.song(track,()->actions.play(new ArrayList<>(results),at)));}
        if(results.isEmpty())rows.add(Row.note("没有找到相关歌曲","试试歌曲名或音乐人。"));
        if(hasMore)rows.add(Row.action("加载更多","",()->searchSongs(true)));update(true);
    }
    private void logout(){
        invalidateRequest();main.removeCallbacks(searchDelay);final long token=generation;busy=true;loading("正在退出"+name+"账户…");
        OnlineMusic.logout(activity.getApplicationContext(),provider,(committed,failure)->{
            // A committed identity change must clear the living host's library even if this page closed.
            if(committed){account=null;playlists.clear();results.clear();if(!activity.isFinishing()&&!activity.isDestroyed())actions.onLogout();}
            if(!active(token))return;busy=false;if(failure!=null)showError(failure,this::logout);else refreshAccount();
        });
    }
    private void showError(Exception failure,Runnable retry){
        ProviderException.Kind kind=failure instanceof ProviderException?((ProviderException)failure).kind:null;
        boolean login=kind==ProviderException.Kind.LOGIN_REQUIRED||kind==ProviderException.Kind.LOGIN_EXPIRED||kind==ProviderException.Kind.LOGIN_CHALLENGE;
        if(provider.supportsAccount()&&(kind==ProviderException.Kind.LOGIN_REQUIRED||kind==ProviderException.Kind.LOGIN_EXPIRED)){account=new MusicProvider.Account("",name,"",-1,false);playlists.clear();playlistsLoaded=false;actions.onAccount(account);}
        if(!results.isEmpty()&&(!query.isEmpty()||!provider.supportsAccount()))renderSearch();else if(query.isEmpty()&&account!=null&&account.loggedIn)renderHome();else rows.clear();
        String message=failure instanceof ProviderException?failure.getMessage():"操作暂时未完成，请重试";rows.add(Row.note(errorTitle(kind),message==null?"请稍后重试":message));rows.add(Row.action("重试","",retry));
        if(provider.supportsAccount()&&(login||"qq".equals(provider.id())&&(kind==ProviderException.Kind.UNAVAILABLE||kind==ProviderException.Kind.INVALID_RESPONSE)))rows.add(Row.action("官方登录","重新验证当前账号",this::officialLogin));
        update(false);
    }
    private static String errorTitle(ProviderException.Kind kind){if(kind==null)return "暂时无法完成";switch(kind){case NETWORK:return "连接失败";case LOGIN_REQUIRED:return "尚未登录";case LOGIN_EXPIRED:return "登录已失效";case LOGIN_CHALLENGE:return "需要官方验证";case RATE_LIMITED:return "请求过于频繁";case INVALID_RESPONSE:return "返回数据不完整";case UNAVAILABLE:return "暂时无法读取";default:return "暂时无法完成";}}
    private void update(boolean restore){if(list==null)return;adapter.notifyDataSetChanged();if(restore&&pendingScroll!=null){Bundle state=pendingScroll;pendingScroll=null;ViewScrollState.restore(list,state);}}
    private static ArrayList<Track> clean(List<Track> source)throws ProviderException{if(source==null)throw invalid("曲目列表为空");ArrayList<Track> result=new ArrayList<>();for(Track track:source)if(track!=null)result.add(track);return result;}
    private static ProviderException invalid(String message){return new ProviderException(ProviderException.Kind.INVALID_RESPONSE,message);}
    private static final class Row {
        final String title,detail,art,accessLabel;final Runnable click;final boolean heading;
        Row(String title,String detail,String art,Runnable click,boolean heading){this(title,detail,art,click,heading,"");}
        Row(String title,String detail,String art,Runnable click,boolean heading,String accessLabel){this.accessLabel=accessLabel;this.title=title==null?"":title;this.detail=detail==null?"":detail;this.art=art==null?"":art;this.click=click;this.heading=heading;}
        static Row song(Track track,Runnable click){return new Row(track.title,track.artist+(track.album.isEmpty()?"":" · "+track.album),track.artworkUri,click,false,track.accessLabel());}
        static Row note(String title,String detail){return new Row(title,detail,"",null,true);}
        static Row action(String title,String detail,Runnable click){return new Row(title,detail,"",click,false);}
        static Row image(String title,String detail,String art,Runnable click){return new Row(title,detail,art,click,false);}
    }
    private final class Rows extends BaseAdapter {
        public int getCount(){return rows.size();}public Object getItem(int at){return rows.get(at);}public long getItemId(int at){return at;}public boolean areAllItemsEnabled(){return false;}public boolean isEnabled(int at){return rows.get(at).click!=null;}
        public View getView(int at,View recycled,ViewGroup parent){Cell cell=recycled instanceof Cell?(Cell)recycled:new Cell();cell.bind(rows.get(at));return cell;}
    }
    private final class Cell extends LinearLayout {
        final CoverView cover;final TextView title,detail;final LinearLayout labels;final PlaybackAccessBadge access;
        Cell(){super(activity);setGravity(Gravity.CENTER_VERTICAL);setPadding(dp(10),dp(13),dp(10),dp(13));setMinimumHeight(dp(60));cover=new CoverView(activity);addView(cover,new LinearLayout.LayoutParams(dp(52),dp(52)));labels=column();title=text("",15,DesignSystem.onAmbient);title.setMaxLines(2);title.setEllipsize(TextUtils.TruncateAt.END);detail=text("",13,DesignSystem.onAmbientMuted);detail.setPadding(0,dp(4),0,0);detail.setMaxLines(3);detail.setEllipsize(TextUtils.TruncateAt.END);labels.addView(title);labels.addView(detail);access=new PlaybackAccessBadge(activity);labels.addView(access);addView(labels,new LinearLayout.LayoutParams(0,-2,1));}
        void bind(Row row){title.setText(row.title);title.setTextSize(row.heading?20:15);title.setTypeface(Typeface.create("sans-serif",row.heading?Typeface.BOLD:Typeface.NORMAL));detail.setText(row.detail);detail.setVisibility(row.detail.isEmpty()?View.GONE:View.VISIBLE);boolean art=!row.art.isEmpty();cover.setVisibility(art?View.VISIBLE:View.GONE);if(art)cover.setSource(row.art,160);else cover.clear();labels.setPadding(art?dp(13):0,0,0,0);access.bind(row.accessLabel);setContentDescription(row.title+(row.accessLabel.isEmpty()?"":"，"+row.accessLabel));setFocusable(row.click!=null);setClickable(row.click!=null);setBackground(row.click==null?null:DesignSystem.settingsCard(activity,18));long token=generation;setOnClickListener(row.click==null?null:v->{if(active(token)&&!busy)row.click.run();});}
    }
    private LinearLayout column(){LinearLayout view=new LinearLayout(activity);view.setOrientation(LinearLayout.VERTICAL);return view;}
    private TextView text(String value,int size,int color){TextView view=new TextView(activity);view.setText(value);view.setTypeface(Typeface.create("sans-serif",Typeface.NORMAL));view.setTextSize(size);view.setTextColor(color);return view;}
    private Button button(String label,Runnable action){Button view=new Button(activity);view.setText(label);view.setTypeface(Typeface.create("sans-serif",Typeface.NORMAL));view.setTextSize(14);view.setTextColor(DesignSystem.onAmbient);view.setAllCaps(false);view.setBackgroundColor(Color.TRANSPARENT);view.setMinWidth(0);view.setMinimumWidth(0);view.setPadding(dp(8),0,dp(8),0);view.setOnClickListener(v->action.run());return view;}
    public void refreshTheme(){if(dialog==null||dialog.getWindow()==null)return;Window window=dialog.getWindow();window.setStatusBarColor(DesignSystem.ambientColor(activity));window.setNavigationBarColor(DesignSystem.ambientColor(activity));window.getDecorView().setSystemUiVisibility(0);}
    private int dp(float value){return DesignSystem.dp(activity,value);}
}
