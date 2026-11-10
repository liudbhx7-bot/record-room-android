/*
 * QQ client-protocol adaptation of Mineradio, Copyright (C) 2026 XxHuberrr.
 * Reference: XxHuberrr/Mineradio-paused d43de565acabfdc1a9c9820a27e81a98ccbebcef,
 * server.js QQ cookie/catalog/search/vkey routines. Modified for Android on 2026-11-09.
 * SPDX-License-Identifier: GPL-3.0-only. See assets/licenses/mineradio-GPL-3.0.txt.
 * Changes: device-bound sessions, strict account verification and response identity,
 * complete pagination, single standard quality, explicit unsupported capabilities,
 * no fallback to other recordings, and actual MP3-duration verification before playback.
 */
package com.recordroom.vinyl;

import android.content.Context;
import android.util.Base64;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** User-authorized QQ web/client interoperability, not an official public SDK. All I/O is off the UI thread. */
public final class QQProvider implements MusicProvider {
    private static final String MUSICU="https://u.y.qq.com/cgi-bin/musicu.fcg";
    public static final String LIKED_PLAYLIST_ID="qq-liked";
    private static final String SYSTEM_PLAYLIST_PREFIX="qq-system-";
    private static final int PAGE=200, MAX_PAGES=250;
    private final ProviderSession session;private final QQHttp http;private final QQAudioProbe audio;
    public QQProvider(Context context){this(new QQSessionStore(context));}
    public QQProvider(ProviderSession session){this(session,new QQHttp(),new QQAudioProbe());}
    QQProvider(ProviderSession session,QQHttp http,QQAudioProbe audio){this.session=session;this.http=http;this.audio=audio;}
    @Override public String id(){return "qq";}
    public static String officialLoginUrl(){return "https://y.qq.com/n/ryqq/profile";}
    @Override public QrLogin beginQrLogin()throws ProviderException{throw webLoginRequired();}
    @Override public LoginPoll pollQrLogin(String key)throws ProviderException{throw webLoginRequired();}
    private static ProviderException webLoginRequired(){return new ProviderException(ProviderException.Kind.LOGIN_CHALLENGE,"请在 QQ 音乐官方网页完成 QQ 或微信授权；此接入不生成自建二维码");}

    /** Cancellation and the final credential commit are strictly ordered by this attempt's monitor. */
    public static final class LoginAttempt {
        private boolean active=true;
        public LoginAttempt(){}
        public synchronized void cancel(){active=false;}
        private synchronized void checkActive()throws ProviderException{
            if(!active||Thread.currentThread().isInterrupted())throw new ProviderException(ProviderException.Kind.LOGIN_EXPIRED,"已取消 QQ 音乐授权，请重新操作");
        }
    }

