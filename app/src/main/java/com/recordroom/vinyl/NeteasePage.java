package com.recordroom.vinyl;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Bitmap;
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
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.common.BitMatrix;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Real provider browsing. Network, credential storage and QR encoding stay off the main thread. */
public final class NeteasePage {
    public interface Actions {
        void openTracks(List<Track> tracks, String heading);
        void play(List<Track> tracks, int index);
        void onLogout();
        void onAccount(MusicProvider.Account account);
        void officialLogin();
    }
    private static final int HOME=0, SEARCH=1, ARTIST=2, LOGIN=3;
    private static final int TEXT=0, ACTION=1, IMAGE_ACTION=2, QR=3;
    private static final int INK=DesignSystem.onAmbient, MUTED=DesignSystem.onAmbientMuted;
    private final Activity activity;
    private final MusicProvider provider;
    private final Actions actions;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final ArrayList<Row> rows=new ArrayList<>();
    private final ArrayList<MusicProvider.Album> searchResults=new ArrayList<>();
    private final Rows adapter=new Rows();
    private Dialog dialog;
    private ListView list;
    private EditText search;
    private TextView heading;
    private Future<?> request;
    private Runnable onClosed;
    private long generation;
    private boolean closed, suppressSearch, busy, searchMore;
    private int mode=HOME, artistBackMode=HOME, tab, nextOffset, restoreOffset;
    private String query="", artistId="", pendingAlbumId="";
    private MusicProvider.Account account;
    private ArrayList<MusicProvider.Playlist> playlists=new ArrayList<>();
    private MusicProvider.Artist artist;
    private MusicProvider.QrLogin qr;
    private Bitmap qrBitmap;
    private String qrStatus="";
    private Bundle pendingScroll, searchBackScroll, restoringParentScroll;
    private boolean restoredState;
    private String restoringAlbumId="";
    private final Runnable searchDelay=()->searchAlbums(false);
    private final Runnable pollDelay=()->pollQr();

