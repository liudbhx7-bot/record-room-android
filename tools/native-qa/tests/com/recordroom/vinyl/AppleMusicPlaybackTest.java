package com.recordroom.vinyl;

import android.content.Context;
import android.os.Handler;
import com.apple.android.music.playback.controller.MediaPlayerController;
import com.apple.android.music.playback.model.MediaPlayerException;
import com.apple.android.music.playback.model.PlaybackState;
import com.apple.android.music.playback.model.PlayerMediaItem;
import com.apple.android.music.playback.model.PlayerQueueItem;
import com.apple.android.music.playback.queue.CatalogPlaybackQueueItemProvider;
import com.apple.android.music.playback.queue.LibraryPlaybackQueueItemProvider;
import com.apple.android.music.playback.queue.PlaybackQueueItemProvider;
import com.apple.android.sdk.authentication.TokenProvider;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Executes the adapter and the shipped SDK queue builders; only the native controller boundary is replaced. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=34)
public class AppleMusicPlaybackTest {
    static class Tokens implements AppleMusicApi.Tokens,TokenProvider {
        String developer="fixture-developer",user="fixture-user";long epoch;
        public String developerToken(){return developer;}public String musicUserToken(){return user;}public long generation(){return epoch;}
        public String getDeveloperToken(){return developer;}public String getUserToken(){return user;}public String getAppleMusicStoreFrontId(){return "143465-19,32";}
    }
    static final class Controller implements AppleMusicPlayback.Factory {
        final List<String> calls=new ArrayList<>();MediaPlayerController.Listener listener,lastListener;
        PlayerQueueItem item;PlaybackQueueItemProvider provider;boolean autoplay,buffering,seek=true,next=true,previous=true;
        long position,duration=180000;int state=PlaybackState.STOPPED,index,count,created,cleared;long queueId;
        final MediaPlayerController sdk=(MediaPlayerController)Proxy.newProxyInstance(MediaPlayerController.class.getClassLoader(),new Class[]{MediaPlayerController.class},(p,m,a)->{
            String name=m.getName();
            if(name.equals("addListener")){listener=(MediaPlayerController.Listener)a[0];lastListener=listener;return null;}
            if(name.equals("removeListener")){assertSame(listener,a[0]);listener=null;calls.add(name);return null;}
            if(name.equals("getCurrentItem"))return item;
            if(name.equals("getPlaybackState"))return state;
            if(name.equals("getCurrentPosition"))return position;
            if(name.equals("getDuration"))return item==null?-1L:duration;
            if(name.equals("getPlaybackQueueIndex"))return index;
            if(name.equals("getPlaybackQueueItemCount"))return count;
            if(name.equals("isBuffering"))return buffering;
            if(name.equals("canSeek"))return seek;
            if(name.equals("canSkipToNextItem"))return next;
            if(name.equals("canSkipToPreviousItem"))return previous;
            if(name.equals("canSetRepeatMode")||name.equals("canSetShuffleMode"))return true;
            if(name.equals("prepare")){provider=(PlaybackQueueItemProvider)a[0];autoplay=(Boolean)a[1];calls.add("prepare:"+autoplay);return null;}
            if(name.equals("seekToPosition")){position=(Long)a[0];calls.add("seek:"+position);return null;}
            if(name.equals("play")){state=PlaybackState.PLAYING;calls.add("play");return null;}
            if(name.equals("pause")){state=PlaybackState.PAUSED;calls.add("pause");return null;}
            if(m.getReturnType()==Void.TYPE){calls.add(name);return null;}
            return zero(m.getReturnType());
        });
        public MediaPlayerController create(Context context,Handler handler,TokenProvider tokens){created++;assertNotNull(tokens.getUserToken());return sdk;}
        public void clearSession(Context context,MediaPlayerController controller){assertSame(sdk,controller);cleared++;}
        void loaded(Track track,long queueId){
            this.queueId=queueId;PlayerQueueItem before=item;PlayerMediaItem media=(PlayerMediaItem)Proxy.newProxyInstance(PlayerMediaItem.class.getClassLoader(),new Class[]{PlayerMediaItem.class},(p,m,a)->{
                switch(m.getName()){
                    case "getCloudLibraryUniversalId":return track.uri.contains("library-songs")?track.id:"";
                    case "getSubscriptionStoreId":case "getPlaybackStoreId":return track.uri.contains("library-songs")?"":track.id;
                    case "getTitle":return "SDK "+track.title;case "getArtistName":return track.artist;
                    case "getAlbumTitle":return track.album;case "getAlbumArtistName":return track.albumArtist;
                    case "getArtworkUrl":return track.artworkUri;case "getDuration":return track.durationMs;
                    case "isPlayableContent":return true;
                }return zero(m.getReturnType());
            });
            item=(PlayerQueueItem)Proxy.newProxyInstance(PlayerQueueItem.class.getClassLoader(),new Class[]{PlayerQueueItem.class},(p,m,a)->m.getName().equals("getItem")?media:m.getName().equals("getPlaybackQueueId")?queueId:zero(m.getReturnType()));
            count=1;state=autoplay?PlaybackState.PLAYING:PlaybackState.PAUSED;buffering=false;
            listener.onCurrentItemChanged(sdk,before,item);listener.onPlaybackStateChanged(sdk,PlaybackState.STOPPED,state);
        }
        void error(int type){listener.onPlaybackError(sdk,new MediaPlayerException(type,new RuntimeException("fixture-secret-token")));}
        static Object zero(Class<?> type){if(type==boolean.class)return false;if(type==int.class)return 0;if(type==long.class)return 0L;if(type==float.class)return 0f;return null;}
    }
    static Track song(String id,boolean library){return new Track(id,"歌曲 "+id,"音乐人","专辑","Apple Music","applemusic://"+(library?"library-songs/":"songs/")+id,"https://is1-ssl.mzstatic.com/image/fixture.jpg",180000,1,"音乐人","","","applemusic","","");}
    static AppleMusicPlayback adapter(Tokens tokens,Controller controller,List<AppleMusicPlayback.State> events){return new AppleMusicPlayback(RuntimeEnvironment.getApplication(),tokens,tokens,events::add,controller);}

