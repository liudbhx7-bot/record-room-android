/* SPDX-License-Identifier: GPL-3.0-only
 * Protocol adaptation of XxHuberrr/Mineradio-paused, d43de565acabfdc1a9c9820a27e81a98ccbebcef.
 * Android session isolation, bounded pagination and strict full-track checks added for RecordRoom.
 */
package com.recordroom.vinyl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** All network methods run off the UI thread. Login is provided by QishuiAuthActivity. */
public final class QishuiProvider implements MusicProvider {
    private final ProviderSession session;private final QishuiHttp http;
    public QishuiProvider(ProviderSession session){this(session,new QishuiHttp());}
    QishuiProvider(ProviderSession session,QishuiHttp http){this.session=session;this.http=http;}
    @Override public String id(){return "qishui";}
    @Override public QrLogin beginQrLogin()throws ProviderException{throw new ProviderException(ProviderException.Kind.LOGIN_CHALLENGE,"请打开汽水扫码授权页完成官方安全验证");}
    @Override public LoginPoll pollQrLogin(String key)throws ProviderException{throw new ProviderException(ProviderException.Kind.LOGIN_CHALLENGE,"汽水授权状态由扫码授权页处理");}
    @Override public void logout()throws ProviderException{session.clear();}
    @Override public Account account()throws ProviderException{return profile(snapshot()).account;}
    static final class Snapshot {final String cookies;final long generation;Snapshot(String cookies,long generation){this.cookies=cookies;this.generation=generation;}}
    private Object sessionLock(){return session instanceof QishuiSessionStore?QishuiSessionStore.LOCK:session;}
    private Snapshot snapshot()throws ProviderException{
        synchronized(sessionLock()){String cookie=session.readCookies();if(!hasLoginCookie(cookie))throw new ProviderException(ProviderException.Kind.LOGIN_REQUIRED,"请先登录汽水音乐");return new Snapshot(cookie,session.generation());}
    }
    private void current(Snapshot snap)throws ProviderException{if(snap.generation!=session.generation())throw new ProviderException(ProviderException.Kind.LOGIN_EXPIRED,"汽水账户状态已变化，请重新读取");}
    private JSONObject request(String path,Map<String,String> query,JSONObject body,Snapshot snap)throws ProviderException{
        current(snap);JSONObject result=http.request(path,query,body,snap.cookies);current(snap);QishuiHttp.check(result);return result;
    }
    private Profile profile(Snapshot snap)throws ProviderException{return parseProfile(request("/luna/pc/me",Collections.emptyMap(),null,snap));}
    /** Cancellation and the final session commit share one gate; successful commits are not rolled back by Activity close. */
    public static final class AuthorizationAttempt {
        private boolean cancelled,committed;
        public synchronized void cancel(){if(!committed)cancelled=true;}
        public synchronized boolean isCommitted(){return committed;}
    }
    /** Validate a completed official authorization before committing it to the encrypted session. */
    public Account acceptAuthorizedSession(String cookies,long expectedGeneration)throws ProviderException{
        return acceptAuthorizedSession(cookies,expectedGeneration,new AuthorizationAttempt());
    }
    public Account acceptAuthorizedSession(String cookies,long expectedGeneration,AuthorizationAttempt attempt)throws ProviderException{
        if(attempt==null)throw QishuiHttp.invalid();
        if(!hasLoginCookie(cookies))throw new ProviderException(ProviderException.Kind.LOGIN_REQUIRED,"汽水授权尚未完成");
        synchronized(attempt){if(attempt.cancelled||attempt.committed||Thread.currentThread().isInterrupted())throw cancelledAuthorization();}
        JSONObject result=http.request("/luna/pc/me",Collections.emptyMap(),null,cookies);QishuiHttp.check(result);Account account=parseProfile(result).account;
        synchronized(attempt){
            if(attempt.cancelled||attempt.committed||Thread.currentThread().isInterrupted())throw cancelledAuthorization();
            synchronized(sessionLock()){
                if(session.generation()!=expectedGeneration)throw cancelledAuthorization();
                session.writeCookies(cookies);attempt.committed=true;
            }
        }
        return account;
    }
    private static ProviderException cancelledAuthorization(){return new ProviderException(ProviderException.Kind.LOGIN_EXPIRED,"这次汽水授权已取消或被替换");}
    @Override public List<Playlist> playlists(String userId)throws ProviderException{
        Snapshot snap=snapshot();Profile profile=profile(snap);
        if(userId!=null&&!userId.isEmpty()&&!profile.account.id.equals(userId))throw new ProviderException(ProviderException.Kind.LOGIN_EXPIRED,"汽水账户已切换，请重新打开曲库");
        Map<String,Playlist> found=new LinkedHashMap<>();
        readPlaylistPages("/luna/pc/user/playlist",map("user_id",profile.account.id),true,profile.account.id,snap,found);
        readPlaylistPages("/luna/pc/me/collection/mixed",Collections.emptyMap(),false,profile.account.id,snap,found);
        current(snap);return new ArrayList<>(found.values());
    }
    private void readPlaylistPages(String path,Map<String,String> extra,boolean owned,String user,Snapshot snap,Map<String,Playlist> output)throws ProviderException{
        String cursor="";Set<String> seen=new HashSet<>();
        for(int page=0;page<2000;page++){
            if(!seen.add(cursor))throw QishuiHttp.invalid();Map<String,String> params=new LinkedHashMap<>(extra);params.put("cursor",cursor);params.put("count","50");
            JSONObject data=data(request(path,params,null,snap));collectPlaylists(data,owned,user,output,0);
            if(!more(data))return;String next=text(data,"next_cursor","nextCursor");if(next.isEmpty()||next.equals(cursor))throw QishuiHttp.invalid();cursor=next;
        }throw QishuiHttp.invalid();
    }
    @Override public List<Track> likedTracks(String userId)throws ProviderException{
        List<Playlist> lists=playlists(userId);for(Playlist p:lists)if(p.name.matches(".*(我喜欢|喜欢的音乐|喜欢的歌曲|我收藏的音乐).*"))return playlistTracks(p.id);
        throw new ProviderException(ProviderException.Kind.UNAVAILABLE,"汽水账号未返回可识别的喜欢歌单");
    }
    @Override public List<Track> playlistTracks(String playlistId)throws ProviderException{
        validId(playlistId);return trackPages("/luna/pc/playlist/detail",map("playlist_id",playlistId),snapshot());
    }
    public List<Track> recentTracks()throws ProviderException{return trackPages("/luna/pc/me/recently-played-media",Collections.emptyMap(),snapshot());}
    private List<Track> trackPages(String path,Map<String,String> extra,Snapshot snap)throws ProviderException{
        String cursor="";Set<String> seen=new HashSet<>();List<Track> out=new ArrayList<>();
        for(int page=0;page<2000;page++){
            if(!seen.add(cursor))throw QishuiHttp.invalid();Map<String,String> params=new LinkedHashMap<>(extra);params.put("cursor",cursor);params.put("count","100");
            JSONObject result=data(request(path,params,null,snap));JSONArray items=media(result);int before=out.size();
            for(int i=0;i<items.length();i++){JSONObject item=items.optJSONObject(i);if(item==null)throw QishuiHttp.invalid();out.add(track(item));}
            if(!more(result)){current(snap);return out;}
            String next=text(result,"next_cursor","nextCursor");if(out.size()==before||next.isEmpty()||next.equals(cursor))throw QishuiHttp.invalid();cursor=next;
        }throw QishuiHttp.invalid();
    }
    public Page<Track> searchTracks(String query,int offset,int limit)throws ProviderException{
        if(query==null||query.trim().isEmpty()||query.length()>512||offset<0||limit<1||limit>50)throw QishuiHttp.invalid();
        JSONObject result=data(request("/luna/pc/search/track",map("q",query.trim(),"cursor",Integer.toString(offset),"count",Integer.toString(limit),"search_method","input"),null,snapshot()));
        JSONObject section=object(result,"search_result","searchResult");if(section==null)section=result;JSONArray items=media(section);List<Track> tracks=new ArrayList<>();
        for(int i=0;i<items.length();i++)tracks.add(track(items.optJSONObject(i)));
        return new Page<>(tracks,section.optInt("total",-1),offset+tracks.size(),more(section));
    }
    @Override public Page<Album> searchAlbums(String query,int offset,int limit)throws ProviderException{throw unsupported("汽水专辑搜索暂未接入");}
    @Override public Album album(String albumId)throws ProviderException{throw unsupported("汽水专辑详情暂未接入");}
    @Override public Artist artist(String artistId)throws ProviderException{throw unsupported("汽水艺人详情暂未接入");}
    private static ProviderException unsupported(String message){return new ProviderException(ProviderException.Kind.UNAVAILABLE,message);}

