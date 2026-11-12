package com.recordroom.vinyl;

import java.net.URI;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** Direct account-scoped interoperability with NetEase's client endpoints, not an authorized public API. */
public final class NeteaseProvider implements MusicProvider {
    private final ProviderSession session;
    private final NeteaseHttp http;
    private String transientCookies="";
    private long cookieGeneration=-1;
    private QrCandidate pendingQr;
    private static final class QrCandidate {
        final long sessionGeneration;
        String key="",cookies="";
        QrCandidate(long generation){sessionGeneration=generation;}
    }
    private static final int PAGE_SIZE=200, SONG_BATCH=500, MAX_PAGES=1000;
    public NeteaseProvider(ProviderSession session){this(session,new NeteaseHttp());}
    NeteaseProvider(ProviderSession session,NeteaseHttp http){this.session=session;this.http=http;}
    @Override public String id(){return "netease";}

    private static JSONObject json(Object... pairs)throws ProviderException{
        JSONObject value=new JSONObject();try{for(int i=0;i<pairs.length;i+=2)value.put((String)pairs[i],pairs[i+1]);return value;}
        catch(Exception failure){throw invalid("无法建立网易云请求");}
    }
    private static ProviderException invalid(String message){return new ProviderException(ProviderException.Kind.INVALID_RESPONSE,message);}
    private static String id(String value)throws ProviderException{
        if(value==null||!value.matches("[1-9][0-9]{0,18}"))throw invalid("网易云资源编号无效");
        try{Long.parseLong(value);}catch(NumberFormatException failure){throw invalid("网易云资源编号无效");}return value;
    }
    private static String value(JSONObject object,String key){return object==null||object.isNull(key)?"":object.optString(key,"");}
    private static JSONObject object(JSONObject parent,String key){JSONObject value=parent==null?null:parent.optJSONObject(key);return value==null?new JSONObject():value;}
    private static JSONArray array(JSONObject parent,String key){JSONArray value=parent==null?null:parent.optJSONArray(key);return value==null?new JSONArray():value;}
    private static boolean hasLogin(String cookies){return !NeteaseHttp.parseCookies(cookies).getOrDefault("MUSIC_U","").isEmpty();}

    /** Serializes cookie updates but never holds the session monitor during network I/O. */
    private synchronized JSONObject request(String path,JSONObject parameters,boolean weapi,boolean qr)throws ProviderException{
        long generation;String cookies;
        synchronized(session){
            generation=session.generation();String stored=session.readCookies();
            if(cookieGeneration!=generation){transientCookies=stored;cookieGeneration=generation;}
            if(!stored.isEmpty()){Map<String,String> merged=NeteaseHttp.parseCookies(transientCookies);merged.putAll(NeteaseHttp.parseCookies(stored));transientCookies=NeteaseHttp.cookieHeader(merged);}
            cookies=transientCookies;
        }
        NeteaseHttp.Response result;
        try{result=http.post(path,parameters,weapi,cookies);}
        catch(ProviderException failure){
            if(failure.kind==ProviderException.Kind.LOGIN_EXPIRED)synchronized(session){if(session.generation()==generation){session.clear();transientCookies="";}}
            throw failure;
        }
        synchronized(session){
            if(session.generation()!=generation)throw new ProviderException(ProviderException.Kind.LOGIN_EXPIRED,"登录状态已变化，请重新操作");
            transientCookies=result.cookies==null?cookies:result.cookies;
            int code=result.body.optInt("code",200);
            if(code==301||code==401){boolean hadLogin=hasLogin(cookies);session.clear();transientCookies="";throw new ProviderException(hadLogin?ProviderException.Kind.LOGIN_EXPIRED:ProviderException.Kind.LOGIN_REQUIRED,hadLogin?"网易云登录已失效，请重新扫码":"请先登录网易云音乐");}
            // A successful response can revoke authentication through Set-Cookie alone.
            // Clear the persisted session before another request can merge its old token back in.
            if(hasLogin(session.readCookies())&&!hasLogin(transientCookies)){
                session.clear();transientCookies="";cookieGeneration=session.generation();
                throw new ProviderException(ProviderException.Kind.LOGIN_EXPIRED,"网易云登录已失效，请重新登录");
            }
            if(code==429||code==-460)throw new ProviderException(ProviderException.Kind.RATE_LIMITED,"网易云暂时限制请求，请稍后重试");
            if(!qr&&code!=200)throw new ProviderException(ProviderException.Kind.UNAVAILABLE,"网易云未允许本次请求（状态 "+code+"）");
            if(!qr&&hasLogin(transientCookies)&&!transientCookies.equals(session.readCookies()))session.writeCookies(transientCookies);
        }
        return result.body;
    }
    private JSONObject request(String path,JSONObject parameters,boolean weapi)throws ProviderException{return request(path,parameters,weapi,false);}
    private void requireLogin()throws ProviderException{synchronized(session){if(!hasLogin(session.readCookies()))throw new ProviderException(ProviderException.Kind.LOGIN_REQUIRED,"请先登录网易云音乐");}}