    @Test public void missingCredentialsNeverCreatesNativeEngineOrClaimsReady(){
        Tokens tokens=new Tokens();Controller engine=new Controller();List<AppleMusicPlayback.State> events=new ArrayList<>();tokens.developer=null;AppleMusicPlayback player=adapter(tokens,engine,events);
        assertFalse(player.prepare(Collections.singletonList(song("111",false)),0,false));assertEquals(0,engine.created);assertEquals(AppleMusicPlayback.Error.CONFIGURATION,player.state().errorCode);assertFalse(player.state().ready);
        tokens.developer="fixture-developer";tokens.user=null;assertFalse(player.play(Collections.singletonList(song("111",false)),0));assertEquals(AppleMusicPlayback.Error.USER_AUTH_REQUIRED,player.state().errorCode);assertEquals(0,engine.created);player.close();
    }
    @Test public void catalogAndLibraryUseActualDistinctSdkBuildersWithoutEarlyPlayback(){
        Tokens tokens=new Tokens();Controller engine=new Controller();AppleMusicPlayback player=adapter(tokens,engine,new ArrayList<>());Track catalog=song("111",false),library=song("i.library",true);
        assertTrue(player.prepare(Collections.singletonList(catalog),0,false));assertTrue(engine.provider instanceof CatalogPlaybackQueueItemProvider);assertFalse(engine.autoplay);assertTrue(player.state().preparing);assertFalse(player.state().ready);assertNull(player.state().currentTrack);assertFalse(engine.calls.contains("play"));
        engine.loaded(catalog,101);assertTrue(player.state().ready);assertFalse(player.state().playing);assertEquals("SDK 歌曲 111",player.state().currentTrack.title);assertEquals(catalog.uri,player.state().currentTrack.uri);
        assertTrue(player.seek(45000));assertTrue(player.resume());assertTrue(engine.calls.indexOf("seek:45000")<engine.calls.indexOf("play"));assertTrue(player.state().playing);
        assertTrue(player.prepare(Collections.singletonList(library),0,false));assertTrue(engine.provider instanceof LibraryPlaybackQueueItemProvider);assertFalse(player.state().ready);assertNull(player.state().currentTrack);
        engine.loaded(library,102);assertEquals("i.library",player.state().currentTrack.id);assertEquals(library.uri,player.state().currentTrack.uri);assertEquals(1,engine.created);player.close();
    }
    @Test public void queueValidationPreservesIdentityAndRejectsMixedSourcesBeforePreparation(){
        Track a=song("111",false),b=song("222",false),library=song("i.library",true);AppleMusicPlayback.QueueSpec queue=AppleMusicPlayback.queueSpec(Arrays.asList(a,b,a),1);assertFalse(queue.library);assertArrayEquals(new String[]{"111","222","111"},queue.ids);
        Controller engine=new Controller();AppleMusicPlayback player=adapter(new Tokens(),engine,new ArrayList<>());assertFalse(player.play(Arrays.asList(a,library),0));assertEquals(0,engine.created);assertEquals(AppleMusicPlayback.Error.QUEUE,player.state().errorCode);
        Track wrong=new Track("111","错误","","","","applemusic://songs/222","",0,0,"","","","applemusic","","");assertFalse(player.play(Collections.singletonList(wrong),0));assertEquals(0,engine.created);player.close();
    }
    @Test public void transportHonorsSdkCapabilitiesAndSeekClampsToCurrentDuration(){
        Controller engine=new Controller();AppleMusicPlayback player=adapter(new Tokens(),engine,new ArrayList<>());Track song=song("111",false);assertTrue(player.prepare(Collections.singletonList(song),0,false));assertFalse(player.seek(1000));
        engine.loaded(song,1);engine.seek=false;engine.next=false;engine.previous=false;player.refresh();int before=engine.calls.size();assertFalse(player.seek(1000));assertFalse(player.next());assertFalse(player.previous());assertEquals(before,engine.calls.size());assertFalse(player.state().canSeek);
        engine.seek=true;engine.next=true;engine.previous=true;player.refresh();assertTrue(player.seek(999999));assertEquals(180000,engine.position);assertFalse(player.seek(-1));assertTrue(player.next());assertTrue(player.previous());assertTrue(player.pause());player.close();
    }
    @Test public void errorMappingIsHonestAndDoesNotExposeSdkTokensOrPretendCompletion(){
        Controller engine=new Controller();List<AppleMusicPlayback.State> events=new ArrayList<>();AppleMusicPlayback player=adapter(new Tokens(),engine,events);Track song=song("111",false);player.play(Collections.singletonList(song),0);engine.loaded(song,1);
        engine.error(MediaPlayerException.TYPE_USER_SUBSCRIPTION_INVALID);assertTrue(player.state().error);assertEquals(AppleMusicPlayback.Error.SUBSCRIPTION,player.state().errorCode);assertFalse(player.state().ready);assertFalse(player.state().ended);assertFalse(player.state().statusMessage.contains("fixture-secret"));
        engine.error(MediaPlayerException.TYPE_MUSIC_USER_TOKEN_ERROR);assertEquals(AppleMusicPlayback.Error.USER_AUTH_INVALID,player.state().errorCode);
        engine.error(MediaPlayerException.TYPE_DRM);assertEquals(AppleMusicPlayback.Error.DRM,player.state().errorCode);player.close();
    }
    @Test public void endCallbackIsDistinctAndDeduplicatedByRealQueueIdentity(){
        Controller engine=new Controller();List<AppleMusicPlayback.State> events=new ArrayList<>();AppleMusicPlayback player=adapter(new Tokens(),engine,events);Track song=song("111",false);player.play(Collections.singletonList(song),0);engine.loaded(song,41);
        engine.listener.onItemEnded(engine.sdk,engine.item,180000);assertTrue(player.state().ended);assertEquals(1,player.state().endEventId);int before=events.size();engine.listener.onItemEnded(engine.sdk,engine.item,180000);assertEquals(before,events.size());
        player.refresh();assertFalse(player.state().ended);assertEquals(1,player.state().endEventId);assertEquals(1,events.stream().filter(e->e.ended).count());player.close();
    }
    @Test public void accountGenerationChangeStopsClearsAndRejectsOldCallbacks(){
        Tokens tokens=new Tokens();Controller engine=new Controller();List<AppleMusicPlayback.State> events=new ArrayList<>();AppleMusicPlayback player=adapter(tokens,engine,events);Track song=song("111",false);player.play(Collections.singletonList(song),0);engine.loaded(song,1);MediaPlayerController.Listener old=engine.listener;
        tokens.epoch++;tokens.user=null;player.tokensChanged();assertEquals(1,engine.cleared);assertTrue(engine.calls.contains("release"));assertTrue(player.state().queue.isEmpty());assertEquals(AppleMusicPlayback.Error.SESSION_CHANGED,player.state().errorCode);int before=events.size();old.onCurrentItemChanged(engine.sdk,null,engine.item);old.onPlaybackStateChanged(engine.sdk,0,1);assertEquals(before,events.size());assertFalse(player.resume());player.close();
    }
    @Test public void closeReleasesOnceAndNeverEmitsLateState(){
        Controller engine=new Controller();List<AppleMusicPlayback.State> events=new ArrayList<>();AppleMusicPlayback player=adapter(new Tokens(),engine,events);player.prepare(Collections.singletonList(song("111",false)),0,false);MediaPlayerController.Listener old=engine.listener;
        player.close();player.close();assertEquals(1,Collections.frequency(engine.calls,"release"));assertEquals(1,Collections.frequency(engine.calls,"removeListener"));assertFalse(player.resume());assertFalse(player.state().initialized);int before=events.size();old.onPlaybackStateUpdated(engine.sdk);assertEquals(before,events.size());
    }