    @Override public PlaybackResource resolve(Track track)throws ProviderException{
        if(track==null||!"qishui".equals(track.provider))throw unsupported("此歌曲不是汽水音乐来源");validId(track.id);Snapshot snap=snapshot();
        JSONObject body=new JSONObject();try{body.put("track_id",track.id);body.put("media_type","track");body.put("queue_type","favorite_track_playlist");body.put("scene_name","library");}catch(Exception failure){throw QishuiHttp.invalid();}
        JSONObject payload=request("/luna/pc/track_v2",Collections.emptyMap(),body,snap),d=data(payload),song=object(d,"track","track_info","trackInfo");
        if(song==null||!track.id.equals(text(song,"id","track_id")))throw QishuiHttp.invalid();
        Profile profile=profile(snap);int trackTier=restriction(song,0);if(trackTier>profile.membership.tier)throw unsupported(trackTier==2?"此汽水歌曲需要当前有效的 SVIP 权益":"此汽水歌曲需要当前有效的 VIP 权益");
        boolean rootTrial=trialFlag(payload)||trialFlag(d)||trialFlag(song),rootEncryption=encryptedFlag(payload)||encryptedFlag(d)||encryptedFlag(song);
        List<Stream> streams=new ArrayList<>();JSONObject audio=object(song,"audio_info","audioInfo");if(audio!=null)collectStreams(audio,streams,0,rootEncryption,rootTrial,trackTier,0,"");
        JSONObject player=object(d,"track_player","trackPlayer");if(player==null)player=object(song,"track_player","trackPlayer");
        if(player!=null){Object model=player.opt("video_model");if(model==null)model=player.opt("videoModel");collectStreams(parseObject(model),streams,0,rootEncryption||encryptedFlag(player),rootTrial||trialFlag(player),Math.max(trackTier,restriction(player,0)),0,"");
            String info=text(player,"url_player_info","URLPlayerInfo","urlPlayerInfo");if(!info.isEmpty()){
                current(snap);JSONObject infoResponse=http.playerInfo(info);current(snap);collectStreams(infoResponse,streams,0,rootEncryption||encryptedFlag(player),rootTrial||trialFlag(player),Math.max(trackTier,restriction(player,0)),0,"");
            }}
        JSONArray bitrates=song.optJSONArray("bit_rates");if(bitrates!=null)collectStreams(bitrates,streams,0,rootEncryption,rootTrial,trackTier,0,"");
        long full=number(song,"duration_ms");if(full<=0)full=durationMs(number(song,"duration"));if(full<=0)full=track.durationMs;
        Stream best=null;boolean encrypted=false,trial=false,premium=false,incomplete=false;
        for(Stream stream:streams){
            if(stream.encrypted){encrypted=true;continue;}
            if(stream.tier>profile.membership.tier){premium=true;continue;}
            if(stream.trial||stream.duration<=0||full<=0){if(stream.trial)trial=true;else incomplete=true;continue;}
            if(stream.duration+1500<full){trial=true;continue;}
            if(!QishuiHttp.safeMediaUrl(stream.url))continue;
            if(best==null||stream.bitrate>best.bitrate)best=stream;
        }
        if(best==null){
            if(trial)throw new ProviderException(ProviderException.Kind.PREVIEW_ONLY,"汽水仅返回试听片段，不能作为整曲播放");
            if(encrypted)throw unsupported("汽水返回受保护的加密音频，当前 Android 播放器尚未支持");
            if(premium)throw unsupported(profile.membership.known?"汽水仅返回超出当前会员等级的音质":"暂时无法验证汽水会员权益，请稍后重试");
            if(incomplete)throw unsupported("汽水返回的音频缺少完整时长证明，暂不能确认整曲播放");
            throw unsupported("汽水没有返回可用的完整音频");
        }
        current(snap);return new PlaybackResource(best.url,Collections.emptyMap(),System.currentTimeMillis()+60000,Math.max(full,best.duration));
    }
    static final class Membership {final int tier;final boolean known;Membership(int tier,boolean known){this.tier=tier;this.known=known;}}
    static final class Profile {final Account account;final Membership membership;Profile(Account account,Membership membership){this.account=account;this.membership=membership;}}
    static Profile parseProfile(JSONObject payload)throws ProviderException{
        JSONObject d=data(payload),u=object(d,"my_info","myInfo","user","user_info","userInfo","account","me");if(u==null)u=d;
        String id=text(u,"id","user_id","userId","uid","sec_uid","open_id");if(id.isEmpty())throw QishuiHttp.invalid();
        Membership member=membership(d,System.currentTimeMillis());return new Profile(new Account(id,text(u,"nickname","nick_name","name","public_name"),image(u,"larger_avatar_url","medium_avatar_url","avatar_url","avatar","pic"),member.tier,true),member);
    }
    static Membership membership(JSONObject data,long now){
        int[] tiers={0,0};boolean[] known={false};readMembership(data,0,now,tiers,known);return new Membership(tiers[1]>0?2:tiers[0]>0?1:0,known[0]);
    }
    private static void readMembership(JSONObject o,int depth,long now,int[] tiers,boolean[] known){
        if(o==null||depth>5)return;
        JSONObject normalized=new JSONObject();java.util.Iterator<String> fields=o.keys();while(fields.hasNext()){String key=fields.next();try{normalized.put(key.toLowerCase(Locale.ROOT).replace("_","").replace("-",""),o.opt(key));}catch(Exception ignored){}}
        for(int t=0;t<2;t++){
            String stem=t==1?"svip":"vip";boolean has=false,active=false,negative=false;
            String[] flags=t==1?new String[]{"issvip","issupervip","hassvip","hassupervip","svipactive","svipenabled"}:new String[]{"isvip","ismember","hasvip","hasmembership","vipactive","vipenabled"};
            String[] numbers=t==1?new String[]{"sviptype","sviplevel","superviptype","superviplevel"}:new String[]{"viptype","viplevel","membertype","memberlevel","musicviptype","musicviplevel"};
            for(String key:flags)if(normalized.has(key)){has=true;active|=positive(normalized.opt(key));}
            for(String key:numbers)if(normalized.has(key)){has=true;active|=number(normalized,key)>0;}
            for(String key:new String[]{stem+"endtime",stem+"expiretime",stem+"expiresat",stem+"expiredat"})if(normalized.has(key)){has=true;long end=number(normalized,key);if(end<100000000000L)end*=1000;if(end<=now)negative=true;}
            for(String key:new String[]{stem+"info",stem+"detail",stem+"package",stem+"benefit",t==1?"supervipinfo":"memberinfo",t==1?"supervipdetail":"membershipinfo"}){
                JSONObject container=normalized.optJSONObject(key);if(container==null)continue;known[0]=true;
                JSONObject c=new JSONObject();java.util.Iterator<String> childKeys=container.keys();while(childKeys.hasNext()){String k=childKeys.next();try{c.put(k.toLowerCase(Locale.ROOT).replace("_","").replace("-",""),container.opt(k));}catch(Exception ignored){}}
                boolean positive=false,denied=false,expiry=false;for(String k:new String[]{"status","state","active","valid","enabled","isactive","isvalid","isenabled"})if(c.has(k)){Object v=c.opt(k);positive|=positive(v);denied|=Boolean.FALSE.equals(v)||"0".equals(String.valueOf(v))||"false".equals(v);}
                for(String k:new String[]{stem+"endtime",stem+"expiretime","endtime","expiretime","expiresat","expirationtime","validuntil"})if(c.has(k)){expiry=true;long end=number(c,k);if(end<100000000000L)end*=1000;if(end<=now)denied=true;else positive=true;}
                if(positive&&!denied&&!negative)tiers[t]=1;
            }
            if(has){known[0]=true;if(active&&!negative)tiers[t]=1;}
        }
        for(String key:new String[]{"my_info","myInfo","user","user_info","userInfo","account","membership"})readMembership(o.optJSONObject(key),depth+1,now,tiers,known);
    }
    private static int restriction(JSONObject o,int depth){
        if(o==null||depth>5)return 0;int tier=0;
        for(String key:new String[]{"only_svip_playable","svip_required","need_svip","is_svip_song"})if(positive(o.opt(key)))tier=2;
        for(String key:new String[]{"only_vip_playable","vip_required","need_vip","is_vip_song"})if(positive(o.opt(key)))tier=Math.max(tier,1);
        if(o.optInt("fee",0)==1||o.optInt("privilege",0)>=9)tier=Math.max(tier,1);
        for(String key:new String[]{"label_info","privilege_info","play_info","permission"})tier=Math.max(tier,restriction(o.optJSONObject(key),depth+1));return tier;
    }
    private static final class Stream {String url;long duration,bitrate;boolean encrypted,trial;int tier;}
    private static boolean trialFlag(JSONObject o){return o!=null&&(positive(o.opt("is_trial"))||positive(o.opt("trial"))||positive(o.opt("is_preview"))||positive(o.opt("preview")));}
    private static boolean encryptedFlag(JSONObject o){
        if(o==null)return false;JSONObject enc=object(o,"encrypt_info","EncryptInfo","encryptInfo");
        return positive(o.opt("encrypted"))||positive(o.opt("is_encrypted"))||number(o,"encrypt_type")>0||!text(o,"play_auth","PlayAuth","spade_a","SpadeA").isEmpty()||(enc!=null&&!text(enc,"spade_a","SpadeA","play_auth","PlayAuth").isEmpty());
    }
    private static void collectStreams(Object value,List<Stream> output,int depth,boolean inheritedEncryption,boolean inheritedTrial,int inheritedTier,long inheritedDuration,String hint){
        if(depth>8||output.size()>1000)return;
        if(value instanceof JSONArray){JSONArray a=(JSONArray)value;for(int i=0;i<a.length();i++)collectStreams(a.opt(i),output,depth+1,inheritedEncryption,inheritedTrial,inheritedTier,inheritedDuration,hint);return;}
        if(!(value instanceof JSONObject))return;JSONObject o=(JSONObject)value;
        boolean encrypted=inheritedEncryption||encryptedFlag(o),trial=inheritedTrial||trialFlag(o);
        long duration=durationMs(number(o,"video_duration","duration","Duration"));if(duration<=0)duration=inheritedDuration;
        String quality=(text(o,"quality","Quality","definition","Definition")+" "+hint).toLowerCase(Locale.ROOT),format=text(o,"format","Format","vtype").toLowerCase(Locale.ROOT);
        long bitrate=number(o,"bitrate","Bitrate","real_bitrate","br");if(bitrate>0&&bitrate<10000)bitrate*=1000;
        int required=Math.max(inheritedTier,restriction(o,0));
        if(quality.matches(".*(svip|hi.?res|master|atmos|dolby|spatial).*"))required=2;
        else if(quality.matches(".*(lossless|flac|highest|higher|high|hq|exhigh|320|sq).*" )||format.matches("flac|alac|wav")||bitrate>192000)required=Math.max(required,1);
        String url=text(o,"main_play_url","MainPlayUrl","main_url","MainUrl","url","URL","play_url","PlayURL");
        if(!url.isEmpty()){
            Stream s=new Stream();s.url=url;s.duration=duration;s.encrypted=encrypted||url.contains("#auth=");s.trial=trial;s.bitrate=bitrate;s.tier=required;output.add(s);
        }
        java.util.Iterator<String> keys=o.keys();while(keys.hasNext()){String key=keys.next();Object child=o.opt(key);if(child instanceof JSONObject||child instanceof JSONArray)collectStreams(child,output,depth+1,encrypted,trial,required,duration,key);}
    }
    private static Object parseObject(Object value){if(value instanceof String)try{return new JSONObject((String)value);}catch(Exception ignored){}return value;}
    static Track track(JSONObject raw)throws ProviderException{
        if(raw==null)throw QishuiHttp.invalid();JSONObject entity=object(raw,"entity","data");if(entity==null)entity=raw;JSONObject media=object(entity,"media");if(media==null)media=entity;
        JSONObject wrapper=object(media,"track_wrapper");JSONObject t=wrapper==null?object(media,"track_entity","track"):wrapper.optJSONObject("track");if(t==null)t=media;
        JSONObject base=object(t,"base_info");if(base==null)base=t;JSONObject display=object(t,"display_info"),related=object(t,"related_info");
        String id=text(base,"id","track_id"),title=text(base,"name","title");if(id.isEmpty()||title.isEmpty())throw QishuiHttp.invalid();
        JSONArray artists=related==null?null:related.optJSONArray("artist_links");if(artists==null)artists=t.optJSONArray("artists");StringBuilder artist=new StringBuilder();String artistId="";
        if(artists!=null)for(int i=0;i<artists.length();i++){JSONObject a=artists.optJSONObject(i);String name=text(a,"name","display_name","artist_name");if(!name.isEmpty()){if(artist.length()>0)artist.append(" / ");artist.append(name);if(artistId.isEmpty())artistId=text(a,"id","artist_id");}}
        if(artist.length()==0)artist.append(text(base,"artist_name","author"));JSONObject album=related==null?null:object(related,"album_link","album");if(album==null)album=object(base,"album");
        String cover=image(display,"cover_url","url_cover");if(cover.isEmpty())cover=image(base,"cover_url","url_cover");if(cover.isEmpty())cover=image(t,"cover_url","url_cover");
        long duration=number(base,"duration_ms");if(duration<=0)duration=durationMs(number(base,"duration"));
        return new Track(id,title,artist.toString(),text(album,"name","title"),"汽水音乐","qishui://song/"+id,cover,duration,(int)number(base,"track_number"),artist.toString(),"","","qishui",text(album,"id","album_id"),artistId);
    }
    private static void collectPlaylists(Object node,boolean owned,String user,Map<String,Playlist> output,int depth)throws ProviderException{
        if(depth>6)return;if(output.size()>100000)throw QishuiHttp.invalid();if(node instanceof JSONArray){JSONArray a=(JSONArray)node;for(int i=0;i<a.length();i++)collectPlaylists(a.opt(i),owned,user,output,depth+1);return;}if(!(node instanceof JSONObject))return;
        JSONObject o=(JSONObject)node;String id=text(o,"playlist_id","collection_id","id"),name=text(o,"title","public_title","name","playlist_name");long count=number(o,"count_tracks","track_count","song_count");
        boolean explicit=o.has("playlist_id")||o.has("count_tracks")||o.has("track_count")||text(o,"type","resource_type").contains("playlist");
        if(explicit&&!id.isEmpty()&&!name.isEmpty())output.putIfAbsent(id,new Playlist(id,name,"",image(o,"cover_url","cover","url_cover"),owned?user:"",owned,(int)Math.min(Integer.MAX_VALUE,count)));
        java.util.Iterator<String> keys=o.keys();while(keys.hasNext()){Object value=o.opt(keys.next());if(value instanceof JSONArray||value instanceof JSONObject)collectPlaylists(value,owned,user,output,depth+1);}
    }
    static JSONArray media(JSONObject d)throws ProviderException{
        JSONArray groups=d.optJSONArray("result_groups");if(groups==null)groups=d.optJSONArray("resultGroups");
        if(groups!=null){JSONArray flattened=new JSONArray();for(int i=0;i<groups.length();i++){JSONObject g=groups.optJSONObject(i);if(g==null)continue;JSONArray rows=g.optJSONArray("data");if(rows==null)rows=g.optJSONArray("items");if(rows==null)rows=g.optJSONArray("list");if(rows==null&&g.optJSONObject("data")!=null)rows=media(g.optJSONObject("data"));if(rows!=null)for(int j=0;j<rows.length();j++)flattened.put(rows.opt(j));}return flattened;}
        for(String key:new String[]{"media_resources","media_list","tracks","track_list","songs","items","list","result","song_list"})if(d.optJSONArray(key)!=null)return d.optJSONArray(key);
        JSONObject nested=object(d,"search_result","searchResult");if(nested!=null)return media(nested);
        // A valid empty page is explicit; a shape we do not recognize must not erase the user's library.
        if(d.length()==0)return new JSONArray();throw QishuiHttp.invalid();
    }
    static JSONObject data(JSONObject body){JSONObject d=body==null?null:body.optJSONObject("data");return d==null?body:d;}
    static JSONObject object(JSONObject o,String... keys){if(o==null)return null;for(String k:keys){JSONObject v=o.optJSONObject(k);if(v!=null)return v;}return null;}
    static String text(JSONObject o,String... keys){if(o==null)return "";for(String k:keys){Object v=o.opt(k);if(v!=null&&v!=JSONObject.NULL&&(v instanceof String||v instanceof Number)){String s=v.toString().trim();if(!s.isEmpty())return s;}}return "";}
    static long number(JSONObject o,String... keys){if(o==null)return 0;for(String k:keys)if(o.has(k))try{return Long.parseLong(o.opt(k).toString());}catch(Exception ignored){try{return (long)Double.parseDouble(o.opt(k).toString());}catch(Exception ignored2){}}return 0;}
    static boolean positive(Object value){return Boolean.TRUE.equals(value)||"true".equals(value)||"1".equals(String.valueOf(value));}
    static boolean more(JSONObject d){return positive(d.opt("has_more"))||positive(d.opt("hasMore"));}
    static long durationMs(long value){return value<=0?0:value>10000?value:value*1000;}
    static Map<String,String> map(String... values){Map<String,String> map=new LinkedHashMap<>();for(int i=0;i<values.length;i+=2)map.put(values[i],values[i+1]);return map;}
    static void validId(String id)throws ProviderException{if(id==null||!id.matches("[A-Za-z0-9._-]{1,256}"))throw QishuiHttp.invalid();}
    static boolean hasLoginCookie(String cookie){if(cookie==null||cookie.length()>32768||cookie.indexOf('\r')>=0||cookie.indexOf('\n')>=0)return false;return java.util.regex.Pattern.compile("(?:^|;\\s*)(?:sessionid|sessionid_ss|sid_guard|sid_tt)=[^;\\s]+").matcher(cookie).find();}
    static String image(JSONObject o,String... keys){if(o==null)return "";for(String k:keys){Object v=o.opt(k);if(v instanceof JSONObject){String u=text((JSONObject)v,"url");if(u.startsWith("https://"))return u;JSONArray urls=((JSONObject)v).optJSONArray("url_list");if(urls!=null&&urls.length()>0&&urls.optString(0).startsWith("https://"))return urls.optString(0);}else if(v instanceof String&&((String)v).startsWith("https://"))return (String)v;}return "";}
}
