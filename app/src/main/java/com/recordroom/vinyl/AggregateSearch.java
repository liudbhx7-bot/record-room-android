package com.recordroom.vinyl;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;

/** Concurrent catalog search. UI snapshots contain no platform-specific labels or failure messages. */
public final class AggregateSearch implements AutoCloseable {
    public interface Listener { void changed(State state); }
    public static final class Result {
        public final String key;
        public final Track displayTrack;
        public final List<Track> alternatives;
        public Result(String key,List<Track> alternatives){
            if(alternatives==null||alternatives.isEmpty())throw new IllegalArgumentException("A result requires a real track");
            this.key=key;this.alternatives=Collections.unmodifiableList(new ArrayList<>(alternatives));displayTrack=this.alternatives.get(0);
        }
        /** Catalog requirements only; never claims whether the current account is entitled. */
        public String accessLabel(){
            int vip=0,purchase=0;
            for(Track track:alternatives){if(track.playbackAccess==Track.PlaybackAccess.VIP)vip++;else if(track.playbackAccess==Track.PlaybackAccess.PURCHASE)purchase++;}
            if(vip+purchase==0)return "";
            boolean all=vip+purchase==alternatives.size();
            if(vip>0&&purchase>0)return all?"需 VIP 或单独购买":"部分版本需 VIP / 购买";
            if(vip>0)return all?"需 VIP":"部分版本需 VIP";
            return all?"需单独购买":"部分版本需购买";
        }
    }
    /** A real artist search object; the provider plus ID is its identity, never the name alone. */
    public static final class ArtistResult {
        public final String key,providerId,name;
        public final MusicProvider.Artist artist;
        ArtistResult(String providerId,MusicProvider.Artist artist){this.providerId=providerId;this.artist=artist;this.name=artist.name;this.key=providerId+"\u001f"+artist.id;}
    }
    public static final class State {
        public final String query;
        public final List<Result> results;
        public final List<ArtistResult> artists;
        public final boolean loading,hasMore,artistLoading,artistHasMore;
        public final int failedCount,skippedCount,artistFailedCount,artistSkippedCount,artistSearchedCount;
        State(String query,List<Result> results,List<ArtistResult> artists,boolean loading,boolean hasMore,int failed,int skipped,
              boolean artistLoading,boolean artistMore,int artistFailed,int artistSkipped,int artistSearched){
            this.query=query;this.results=Collections.unmodifiableList(results);this.artists=Collections.unmodifiableList(artists);this.loading=loading;this.hasMore=hasMore;failedCount=failed;skippedCount=skipped;
            this.artistLoading=artistLoading;artistHasMore=artistMore;artistFailedCount=artistFailed;artistSkippedCount=artistSkipped;artistSearchedCount=artistSearched;
        }
    }
    static final class Source {
        final MusicProvider provider;final LongSupplier epoch;final BooleanSupplier enabled;final IntSupplier priority;
        Source(MusicProvider provider,LongSupplier epoch,BooleanSupplier enabled,IntSupplier priority){this.provider=provider;this.epoch=epoch;this.enabled=enabled;this.priority=priority;}
    }
    private static final int PAGE_SIZE=25;
    private final ArrayList<Lane> lanes=new ArrayList<>();
    private final ArrayList<ArtistLane> artistLanes=new ArrayList<>();
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService workers=Executors.newFixedThreadPool(3,r->{Thread t=new Thread(r,"vinyl-unified-search");t.setDaemon(true);return t;});
    private final ExecutorService artistWorkers=Executors.newFixedThreadPool(3,r->{Thread t=new Thread(r,"vinyl-artist-search");t.setDaemon(true);return t;});
    private Listener listener;
    private String query="";
    private long generation;
    private boolean closed;
    private State current=new State("",new ArrayList<>(),new ArrayList<>(),false,false,0,0,false,false,0,0,0);