    @Test public void warmProcessWithPendingCleanupBlocksBeforeNativeCreationAndReportsRestart(){
        Tokens tokens=new Tokens();Controller engine=new Controller();boolean[] used={true};
        AppleMusicPlayback.Factory guarded=new AppleMusicPlayback.Factory(){
            public MediaPlayerController create(Context context,Handler handler,TokenProvider provider){AppleMusicPlayback.checkCleanupBoundary(true,used[0]);return engine.create(context,handler,provider);}
            public void clearSession(Context context,MediaPlayerController controller){engine.clearSession(context,controller);}
        };
        AppleMusicPlayback player=new AppleMusicPlayback(RuntimeEnvironment.getApplication(),tokens,tokens,state->{},guarded);
        assertFalse(player.prepare(Collections.singletonList(song("111",false)),0,false));assertEquals(0,engine.created);assertEquals(AppleMusicPlayback.Error.SESSION_CHANGED,player.state().errorCode);assertTrue(player.state().statusMessage.contains("完全关闭"));assertFalse(player.state().ready);
        // A cold process can perform cleanup before its first controller; this seam does not invoke JNI.
        used[0]=false;assertTrue(player.prepare(Collections.singletonList(song("111",false)),0,false));assertEquals(1,engine.created);assertFalse(player.state().ready);player.close();
        AppleMusicPlayback.checkCleanupBoundary(false,true);
    }
}