    @Override public synchronized QrLogin beginQrLogin()throws ProviderException{
        // A fresh QR never reuses the already verified web account's authentication.
        QrCandidate candidate=new QrCandidate(session.generation());pendingQr=candidate;
        JSONObject body=qrRequest(candidate,"/api/login/qrcode/unikey",json("type",3));
        if(body.optInt("code",200)!=200){pendingQr=null;throw qrRejected(body.optInt("code",0));}
        String key=value(body,"unikey");if(key.isEmpty())key=value(object(body,"data"),"unikey");
        if(key.isEmpty()||key.length()>512)throw invalid("网易云未返回有效二维码，请重试");
        candidate.key=key;
        try{return new QrLogin(key,"https://music.163.com/login?codekey="+URLEncoder.encode(key,"UTF-8"),System.currentTimeMillis()+120000);}
        catch(Exception failure){throw invalid("无法生成网易云二维码链接");}
    }
    @Override public synchronized LoginPoll pollQrLogin(String key)throws ProviderException{
        if(key==null||key.isEmpty()||key.length()>512)throw invalid("二维码已无效，请刷新");
        QrCandidate candidate=pendingQr;
        if(candidate==null||!key.equals(candidate.key))throw invalid("二维码已更换或取消，请刷新");
        JSONObject body=qrRequest(candidate,"/api/login/qrcode/client/login",json("key",key,"type",3));
        int code=body.optInt("code",0);
        if(code==800){pendingQr=null;return new LoginPoll(LoginStatus.EXPIRED,"二维码已过期，请刷新");}
        if(code==801)return new LoginPoll(LoginStatus.WAITING_SCAN,"请使用网易云音乐扫描二维码");
        if(code==802)return new LoginPoll(LoginStatus.WAITING_CONFIRM,"请在网易云音乐中确认登录");
        if(code!=803){pendingQr=null;throw qrRejected(code);}
        if(!hasLogin(candidate.cookies))throw invalid("此次扫码授权未返回登录凭据，请重新扫码");
        // Verify with the candidate cookies without changing the normal provider session.
        NeteaseHttp.Response verified=http.post("/api/w/nuser/account/get",json(),true,candidate.cookies);
        checkCandidate(candidate);
        if(verified==null||verified.body==null)throw invalid("无法验证此次扫码账户，请重试");
        if(verified.body.optInt("code",200)!=200)throw qrRejected(verified.body.optInt("code",0));
        JSONObject data=verified.body.optJSONObject("data");if(data==null)data=verified.body;
        JSONObject profile=object(data,"profile"),account=object(data,"account");String uid=value(profile,"userId");if(uid.isEmpty())uid=value(account,"id");
        if(profile.length()==0||uid.isEmpty())throw invalid("此次扫码授权未通过账户验证，请使用官方网页登录");
        id(uid);if(verified.cookies!=null)candidate.cookies=verified.cookies;
        if(!hasLogin(candidate.cookies))throw invalid("此次扫码凭据未通过验证，请重新登录");
        synchronized(session){
            checkCandidate(candidate);
            // Write atomically instead of clearing the old valid account first.
            session.writeCookies(candidate.cookies);transientCookies=candidate.cookies;cookieGeneration=session.generation();pendingQr=null;
        }
        return new LoginPoll(LoginStatus.SUCCESS,"网易云账户已验证");
    }
    private void checkCandidate(QrCandidate candidate)throws ProviderException{
        if(Thread.currentThread().isInterrupted()||pendingQr!=candidate||session.generation()!=candidate.sessionGeneration)
            throw new ProviderException(ProviderException.Kind.LOGIN_EXPIRED,"扫码已取消或登录状态已变化，请重新操作");
    }
    private JSONObject qrRequest(QrCandidate candidate,String path,JSONObject parameters)throws ProviderException{
        checkCandidate(candidate);NeteaseHttp.Response response=http.postQr(path,parameters,candidate.cookies);checkCandidate(candidate);
        if(response==null||response.body==null)throw invalid("网易云未返回有效扫码状态");
        if(response.cookies!=null)candidate.cookies=response.cookies;return response.body;
    }
    private static ProviderException qrRejected(int code){
        if(code==8821)return new ProviderException(ProviderException.Kind.LOGIN_CHALLENGE,"网易云拒绝了本次扫码授权（8821），请在官方网页登录中完成验证");
        return new ProviderException(ProviderException.Kind.UNAVAILABLE,"网易云未完成本次扫码授权（状态 "+code+"），可尝试官方网页登录");
    }
    @Override public Account account()throws ProviderException{
        long generation;synchronized(session){generation=session.generation();if(!hasLogin(session.readCookies()))return new Account("","","",0,false);}
        JSONObject body=request("/api/w/nuser/account/get",json(),true);JSONObject data=body.optJSONObject("data");if(data==null)data=body;
        JSONObject profile=object(data,"profile"),account=object(data,"account");String uid=value(profile,"userId");if(uid.isEmpty())uid=value(account,"id");
        synchronized(session){if(session.generation()!=generation)throw new ProviderException(ProviderException.Kind.LOGIN_EXPIRED,"登录状态已变化，请重新操作");
            if(uid.isEmpty()||profile.length()==0){session.clear();transientCookies="";throw new ProviderException(ProviderException.Kind.LOGIN_EXPIRED,"网易云登录已失效，请重新扫码");}}
        return new Account(id(uid),value(profile,"nickname"),image(value(profile,"avatarUrl")),profile.optInt("vipType",0),true);
    }
    @Override public List<Playlist> playlists(String userId)throws ProviderException{
        requireLogin();id(userId);ArrayList<Playlist> result=new ArrayList<>();HashSet<String> seen=new HashSet<>();int offset=0;
        for(int page=0;page<MAX_PAGES;page++){
            JSONObject body=request("/api/user/playlist",json("uid",userId,"limit",PAGE_SIZE,"offset",offset,"includeVideo",false),true);
            JSONArray entries=body.optJSONArray("playlist");if(entries==null)throw invalid("网易云未返回歌单列表");int added=0;
            for(int i=0;i<entries.length();i++){JSONObject entry=entries.optJSONObject(i);if(entry==null)throw invalid("网易云歌单内容不完整");
                String pid=id(value(entry,"id"));if(seen.add(pid)){String owner=value(object(entry,"creator"),"userId");result.add(new Playlist(pid,value(entry,"name"),value(entry,"description"),image(value(entry,"coverImgUrl")),owner,userId.equals(owner),entry.optInt("trackCount",0)));added++;}}
            boolean more=body.has("more")?body.optBoolean("more"):entries.length()>=PAGE_SIZE;
            if(!more)return Collections.unmodifiableList(result);
            if(entries.length()==0||added==0)throw invalid("网易云歌单分页未前进，未将部分列表当成完整结果");offset+=entries.length();
        }
        throw invalid("网易云歌单分页超出安全范围，请稍后重试");
    }
    @Override public List<Track> likedTracks(String userId)throws ProviderException{
        requireLogin();JSONObject body=request("/api/song/like/get",json("uid",id(userId)),false);JSONArray values=body.optJSONArray("ids");
        if(values==null)throw invalid("网易云未返回喜欢歌曲列表");ArrayList<String> ids=new ArrayList<>();for(int i=0;i<values.length();i++)ids.add(id(values.optString(i)));return songDetails(ids);
    }
    @Override public List<Track> playlistTracks(String playlistId)throws ProviderException{
        JSONObject body=request("/api/v6/playlist/detail",json("id",id(playlistId),"n",100000,"s",0),false);JSONObject playlist=body.optJSONObject("playlist");
        if(playlist==null)throw invalid("网易云未返回歌单详情");JSONArray trackIds=playlist.optJSONArray("trackIds");
        if(trackIds==null)throw invalid("网易云未返回完整曲目编号，无法确认歌单完整性");
        int total=playlist.optInt("trackCount",trackIds.length());if(total>trackIds.length())throw new ProviderException(ProviderException.Kind.UNAVAILABLE,"网易云只返回部分歌单曲目，可能需要重新登录或没有访问权限");
        ArrayList<String> ids=new ArrayList<>();for(int i=0;i<trackIds.length();i++){JSONObject item=trackIds.optJSONObject(i);if(item==null)throw invalid("网易云曲目编号格式无效");ids.add(id(value(item,"id")));}
        return songDetails(ids);
    }
    private List<Track> songDetails(List<String> ids)throws ProviderException{
        ArrayList<Track> result=new ArrayList<>();
        for(int from=0;from<ids.size();from+=SONG_BATCH){int end=Math.min(ids.size(),from+SONG_BATCH);JSONArray query=new JSONArray();
            for(int i=from;i<end;i++)query.put(json("id",Long.parseLong(id(ids.get(i)))));
            JSONObject body=request("/api/v3/song/detail",json("c",query.toString()),true);JSONArray songs=body.optJSONArray("songs");
            if(songs==null)throw invalid("网易云未返回曲目详情");Map<String,Track> byId=new HashMap<>();
            for(int i=0;i<songs.length();i++){JSONObject entry=songs.optJSONObject(i);if(entry==null)throw invalid("网易云曲目详情无效");Track track=track(entry);byId.put(track.id,track);}
            for(int i=from;i<end;i++){Track track=byId.get(ids.get(i));if(track==null)throw new ProviderException(ProviderException.Kind.UNAVAILABLE,"部分曲目已下架或无权访问，未将缺失列表当作完整歌单");result.add(track);}
        }
        return Collections.unmodifiableList(result);
    }
    @Override public Page<Track> searchTracks(String query,int offset,int limit)throws ProviderException{
        if(query==null||query.trim().isEmpty())return new Page<>(Collections.emptyList(),0,0,false);
        if(offset<0)throw invalid("搜索页码无效");limit=Math.max(1,Math.min(100,limit));
        JSONObject body=request("/api/cloudsearch/pc",json("s",query.trim(),"type",1,"limit",limit,"offset",offset,"total",true),false);
        JSONObject result=body.optJSONObject("result");if(result==null)throw invalid("网易云未返回搜索结果");
        JSONArray rows=result.optJSONArray("songs");int total=result.optInt("songCount",-1);
        if(rows==null&&total!=0)throw invalid("网易云歌曲搜索结果不完整");
        ArrayList<Track> songs=new ArrayList<>();if(rows!=null)for(int i=0;i<rows.length();i++)songs.add(track(rows.optJSONObject(i)));
        int next=offset+songs.size();if(songs.isEmpty()&&total>offset)throw invalid("网易云搜索分页未前进");
        return new Page<>(songs,total,next,total>=0?next<total:songs.size()>=limit);
    }
    @Override public boolean supportsArtistSearch(){return true;}
    @Override public Page<Artist> searchArtists(String query,int offset,int limit)throws ProviderException{
        if(query==null||query.trim().isEmpty())return new Page<>(Collections.emptyList(),0,0,false);
        if(query.length()>512||offset<0||limit<1||limit>100)throw invalid("音乐人搜索参数无效");
        JSONObject body=request("/api/cloudsearch/pc",json("s",query.trim(),"type",100,"limit",limit,"offset",offset,"total",true),false);
        JSONObject result=body.optJSONObject("result");if(result==null)throw invalid("网易云未返回音乐人搜索结果");
        JSONArray rows=result.optJSONArray("artists");int total=result.optInt("artistCount",-1);
        if(rows==null&&total!=0)throw invalid("网易云音乐人搜索结果不完整");
        List<Artist> artists=new ArrayList<>();HashSet<String> ids=new HashSet<>();
        if(rows!=null)for(int i=0;i<rows.length();i++){
            JSONObject row=rows.optJSONObject(i);if(row==null)throw invalid("网易云音乐人资料不完整");
            String artistId=id(value(row,"id")),name=value(row,"name").trim();if(name.isEmpty()||!ids.add(artistId))throw invalid("网易云音乐人身份不完整或重复");
            String portrait=value(row,"img1v1Url");if(portrait.isEmpty())portrait=value(row,"picUrl");
            artists.add(new Artist(artistId,name,value(row,"briefDesc"),artistImage(portrait),Collections.emptyList(),Collections.emptyList()));
        }
        int next=offset+artists.size();boolean more=total>=0?next<total:result.has("hasMore")?result.optBoolean("hasMore"):artists.size()>=limit;
        if(artists.size()>limit||artists.isEmpty()&&more||total>=0&&!artists.isEmpty()&&next>total)throw invalid("网易云音乐人搜索分页未完整返回");
        return new Page<>(artists,total,next,more);
    }
    private static String artistImage(String value){
        String url=image(value);try{URI uri=new URI(url);String host=uri.getHost();return "https".equals(uri.getScheme())&&host!=null&&(host.endsWith(".music.126.net")||host.endsWith(".music.163.com"))&&uri.getUserInfo()==null&&uri.getPort()==-1&&uri.getFragment()==null?url:"";}catch(Exception ignored){return "";}
    }
    @Override public Page<Album> artistAlbums(String artistId,int offset,int limit)throws ProviderException{
        if(offset<0)throw invalid("专辑页码无效");limit=Math.max(1,Math.min(100,limit));
        JSONObject response=request("/api/artist/albums/"+id(artistId),json("limit",limit,"offset",offset,"total",true),true);
        JSONArray rows=response.optJSONArray("hotAlbums");if(rows==null)throw invalid("网易云未返回音乐人专辑列表");
        ArrayList<Album> values=new ArrayList<>();for(int i=0;i<rows.length();i++)values.add(albumMeta(rows.optJSONObject(i),Collections.emptyList()));
        boolean more=response.has("more")?response.optBoolean("more"):values.size()>=limit;
        if(more&&values.isEmpty())throw invalid("音乐人专辑分页未前进");
        return new Page<>(values,response.optInt("albumSize",-1),offset+values.size(),more);
    }
    @Override public boolean supportsAlbumSearch(){return true;}
    @Override public Page<Album> searchAlbums(String query,int offset,int limit)throws ProviderException{
        if(query==null||query.trim().isEmpty())return new Page<>(Collections.emptyList(),0,0,false);
        if(offset<0)throw invalid("搜索页码无效");limit=Math.max(1,Math.min(100,limit));
        JSONObject body=request("/api/cloudsearch/pc",json("s",query.trim(),"type",10,"limit",limit,"offset",offset,"total",true),false);
        JSONObject result=body.optJSONObject("result");if(result==null)throw invalid("网易云未返回搜索结果");JSONArray albums=array(result,"albums");ArrayList<Album> values=new ArrayList<>();
        for(int i=0;i<albums.length();i++)values.add(albumMeta(albums.optJSONObject(i),Collections.emptyList()));
        int total=result.optInt("albumCount",offset+values.size()),next=offset+values.size();
        if(values.isEmpty()&&total>offset)throw invalid("网易云搜索分页结果不完整");return new Page<>(values,total,next,next<total);
    }
    @Override public Album album(String albumId)throws ProviderException{
        JSONObject body=request("/api/v1/album/"+id(albumId),json(),true);JSONArray songs=body.optJSONArray("songs");JSONObject album=body.optJSONObject("album");
        if(album==null||songs==null)throw invalid("网易云未返回完整专辑");ArrayList<Track> values=new ArrayList<>();
        Album metadata=albumMeta(album,Collections.emptyList());
        for(int i=0;i<songs.length();i++){
            Track song=track(songs.optJSONObject(i));
            values.add(new Track(song.id,song.title,song.artist,metadata.name.isEmpty()?song.album:metadata.name,song.source,song.uri,
                    metadata.coverUrl.isEmpty()?song.artworkUri:metadata.coverUrl,song.durationMs,song.trackNumber,
                    metadata.artist.isEmpty()?song.albumArtist:metadata.artist,song.genre,song.year,song.provider,metadata.id,song.artistId,
                    metadata.artistId.isEmpty()?song.albumArtistId:metadata.artistId).withPlaybackAccess(song.playbackAccess));
        }
        int total=album.optInt("size",values.size());if(total>values.size())throw new ProviderException(ProviderException.Kind.UNAVAILABLE,"网易云未返回该专辑的全部曲目");return albumMeta(album,values);
    }
    @Override public Artist artist(String artistId)throws ProviderException{
        String aid=id(artistId);JSONObject body=request("/api/artist/head/info/get",json("id",aid),false);JSONObject info=object(object(body,"data"),"artist");if(info.length()==0)info=object(body,"artist");
        if(info.length()==0)throw invalid("网易云未返回音乐人资料");
        JSONObject top=request("/api/v1/artist/songs",json("id",aid,"private_cloud",true,"work_type",1,"order","hot","limit",50,"offset",0),false);
        ArrayList<Track> songs=new ArrayList<>();JSONArray topSongs=top.optJSONArray("songs");if(topSongs==null)throw invalid("网易云未返回音乐人热门曲目");for(int i=0;i<topSongs.length();i++)songs.add(track(topSongs.optJSONObject(i)));
        Page<Album> albums=artistAlbums(aid,0,20);
        return new Artist(aid,value(info,"name"),value(info,"briefDesc"),image(value(info,"cover").isEmpty()?value(info,"avatar"):value(info,"cover")),songs,albums.items);
    }