    /** Candidate cookies are never persisted until the authenticated liked-list and profile checks succeed. */
    public Account acceptOfficialWebCookies(String cookies)throws ProviderException{
        return acceptOfficialWebCookies(cookies,new LoginAttempt());
    }
    public Account acceptOfficialWebCookies(String cookies,LoginAttempt attempt)throws ProviderException{
        if(attempt==null)throw QQHttp.invalid();attempt.checkActive();
        long epoch=session.generation();Auth candidate=Auth.parse(cookies,epoch,false,true);
        Account account=verifyAccount(candidate);
        synchronized(attempt){synchronized(session){attempt.checkActive();checkEpoch(candidate);session.writeCookies(candidate.cookies);}}
        return account;
    }
    @Override public Account account()throws ProviderException{
        Auth auth=auth(false);return auth==null?new Account("","QQ音乐","",-1,false):verifyAccount(auth);
    }
    private Account verifyAccount(Auth auth)throws ProviderException{
        JSONObject liked=likedPage(auth,0,1);verifyOwner(object(liked,"dirinfo"),auth.uin);
        // The current QQ web client uses userid=0 for the authenticated self. A WeChat
        // music uin is not the public/obfuscated profile address accepted by userid.
        JSONObject result=get("/rsc/fcgi-bin/fcg_get_profile_homepage.fcg",json("cid","205360838","ct",24,"userid",0,"reqfrom",1,"reqtype",0,"loginUin",auth.uin,"hostUin",0),auth);
        JSONObject data=requiredObject(result,"data"),creator=data.optJSONObject("creator");if(creator==null)creator=data.optJSONObject("user");if(creator==null)throw QQHttp.invalid();
        verifyOwner(creator,auth.uin);
        String likedAddress=profileAddress(object(liked,"dirinfo")),selfAddress=profileAddress(creator);
        if(!likedAddress.isEmpty()&&!selfAddress.isEmpty()&&!likedAddress.equals(selfAddress))throw accountMismatch();
        String name=first(creator,"nick","nickname","name","hostname"),avatar=first(creator,"headpic","avatar","avatarUrl");
        if(name.isEmpty()&&first(creator,"uin","wxuin","encrypt_uin").isEmpty())throw QQHttp.invalid();
        if(name.isEmpty())name="QQ "+auth.uin;
        // A WeChat music uin is not necessarily a QQ number; missing artwork stays missing.
        // Membership metadata is not inferred from cookies, playable songs, or numeric level fields.
        return new Account(auth.uin,name,httpsArtwork(avatar),-1,true);
    }
    @Override public List<Playlist> playlists(String userId)throws ProviderException{
        Auth auth=auth(true);ownUser(auth,userId);LinkedHashMap<String,Playlist> result=new LinkedHashMap<>();
        try{
            JSONObject liked=likedPage(auth,0,1);JSONArray songs=array(liked,"songlist");String cover=songs.length()>0?track(songs.optJSONObject(0)).artworkUri:"";
            result.put(LIKED_PLAYLIST_ID,new Playlist(LIKED_PLAYLIST_ID,"我的喜欢","",cover,auth.uin,true,Math.max(0,liked.optInt("total_song_num",songs.length()))));
        }catch(ProviderException failure){throw playlistStage("读取喜欢列表摘要",failure);}
        playlistPages(auth,false,result);playlistPages(auth,true,result);return new ArrayList<>(result.values());
    }
    private void playlistPages(Auth auth,boolean collected,Map<String,Playlist> out)throws ProviderException{
        String stage=collected?"读取收藏歌单":"读取本人歌单";
        try{
            Set<String> pages=new HashSet<>();int offset=0,knownTotal=-1;
            for(int page=0;page<MAX_PAGES;page++){
                String path=collected?"/fav/fcgi-bin/fcg_get_profile_order_asset.fcg":"/rsc/fcgi-bin/fcg_user_created_diss";
                JSONObject parameters=collected?json("ct",20,"cid","205360956","userid",auth.uin,"reqtype",3,"sin",offset,"ein",offset+PAGE-1):json("hostUin",0,"hostuin",auth.uin,"sin",offset,"size",PAGE,"loginUin",auth.uin);
                JSONObject response=get(path,parameters,auth),data=response.optJSONObject("data");
                if(data==null)throw playlistShape(page,-1,"data 未返回对象");
                String field=collected?"cdlist":"disslist";JSONArray rows=data.optJSONArray(field);
                if(rows==null)throw playlistShape(page,-1,"data."+field+" 未返回数组");
                if(rows.length()>0&&!pages.add(rows.toString()))throw incomplete();
                for(int i=0;i<rows.length();i++){
                    JSONObject row=rows.optJSONObject(i);if(row==null)throw playlistShape(page,i,"歌单条目未返回对象");
                    int directory=row.optInt("dirid",row.optInt("dir_id",0));
                    int count=row.optInt("song_cnt",row.optInt("songnum",row.optInt("total_song_num",row.optInt("song_count",row.optInt("song_num",-1)))));
                    // QQ's published created-list parser excludes empty system directories.
                    // They may legitimately have no tid or ordinary playlist title.
                    if(!collected&&directory>200&&directory<210&&(directory==201||count<=0))continue;
                    String id=firstId(row,"dissid","tid","mid","diss_id","id"),name=first(row,"dissname","diss_name","title","name");
                    String cover=httpsArtwork(first(row,"imgurl","logo","pic","diss_cover","albumPic","picurl","cover"));
                    if(!collected&&(directory==205||directory==206)&&(id.isEmpty()||"0".equals(id)||Integer.toString(directory).equals(id))){
                        // These official folders use dedicated Qzone/upload services, not a dissid.
                        // Preserve the visible metadata and explain the unsupported content on open.
                        id=SYSTEM_PLAYLIST_PREFIX+directory;
                        if(name.isEmpty())name=directory==205?"Qzone 背景音乐":"本地上传";
                        out.put(id,new Playlist(id,name,"QQ 系统目录，请在官方音乐应用查看",cover,auth.uin,true,count));continue;
                    }
                    if(id.isEmpty())throw playlistShape(page,i,"缺少歌单编号（dissid / tid）");
                    if(!id.matches("[1-9][0-9]{0,18}"))throw playlistShape(page,i,"歌单编号格式不受支持");
                    if(name.isEmpty())throw playlistShape(page,i,"缺少歌单名称（dissname / diss_name）");
                    if(!out.containsKey(id))out.put(id,new Playlist(id,name,"",cover,collected?"":auth.uin,!collected,Math.max(-1,count)));
                }
                // `totoal` is the official spelling. Advance by all raw rows, including
                // filtered system folders, so pagination does not repeat or skip entries.
                int total=data.optInt(collected?"totaldiss":"totoal",data.optInt("total",data.optInt("diss_num",-1)));
                if(knownTotal>=0&&total>=0&&knownTotal!=total)throw incomplete();if(total>=0)knownTotal=total;
                offset+=rows.length();
                if(knownTotal>=0&&offset>=knownTotal)return;
                if(rows.length()==0){if(knownTotal>offset)throw incomplete();return;}
                if(knownTotal<0&&rows.length()<PAGE)return;
            }
            throw incomplete();
        }catch(ProviderException failure){throw playlistStage(stage,failure);}
    }
    private static ProviderException playlistShape(int page,int row,String reason){
        return new ProviderException(ProviderException.Kind.INVALID_RESPONSE,"第 "+(page+1)+" 页"+(row>=0?"第 "+(row+1)+" 项":"")+"："+reason+"。请重试；仍失败请反馈此提示。");
    }
    private static ProviderException playlistStage(String stage,ProviderException failure){
        String message=failure.getMessage();if(message!=null&&message.startsWith(stage+"失败："))return failure;
        return new ProviderException(failure.kind,stage+"失败："+(message==null?"未返回可识别的数据，请重试":message));
    }
    @Override public List<Track> likedTracks(String userId)throws ProviderException{Auth auth=auth(true);ownUser(auth,userId);return tracks(auth,LIKED_PLAYLIST_ID);}
    @Override public List<Track> playlistTracks(String playlistId)throws ProviderException{
        Auth auth=auth(true);
        if((SYSTEM_PLAYLIST_PREFIX+205).equals(playlistId)||(SYSTEM_PLAYLIST_PREFIX+206).equals(playlistId))throw new ProviderException(ProviderException.Kind.UNAVAILABLE,"这是 QQ 音乐的"+((SYSTEM_PLAYLIST_PREFIX+205).equals(playlistId)?"Qzone 背景音乐":"本地上传")+"系统目录，当前接入未支持其中的曲目，请到 QQ 音乐官方应用查看；其他歌单可正常尝试读取");
        if(!LIKED_PLAYLIST_ID.equals(playlistId))numericId(playlistId);return tracks(auth,playlistId);
    }
    private List<Track> tracks(Auth auth,String playlistId)throws ProviderException{
        boolean liked=LIKED_PLAYLIST_ID.equals(playlistId);int pageSize=liked?100:PAGE,offset=0;ArrayList<Track> result=new ArrayList<>();Set<String> pages=new HashSet<>();
        for(int page=0;page<MAX_PAGES;page++){
            JSONObject detail;
            if(liked)detail=likedPage(auth,offset,pageSize);
            else{
                JSONObject body=get("/qzone/fcg-bin/fcg_ucc_getcdinfo_byids_cp.fcg",json("type",1,"utf8",1,"disstid",playlistId,"song_begin",offset,"song_num",pageSize,"loginUin",auth.uin),auth);
                JSONArray list=requiredArray(body,"cdlist");if(list.length()!=1||list.optJSONObject(0)==null)throw QQHttp.invalid();detail=list.optJSONObject(0);
                String returned=first(detail,"disstid","dissid","diss_id");if(!returned.isEmpty()&&!playlistId.equals(returned))throw QQHttp.invalid();
            }
            JSONArray rows=requiredArray(detail,"songlist");int total=detail.optInt("total_song_num",detail.optInt("songnum",detail.optInt("song_cnt",detail.optInt("song_count",-1))));
            if(detail.optInt("songlist_size",rows.length())>rows.length())throw incomplete();
            if(rows.length()>0&&!pages.add(rows.toString()))throw incomplete();
            for(int i=0;i<rows.length();i++)result.add(track(rows.optJSONObject(i)));
            offset+=rows.length();boolean more=detail.optInt("hasmore",0)>0;
            if(total>=0&&offset>=total){if(offset!=total)throw incomplete();return result;}
            if(rows.length()==0||(!more&&rows.length()<pageSize)){if(total>offset||more)throw incomplete();return result;}
        }
        throw incomplete();
    }
    private JSONObject likedPage(Auth auth,int offset,int limit)throws ProviderException{
        JSONObject data=music("req_0","music.srfDissInfo.DissInfo","CgiGetDiss",json("disstid",0,"dirid",201,"tag",1,"song_begin",offset,"song_num",limit,"userinfo",1,"orderlist",1),auth);
        requiredArray(data,"songlist");verifyOwner(object(data,"dirinfo"),auth.uin);return data;
    }

