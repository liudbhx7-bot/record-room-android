package com.recordroom.vinyl;

import java.net.URI;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Official Apple Music REST boundary. Authentication and full playback require the native MusicKit SDK. */
public final class AppleMusicApi {
    public enum Error { CONFIGURATION, DEVELOPER_AUTH, USER_AUTH_REQUIRED, USER_ACCESS_DENIED, SESSION_CHANGED, STORAGE, NETWORK, RATE_LIMITED, INVALID_RESPONSE, UNAVAILABLE }
    public static final class Failure extends Exception {
        public final Error error;
        public Failure(Error error,String message){super(message);this.error=error;}
    }
    /** Implement with the approved developer-token backend and device-bound MusicKit user-token store. */
    public interface Tokens {
        String developerToken()throws Failure;
        String musicUserToken()throws Failure;
        long generation();
    }
    public static final class Item {
        public final String id,type,name,artist,album,releaseDate,artworkUrl,playParamsJson,catalogId;
        public final long durationMs;public final int trackNumber;public final boolean isLibrary;
        private final String resourceJson;
        Item(JSONObject object)throws Failure{
            id=object.optString("id","");type=object.optString("type","");
            if(id.isEmpty()||type.isEmpty())throw AppleMusicHttp.invalid();
            resourceJson=object.toString();JSONObject a=object.optJSONObject("attributes");if(a==null)a=new JSONObject();
            name=a.optString("name","");artist=a.optString("artistName","");album=a.optString("albumName","");releaseDate=a.optString("releaseDate","");
            durationMs=Math.max(0,a.optLong("durationInMillis",0));trackNumber=Math.max(0,a.optInt("trackNumber",0));
            JSONObject artwork=a.optJSONObject("artwork");artworkUrl=artworkUrl(artwork==null?"":artwork.optString("url",""));
            JSONObject params=a.optJSONObject("playParams");playParamsJson=params==null?"":params.toString();
            isLibrary=type.startsWith("library-");String relatedCatalog=relatedId(object.optJSONObject("relationships"),"catalog");catalogId=!relatedCatalog.isEmpty()?relatedCatalog:params==null?(isLibrary?"":id):params.optString("catalogId",isLibrary?"":id);
        }
        /** Metadata identity only. The opaque URI is not a Media3 audio URL or playback entitlement. */
        public Track asTrack()throws Failure{
            if(!"songs".equals(type)&&!"library-songs".equals(type))throw new Failure(Error.UNAVAILABLE,"此 Apple Music 项目不是歌曲");
            JSONObject relationships=resource().optJSONObject("relationships");String albumId=relatedId(relationships,"albums"),artistId=relatedId(relationships,"artists");
            // Navigation may use catalog relationships while SDK playback keeps the library ID.
            JSONObject catalog=firstRelationship(relationships,"catalog"),catalogRelations=catalog==null?null:catalog.optJSONObject("relationships");
            String catalogAlbum=relatedId(catalogRelations,"albums"),catalogArtist=relatedId(catalogRelations,"artists");
            if(!catalogAlbum.isEmpty())albumId=catalogAlbum;if(!catalogArtist.isEmpty())artistId=catalogArtist;
            return new Track(id,name,artist,album,"Apple Music","applemusic://"+type+"/"+encoded(id),artworkUrl,durationMs,trackNumber,artist,"",releaseDate.length()>=4?releaseDate.substring(0,4):releaseDate,"applemusic",albumId,artistId);
        }
        private static JSONObject firstRelationship(JSONObject relationships,String name){JSONObject relation=relationships==null?null:relationships.optJSONObject(name);JSONArray data=relation==null?null:relation.optJSONArray("data");return data==null?null:data.optJSONObject(0);}
        private static String relatedId(JSONObject relationships,String name){JSONObject first=firstRelationship(relationships,name);return first==null?"":first.optString("id","");}
        /** Preserve library/catalog distinction and all opaque play parameters for the eventual SDK adapter. */
        public JSONObject resource()throws Failure{try{return new JSONObject(resourceJson);}catch(Exception failure){throw AppleMusicHttp.invalid();}}
    }
    public static final class Page {
        public final List<Item> items;public final String next;public final int total;
        Page(List<Item> items,String next,int total){this.items=Collections.unmodifiableList(new ArrayList<>(items));this.next=next;this.total=total;}
        public int nextOffset(int current)throws Failure{
            if(next.isEmpty())return current+items.size();
            try{String query=new URI(next).getRawQuery();if(query!=null)for(String pair:query.split("&")){String[] parts=pair.split("=",2);if(parts.length==2&&"offset".equals(java.net.URLDecoder.decode(parts[0],"UTF-8"))){int offset=Integer.parseInt(java.net.URLDecoder.decode(parts[1],"UTF-8"));if(offset>current&&!items.isEmpty())return offset;break;}}}catch(Exception ignored){}
            throw AppleMusicHttp.invalid();
        }
    }
    private final Tokens tokens;private final AppleMusicHttp http;
    public AppleMusicApi(Tokens tokens){this(tokens,new AppleMusicHttp());}
    AppleMusicApi(Tokens tokens,AppleMusicHttp http){if(tokens==null||http==null)throw new IllegalArgumentException("Apple Music dependencies required");this.tokens=tokens;this.http=http;}