    @Override public PlaybackResource resolve(Track track)throws ProviderException{
        if(track==null||!"netease".equals(track.provider))throw invalid("这首歌曲不是网易云资源");String songId=id(track.id);
        JSONObject body=request("/api/song/enhance/player/url/v1",json("ids","["+songId+"]","level","standard","encodeType","flac"),false);
        JSONArray data=array(body,"data");JSONObject item=null;for(int i=0;i<data.length();i++){JSONObject candidate=data.optJSONObject(i);if(candidate!=null&&songId.equals(value(candidate,"id"))){item=candidate;break;}}
        if(item==null)throw invalid("网易云未返回歌曲播放权限");
        JSONObject trial=object(item,"freeTrialPrivilege");
        if(!item.isNull("freeTrialInfo")&&item.has("freeTrialInfo")||trial.optBoolean("resConsumable")||trial.optBoolean("userConsumable")||trial.optBoolean("listenable"))
            throw new ProviderException(ProviderException.Kind.PREVIEW_ONLY,"网易云仅提供试听，不能作为完整歌曲播放");
        if(item.optInt("code",200)!=200||value(item,"url").isEmpty())throw new ProviderException(ProviderException.Kind.UNAVAILABLE,"网易云未授权播放此歌曲，请检查会员、购买或版权权限");
        String type=value(item,"type");if(type.equalsIgnoreCase("ncm"))throw new ProviderException(ProviderException.Kind.UNAVAILABLE,"此音频需要受保护的解码，当前不支持");
        long expected=track.durationMs;if(expected<=0)expected=songDetails(Collections.singletonList(songId)).get(0).durationMs;
        long duration=item.optLong("time",0);
        if(expected<=0||duration<=0)throw new ProviderException(ProviderException.Kind.UNAVAILABLE,"无法确认音频为完整歌曲，请稍后重试");
        long tolerance=Math.max(2000,expected/50);
        if(duration<expected-tolerance)throw new ProviderException(ProviderException.Kind.PREVIEW_ONLY,"返回音频短于完整歌曲，已阻止试听替代完整播放");
        if(duration>expected+tolerance)throw new ProviderException(ProviderException.Kind.UNAVAILABLE,"播放资源时长与歌曲不符");
        String url=value(item,"url");
        try{URI parsed=new URI(url);String host=parsed.getHost();if(host==null||parsed.getUserInfo()!=null)throw new Exception();
            if("http".equalsIgnoreCase(parsed.getScheme())&&(host.endsWith(".music.126.net")||host.endsWith(".music.163.com")))url="https"+url.substring(4);
            else if(!"https".equalsIgnoreCase(parsed.getScheme()))throw new Exception();
        }catch(Exception failure){throw new ProviderException(ProviderException.Kind.UNAVAILABLE,"网易云未返回安全可用的音频地址");}
        long seconds=item.optLong("expi",120);if(seconds<=0)throw new ProviderException(ProviderException.Kind.UNAVAILABLE,"播放地址已过期，请重试");
        return new PlaybackResource(url,Collections.emptyMap(),System.currentTimeMillis()+Math.min(seconds,3600)*1000,duration);
    }
    @Override public synchronized void logout()throws ProviderException{synchronized(session){session.clear();transientCookies="";pendingQr=null;cookieGeneration=session.generation();}}