    public NeteasePage(Activity activity, MusicProvider provider, Actions actions) {
        this.activity=activity;this.provider=provider;this.actions=actions;
    }
    public static NeteasePage restore(Activity activity,MusicProvider provider,Actions actions,Bundle state) {
        NeteasePage page=new NeteasePage(activity,provider,actions);
        if(state!=null){
            page.restoredState=true;
            page.mode=Math.max(HOME,Math.min(LOGIN,state.getInt("mode",HOME)));
            page.query=state.getString("query","");page.artistId=state.getString("artist_id","");
            page.artistBackMode=state.getInt("artist_back",HOME)==SEARCH?SEARCH:HOME;
            page.tab=Math.max(0,Math.min(1,state.getInt("tab")));
            page.restoreOffset=Math.max(0,state.getInt("search_offset"));
            page.pendingAlbumId=state.getString("pending_album","");
            page.pendingScroll=state.getBundle("viewport");page.searchBackScroll=state.getBundle("search_back_scroll");page.nextOffset=Math.max(0,state.getInt("search_offset"));
            if(page.mode==ARTIST&&page.artistId.isEmpty())page.mode=HOME;
        }
        return page;
    }
    public Bundle snapshot() {
        Bundle state=new Bundle();state.putInt("mode",mode);state.putString("query",query);
        state.putString("artist_id",artistId);state.putInt("artist_back",artistBackMode);state.putInt("tab",tab);
        state.putInt("search_offset",Math.max(nextOffset,restoreOffset));state.putString("pending_album",pendingAlbumId.isEmpty()?restoringAlbumId:pendingAlbumId);state.putBundle("search_back_scroll",searchBackScroll);
        if(pendingScroll!=null)state.putBundle("viewport",pendingScroll);
        else if(list!=null)state.putBundle("viewport",ViewScrollState.capture(list));
        // Login keys, cookies, signed URLs and full collections are never saved in Activity state.
        return state;
    }
    public void setOnClosed(Runnable callback){onClosed=callback;}
    public void refreshAccount(){restoringAlbumId="";mode=HOME;pendingAlbumId="";pendingScroll=null;query="";setSearchText("");if(dialog!=null&&!closed)loadHome();}
    private void officialLogin(){invalidateRequest();main.removeCallbacks(searchDelay);actions.officialLogin();}
    public Dialog show(){
        if(closed)throw new IllegalStateException("NetEase page is closed");
        if(dialog!=null&&dialog.isShowing())return dialog;
        dialog=new Dialog(activity,android.R.style.Theme_Material_Light_NoActionBar){
            @Override public void onBackPressed(){back();}
        };
        LinearLayout page=column();page.setBackground(DesignSystem.ambient(activity));
        page.setOnApplyWindowInsetsListener((v,insets)->{v.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());return insets;});
        LinearLayout bar=new LinearLayout(activity);bar.setGravity(Gravity.CENTER_VERTICAL);bar.setPadding(dp(10),0,dp(12),0);
        Button back=button("‹",this::back);back.setContentDescription("返回网易云上层");back.setTextSize(28);bar.addView(back,new LinearLayout.LayoutParams(dp(48),dp(52)));
        heading=text("网易云音乐",18,INK);heading.setTypeface(Typeface.create("sans-serif",Typeface.BOLD));bar.addView(heading,new LinearLayout.LayoutParams(0,-2,1));
        Button refresh=button("刷新",this::reload);bar.addView(refresh,new LinearLayout.LayoutParams(dp(64),dp(48)));page.addView(bar);
        search=new EditText(activity);search.setTextColor(INK);search.setHintTextColor(MUTED);search.setTextSize(15);search.setSingleLine(true);search.setHint("搜索网易云专辑");search.setContentDescription("搜索网易云专辑");search.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        search.setTypeface(Typeface.create("sans-serif",Typeface.NORMAL));search.setBackground(DesignSystem.settingsCard(activity,20));search.setPadding(dp(16),0,dp(16),0);LinearLayout.LayoutParams searchLp=new LinearLayout.LayoutParams(-1,dp(48));searchLp.setMargins(dp(18),0,dp(18),dp(8));page.addView(search,searchLp);
        suppressSearch=true;search.setText(query);suppressSearch=false;
        search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int count,int after){}public void afterTextChanged(Editable e){}public void onTextChanged(CharSequence s,int st,int before,int count){if(suppressSearch||closed)return;restoringAlbumId="";query=s.toString().trim();mode=query.isEmpty()?HOME:SEARCH;restoreOffset=0;pendingScroll=null;main.removeCallbacks(searchDelay);invalidateRequest();main.postDelayed(searchDelay,350);}});
        search.setOnEditorActionListener((v,action,event)->{if(action==EditorInfo.IME_ACTION_SEARCH){main.removeCallbacks(searchDelay);searchAlbums(false);return true;}return false;});
        list=new ListView(activity);list.setDivider(null);list.setDividerHeight(0);list.setSelector(new ColorDrawable(0x14736578));list.setCacheColorHint(Color.TRANSPARENT);list.setClipToPadding(false);list.setPadding(dp(18),dp(8),dp(18),dp(28));list.setContentDescription("网易云音乐内容");list.setAdapter(adapter);page.addView(list,new LinearLayout.LayoutParams(-1,0,1));
        dialog.setContentView(page);dialog.setOnDismissListener(d->close());dialog.show();Window window=dialog.getWindow();
        if(window!=null){window.setLayout(-1,-1);refreshTheme();}
        page.requestApplyInsets();
        if(restoredState&&!pendingAlbumId.isEmpty()){
            restoringAlbumId=pendingAlbumId;restoringParentScroll=pendingScroll;pendingAlbumId="";reload();
        }else if(!pendingAlbumId.isEmpty())showAlbum(pendingAlbumId);else reload();
        return dialog;
    }
    private void back(){
        restoringAlbumId="";
        if(mode==HOME){close();return;}
        int destination=mode==ARTIST?artistBackMode:HOME;
        pendingScroll=null;pendingAlbumId="";restoreOffset=0;
        if(destination==SEARCH){mode=SEARCH;pendingScroll=searchBackScroll;searchBackScroll=null;if(!searchResults.isEmpty()){invalidateRequest();renderSearch();}else{restoreOffset=nextOffset;searchAlbums(false);}}else{mode=HOME;query="";setSearchText("");loadHome();}
    }
    private void setSearchText(String value){if(search!=null){suppressSearch=true;search.setText(value);suppressSearch=false;}}
    private void reload(){if(closed)return;switch(mode){case SEARCH:searchAlbums(false);break;case ARTIST:showArtist(artistId);break;case LOGIN:login();break;default:loadHome();}}
    private void invalidateRequest(){generation++;main.removeCallbacks(pollDelay);if(request!=null)request.cancel(true);request=null;busy=false;}
    private interface Work<T>{T run()throws Exception;}
    private interface Result<T>{void accept(T result);}
    private <T>void run(Work<T> work,Result<T> done,Runnable retry){
        invalidateRequest();main.removeCallbacks(searchDelay);final long token=generation;busy=true;
        request=worker.submit(()->{
            try{T result=work.run();main.post(()->{if(!active(token))return;busy=false;done.accept(result);});}
            catch(Exception failure){main.post(()->{if(!active(token))return;busy=false;showError(failure,retry);});}
        });
    }
    private boolean active(long token){return !closed&&generation==token&&!activity.isFinishing()&&!activity.isDestroyed();}
    private void loading(String label){rows.clear();rows.add(Row.text(label,"",true));update(false);}
    private void update(boolean restore){
        if(list==null)return;heading.setText(mode==ARTIST&&artist!=null?artist.name:mode==LOGIN?"扫码登录":"网易云音乐");
        search.setVisibility(mode==ARTIST||mode==LOGIN?View.GONE:View.VISIBLE);adapter.notifyDataSetChanged();
        if(restore&&pendingScroll!=null){Bundle state=pendingScroll;pendingScroll=null;ViewScrollState.restore(list,state);}
    }
    private void showError(Exception failure,Runnable retry){
        boolean login=failure instanceof ProviderException&&(((ProviderException)failure).kind==ProviderException.Kind.LOGIN_REQUIRED||((ProviderException)failure).kind==ProviderException.Kind.LOGIN_EXPIRED);
        boolean challenge=failure instanceof ProviderException&&((ProviderException)failure).kind==ProviderException.Kind.LOGIN_CHALLENGE;
        String message=failure instanceof ProviderException?failure.getMessage():"操作暂时未完成，请重试";
        rows.clear();rows.add(Row.text(challenge?"扫码授权未通过":"暂时无法完成",message==null?"请稍后重试":message,true));
        if(challenge){qr=null;qrBitmap=null;main.removeCallbacks(pollDelay);rows.add(Row.control("官方网页登录","在网易云官方页面完成登录或验证，再自动返回应用",this::officialLogin));}
        rows.add(Row.action("重试","",retry));if(login)rows.add(Row.action("重新扫码登录","",this::login));
        if(!challenge&&(mode==LOGIN||login))rows.add(Row.control("官方网页登录","在网易云官方页面完成验证",this::officialLogin));update(false);
    }
    private void loadHome(){
        mode=HOME;artist=null;qr=null;qrBitmap=null;pendingAlbumId="";loading("正在读取网易云账户…");
        run(provider::account,value->{
            account=value;playlists.clear();actions.onAccount(value);
            if(value.loggedIn)loadPlaylists(value);else renderHome();
        },this::loadHome);
    }
    private void loadPlaylists(MusicProvider.Account identity){
        run(()->provider.playlists(identity.id),values->{playlists=new ArrayList<>(values);renderHome();},()->loadPlaylists(identity));
    }
    private void continueRestoredAlbum(){
        if(restoringAlbumId.isEmpty())return;
        String id=restoringAlbumId;restoringAlbumId="";pendingScroll=restoringParentScroll;restoringParentScroll=null;showAlbum(id);
    }
    private void renderHome(){
        rows.clear();
        if(account==null||!account.loggedIn){rows.add(Row.text("连接你的网易云音乐","完成登录后读取歌单与喜欢的歌曲。",true));rows.add(Row.action("官方网页登录","在网易云官方页面完成登录与验证",this::officialLogin));rows.add(Row.action("扫码登录","使用网易云音乐 App 扫码授权",this::login));rows.add(Row.text("专辑搜索","可在上方搜索网易云专辑；播放能力由账户权限和歌曲授权决定。",false));update(true);continueRestoredAlbum();return;}
        rows.add(Row.image(account.nickname,"网易云账户 · "+(account.vipType>0?"会员账户":"普通账户"),account.avatarUrl,null));
        rows.add(Row.action("喜欢的歌曲","读取账户中的完整喜欢列表",()->openCollection("喜欢的歌曲",()->provider.likedTracks(account.id))));
        rows.add(Row.action(tab==0?"我创建的歌单 · 已选":"我创建的歌单","",()->{tab=0;renderHome();}));
        rows.add(Row.action(tab==1?"收藏的歌单 · 已选":"收藏的歌单","",()->{tab=1;renderHome();}));
        int count=0;for(MusicProvider.Playlist p:playlists)if(p.owned==(tab==0)){count++;rows.add(Row.image(p.name,p.trackCount+" 首歌曲"+(p.description.isEmpty()?"":" · "+p.description),p.coverUrl,()->openCollection(p.name,()->provider.playlistTracks(p.id))));}
        if(count==0)rows.add(Row.text("暂无"+(tab==0?"创建":"收藏")+"的歌单","",false));
        rows.add(Row.action("扫码切换账号","只有新的扫码授权通过账户验证后才会替换当前登录",this::login));
        rows.add(Row.action("退出网易云账户","清除本应用保存的登录凭据",this::logout));update(true);continueRestoredAlbum();
    }
    private void logout(){
        restoringAlbumId="";invalidateRequest();main.removeCallbacks(searchDelay);final long token=generation;busy=true;loading("正在退出网易云账户…");
        // Logout commits identity state independently of this cancelable browsing page.
        OnlineMusic.logout(activity.getApplicationContext(),provider,(committed,failure)->{
            if(!active(token))return;busy=false;
            if(committed){account=null;playlists.clear();actions.onLogout();}
            if(failure!=null)showError(failure,this::logout);else loadHome();
        });
    }
    private void openCollection(String title,Work<List<Track>> work){
        loading("正在读取“"+title+"”的完整曲目…");run(work,tracks->{renderHome();if(tracks.isEmpty()){rows.add(0,Row.text(title+"暂无可显示的歌曲","",false));update(false);}else actions.openTracks(new ArrayList<>(tracks),title);},()->openCollection(title,work));
    }
    public void showAlbum(String id){
        pendingAlbumId=id==null?"":id;if(dialog==null)return;
        if(pendingAlbumId.isEmpty()){showError(new ProviderException(ProviderException.Kind.INVALID_RESPONSE,"专辑编号为空"),this::reload);return;}
        // A temporary loading row must not replace the collection's return position.
        if(pendingScroll==null&&list!=null)pendingScroll=ViewScrollState.capture(list);
        final String requested=pendingAlbumId;loading("正在读取专辑曲目…");
        run(()->provider.album(requested),album->{pendingAlbumId="";if(mode==HOME&&account==null)loadHome();else renderCurrent();if(album.tracks.isEmpty()){rows.add(0,Row.text("这张专辑暂未返回曲目",album.name,false));update(false);}else actions.openTracks(new ArrayList<>(album.tracks),null);},()->showAlbum(requested));
    }
    private void renderCurrent(){if(mode==SEARCH)renderSearch();else if(mode==ARTIST&&artist!=null)renderArtist();else renderHome();}
    private static final class SearchResult {
        final ArrayList<MusicProvider.Album> albums;final int offset;final boolean more;
        SearchResult(ArrayList<MusicProvider.Album> albums,int offset,boolean more){this.albums=albums;this.offset=offset;this.more=more;}
    }
    private void searchAlbums(boolean append){
        if(closed)return;if(query.isEmpty()){loadHome();return;}mode=SEARCH;qr=null;qrBitmap=null;artist=null;pendingAlbumId="";
        final String requested=query;final int first=append?nextOffset:0;final int until=append?first:restoreOffset;
        if(!append){searchResults.clear();nextOffset=0;loading("正在搜索专辑…");}else{rows.add(Row.text("正在读取更多专辑…","",false));update(false);}
        run(()->{
            ArrayList<MusicProvider.Album> fetched=new ArrayList<>();HashSet<String> seen=new HashSet<>();int offset=first;boolean more;
            do{MusicProvider.Page<MusicProvider.Album> page=provider.searchAlbums(requested,offset,30);
                for(MusicProvider.Album album:page.items)if(seen.add(album.id))fetched.add(album);
                more=page.hasMore;if(more&&page.nextOffset<=offset)throw new ProviderException(ProviderException.Kind.INVALID_RESPONSE,"专辑分页没有前进，请重试");offset=page.nextOffset;
            }while(more&&offset<until&&!Thread.currentThread().isInterrupted());
            if(Thread.currentThread().isInterrupted())throw new InterruptedException();return new SearchResult(fetched,offset,more);
        },result->{HashSet<String> seen=new HashSet<>();for(MusicProvider.Album a:searchResults)seen.add(a.id);for(MusicProvider.Album a:result.albums)if(seen.add(a.id))searchResults.add(a);nextOffset=result.offset;searchMore=result.more;restoreOffset=0;renderSearch();},()->searchAlbums(append));
    }
    private void renderSearch(){
        rows.clear();rows.add(Row.text("“"+query+"”的专辑",searchResults.size()+" 张已读取",true));
        for(MusicProvider.Album album:searchResults){rows.add(Row.image(album.name,album.artist,album.coverUrl,()->showAlbum(album.id)));if(!album.artistId.isEmpty())rows.add(Row.action("查看音乐人 "+album.artist,"",()->showArtist(album.artistId)));}
        if(searchResults.isEmpty())rows.add(Row.text("没有找到匹配专辑","换一个专辑名或音乐人名称试试。",false));
        if(searchMore)rows.add(Row.action("加载更多专辑","",()->searchAlbums(true)));update(true);continueRestoredAlbum();
    }
    public void showArtist(String id){
        if(mode!=ARTIST){artistBackMode=mode==SEARCH?SEARCH:HOME;if(mode==SEARCH&&list!=null)searchBackScroll=ViewScrollState.capture(list);}
        mode=ARTIST;if(!artistId.equals(id))artist=null;artistId=id==null?"":id;pendingAlbumId="";if(dialog==null)return;
        final String requested=artistId;loading("正在读取音乐人资料…");run(()->provider.artist(requested),value->{artist=value;renderArtist();},()->showArtist(requested));
    }
    private void renderArtist(){
        rows.clear();rows.add(Row.image(artist.name,"网易云音乐人",artist.avatarUrl,null));
        rows.add(Row.text("音乐人简介",artist.description.isEmpty()?"网易云暂未提供简介":artist.description,false));
        if(!artist.topTracks.isEmpty()){
            rows.add(Row.action("播放热门歌曲",artist.topTracks.size()+" 首",()->actions.play(new ArrayList<>(artist.topTracks),0)));
            for(int i=0;i<artist.topTracks.size();i++){final int at=i;Track track=artist.topTracks.get(i);rows.add(Row.image(track.title,track.album,track.artworkUri,()->actions.play(new ArrayList<>(artist.topTracks),at)));}
        }else rows.add(Row.text("暂未返回热门歌曲","",false));
        rows.add(Row.text("专辑",artist.albums.size()+" 张",true));for(MusicProvider.Album album:artist.albums)rows.add(Row.image(album.name,album.artist,album.coverUrl,()->showAlbum(album.id)));
        update(true);continueRestoredAlbum();
    }
    private static final class LoginImage {final MusicProvider.QrLogin qr;final Bitmap image;LoginImage(MusicProvider.QrLogin qr,Bitmap image){this.qr=qr;this.image=image;}}
    private void login(){
        restoringAlbumId="";mode=LOGIN;qr=null;qrBitmap=null;pendingAlbumId="";loading("正在生成网易云登录二维码…");
        run(()->{MusicProvider.QrLogin code=provider.beginQrLogin();HashMap<EncodeHintType,Object> hints=new HashMap<>();hints.put(EncodeHintType.MARGIN,2);hints.put(EncodeHintType.CHARACTER_SET,"UTF-8");BitMatrix matrix=new QRCodeWriter().encode(code.url,BarcodeFormat.QR_CODE,320,320,hints);int[] pixels=new int[320*320];for(int y=0;y<320;y++)for(int x=0;x<320;x++)pixels[y*320+x]=matrix.get(x,y)?Color.BLACK:Color.WHITE;Bitmap image=Bitmap.createBitmap(pixels,320,320,Bitmap.Config.ARGB_8888);return new LoginImage(code,image);},value->{qr=value.qr;qrBitmap=value.image;qrStatus="请使用网易云音乐扫描二维码";renderQr();main.postDelayed(pollDelay,1800);},this::login);
    }
    private void renderQr(){rows.clear();rows.add(Row.text("网易云扫码登录",qrStatus,true));if(qrBitmap!=null)rows.add(Row.qr(qrBitmap));rows.add(Row.text("请在网易云音乐中确认授权","登录凭据仅保存在本机。二维码过期后可重新生成。",false));rows.add(Row.control("刷新二维码","",this::login));rows.add(Row.control("官方网页登录","如扫码要求额外验证，可前往网易云官方页面",this::officialLogin));update(false);}
    private void pollQr(){
        if(closed||mode!=LOGIN||qr==null)return;
        if(System.currentTimeMillis()>=qr.expiresAtMs){qrStatus="二维码已过期，请刷新";qrBitmap=null;renderQr();return;}
        final String key=qr.key;
        run(()->provider.pollQrLogin(key),result->{
            switch(result.status){
                case SUCCESS:qr=null;qrBitmap=null;mode=HOME;loadHome();break;
                case EXPIRED:qrStatus="二维码已过期，请刷新";qrBitmap=null;renderQr();break;
                default:qrStatus=result.status==MusicProvider.LoginStatus.WAITING_CONFIRM?"请在网易云音乐中确认登录":"请使用网易云音乐扫描二维码";renderQr();main.postDelayed(pollDelay,1800);break;
            }
        },this::login);
    }
    public void close(){
        if(closed)return;closed=true;invalidateRequest();main.removeCallbacksAndMessages(null);worker.shutdownNow();qr=null;qrBitmap=null;
        if(dialog!=null&&dialog.isShowing())dialog.dismiss();Runnable callback=onClosed;onClosed=null;if(callback!=null)callback.run();
    }
    private static final class Row {
        final int type;final String title,subtitle,image;final boolean large,interrupt;final Runnable click;final Bitmap qr;
        private Row(int type,String title,String subtitle,String image,boolean large,Runnable click,Bitmap qr,boolean interrupt){this.type=type;this.title=title;this.subtitle=subtitle;this.image=image;this.large=large;this.click=click;this.qr=qr;this.interrupt=interrupt;}
        static Row text(String t,String s,boolean large){return new Row(TEXT,t,s,"",large,null,null,false);}
        static Row action(String t,String s,Runnable click){return new Row(ACTION,t,s,"",false,click,null,false);}
        static Row control(String t,String s,Runnable click){return new Row(ACTION,t,s,"",false,click,null,true);}
        static Row image(String t,String s,String image,Runnable click){return new Row(IMAGE_ACTION,t,s,image,false,click,null,false);}
        static Row qr(Bitmap image){return new Row(QR,"","","",false,null,image,false);}
    }
    private final class Rows extends BaseAdapter {
        public int getCount(){return rows.size();}public Object getItem(int position){return rows.get(position);}public long getItemId(int position){return position;}
        public int getViewTypeCount(){return 4;}public int getItemViewType(int position){return rows.get(position).type;}
        public boolean areAllItemsEnabled(){return false;}public boolean isEnabled(int position){return rows.get(position).click!=null;}
        public View getView(int position,View recycled,ViewGroup parent){
            Row row=rows.get(position);
            if(row.type==QR){ImageView image=recycled instanceof ImageView?(ImageView)recycled:new ImageView(activity);image.setImageBitmap(row.qr);image.setAdjustViewBounds(true);image.setScaleType(ImageView.ScaleType.FIT_CENTER);image.setPadding(dp(8),dp(12),dp(8),dp(12));image.setContentDescription("网易云登录二维码");image.setLayoutParams(new android.widget.AbsListView.LayoutParams(-1,dp(280)));return image;}
            Cell cell=recycled instanceof Cell?(Cell)recycled:new Cell(row.type==IMAGE_ACTION);cell.bind(row);return cell;
        }
    }
    private final class Cell extends LinearLayout {
        final TextView title,detail;final CoverView image;
        Cell(boolean artwork){super(activity);setGravity(Gravity.CENTER_VERTICAL);setPadding(dp(10),dp(13),dp(10),dp(13));setMinimumHeight(dp(54));
            if(artwork){image=new CoverView(activity);addView(image,new LinearLayout.LayoutParams(dp(54),dp(54)));}else image=null;
            LinearLayout labels=column();if(artwork)labels.setPadding(dp(13),0,0,0);title=text("",15,INK);title.setMaxLines(3);title.setEllipsize(TextUtils.TruncateAt.END);labels.addView(title);detail=text("",12,MUTED);detail.setPadding(0,dp(5),0,0);labels.addView(detail);addView(labels,new LinearLayout.LayoutParams(0,-2,1));
        }
        void bind(Row row){title.setText(row.title);title.setTextSize(row.large?22:15);title.setTypeface(Typeface.create("sans-serif",row.large?Typeface.BOLD:Typeface.NORMAL));detail.setText(row.subtitle);detail.setVisibility(row.subtitle.isEmpty()?View.GONE:View.VISIBLE);detail.setMaxLines(row.type==TEXT?Integer.MAX_VALUE:3);detail.setEllipsize(row.type==TEXT?null:TextUtils.TruncateAt.END);
            if(image!=null){if(row.image==null||row.image.isEmpty())image.clear();else image.setSource(row.image,160);}
            setBackground(row.type==ACTION?DesignSystem.settingsCard(activity,18):null);setFocusable(row.click!=null);setClickable(row.click!=null);setContentDescription(row.title);final long bound=generation;setOnClickListener(row.click==null?null:v->{if(!closed&&(row.interrupt&&mode==LOGIN||!busy&&bound==generation))row.click.run();});
        }
    }
    private LinearLayout column(){LinearLayout value=new LinearLayout(activity);value.setOrientation(LinearLayout.VERTICAL);return value;}
    private TextView text(String value,int size,int color){TextView view=new TextView(activity);view.setText(value);view.setTypeface(Typeface.create("sans-serif",Typeface.NORMAL));view.setTextSize(size);view.setTextColor(color);return view;}
    private Button button(String label,Runnable click){Button button=new Button(activity);button.setText(label);button.setTypeface(Typeface.create("sans-serif",Typeface.NORMAL));button.setTextColor(INK);button.setTextSize(14);button.setAllCaps(false);button.setBackgroundColor(Color.TRANSPARENT);button.setMinWidth(0);button.setMinimumWidth(0);button.setPadding(dp(8),0,dp(8),0);button.setOnClickListener(v->click.run());return button;}
    public void refreshTheme(){if(dialog==null||dialog.getWindow()==null)return;Window window=dialog.getWindow();window.setStatusBarColor(DesignSystem.ambientColor(activity));window.setNavigationBarColor(DesignSystem.ambientColor(activity));window.getDecorView().setSystemUiVisibility(0);}
    private int dp(float value){return Math.round(value*activity.getResources().getDisplayMetrics().density);}
}
