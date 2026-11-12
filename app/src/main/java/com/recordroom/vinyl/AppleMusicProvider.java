package com.recordroom.vinyl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Apple Music library/catalog adapter. Full audio belongs exclusively to the official MusicKit playback SDK. */
public final class AppleMusicProvider implements MusicProvider {
    public static final String LIBRARY_SONGS="__library_songs__";
    private final AppleMusicSessionStore store;private final AppleMusicHttp http;private final AppleMusicApi api;
    public AppleMusicProvider(AppleMusicSessionStore store){this(store,new AppleMusicHttp());}
    AppleMusicProvider(AppleMusicSessionStore store,AppleMusicHttp http){this.store=store;this.http=http;this.api=new AppleMusicApi(store,http);}
    public AppleMusicSessionStore sessionStore(){return store;}
    @Override public String id(){return "applemusic";}
    @Override public QrLogin beginQrLogin()throws ProviderException{throw new ProviderException(ProviderException.Kind.LOGIN_CHALLENGE,"请打开 Apple Music 官方授权入口");}
    @Override public LoginPoll pollQrLogin(String key)throws ProviderException{throw new ProviderException(ProviderException.Kind.LOGIN_CHALLENGE,"Apple Music 授权由官方 SDK 返回结果");}
    @Override public Account account()throws ProviderException{
        try{if(store.getUserToken()==null)return new Account("","Apple Music","",-1,false);store.prepareDeveloperToken();String sf=api.userStorefront();String identity=store.accountIdentity();if(identity.isEmpty())throw new AppleMusicApi.Failure(AppleMusicApi.Error.USER_AUTH_REQUIRED,"请重新授权 Apple Music");return new Account(identity,"Apple Music · "+sf.toUpperCase(java.util.Locale.ROOT),"",-1,true);}catch(AppleMusicApi.Failure failure){throw convert(failure);}
    }
    /** An API read verifies the candidate MUT before either token or storefront is committed. */
    public Account acceptAuthorization(String user,String sdkStorefront,String encodedStorefront,long expected,AppleMusicSessionStore.Attempt gate)throws ProviderException{
        try{
            final String developer=store.developerToken();AppleMusicApi.Tokens candidate=new AppleMusicApi.Tokens(){public String developerToken(){return developer;}public String musicUserToken(){return user;}public long generation(){return store.generation();}};
            if(store.generation()!=expected)throw new AppleMusicApi.Failure(AppleMusicApi.Error.SESSION_CHANGED,"Apple Music 会话已变化，请重新授权");
            String actual=new AppleMusicApi(candidate,http).userStorefront();
            if(sdkStorefront!=null&&!sdkStorefront.isEmpty()&&!actual.equals(sdkStorefront))throw new AppleMusicApi.Failure(AppleMusicApi.Error.INVALID_RESPONSE,"Apple Music 授权地区与账号地区不一致，请重新授权");
            store.commitAuthorization(user,actual,encodedStorefront,expected,gate);
            return new Account(store.accountIdentity(),"Apple Music · "+actual.toUpperCase(java.util.Locale.ROOT),"",-1,true);
        }catch(AppleMusicApi.Failure failure){throw convert(failure);}
    }
    @Override public List<Playlist> playlists(String userId)throws ProviderException{
        try{store.prepareDeveloperToken();String identity=store.accountIdentity();if(identity.isEmpty()||userId!=null&&!userId.isEmpty()&&!identity.equals(userId))throw new AppleMusicApi.Failure(AppleMusicApi.Error.SESSION_CHANGED,"Apple Music 账户已变化，请重新读取");
            List<Playlist> out=new ArrayList<>();out.add(new Playlist(LIBRARY_SONGS,"资料库歌曲","Apple Music 资料库中的全部歌曲","",identity,true,-1));
            for(AppleMusicApi.Item item:api.libraryPlaylists()){
                JSONObject a=item.resource().optJSONObject("attributes");boolean owned=a!=null&&a.optBoolean("canEdit",false);String description="";if(a!=null){JSONObject d=a.optJSONObject("description");if(d!=null)description=d.optString("standard","");}
                out.add(new Playlist(item.id,item.name,description,item.artworkUrl,owned?identity:"",owned,a==null?-1:Math.max(-1,a.optInt("trackCount",-1))));
            }return out;
        }catch(AppleMusicApi.Failure failure){throw convert(failure);}
    }
    public List<Track> libraryTracks()throws ProviderException{try{store.prepareDeveloperToken();return tracks(api.librarySongs());}catch(AppleMusicApi.Failure failure){throw convert(failure);}}
    @Override public List<Track> likedTracks(String userId)throws ProviderException{throw new ProviderException(ProviderException.Kind.UNAVAILABLE,"Apple Music 的喜欢列表尚未接入，请使用“资料库歌曲”或歌单");}
    @Override public List<Track> playlistTracks(String id)throws ProviderException{if(LIBRARY_SONGS.equals(id))return libraryTracks();try{store.prepareDeveloperToken();return tracks(api.libraryPlaylistTracks(id));}catch(AppleMusicApi.Failure failure){throw convert(failure);}}
    @Override public Page<Track> searchTracks(String query,int offset,int limit)throws ProviderException{
        try{store.prepareDeveloperToken();AppleMusicApi.Page page=api.searchCatalog(storefront(),"songs",query,Math.max(1,Math.min(25,limit)),offset);List<Track> songs=tracks(page.items);return new Page<>(songs,page.total,page.nextOffset(offset),!page.next.isEmpty());}catch(AppleMusicApi.Failure failure){throw convert(failure);}
    }
    @Override public boolean supportsArtistSearch(){return true;}
    @Override public Page<Artist> searchArtists(String query,int offset,int limit)throws ProviderException{
        try{
            store.prepareDeveloperToken();AppleMusicApi.Page page=api.searchCatalog(storefront(),"artists",query,Math.max(1,Math.min(25,limit)),offset);
            List<Artist> artists=new ArrayList<>();java.util.HashSet<String> ids=new java.util.HashSet<>();
            for(AppleMusicApi.Item item:page.items){
                if(!"artists".equals(item.type)||!item.id.matches("[0-9]+")||item.name.trim().isEmpty()||!ids.add(item.id))throw AppleMusicHttp.invalid();
                JSONObject attributes=item.resource().optJSONObject("attributes"),notes=attributes==null?null:attributes.optJSONObject("editorialNotes");
                String description=notes==null?"":notes.optString("standard",notes.optString("short",""));description=android.text.Html.fromHtml(description,android.text.Html.FROM_HTML_MODE_COMPACT).toString().trim();
                artists.add(new Artist(item.id,item.name,description,item.artworkUrl,Collections.emptyList(),Collections.emptyList()));
            }
            int next=page.nextOffset(offset);if(page.total>=0&&!artists.isEmpty()&&offset+artists.size()>page.total)throw AppleMusicHttp.invalid();
            return new Page<>(artists,page.total,next,!page.next.isEmpty());
        }catch(AppleMusicApi.Failure failure){throw convert(failure);}
    }
    @Override public boolean supportsAlbumSearch(){return true;}
    @Override public Page<Album> searchAlbums(String query,int offset,int limit)throws ProviderException{
        try{store.prepareDeveloperToken();AppleMusicApi.Page page=api.searchCatalog(storefront(),"albums",query,Math.max(1,Math.min(25,limit)),offset);return albumPage(page,offset);}catch(AppleMusicApi.Failure failure){throw convert(failure);}
    }
    @Override public Album album(String id)throws ProviderException{
        try{
            store.prepareDeveloperToken();long generation=store.generation();String sf=storefront(),catalogId=id;
            if(id==null||!id.matches("[0-9]+")){
                AppleMusicApi.Item library=api.libraryAlbum(id);catalogId=library.catalogId;
                if(catalogId.isEmpty()){AppleMusicApi.Item mapped=api.libraryCatalogResource("albums",id);catalogId=mapped==null?"":mapped.id;}
                if(catalogId.isEmpty())throw new AppleMusicApi.Failure(AppleMusicApi.Error.UNAVAILABLE,"此 Apple Music 资料库专辑没有对应的目录版本，无法读取完整专辑；已收藏歌曲仍可在资料库播放");
            }
            AppleMusicApi.Item item=api.catalogResource(sf,"albums",catalogId);List<Track> songs=tracks(api.catalogAlbumTracks(sf,catalogId));
            JSONObject attributes=item.resource().optJSONObject("attributes");int expected=attributes==null?-1:attributes.optInt("trackCount",-1);
            if(expected>=0&&expected!=songs.size())throw new AppleMusicApi.Failure(AppleMusicApi.Error.INVALID_RESPONSE,"Apple Music 尚未返回完整专辑曲目，请稍后重试");
            String artistId=relationshipId(item,"artists");List<Track> related=new ArrayList<>();for(Track track:songs)related.add(withAlbum(track,item,artistId));
            ensureGeneration(generation);return new Album(item.id,item.name,item.artist,artistId,item.artworkUrl,related);
        }catch(AppleMusicApi.Failure failure){throw convert(failure);}
    }
    @Override public Artist artist(String id)throws ProviderException{
        try{
            store.prepareDeveloperToken();long generation=store.generation();String sf=storefront(),catalogId=catalogArtistId(id);
            AppleMusicApi.Item item=api.catalogResource(sf,"artists",catalogId);List<Track> songs=tracks(api.catalogArtistTopSongs(sf,catalogId));
            Page<Album> albums=albumPage(api.catalogArtistAlbums(sf,catalogId,0,25),0);JSONObject a=item.resource().optJSONObject("attributes"),notes=a==null?null:a.optJSONObject("editorialNotes");
            String description=notes==null?"":notes.optString("standard",notes.optString("short",""));description=android.text.Html.fromHtml(description,android.text.Html.FROM_HTML_MODE_COMPACT).toString().trim();
            ensureGeneration(generation);return new Artist(id,item.name,description,item.artworkUrl,songs,albums.items);
        }catch(AppleMusicApi.Failure failure){throw convert(failure);}
    }
    @Override public Page<Album> artistAlbums(String id,int offset,int limit)throws ProviderException{
        try{store.prepareDeveloperToken();long generation=store.generation();String sf=storefront(),catalogId=catalogArtistId(id);Page<Album> page=albumPage(api.catalogArtistAlbums(sf,catalogId,offset,Math.max(1,Math.min(100,limit))),offset);ensureGeneration(generation);return page;}catch(AppleMusicApi.Failure failure){throw convert(failure);}
    }
    private String catalogArtistId(String id)throws AppleMusicApi.Failure{
        if(id!=null&&id.matches("[0-9]+"))return id;AppleMusicApi.Item item=api.libraryCatalogResource("artists",id);
        if(item==null)throw new AppleMusicApi.Failure(AppleMusicApi.Error.UNAVAILABLE,"此 Apple Music 资料库艺人没有可用的目录主页");return item.id;
    }
    private static Page<Album> albumPage(AppleMusicApi.Page page,int offset)throws AppleMusicApi.Failure{
        List<Album> albums=new ArrayList<>();for(AppleMusicApi.Item item:page.items){if(!"albums".equals(item.type))throw AppleMusicHttp.invalid();albums.add(new Album(item.id,item.name,item.artist,relationshipId(item,"artists"),item.artworkUrl,Collections.emptyList()));}return new Page<>(albums,page.total,page.nextOffset(offset),!page.next.isEmpty());
    }
    private static Track withAlbum(Track t,AppleMusicApi.Item album,String albumArtistId){
        String artistId=t.artistId.isEmpty()?albumArtistId:t.artistId;
        return new Track(t.id,t.title,t.artist,album.name,t.source,t.uri,t.artworkUri.isEmpty()?album.artworkUrl:t.artworkUri,t.durationMs,t.trackNumber,album.artist,t.genre,t.year,t.provider,album.id,artistId,albumArtistId).withPlaybackAccess(t.playbackAccess);
    }
    private void ensureGeneration(long generation)throws AppleMusicApi.Failure{if(store.generation()!=generation)throw new AppleMusicApi.Failure(AppleMusicApi.Error.SESSION_CHANGED,"Apple Music 账户状态已变化，请重新读取");}
    @Override public PlaybackResource resolve(Track track)throws ProviderException{throw new ProviderException(ProviderException.Kind.UNAVAILABLE,"SDK_REQUIRED：Apple Music 完整播放须使用官方 MusicKit 播放引擎");}
    @Override public void logout()throws ProviderException{try{store.clearUserSession();}catch(AppleMusicApi.Failure failure){throw convert(failure);}}
    private String storefront()throws AppleMusicApi.Failure{String value=store.storefront();if(!value.matches("[a-z]{2}"))throw new AppleMusicApi.Failure(AppleMusicApi.Error.USER_AUTH_REQUIRED,"请先授权 Apple Music，以读取你的地区曲库");return value;}
    private static List<Track> tracks(List<AppleMusicApi.Item> items)throws AppleMusicApi.Failure{
        List<Track> out=new ArrayList<>();for(AppleMusicApi.Item item:items){if(!"songs".equals(item.type)&&!"library-songs".equals(item.type))throw new AppleMusicApi.Failure(AppleMusicApi.Error.UNAVAILABLE,"此 Apple Music 歌单包含当前音频界面尚未支持的音乐视频，未将其静默遗漏");out.add(item.asTrack());}return out;
    }
    private static String relationshipId(AppleMusicApi.Item item,String type)throws AppleMusicApi.Failure{JSONObject r=item.resource().optJSONObject("relationships");JSONObject relation=r==null?null:r.optJSONObject(type);JSONArray data=relation==null?null:relation.optJSONArray("data");JSONObject first=data==null?null:data.optJSONObject(0);return first==null?"":first.optString("id","");}
    static ProviderException convert(AppleMusicApi.Failure failure){
        ProviderException.Kind kind;switch(failure.error){case USER_AUTH_REQUIRED:case CONFIGURATION:kind=ProviderException.Kind.LOGIN_REQUIRED;break;case SESSION_CHANGED:case DEVELOPER_AUTH:kind=ProviderException.Kind.LOGIN_EXPIRED;break;case USER_ACCESS_DENIED:kind=ProviderException.Kind.LOGIN_CHALLENGE;break;case RATE_LIMITED:kind=ProviderException.Kind.RATE_LIMITED;break;case NETWORK:kind=ProviderException.Kind.NETWORK;break;case STORAGE:kind=ProviderException.Kind.STORAGE;break;case INVALID_RESPONSE:kind=ProviderException.Kind.INVALID_RESPONSE;break;default:kind=ProviderException.Kind.UNAVAILABLE;}return new ProviderException(kind,failure.getMessage());
    }
}