    static Track track(JSONObject song)throws ProviderException{
        if(song==null)throw invalid("网易云曲目资料无效");String sid=id(value(song,"id"));JSONObject album=song.optJSONObject("al");if(album==null)album=object(song,"album");
        JSONArray artists=song.optJSONArray("ar");if(artists==null)artists=array(song,"artists");String names=artistNames(artists);String artistId=value(artists.optJSONObject(0),"id");
        JSONObject albumPerformer=object(album,"artist");String albumArtist=value(albumPerformer,"name");if(albumArtist.isEmpty())albumArtist=names;
        return new Track(sid,value(song,"name"),names,value(album,"name"),"网易云音乐","netease://song/"+sid,image(value(album,"picUrl")),
                song.optLong("dt",song.optLong("duration",0)),song.optInt("no",0),albumArtist,"","","netease",value(album,"id"),artistId,value(albumPerformer,"id"));
    }
    private static String artistNames(JSONArray artists){StringBuilder names=new StringBuilder();for(int i=0;i<artists.length();i++){String name=value(artists.optJSONObject(i),"name");if(!name.isEmpty()){if(names.length()>0)names.append(" / ");names.append(name);}}return names.toString();}
    private static Album albumMeta(JSONObject album,List<Track> tracks)throws ProviderException{
        if(album==null)throw invalid("网易云专辑资料无效");JSONObject artist=object(album,"artist");String name=value(artist,"name");if(name.isEmpty())name=artistNames(array(album,"artists"));
        return new Album(id(value(album,"id")),value(album,"name"),name,value(artist,"id"),image(value(album,"picUrl")),tracks);
    }
    private static String image(String url){if(url.startsWith("https://"))return url;if(url.startsWith("http://"))return "https"+url.substring(4);return "";}
}