    public AggregateSearch(Context context){this(sources(context.getApplicationContext()));}
    AggregateSearch(List<Source> sources){for(Source source:sources){lanes.add(new Lane(source));artistLanes.add(new ArtistLane(source));}}
    private static List<Source> sources(Context app){
        ArrayList<Source> result=new ArrayList<>();int index=0;
        for(String id:new String[]{"qq","netease","applemusic"}){
            final int order=index++;MusicProvider provider=OnlineMusic.provider(app,id);
            result.add(new Source(provider,()->OnlineMusic.sessionGeneration(app,id),
                    ()->!"applemusic".equals(id)||OnlineMusic.hasSearchAuthorization(app,id),
                    ()->(OnlineMusic.hasSearchAuthorization(app,id)?0:10)+order));
        }
        return result;
    }
    public void setListener(Listener value){listener=value;if(value!=null&&!closed)value.changed(current);}
    public State state(){return current;}
    public void search(String value){
        if(closed)return;generation++;cancelRequests();query=value==null?"":value.trim();
        for(Lane lane:lanes){lane.tracks.clear();lane.offset=0;lane.more=true;lane.failed=false;lane.skipped=false;lane.epoch=-1;}
        for(ArtistLane lane:artistLanes){lane.artists.clear();lane.offset=0;lane.more=true;lane.failed=false;lane.skipped=false;lane.epoch=-1;}
        if(query.isEmpty()){publish();return;}
        for(Lane lane:lanes)launch(lane);for(ArtistLane lane:artistLanes)launchArtist(lane);publish();
    }
    public void loadMore(){if(closed||query.isEmpty())return;for(Lane lane:lanes)if(lane.more&&!lane.busy&&!lane.failed&&!lane.skipped)launch(lane);publish();}
    public void retryFailed(){if(closed||query.isEmpty())return;for(Lane lane:lanes)if(lane.failed&&!lane.busy)launch(lane);publish();}
    public void loadMoreArtists(){if(closed||query.isEmpty())return;for(ArtistLane lane:artistLanes)if(lane.more&&!lane.busy&&!lane.failed&&!lane.skipped)launchArtist(lane);publish();}
    public void retryArtists(){if(closed||query.isEmpty())return;for(ArtistLane lane:artistLanes)if(lane.failed&&!lane.busy)launchArtist(lane);publish();}
    public void refresh(){search(query);}
    private void launch(Lane lane){
        final long token=generation,request=++lane.requestId;final String requested=query;
        if(lane.epoch>=0&&lane.epoch!=lane.source.epoch.getAsLong()){lane.tracks.clear();lane.offset=0;lane.more=true;}
        final int offset=lane.offset;final long epoch=lane.source.epoch.getAsLong();
        lane.epoch=epoch;lane.busy=true;lane.failed=false;lane.skipped=false;
        lane.future=workers.submit(()->{
            MusicProvider.Page<Track> page=null;boolean skipped=false,failed=false;int priority=100;
            try{
                if(!lane.source.enabled.getAsBoolean())skipped=true;
                else{priority=lane.source.priority.getAsInt();page=lane.source.provider.searchTracks(requested,offset,PAGE_SIZE);validate(page,offset,lane.source.provider.id());}
            }catch(Exception failure){failed=true;}
            final MusicProvider.Page<Track> response=page;final boolean unavailable=skipped,error=failed;final int rank=priority;
            main.post(()->{
                if(closed||generation!=token||lane.requestId!=request)return;
                lane.busy=false;lane.future=null;
                if(lane.timeout!=null)main.removeCallbacks(lane.timeout);lane.timeout=null;
                if(lane.source.epoch.getAsLong()!=epoch){lane.tracks.clear();lane.offset=0;lane.failed=true;publish();return;}
                lane.skipped=unavailable;lane.failed=error;lane.priority=rank;
                if(!error&&!unavailable){for(Track track:response.items)lane.tracks.put(LibraryStore.identity(track),track);lane.offset=response.nextOffset;lane.more=response.hasMore;}
                publish();
            });
        });
        lane.timeout=()->{if(closed||generation!=token||lane.requestId!=request||!lane.busy)return;lane.requestId++;lane.busy=false;lane.failed=true;if(lane.future!=null)lane.future.cancel(true);lane.future=null;lane.timeout=null;publish();};
        main.postDelayed(lane.timeout,15000);
    }
    private void launchArtist(ArtistLane lane){
        final long token=generation,request=++lane.requestId;final String requested=query;
        if(lane.epoch>=0&&lane.epoch!=lane.source.epoch.getAsLong()){lane.artists.clear();lane.offset=0;lane.more=true;}
        final int offset=lane.offset;final long epoch=lane.source.epoch.getAsLong();
        lane.epoch=epoch;lane.busy=true;lane.failed=false;lane.skipped=false;
        lane.future=artistWorkers.submit(()->{
            MusicProvider.Page<MusicProvider.Artist> page=null;boolean skipped=false,failed=false;
            try{if(!lane.source.provider.supportsArtistSearch()||!lane.source.enabled.getAsBoolean())skipped=true;
                else{page=lane.source.provider.searchArtists(requested,offset,PAGE_SIZE);validateArtists(page,offset);}}
            catch(Exception failure){failed=true;}
            final MusicProvider.Page<MusicProvider.Artist> response=page;final boolean unavailable=skipped,error=failed;
            main.post(()->{
                if(closed||generation!=token||lane.requestId!=request)return;
                lane.busy=false;lane.future=null;if(lane.timeout!=null)main.removeCallbacks(lane.timeout);lane.timeout=null;
                if(lane.source.epoch.getAsLong()!=epoch){lane.artists.clear();lane.offset=0;lane.failed=true;publish();return;}
                lane.skipped=unavailable;lane.failed=error;
                if(!error&&!unavailable){for(MusicProvider.Artist artist:response.items)lane.artists.put(artist.id,artist);lane.offset=response.nextOffset;lane.more=response.hasMore;}
                publish();
            });
        });
        lane.timeout=()->{if(closed||generation!=token||lane.requestId!=request||!lane.busy)return;lane.requestId++;lane.busy=false;lane.failed=true;if(lane.future!=null)lane.future.cancel(true);lane.future=null;lane.timeout=null;publish();};main.postDelayed(lane.timeout,15000);
    }
    private static void validateArtists(MusicProvider.Page<MusicProvider.Artist> page,int offset)throws ProviderException{
        if(page==null||page.items.size()>PAGE_SIZE||page.nextOffset<offset||page.hasMore&&(page.items.isEmpty()||page.nextOffset<=offset))throw invalid();
        for(MusicProvider.Artist artist:page.items)if(artist==null||artist.id==null||artist.id.trim().isEmpty()||artist.name==null||artist.name.trim().isEmpty())throw invalid();
    }
    private static void validate(MusicProvider.Page<Track> page,int offset,String provider)throws ProviderException {
        if(page==null||page.items.size()>PAGE_SIZE||page.nextOffset<offset||page.hasMore&&(page.items.isEmpty()||page.nextOffset<=offset))throw invalid();
        for(Track track:page.items)if(track==null||!provider.equals(OnlineMusic.providerId(track))||track.id.isEmpty()||track.uri.isEmpty())throw invalid();
    }
    private static ProviderException invalid(){return new ProviderException(ProviderException.Kind.INVALID_RESPONSE,"搜索结果不完整");}
    private void publish(){
        LinkedHashMap<String,ArrayList<Candidate>> groups=new LinkedHashMap<>();boolean loading=false,more=false;int failed=0,skipped=0;
        for(Lane lane:lanes){
            // Discard an already displayed account's results as soon as any UI update observes a change.
            if(lane.epoch>=0&&lane.epoch!=lane.source.epoch.getAsLong()){lane.tracks.clear();if(!lane.busy)lane.failed=true;}
            loading|=lane.busy;more|=!query.isEmpty()&&lane.more&&!lane.failed&&!lane.skipped;
            if(lane.failed)failed++;if(lane.skipped)skipped++;
            for(Track track:lane.tracks.values())groups.computeIfAbsent(versionKey(track),key->new ArrayList<>()).add(new Candidate(track,lane.priority));
        }
        ArrayList<Result> results=new ArrayList<>();
        for(java.util.Map.Entry<String,ArrayList<Candidate>> group:groups.entrySet()){
            group.getValue().sort(Comparator.comparingInt(candidate->candidate.priority));ArrayList<Track> tracks=new ArrayList<>();
            java.util.HashSet<String> ids=new java.util.HashSet<>();for(Candidate candidate:group.getValue())if(ids.add(LibraryStore.identity(candidate.track)))tracks.add(candidate.track);
            results.add(new Result(group.getKey(),tracks));
        }
        ArrayList<ArtistResult> artists=new ArrayList<>();boolean artistLoading=false,artistMore=false;int artistFailed=0,artistSkipped=0,artistSearched=0;
        for(ArtistLane lane:artistLanes){
            if(lane.epoch>=0&&lane.epoch!=lane.source.epoch.getAsLong()){lane.artists.clear();if(!lane.busy)lane.failed=true;}
            artistLoading|=lane.busy;artistMore|=!query.isEmpty()&&lane.more&&!lane.failed&&!lane.skipped;
            if(lane.failed)artistFailed++;if(lane.skipped)artistSkipped++;if(lane.epoch>=0&&!lane.busy&&!lane.skipped&&!lane.failed)artistSearched++;
            for(MusicProvider.Artist artist:lane.artists.values())artists.add(new ArtistResult(lane.source.provider.id(),artist));
        }
        current=new State(query,results,artists,loading,more,failed,skipped,artistLoading,artistMore,artistFailed,artistSkipped,artistSearched);if(listener!=null&&!closed)listener.changed(current);
    }
    /** Conservative identity: live/remix titles and different albums remain separate, unknown metadata never merges. */
    static String versionKey(Track track){
        if(track.durationMs<=0||track.album.isEmpty()||"未分类专辑".equals(track.album)||"未知专辑".equals(track.album)||"未知歌手".equals(track.artist))return "id:"+LibraryStore.identity(track);
        return norm(track.title)+"\u001f"+norm(track.artist)+"\u001f"+norm(track.album)+"\u001f"+Math.round(track.durationMs/1000d);
    }
    private static String norm(String value){return Normalizer.normalize(value,Normalizer.Form.NFKC).trim().replaceAll("\\s+"," ").toLowerCase(Locale.ROOT);}
    private void cancelRequests(){for(ArtistLane lane:artistLanes){lane.requestId++;if(lane.future!=null)lane.future.cancel(true);lane.future=null;lane.busy=false;if(lane.timeout!=null)main.removeCallbacks(lane.timeout);lane.timeout=null;}for(Lane lane:lanes){lane.requestId++;if(lane.future!=null)lane.future.cancel(true);lane.future=null;lane.busy=false;if(lane.timeout!=null)main.removeCallbacks(lane.timeout);lane.timeout=null;}}
    @Override public void close(){if(closed)return;closed=true;generation++;cancelRequests();listener=null;workers.shutdownNow();artistWorkers.shutdownNow();main.removeCallbacksAndMessages(null);}
    private static final class Candidate {final Track track;final int priority;Candidate(Track track,int priority){this.track=track;this.priority=priority;}}
    private static final class ArtistLane {
        final Source source;final LinkedHashMap<String,MusicProvider.Artist> artists=new LinkedHashMap<>();Future<?> future;Runnable timeout;
        long epoch=-1,requestId;int offset;boolean more=true,busy,failed,skipped;
        ArtistLane(Source source){this.source=source;}
    }
    private static final class Lane {
        final Source source;final LinkedHashMap<String,Track> tracks=new LinkedHashMap<>();Future<?> future;Runnable timeout;
        long epoch=-1,requestId;int offset,priority=100;boolean more=true,busy,failed,skipped;
        Lane(Source source){this.source=source;}
    }
}