    public Page searchCatalog(String storefront,String type,String term,int limit,int offset)throws Failure{
        if(storefront==null||!storefront.matches("[a-z]{2}")||!("songs".equals(type)||"albums".equals(type)||"artists".equals(type)||"playlists".equals(type))||term==null||term.trim().isEmpty()||term.length()>512||limit<1||limit>25||offset<0)throw new Failure(Error.CONFIGURATION,"Apple Music 搜索参数无效");
        String path="/v1/catalog/"+storefront+"/search?types="+type+"&term="+encoded(term.trim())+"&limit="+limit+"&offset="+offset;
        JSONObject body=request(path,false,tokens.generation()),results=body.optJSONObject("results");
        if(results==null)throw AppleMusicHttp.invalid();JSONObject section=results.optJSONObject(type);
        if(section==null)return new Page(Collections.emptyList(),"",0);
        return page(section,path);
    }
    public String userStorefront()throws Failure{
        JSONObject body=request("/v1/me/storefront",true,tokens.generation());JSONArray data=body.optJSONArray("data");
        if(data==null||data.length()!=1||data.optJSONObject(0)==null)throw AppleMusicHttp.invalid();
        String id=data.optJSONObject(0).optString("id","");if(!id.matches("[a-z]{2}"))throw AppleMusicHttp.invalid();return id;
    }
    public List<Item> librarySongs()throws Failure{return all("/v1/me/library/songs?limit=100&include=albums,artists,catalog&include%5Bsongs%5D=albums,artists",Collections.singleton("library-songs"),true);}
    public List<Item> libraryPlaylists()throws Failure{return all("/v1/me/library/playlists?limit=100",Collections.singleton("library-playlists"),true);}
    public List<Item> libraryPlaylistTracks(String id)throws Failure{
        validId(id);Set<String> types=new HashSet<>();Collections.addAll(types,"library-songs","library-music-videos","songs","music-videos");
        return all("/v1/me/library/playlists/"+encoded(id)+"/tracks?limit=100&include%5Blibrary-songs%5D=albums,artists,catalog&include%5Bsongs%5D=albums,artists",types,false);
    }
    public Item catalogResource(String storefront,String type,String id)throws Failure{
        if(storefront==null||!storefront.matches("[a-z]{2}")||!("albums".equals(type)||"songs".equals(type)||"artists".equals(type)||"playlists".equals(type)))throw new Failure(Error.CONFIGURATION,"Apple Music 目录参数无效");validId(id);
        JSONObject body=request("/v1/catalog/"+storefront+"/"+type+"/"+encoded(id),false,tokens.generation());JSONArray items=body.optJSONArray("data");
        if(items==null||items.length()!=1||items.optJSONObject(0)==null)throw AppleMusicHttp.invalid();Item item=new Item(items.optJSONObject(0));if(!id.equals(item.id)||!type.equals(item.type))throw AppleMusicHttp.invalid();return item;
    }
    public Item libraryAlbum(String id)throws Failure{
        validId(id);JSONObject body=request("/v1/me/library/albums/"+encoded(id)+"?include=catalog,artists",true,tokens.generation());JSONArray data=body.optJSONArray("data");if(data==null||data.length()!=1||data.optJSONObject(0)==null)throw AppleMusicHttp.invalid();Item item=new Item(data.optJSONObject(0));if(!id.equals(item.id)||!"library-albums".equals(item.type))throw AppleMusicHttp.invalid();return item;
    }
    /** A library album/song/artist may have no catalog counterpart (for example uploaded music). */
    public Item libraryCatalogResource(String type,String id)throws Failure{
        if(!("albums".equals(type)||"artists".equals(type)||"songs".equals(type)))throw new Failure(Error.CONFIGURATION,"Apple Music 资料库类型无效");validId(id);
        String path="/v1/me/library/"+type+"/"+encoded(id)+"/catalog";Page response=page(request(path,true,tokens.generation()),path);
        if(response.items.isEmpty()&&response.next.isEmpty())return null;
        if(response.items.size()!=1||!response.next.isEmpty()||!type.equals(response.items.get(0).type))throw AppleMusicHttp.invalid();return response.items.get(0);
    }
    public Page catalogArtistAlbums(String storefront,String id,int offset,int limit)throws Failure{
        validStorefront(storefront);validId(id);if(offset<0||limit<1||limit>100)throw new Failure(Error.CONFIGURATION,"Apple Music 艺人专辑分页参数无效");
        String path="/v1/catalog/"+storefront+"/artists/"+encoded(id)+"/albums?limit="+limit+"&offset="+offset;
        return typedPage(request(path,false,tokens.generation()),path,"albums");
    }
    /** Apple Music's storefront-specific popular songs, not a personal recommendation algorithm. */
    public List<Item> catalogArtistTopSongs(String storefront,String id)throws Failure{
        validStorefront(storefront);validId(id);String path="/v1/catalog/"+storefront+"/artists/"+encoded(id)+"/view/top-songs?limit=10&include=albums,artists";
        return typedPage(request(path,false,tokens.generation()),path,"songs").items;
    }
    private Page typedPage(JSONObject body,String path,String type)throws Failure{Page result=page(body,path);for(Item item:result.items)if(!type.equals(item.type))throw AppleMusicHttp.invalid();return result;}
    private static void validStorefront(String storefront)throws Failure{if(storefront==null||!storefront.matches("[a-z]{2}"))throw new Failure(Error.CONFIGURATION,"Apple Music 地区无效");}
    public List<Item> libraryAlbumTracks(String id)throws Failure{validId(id);Set<String> types=new HashSet<>();Collections.addAll(types,"library-songs","library-music-videos");return all("/v1/me/library/albums/"+encoded(id)+"/tracks?limit=100",types,false,true);}
    public List<Item> catalogAlbumTracks(String storefront,String id)throws Failure{
        if(storefront==null||!storefront.matches("[a-z]{2}"))throw new Failure(Error.CONFIGURATION,"Apple Music 地区无效");validId(id);Set<String> types=new HashSet<>();Collections.addAll(types,"songs","music-videos");return all("/v1/catalog/"+storefront+"/albums/"+encoded(id)+"/tracks?limit=100",types,false,false);
    }
    private List<Item> all(String first,Set<String> allowedTypes,boolean rejectDuplicates)throws Failure{return all(first,allowedTypes,rejectDuplicates,true);}
    private List<Item> all(String first,Set<String> allowedTypes,boolean rejectDuplicates,boolean user)throws Failure{
        long generation=tokens.generation();List<Item> output=new ArrayList<>();Set<String> paths=new HashSet<>(),ids=new HashSet<>();String path=first;
        while(!path.isEmpty()){
            if(paths.size()>=2000||!paths.add(path))throw AppleMusicHttp.invalid();
            Page page=page(request(path,user,generation),first);
            if(page.items.isEmpty()&&!page.next.isEmpty())throw AppleMusicHttp.invalid();
            for(Item item:page.items){
                if(!allowedTypes.contains(item.type))throw AppleMusicHttp.invalid();
                if(rejectDuplicates&&!ids.add(item.type+":"+item.id))throw AppleMusicHttp.invalid();
                output.add(item);if(output.size()>200000)throw new Failure(Error.UNAVAILABLE,"Apple Music 曲库过大，请分批读取");
            }
            path=page.next;
            // Apple's next link can omit include; preserve requested metadata on subsequent pages.
            if(!path.isEmpty())path=preserveIncludes(first,path);
        }
        ensureGeneration(generation);return Collections.unmodifiableList(output);
    }
    private static String preserveIncludes(String first,String next){
        int query=first.indexOf('?');if(query<0)return next;String result=next;
        for(String pair:first.substring(query+1).split("&")){int equals=pair.indexOf('=');if(equals<0)continue;String key=pair.substring(0,equals);if(key.startsWith("include")&&!result.contains("?"+key+"=")&&!result.contains("&"+key+"="))result+=(result.contains("?")?"&":"?")+pair;}
        return result;
    }
    private Page page(JSONObject body,String initialPath)throws Failure{
        JSONArray data=body.optJSONArray("data");if(data==null)throw AppleMusicHttp.invalid();
        List<Item> items=new ArrayList<>();for(int i=0;i<data.length();i++){JSONObject object=data.optJSONObject(i);if(object==null)throw AppleMusicHttp.invalid();items.add(new Item(object));}
        String next=body.isNull("next")?"":body.optString("next","");
        if(!next.isEmpty()){
            AppleMusicHttp.safePath(next);
            try{if(!new URI(initialPath).getRawPath().equals(new URI(next).getRawPath()))throw AppleMusicHttp.invalid();}
            catch(Failure failure){throw failure;}catch(Exception failure){throw AppleMusicHttp.invalid();}
        }
        JSONObject meta=body.optJSONObject("meta");return new Page(items,next,meta==null?-1:meta.optInt("total",-1));
    }
    private JSONObject request(String path,boolean user,long generation)throws Failure{
        AppleMusicHttp.safePath(path);ensureGeneration(generation);
        String developer=token(tokens.developerToken(),false);String musicUser=user?token(tokens.musicUserToken(),true):"";
        ensureGeneration(generation);Map<String,String> headers=new LinkedHashMap<>();headers.put("Authorization","Bearer "+developer);if(user)headers.put("Music-User-Token",musicUser);
        AppleMusicHttp.Response response=http.get(path,Collections.unmodifiableMap(headers));ensureGeneration(generation);
        if(response.status==401)throw new Failure(Error.DEVELOPER_AUTH,"Apple Music 开发者令牌无效或已过期");
        if(response.status==403)throw new Failure(user?Error.USER_ACCESS_DENIED:Error.DEVELOPER_AUTH,user?"Apple Music 未授权此访问，请检查账户授权与订阅":"Apple Music 开发者授权未获允许");
        if(response.status==429)throw new Failure(Error.RATE_LIMITED,"Apple Music 请求过于频繁，请稍后重试");
        if(response.status==404)throw new Failure(Error.UNAVAILABLE,"Apple Music 中找不到此内容");
        if(response.status!=200)throw new Failure(Error.NETWORK,"Apple Music 连接失败（HTTP "+response.status+"）");
        if(response.body==null||response.body.has("errors"))throw AppleMusicHttp.invalid();return response.body;
    }
    private void ensureGeneration(long generation)throws Failure{if(generation!=tokens.generation())throw new Failure(Error.SESSION_CHANGED,"Apple Music 账户状态已变化，请重新读取");}
    private static String token(String value,boolean user)throws Failure{
        if(value==null||value.trim().isEmpty())throw new Failure(user?Error.USER_AUTH_REQUIRED:Error.CONFIGURATION,user?"尚未授权 Apple Music 账户":"尚未配置 Apple Music 开发者令牌服务");
        if(value.length()>32768||!value.equals(value.trim()))throw AppleMusicHttp.invalid();
        for(int i=0;i<value.length();i++)if(value.charAt(i)<=32||value.charAt(i)>=127)throw AppleMusicHttp.invalid();
        return value;
    }
    private static String encoded(String value){try{return URLEncoder.encode(value,"UTF-8").replace("+","%20");}catch(Exception impossible){throw new IllegalArgumentException("Invalid text");}}
    private static void validId(String id)throws Failure{if(id==null||!id.matches("[A-Za-z0-9._-]{1,256}"))throw new Failure(Error.CONFIGURATION,"Apple Music 内容标识无效");}
    private static String artworkUrl(String template){
        try{
            String url=template.replace("{w}","800").replace("{h}","800");URI parsed=new URI(url);String host=parsed.getHost();
            if(!"https".equals(parsed.getScheme())||host==null||!host.endsWith(".mzstatic.com")||parsed.getUserInfo()!=null||parsed.getFragment()!=null)return "";return url;
        }catch(Exception invalid){return "";}
    }
}