    /** The catalog endpoint searches all available results, using this session when it exists. */
    @Override public Page<Track> searchTracks(String query,int offset,int limit)throws ProviderException{
        JSONObject data=searchData(query,offset,limit,0),body=object(data,"body");
        JSONArray rows=body.optJSONArray("item_song");if(rows==null)rows=object(body,"song").optJSONArray("list");if(rows==null)rows=body.optJSONArray("list");if(rows==null)throw QQHttp.invalid();
        ArrayList<Track> tracks=new ArrayList<>();for(int i=0;i<rows.length();i++)tracks.add(track(rows.optJSONObject(i)));
        return searchPage(data,body,tracks,offset,limit);
    }
    @Override public boolean supportsArtistSearch(){return true;}
    @Override public Page<Artist> searchArtists(String query,int offset,int limit)throws ProviderException{
        // Public mobile search type 1 returns artist entities in body.singer, not song singers.
        JSONObject data=searchData(query,offset,limit,1),body=requiredObject(data,"body");
        JSONArray rows=requiredArray(body,"singer");List<Artist> artists=new ArrayList<>();Set<String> ids=new HashSet<>();
        for(int i=0;i<rows.length();i++){
            JSONObject row=rows.optJSONObject(i);if(row==null)throw QQHttp.invalid();
            String id=first(row,"singerMID"),name=first(row,"singerName");mid(id);
            if(name.trim().isEmpty()||!ids.add(id))throw QQHttp.invalid();
            artists.add(new Artist(id,name,"",httpsArtwork(first(row,"singerPic")),Collections.emptyList(),Collections.emptyList()));
        }
        return searchPage(data,body,artists,offset,limit);
    }
    private JSONObject searchData(String query,int offset,int limit,int type)throws ProviderException{
        if(query==null||query.trim().isEmpty()||query.length()>512||offset<0||limit<1||limit>30)throw QQHttp.invalid();
        // Mobile endpoint pagination is page based; offsets must be aligned so results cannot repeat silently.
        if(offset%limit!=0)throw new ProviderException(ProviderException.Kind.INVALID_RESPONSE,"QQ 音乐搜索分页位置无效");
        JSONObject comm=json("ct","11","cv","14090508","v","14090508","tmeAppID","qqmusic","phonetype","EBG-AN10","os_ver","12","OpenUDID","0","QIMEI36","0","udid","0","chid","0","aid","0","oaid","0","taid","0","tid","0","wid","0","uid","0","sid","0","modeSwitch","6","teenMode","0","ui_mode","2","nettype","1020");
        JSONObject param=json("search_type",type,"searchid",Long.toString(System.currentTimeMillis()),"query",query.trim(),"page_num",offset/limit+1,"num_per_page",limit,"highlight",0,"nqc_flag",0,"multi_zhida",0,"cat",2,"grp",1,"sin",offset,"sem",0);
        JSONObject payload=json("comm",comm,"req",json("module","music.search.SearchCgiService","method","DoSearchForQQMusicMobile","param",param));
        Auth auth=auth(false);if(auth!=null){put(comm,"uin",auth.uin);put(comm,"authst",auth.playbackKey);}
        JSONObject response=request("https://u.y.qq.com/cgi-bin/musics.fcg?sign="+searchSign(payload.toString()),payload,auth);
        JSONObject data=block(response,"req");requiredObject(data,"body");
        if(object(data,"meta").optInt("is_filter",0)<0)throw new ProviderException(ProviderException.Kind.UNAVAILABLE,"QQ 音乐限制了这次搜索，请完成官方授权或稍后重试；未将受限结果显示为没有歌曲");
        return data;
    }
    private static <T> Page<T> searchPage(JSONObject data,JSONObject body,List<T> items,int offset,int limit)throws ProviderException{
        int total=object(data,"meta").optInt("sum",body.optInt("sum",body.optInt("totalnum",-1)));int received=offset+items.size();boolean more=total>=0?received<total:items.size()==limit;
        if(items.size()>limit||items.isEmpty()&&more)throw incomplete();return new Page<>(items,total,more?offset+limit:received,more);
    }
    @Override public Page<Album> searchAlbums(String query,int offset,int limit)throws ProviderException{
        JSONObject data=searchData(query,offset,limit,2),body=requiredObject(data,"body");JSONArray rows=body.optJSONArray("item_album");if(rows==null)rows=object(body,"album").optJSONArray("list");if(rows==null)throw QQHttp.invalid();
        List<Album> albums=new ArrayList<>();for(int i=0;i<rows.length();i++)albums.add(albumSummary(rows.optJSONObject(i),""));return searchPage(data,body,albums,offset,limit);
    }
    @Override public Album album(String albumId)throws ProviderException{
        albumId=canonicalAlbumId(albumId);
        mid(albumId);boolean numeric=albumId.matches("[1-9][0-9]{0,18}");Object requestId=numeric?new java.math.BigInteger(albumId):0;
        JSONObject detail=music("album","music.musichallAlbum.AlbumInfoServer","GetAlbumDetail",json("albumMid",numeric?"":albumId,"albumID",requestId),null);
        JSONObject info=requiredObject(detail,"basicInfo");String canonical=first(info,"albumMid");mid(canonical);
        if(numeric?!albumId.equals(first(info,"albumID")):!albumId.equals(canonical))throw QQHttp.invalid();
        JSONArray singers=object(detail,"singer").optJSONArray("singerList");String artist=artistNames(singers),artistId=firstSingerId(singers);
        String name=first(info,"albumName","name");if(name.isEmpty())throw QQHttp.invalid();
        List<Track> tracks=new ArrayList<>();Set<String> pages=new HashSet<>();int offset=0,total=-1;
        for(int page=0;page<MAX_PAGES;page++){
            JSONObject data=music("tracks","music.musichallAlbum.AlbumSongList","GetAlbumSongList",json("albumMid",canonical,"albumID",0,"begin",offset,"num",PAGE,"order",2),null);
            if(!canonical.equals(first(data,"albumMid")))throw QQHttp.invalid();
            if(data.has("curBegin")&&data.optInt("curBegin",-1)!=offset)throw incomplete();
            JSONArray rows=requiredArray(data,"songList");int reported=data.optInt("totalNum",-1);if(reported<0||total>=0&&reported!=total)throw incomplete();total=reported;
            if(rows.length()>PAGE||rows.length()==0&&offset<total)throw incomplete();
            StringBuilder signature=new StringBuilder();for(int i=0;i<rows.length();i++){
                Track song=track(rows.optJSONObject(i));if(!song.albumId.isEmpty()&&!canonical.equals(song.albumId)&&!first(info,"albumID").equals(song.albumId))throw QQHttp.invalid();
                song=new Track(song.id,song.title,song.artist,name,song.source,song.uri,albumCover(first(info,"pmid","albumMid")),song.durationMs,song.trackNumber,artist.isEmpty()?song.albumArtist:artist,song.genre,song.year,"qq",canonical,song.artistId,artistId.isEmpty()?song.albumArtistId:artistId).withPlaybackAccess(song.playbackAccess);
                tracks.add(song);signature.append(song.id).append('\n');
            }
            if(rows.length()>0&&!pages.add(signature.toString()))throw incomplete();offset+=rows.length();
            if(offset==total)return new Album(canonical,name,artist,artistId,albumCover(first(info,"pmid","albumMid")),tracks);
            if(offset>total||rows.length()==0)throw incomplete();
        }
        throw incomplete();
    }
    @Override public Page<Album> artistAlbums(String artistId,int offset,int limit)throws ProviderException{
        mid(artistId);if(offset<0||limit<1||limit>100)throw QQHttp.invalid();
        JSONObject data=music("albums","music.musichallAlbum.AlbumListServer","GetAlbumList",json("singerMid",artistId,"singerID",0,"order",0,"begin",offset,"num",limit,"songNumTag",0),null);
        if(!artistId.equals(first(data,"singerMid")))throw QQHttp.invalid();JSONArray rows=requiredArray(data,"albumList");int total=data.optInt("total",-1);if(total<0||rows.length()>limit||offset+rows.length()>total||rows.length()==0&&offset<total)throw incomplete();
        List<Album> albums=new ArrayList<>();Set<String> ids=new HashSet<>();for(int i=0;i<rows.length();i++){Album album=albumSummary(rows.optJSONObject(i),artistId);if(!ids.add(album.id))throw incomplete();albums.add(album);}
        return new Page<>(albums,total,offset+rows.length(),offset+rows.length()<total);
    }
    @Override public Artist artist(String artistId)throws ProviderException{
        mid(artistId);JSONObject data=music("singer","music.musichallSinger.SingerInfoInter","GetSingerDetail",json("singer_mids",new JSONArray().put(artistId),"ex_singer",1,"wiki_singer",1,"group_singer",0,"pic",1,"photos",0),null);
        JSONArray details=requiredArray(data,"singer_list");if(details.length()!=1)throw QQHttp.invalid();JSONObject item=details.optJSONObject(0),info=requiredObject(item,"basic_info");
        if(!artistId.equals(first(info,"singer_mid")))throw QQHttp.invalid();String name=first(info,"name");if(name.isEmpty())throw QQHttp.invalid();
        String description=plainDescription(first(object(item,"ex_info"),"desc"));if(description.isEmpty())description=wikiDescription(first(item,"wiki"));
        JSONObject recommended=music("songs","musichall.song_list_server","GetSingerSongList",json("singerMid",artistId,"order",1,"begin",0,"num",20),null);
        if(!artistId.equals(first(recommended,"singerMid")))throw QQHttp.invalid();JSONArray rows=requiredArray(recommended,"songList");List<Track> songs=new ArrayList<>();for(int i=0;i<rows.length();i++)songs.add(track(rows.optJSONObject(i)));
        String avatar=httpsArtwork(first(object(item,"pic"),"pic"));if(avatar.isEmpty())avatar=singerCover(artistId);
        return new Artist(artistId,name,description,avatar,songs,artistAlbums(artistId,0,20).items);
    }
    private static Album albumSummary(JSONObject item,String artistId)throws ProviderException{
        if(item==null)throw QQHttp.invalid();String id=first(item,"albumMid","albumMID","album_mid","mid"),name=first(item,"albumName","album_name","albumname","name","title");mid(id);if(name.isEmpty())throw QQHttp.invalid();
        JSONArray singers=item.optJSONArray("singer");if(singers==null)singers=item.optJSONArray("singerList");String artist=artistNames(singers);if(artist.isEmpty())artist=first(item,"singerName","singer_name","singername");String singerId=firstSingerId(singers);if(singerId.isEmpty())singerId=artistId;
        return new Album(id,name,artist,singerId,albumCover(first(item,"pmid","albumMid","albumMID","album_mid","mid")),Collections.emptyList());
    }
    private static String firstSingerId(JSONArray singers){return singers==null||singers.length()==0?"":first(singers.optJSONObject(0),"mid","singerMid","singer_mid");}
    private static String artistNames(JSONArray singers){StringBuilder names=new StringBuilder();if(singers!=null)for(int i=0;i<singers.length();i++){String name=first(singers.optJSONObject(i),"name","singerName");if(!name.isEmpty()){if(names.length()>0)names.append(" / ");names.append(name);}}return names.toString();}
    private static String plainDescription(String text){if(text==null||text.isEmpty())return "";String bounded=text.substring(0,Math.min(text.length(),30000));return android.text.Html.fromHtml(bounded.replace("<![CDATA[","").replace("]]>",""),android.text.Html.FROM_HTML_MODE_LEGACY).toString().trim();}
    private static String wikiDescription(String wiki){java.util.regex.Matcher match=java.util.regex.Pattern.compile("<desc>([\\s\\S]*?)</desc>").matcher(wiki);return match.find()?plainDescription(match.group(1)):"";}
    @Override public PlaybackResource resolve(Track track)throws ProviderException{
        if(track==null||!"qq".equals(track.provider)||!track.uri.equals("qq://song/"+track.id))throw QQHttp.invalid();mid(track.id);Auth auth=auth(true);
        JSONObject detail=music("songinfo","music.pf_song_detail_svr","get_song_detail_yqq",json("song_mid",track.id),auth);JSONObject raw=requiredObject(detail,"track_info");Track canonical=track(raw);
        if(!track.id.equals(canonical.id)||canonical.durationMs<=0)throw QQHttp.invalid();
        String media=first(object(raw,"file"),"media_mid");if(media.isEmpty())media=track.id;mid(media);String filename="M500"+media+".mp3";
        String guid=Long.toString(10000000L+new java.security.SecureRandom().nextInt(90000000));
        JSONObject parameters=json("guid",guid,"songmid",new JSONArray().put(track.id),"songtype",new JSONArray().put(0),"uin",auth.uin,"loginflag",1,"platform","20","filename",new JSONArray().put(filename));
        JSONObject payload=json("comm",json("uin",auth.uin,"format","json","ct",19,"cv",0,"authst",auth.playbackKey),"req_0",json("module","vkey.GetVkeyServer","method","CgiGetVkey","param",parameters));
        JSONObject data=block(request(MUSICU,payload,auth),"req_0");JSONArray infos=requiredArray(data,"midurlinfo");JSONObject info=null;
        for(int i=0;i<infos.length();i++){JSONObject item=infos.optJSONObject(i);if(item!=null&&filename.equals(first(item,"filename"))){info=item;break;}}
        if(info==null)throw playbackFailure("QQ 音乐返回的播放资源与所请求的标准音质不匹配（返回记录 "+infos.length()+"），请重试或在官方 App 核对该版本歌曲");String purl=first(info,"purl");
        if(preview(info)||preview(data))throw new ProviderException(ProviderException.Kind.PREVIEW_ONLY,"QQ 音乐只返回试听，未作为完整歌曲播放");
        int resourceCode=info.optInt("result",0);
        if(resourceCode!=0)throw playbackFailure("QQ 音乐未批准这次播放请求（资源 code "+resourceCode+"），请重试或在官方 App 核对同账号、同歌曲能否完整播放");
        if(purl.isEmpty())throw playbackFailure("QQ 音乐未向当前接入返回播放地址；仅凭此响应无法确认是否为会员限制。请重试或在官方 App 核对同账号、同歌曲能否完整播放");
        String returnedMid=first(info,"songmid");if(!returnedMid.isEmpty()&&!track.id.equals(returnedMid))throw QQHttp.invalid();
        URI relative;try{relative=new URI(purl);if(relative.isAbsolute()||relative.getRawAuthority()!=null||relative.getFragment()!=null||!relative.getPath().equals(filename)||relative.getRawQuery()==null||!relative.getRawQuery().matches("(?:.*&)?vkey=[^&]+(?:&.*)?"))throw QQHttp.invalid();}catch(ProviderException failure){throw failure;}catch(Exception failure){throw QQHttp.invalid();}
        JSONArray sips=data.optJSONArray("sip");if(sips==null||sips.length()==0)sips=new JSONArray().put("https://ws.stream.qqmusic.qq.com/");
        Map<String,String> headers=new LinkedHashMap<>();headers.put("Referer","https://y.qq.com/");headers.put("User-Agent",QQHttp.USER_AGENT);
        ProviderException last=null;
        for(int i=0;i<Math.min(2,sips.length());i++){
            String base=sips.optString(i,"");if(base.startsWith("http://"))base="https://"+base.substring(7);String url=base+purl;
            try{QQAudioProbe.validateAudioUrl(url);long duration=audio.durationMs(url,headers);checkEpoch(auth);if(duration<=0)throw playbackFailure("未能确认 QQ 音乐音频的完整时长，未开始播放，请稍后重试");long tolerance=Math.max(1000,Math.min(4000,canonical.durationMs/100));
                if(duration<canonical.durationMs-tolerance)throw new ProviderException(ProviderException.Kind.PREVIEW_ONLY,"QQ 音乐音频短于整曲，已阻止试听替代完整播放");
                if(duration>canonical.durationMs+tolerance)throw playbackFailure("QQ 音乐返回音频与歌曲标注时长不一致（标注 "+canonical.durationMs/1000+" 秒 / 音频 "+duration/1000+" 秒），为避免播放错误版本已停止");
                return new PlaybackResource(url,headers,System.currentTimeMillis()+300000,duration);
            }catch(ProviderException failure){if(failure.kind==ProviderException.Kind.PREVIEW_ONLY||failure.kind==ProviderException.Kind.LOGIN_EXPIRED)throw failure;last=failure;}
        }
        throw last==null?playbackFailure("QQ 音乐没有返回可验证的播放资源，请稍后重试"):last;
    }
    @Override public void logout()throws ProviderException{session.clear();}
    private JSONObject get(String path,JSONObject parameters,Auth auth)throws ProviderException{
        put(parameters,"format","json");put(parameters,"uin",auth==null?"0":auth.uin);put(parameters,"g_tk",auth==null?5381:auth.csrf);put(parameters,"g_tk_new_20200303",auth==null?5381:auth.csrfNew);put(parameters,"inCharset","utf8");put(parameters,"outCharset","utf-8");put(parameters,"notice",0);put(parameters,"platform","yqq.json");put(parameters,"needNewCode",0);
        StringBuilder url=new StringBuilder("https://c.y.qq.com"+path+"?");java.util.Iterator<String> keys=parameters.keys();while(keys.hasNext()){String key=keys.next();if(url.charAt(url.length()-1)!='?')url.append('&');url.append(encoded(key)).append('=').append(encoded(parameters.optString(key,"")));}
        return request(url.toString(),null,auth);
    }
    private JSONObject music(String key,String module,String method,JSONObject parameters,Auth auth)throws ProviderException{
        JSONObject comm=json("ct",24,"cv",0,"format","json");if(auth!=null){put(comm,"uin",auth.uin);put(comm,"authst",auth.playbackKey);}
        return block(request(MUSICU,json("comm",comm,key,json("module",module,"method",method,"param",parameters)),auth),key);
    }
    private JSONObject request(String url,JSONObject body,Auth auth)throws ProviderException{
        try{JSONObject result=http.request(url,body,auth==null?"":auth.cookies);if(auth!=null)checkEpoch(auth);checkCode(result);
            JSONObject data=result.optJSONObject("data");if(data!=null)checkCode(data);
            if(body!=null){java.util.Iterator<String> keys=body.keys();while(keys.hasNext()){String key=keys.next();if("comm".equals(key))continue;JSONObject response=result.optJSONObject(key);if(response!=null){checkCode(response);JSONObject nested=response.optJSONObject("data");if(nested!=null)checkCode(nested);}}}
            return result;}
        catch(ProviderException failure){if(auth!=null&&auth.persisted&&failure.kind==ProviderException.Kind.LOGIN_EXPIRED)synchronized(session){if(session.generation()==auth.epoch)session.clear();}throw new ProviderException(failure.kind,operation(url,body)+"失败："+failure.getMessage());}
    }
    private static String operation(String url,JSONObject body){
        if(url.contains("fcg_get_profile_homepage"))return "读取 QQ 账号";if(url.contains("fcg_user_created_diss"))return "读取本人歌单";if(url.contains("fcg_get_profile_order_asset"))return "读取收藏歌单";if(url.contains("fcg_ucc_getcdinfo"))return "读取歌单曲目";if(url.contains("musics.fcg"))return "搜索 QQ 曲库";
        if(body!=null){if(body.has("req_0")){String module=first(object(body,"req_0"),"module");if(module.contains("DissInfo"))return "读取喜欢列表";if(module.contains("Vkey"))return "取得播放资源";}if(body.has("album")||body.has("tracks"))return "读取完整专辑";if(body.has("singer")||body.has("songs")||body.has("albums"))return "读取艺人资料";}return "读取 QQ 音乐";
    }
    private static JSONObject block(JSONObject root,String key)throws ProviderException{JSONObject block=requiredObject(root,key);checkCode(block);JSONObject data=requiredObject(block,"data");checkCode(data);return data;}
    private static void checkCode(JSONObject body)throws ProviderException{
        int code=body.optInt("code",0),subcode=body.optInt("subcode",0);if(code==0)code=subcode;if(code==0)code=body.optInt("result",0);
        String diagnostic="（code "+code+(subcode!=0&&subcode!=code?" / "+subcode:"")+"）";
        if(code==1000||code==10004||code==104003||code==301||code==-100008)throw new ProviderException(ProviderException.Kind.LOGIN_EXPIRED,"官方授权已失效，请重新登录"+diagnostic);
        if(code==429)throw new ProviderException(ProviderException.Kind.RATE_LIMITED,"QQ 音乐请求过于频繁，请稍后重试"+diagnostic);
        if(code==4000)throw new ProviderException(ProviderException.Kind.UNAVAILABLE,"官方服务限制了这份资料的访问，请在 QQ 音乐检查账号授权与资料可见性"+diagnostic);
        if(code!=0)throw new ProviderException(ProviderException.Kind.UNAVAILABLE,"官方接口暂未允许本次请求，请重试；仍失败可重新授权"+diagnostic);
    }
    private Auth auth(boolean required)throws ProviderException{synchronized(session){String cookies=session.readCookies();if(cookies.isEmpty()){if(required)throw new ProviderException(ProviderException.Kind.LOGIN_REQUIRED,"请先在 QQ 音乐官方网页授权");return null;}return Auth.parse(cookies,session.generation(),true,required);}}
    private void checkEpoch(Auth auth)throws ProviderException{if(session.generation()!=auth.epoch)throw new ProviderException(ProviderException.Kind.LOGIN_EXPIRED,"QQ 音乐账号已切换或退出，请重新操作");}
    private static void ownUser(Auth auth,String id)throws ProviderException{if(!auth.uin.equals(id))throw new ProviderException(ProviderException.Kind.LOGIN_REQUIRED,"请使用当前 QQ 音乐账号的资料库");}
    private static void verifyOwner(JSONObject object,String expected)throws ProviderException{
        // Zero means hidden/unset; encrypt_uin and opaque userid are profile addresses,
        // not numeric QQ/WeChat music uins. Never coerce these into a numeric identity.
        for(String key:new String[]{"wxuin","uin","userid","user_id"}){
            String owner=uin(first(object,key));
            if(!owner.isEmpty()&&!"0".equals(owner)&&!expected.equals(owner))throw accountMismatch();
        }
        JSONObject creator=object.optJSONObject("creator");if(creator!=null)verifyOwner(creator,expected);
    }
    private static String profileAddress(JSONObject object){String address=first(object,"encrypt_uin");return address.isEmpty()?first(object(object,"creator"),"encrypt_uin"):address;}
    private static ProviderException accountMismatch(){return new ProviderException(ProviderException.Kind.LOGIN_REQUIRED,"QQ 音乐返回的账号与当前授权不一致，请完成当前账户的官方音乐授权");}
    static final class Auth {
        final String cookies,uin,playbackKey;final long epoch;final boolean persisted;final int csrf,csrfNew;
        Auth(String cookies,String uin,String key,long epoch,boolean persisted,int csrf,int csrfNew){this.cookies=cookies;this.uin=uin;playbackKey=key;this.epoch=epoch;this.persisted=persisted;this.csrf=csrf;this.csrfNew=csrfNew;}
        static Auth parse(String header,long epoch,boolean persisted,boolean required)throws ProviderException{
            if(header==null||header.length()>32768||header.matches("(?s).*[\\x00-\\x1f\\x7f].*"))throw QQHttp.invalid();
            Set<String> allowed=new HashSet<>(Arrays.asList("uin","wxuin","qqmusic_uin","p_uin","qm_keyst","qqmusic_key","music_key","wxskey","login_type","tmeLoginType","wxopenid","wxunionid","music_ignore_pskey","p_skey","skey","p_lskey","lskey","psrf_qqaccess_token","psrf_qqopenid","psrf_qqrefresh_token","wxrefresh_token"));
            Map<String,String> values=new LinkedHashMap<>();for(String part:header.split(";")){int equals=part.indexOf('=');if(equals>0){String key=part.substring(0,equals).trim(),value=part.substring(equals+1).trim();if(allowed.contains(key)&&!value.isEmpty())values.put(key,value);}}
            boolean wechat=!values.getOrDefault("wxopenid","").isEmpty()||"2".equals(values.get("login_type"))||"2".equals(values.get("tmeLoginType"));
            String identity=uin(first(values,wechat?new String[]{"wxuin","uin","p_uin"}:new String[]{"uin","qqmusic_uin","wxuin","p_uin"}));
            String key=first(values,new String[]{"qm_keyst","qqmusic_key","music_key","wxskey"});
            if(identity.isEmpty()||"0".equals(identity)||key.isEmpty())throw webLoginRequired();values.put("uin",identity);
            StringBuilder canonical=new StringBuilder();for(Map.Entry<String,String> item:values.entrySet()){if(canonical.length()>0)canonical.append("; ");canonical.append(item.getKey()).append('=').append(item.getValue());}
            return new Auth(canonical.toString(),identity,key,epoch,persisted,csrf(values,false),csrf(values,true));
        }
        // Exact key priorities in QQ's current getACSRFToken implementation.
        private static int csrf(Map<String,String> values,boolean modern){String key=first(values,modern?new String[]{"qqmusic_key","p_skey","skey","p_lskey","lskey"}:new String[]{"skey","qqmusic_key"});int hash=5381;for(int i=0;i<key.length();i++)hash+=(hash<<5)+key.charAt(i);return hash&0x7fffffff;}
    }
    static Track track(JSONObject source)throws ProviderException{
        if(source==null)throw QQHttp.invalid();JSONObject item=source;
        for(String wrapper:new String[]{"track_info","songInfo","songinfo","song"})if(source.optJSONObject(wrapper)!=null){item=source.optJSONObject(wrapper);break;}
        String mid=first(item,"mid","songmid"),title=first(item,"name","songname","title");mid(mid);if(title.isEmpty())throw QQHttp.invalid();JSONObject album=object(item,"album");String albumMid=firstId(album,"mid","albumMid","albumMID");if(albumMid.isEmpty())albumMid=firstId(item,"albummid","albumMid","album_mid");if(albumMid.isEmpty())albumMid=firstId(album,"id","albumID");if(albumMid.isEmpty())albumMid=firstId(item,"albumid","albumId");albumMid=canonicalAlbumId(albumMid);String albumName=first(album,"name","title");if(albumName.isEmpty())albumName=first(item,"albumname");
        JSONArray singers=item.optJSONArray("singer");if(singers==null)singers=item.optJSONArray("singers");StringBuilder name=new StringBuilder();String artistId="";
        if(singers!=null)for(int i=0;i<singers.length();i++){JSONObject singer=singers.optJSONObject(i);if(singer==null)continue;String artist=first(singer,"name","title");if(!artist.isEmpty()){if(name.length()>0)name.append(" / ");name.append(artist);if(artistId.isEmpty())artistId=first(singer,"mid");}}
        if(name.length()==0)name.append(first(item,"singername"));if(artistId.isEmpty())artistId=first(item,"singermid","singer_mid");
        long seconds=item.optLong("interval",0);if(seconds<0||seconds>86400)throw QQHttp.invalid();
        return new Track(mid,title,name.toString(),albumName,"QQ音乐","qq://song/"+mid,albumCover(first(album,"pmid").isEmpty()?albumMid:first(album,"pmid")),seconds*1000,Math.max(0,item.optInt("index_album",item.optInt("belongCD",0))),name.toString(),"","","qq",albumMid,artistId).withPlaybackAccess(playbackAccess(item));
    }
    /** Public catalog requirement only; never infer the current account's entitlement or block resolve. */
    static Track.PlaybackAccess playbackAccess(JSONObject item){
        JSONObject pay=item.optJSONObject("pay");
        int play=accessNumber(pay,"pay_play","payplay");
        if(play==0)return Track.PlaybackAccess.STANDARD; // Download fees and album prices do not restrict streaming.
        if(play!=1)return Track.PlaybackAccess.UNKNOWN;
        JSONObject action=item.optJSONObject("action");
        int alert=accessNumber(action,"alert");
        if(action!=null&&action.has("alert")&&alert<0)return Track.PlaybackAccess.UNKNOWN;
        if(alert<0){alert=accessNumber(item,"alertid");if(item.has("alertid")&&alert<0)return Track.PlaybackAccess.UNKNOWN;}
        // QQ's published GetPayAlertH5 playback scenes, verified 2026-11-10. Unrecognised codes stay unknown.
        switch(alert){
            case 6: case 7: case 17: case 28: case 63: case 64: case 65: case 68:
                return Track.PlaybackAccess.PURCHASE;
            case 2: case 21: case 22: case 31: case 32: case 33: case 40: case 41:
            case 42: case 43: case 44: case 45: case 66: case 69: case 70: case 205: case 214: case 88888888:
                return Track.PlaybackAccess.VIP;
            default:
                // Legacy rows may lack an action block. A positive song/album price alone proves neither kind.
                if(alert<=0&&accessNumber(pay,"pay_month","paytrackmouth")==1
                        &&accessNumber(pay,"price_album","payalbumprice","payalbum")==0)return Track.PlaybackAccess.VIP;
                return Track.PlaybackAccess.UNKNOWN;
        }
    }
    private static int accessNumber(JSONObject object,String... keys){
        if(object==null)return -1;
        for(String key:keys)if(object.has(key)){
            Object value=object.opt(key);String text=value instanceof Number||value instanceof String?String.valueOf(value):"";
            if(!text.matches("[0-9]{1,9}"))return -1;
            try{return Integer.parseInt(text);}catch(NumberFormatException ignored){return -1;}
        }
        return -1;
    }
    static String searchSign(String text)throws ProviderException{
        try{byte[] hash=MessageDigest.getInstance("SHA-1").digest(text.getBytes(StandardCharsets.UTF_8));StringBuilder hex=new StringBuilder();for(byte value:hash)hex.append(String.format(java.util.Locale.ROOT,"%02x",value&255));String value=hex.toString();StringBuilder first=new StringBuilder(),last=new StringBuilder();for(int at:new int[]{23,14,6,36,16,40,7,19})if(at<value.length())first.append(value.charAt(at));for(int at:new int[]{16,1,32,12,19,27,8,5})last.append(value.charAt(at));int[] salt={89,39,179,150,218,82,58,252,177,52,186,123,120,64,242,133,143,161,121,179};byte[] mixed=new byte[20];for(int i=0;i<20;i++)mixed[i]=(byte)(salt[i]^hash[i]);return ("zzc"+first+Base64.encodeToString(mixed,Base64.NO_WRAP).replaceAll("[/+=]","")+last).toLowerCase(java.util.Locale.ROOT);}
        catch(Exception failure){throw QQHttp.invalid();}
    }
    private static boolean preview(JSONObject object){for(String key:new String[]{"trial","is_trial","isTrial","freeTrial","free_trial"})if(object.optBoolean(key,false)||object.optInt(key,0)>0)return true;return object.optJSONObject("freeTrialInfo")!=null||object.optJSONObject("trialInfo")!=null;}
    private static String albumCover(String id){return id!=null&&id.matches("[A-Za-z0-9_]{1,80}")?"https://y.qq.com/music/photo_new/T002R500x500M000"+id+".jpg":"";}
    private static String singerCover(String id){return "https://y.qq.com/music/photo_new/T001R500x500M000"+id+".jpg";}
    private static String httpsArtwork(String url){if(url.startsWith("//"))url="https:"+url;if(url.startsWith("http://"))url="https://"+url.substring(7);try{URI uri=new URI(url);String host=uri.getHost();return "https".equals(uri.getScheme())&&host!=null&&(host.equals("qq.com")||host.endsWith(".qq.com")||host.endsWith(".qlogo.cn")||host.endsWith(".qpic.cn")||host.equals("y.gtimg.cn"))&&uri.getUserInfo()==null&&uri.getPort()==-1?url:"";}catch(Exception ignored){return "";}}
    private static String uin(String value){if(value==null||!value.matches("[oO]?[0-9]{1,20}"))return "";return value.replaceFirst("^[oO]","").replaceFirst("^0+(?!$)","");}
    private static String first(Map<String,String> values,String[] names){for(String name:names){String value=values.get(name);if(value!=null&&!value.isEmpty())return value;}return "";}
    private static String first(JSONObject object,String... names){if(object==null)return "";for(String name:names)if(!object.isNull(name)){String value=object.optString(name,"");if(!value.isEmpty())return value;}return "";}
    private static String firstId(JSONObject object,String... names){for(String name:names){String value=first(object,name);if(!value.isEmpty()&&!"0".equals(value))return value;}return "";}
    /** Old metadata sometimes stored a picture version (pmid) in place of the 14-character album mid. */
    private static String canonicalAlbumId(String id){return id!=null&&id.matches("[A-Za-z0-9]{14}_[0-9]+")?id.substring(0,14):id;}
    private static JSONObject object(JSONObject parent,String key){JSONObject value=parent==null?null:parent.optJSONObject(key);return value==null?new JSONObject():value;}
    private static JSONArray array(JSONObject parent,String key){JSONArray value=parent==null?null:parent.optJSONArray(key);return value==null?new JSONArray():value;}
    private static JSONObject requiredObject(JSONObject parent,String key)throws ProviderException{JSONObject value=parent==null?null:parent.optJSONObject(key);if(value==null)throw QQHttp.invalid();return value;}
    private static JSONArray requiredArray(JSONObject parent,String key)throws ProviderException{JSONArray value=parent==null?null:parent.optJSONArray(key);if(value==null)throw QQHttp.invalid();return value;}
    private static JSONObject json(Object... pairs)throws ProviderException{JSONObject value=new JSONObject();try{for(int i=0;i<pairs.length;i+=2)value.put((String)pairs[i],pairs[i+1]);return value;}catch(Exception failure){throw QQHttp.invalid();}}
    private static JSONObject jsonCopy(JSONObject value)throws ProviderException{try{return new JSONObject(value.toString());}catch(Exception failure){throw QQHttp.invalid();}}
    private static void put(JSONObject object,String key,Object value)throws ProviderException{try{object.put(key,value);}catch(Exception failure){throw QQHttp.invalid();}}
    private static String encoded(String value)throws ProviderException{try{return URLEncoder.encode(value,"UTF-8");}catch(Exception failure){throw QQHttp.invalid();}}
    private static void mid(String value)throws ProviderException{if(value==null||!value.matches("[A-Za-z0-9]{1,64}"))throw QQHttp.invalid();}
    private static void numericId(String value)throws ProviderException{if(value==null||!value.matches("[1-9][0-9]{0,18}"))throw QQHttp.invalid();}
    private static ProviderException incomplete(){return new ProviderException(ProviderException.Kind.UNAVAILABLE,"QQ 音乐未返回完整列表，请稍后重试，未保存截断数据");}
    private static ProviderException playbackFailure(String message){return new ProviderException(ProviderException.Kind.UNAVAILABLE,message);}
}
