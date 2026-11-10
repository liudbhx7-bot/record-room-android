package com.recordroom.vinyl;

import android.content.Context;
import android.content.SharedPreferences;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=34)
public class PlayerPersistenceTest {
    private ServiceController<PlayerService> controller;
    private PlayerService player;
    private PlayerStateStore store;
    private CountDownLatch release;
    @Before public void setup() {
        RuntimeEnvironment.getApplication().getSharedPreferences(PlayerService.PREFS,Context.MODE_PRIVATE).edit().clear().commit();
        controller=Robolectric.buildService(PlayerService.class).create();player=controller.get();store=PlayerStateStore.get(player);
    }
    @After public void cleanup()throws Exception{if(release!=null)release.countDown();if(controller!=null)controller.destroy();drain();}
    private static Object field(Object target,String name)throws Exception{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}
    private ExecutorService writer()throws Exception{return (ExecutorService)field(store,"writer");}
    private void drain()throws Exception{writer().submit(()->{}).get(30,TimeUnit.SECONDS);}
    private long encodes()throws Exception{return (Long)field(store,"queueEncodeCount");}
    private void blockWriter()throws Exception{
        drain();CountDownLatch entered=new CountDownLatch(1);release=new CountDownLatch(1);
        writer().execute(()->{entered.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}});
        assertTrue(entered.await(5,TimeUnit.SECONDS));
    }
    private void unblock()throws Exception{release.countDown();release=null;drain();}
    private static ArrayList<Track> tracks(){ArrayList<Track> tracks=new ArrayList<>();for(int i=0;i<1200;i++)tracks.add(new Track("t"+i,"Song "+i,"Artist","Album","local","file:///song"+i,"",240000,i));return tracks;}
    private SharedPreferences preferences(){return player.getSharedPreferences(PlayerService.PREFS,Context.MODE_PRIVATE);}

    @Test public void largeQueueIsEncodedOffThreadOnceAndScalarEventsReuseImmutableSnapshot()throws Exception{
        blockWriter();long before=encodes(),started=System.nanoTime();player.setQueue(tracks(),17,false);
        long mainMicros=(System.nanoTime()-started)/1000;List<Track> snapshot=player.getQueue();long revision=player.getQueueRevision();
        assertEquals(1200,player.getQueueSize());assertSame(snapshot,player.getQueue());
        assertEquals("Blocked IO proves setQueue does no JSON serialization",before,encodes());
        try{snapshot.clear();fail("Published queue must be immutable");}catch(UnsupportedOperationException expected){}
        unblock();assertEquals(before+1,encodes());
        long scalarStart=System.nanoTime();
        for(int i=0;i<200;i++){player.seekTo(i*10);player.setVolume(i/200f);player.setPlayMode(i%4);}
        drain();assertEquals("Position/mode/volume updates must not encode the 1200 tracks again",before+1,encodes());
        assertEquals(revision,player.getQueueRevision());assertSame(snapshot,player.getQueue());
        assertEquals(1990,preferences().getLong("position",-1));assertEquals(3,preferences().getInt("mode",-1));
        assertEquals(.995f,preferences().getFloat("volume",-1),.0001f);
        System.out.println("PLAYER_PERSISTENCE_JVM queue=1200 setQueue_main_us="+mainMicros+" scalar_events=600 elapsed_us="+((System.nanoTime()-scalarStart)/1000)+" queue_serializations=1 (not device timing)");
    }

    @Test public void rapidReordersCoalesceToLatestAtomicQueueAndIndex()throws Exception{
        player.setQueue(tracks(),0,false);drain();long before=encodes();List<Track> original=player.getQueue();long oldRevision=player.getQueueRevision();
        blockWriter();
        for(int i=0;i<80;i++){player.moveQueueItem(0,1199);player.seekTo(1000+i);}
        PlayerStateStore.State pending=(PlayerStateStore.State)field(store,"pending");
        assertSame(store.restore(),pending);assertEquals(player.getQueueRevision(),pending.queue.revision);
        assertTrue(player.getQueueRevision()>oldRevision);assertEquals("t0",original.get(0).id);
        assertEquals("t0",player.getCurrent().id);assertEquals(1120,player.getIndex());
        assertEquals(before,encodes());unblock();assertEquals("Only latest queued revision should be encoded",before+1,encodes());
        JSONArray persisted=new JSONArray(preferences().getString("queue","[]"));assertEquals(1200,persisted.length());
        int selected=preferences().getInt("index",-1);assertEquals(player.getIndex(),selected);
        assertEquals("t0",persisted.getJSONObject(selected).getString("id"));
        assertEquals(player.getQueue().get(0).id,persisted.getJSONObject(0).getString("id"));assertEquals(1079,preferences().getLong("position",-1));
        System.out.println("PLAYER_PERSISTENCE_COALESCE queue=1200 mutations=80 pending_slots=1 queue_serializations=1");
    }

    @Test public void serviceRecreationReadsLatestMemoryAndRejectsOlderSessionSave()throws Exception{
        player.setQueue(tracks(),1,false);drain();blockWriter();player.setQueue(tracks(),0,false);player.seekTo(12345);player.moveQueueItem(0,1199);player.setVolume(.4f);
        PlayerService old=player;controller.destroy();controller=null;
        assertEquals("Disk intentionally remains behind while worker is blocked",1,preferences().getInt("index",-1));
        assertEquals("Detached widget must use the latest in-memory queue while disk lags","t0",PlayerService.widgetTrack(old).id);
        assertEquals(1199,store.restore().index);
        controller=Robolectric.buildService(PlayerService.class).create();player=controller.get();
        assertEquals(1200,player.getQueueSize());assertEquals(1199,player.getIndex());assertEquals("t0",player.getCurrent().id);
        assertEquals(12345,player.getPositionMs());assertEquals(.4f,player.getVolume(),.001f);player.seekTo(54321);
        Method persist=PlayerService.class.getDeclaredMethod("persist");persist.setAccessible(true);persist.invoke(old);
        assertEquals("Old service epoch must not replace newer in-memory state",54321,store.restore().position);
        unblock();assertEquals(54321,preferences().getLong("position",-1));assertEquals(1199,preferences().getInt("index",-1));
        assertEquals("t0",PlayerService.widgetTrack(player).id);
    }

    @Test public void queueRevisionChangesForMetadataAndRemovalButNotNoops()throws Exception{
        ArrayList<Track> source=tracks();player.setQueue(source,4,false);long revision=player.getQueueRevision();
        player.moveQueueItem(4,4);player.enqueue(new ArrayList<>(),false);assertEquals(revision,player.getQueueRevision());
        Track old=source.get(4);source.set(4,new Track(old.id,"Edited",old.artist,old.album,old.source,old.uri,old.artworkUri,old.durationMs,old.trackNumber));
        player.refreshMetadata(source);assertTrue(player.getQueueRevision()>revision);assertEquals("Edited",player.getCurrent().title);
        List<Track> retained=player.getQueue();revision=player.getQueueRevision();player.removeQueueItem(0);
        assertTrue(player.getQueueRevision()>revision);assertEquals(1199,player.getQueueSize());assertEquals(1200,retained.size());
        drain();assertEquals(1199,new JSONArray(preferences().getString("queue","[]")).length());
    }

    @Test public void coldStoreReadsEstablishedPreferencesWithoutMigration()throws Exception{
        JSONArray legacy=new JSONArray();legacy.put(tracks().get(0).toJson());legacy.put(tracks().get(1).toJson());
        preferences().edit().putString("queue",legacy.toString()).putInt("index",1).putLong("position",9000).putBoolean("ended",true).putInt("mode",2).putFloat("volume",.25f).commit();
        java.lang.reflect.Constructor<PlayerStateStore> constructor=PlayerStateStore.class.getDeclaredConstructor(SharedPreferences.class);constructor.setAccessible(true);
        PlayerStateStore cold=constructor.newInstance(preferences());PlayerStateStore.State state=cold.restore();
        assertEquals(2,state.queue.tracks.size());assertEquals("t1",state.queue.tracks.get(state.index).id);assertEquals(9000,state.position);assertTrue(state.ended);assertEquals(2,state.mode);assertEquals(.25f,state.volume,.001f);
    }

    /** Drop only the process cache after the old service has drained, keeping real legacy preference data. */
    private PlayerStateStore.State coldRestore(JSONArray queue,int selected)throws Exception{
        if(controller!=null){controller.destroy();controller=null;}
        drain();
        Context app=RuntimeEnvironment.getApplication();
        app.getSharedPreferences(PlayerService.PREFS,Context.MODE_PRIVATE).edit()
                .putString("queue",queue.toString()).putInt("index",selected).putLong("position",9000)
                .putBoolean("ended",true).putInt("mode",2).putFloat("volume",.25f).commit();
        Field instance=PlayerStateStore.class.getDeclaredField("instance");instance.setAccessible(true);instance.set(null,null);
        store=PlayerStateStore.get(app);
        return store.restore();
    }

    private static Track invalidTrack(){return new Track("invalid","Unavailable","Artist","Album","local","","",240000,1);}

    @Test public void coldRestoreKeepsOriginalSelectionWhenEarlierInvalidTracksAreRemoved()throws Exception{
        ArrayList<Track> songs=tracks();
        JSONArray legacy=new JSONArray().put(invalidTrack().toJson()).put(songs.get(0).toJson()).put(songs.get(1).toJson());
        PlayerStateStore.State restored=coldRestore(legacy,1);
        assertEquals(2,restored.queue.tracks.size());assertEquals(0,restored.index);
        assertEquals("t0",restored.queue.tracks.get(restored.index).id);assertEquals(9000,restored.position);assertTrue(restored.ended);
        assertEquals("Detached widget must show the originally selected valid track","t0",PlayerService.widgetTrack(RuntimeEnvironment.getApplication()).id);
        controller=Robolectric.buildService(PlayerService.class).create();player=controller.get();
        assertEquals("t0",player.getCurrent().id);assertEquals(0,player.getIndex());assertEquals(9000,player.getPositionMs());
        assertEquals(2,player.getPlayMode());assertEquals(.25f,player.getVolume(),.001f);
    }

    @Test public void coldRestoreMissingSelectionChoosesNeighborWithoutForeignProgress()throws Exception{
        ArrayList<Track> songs=tracks();
        JSONArray legacy=new JSONArray().put(songs.get(0).toJson()).put(invalidTrack().toJson())
                .put(songs.get(1).toJson()).put(invalidTrack().toJson());
        int[] indices={1,3,-5,99};String[] expected={"t1","t1","t0","t1"};
        for(int i=0;i<indices.length;i++){
            PlayerStateStore.State restored=coldRestore(legacy,indices[i]);
            assertEquals(expected[i],restored.queue.tracks.get(restored.index).id);
            assertEquals("A replacement selection must start at its own beginning",0,restored.position);
            assertFalse("Completion belongs to the old selection",restored.ended);
            assertEquals(expected[i],PlayerService.widgetTrack(RuntimeEnvironment.getApplication()).id);
        }
        PlayerStateStore.State empty=coldRestore(new JSONArray().put(invalidTrack().toJson()),0);
        assertTrue(empty.queue.tracks.isEmpty());assertEquals(-1,empty.index);assertEquals(0,empty.position);assertFalse(empty.ended);
        assertNull(PlayerService.widgetTrack(RuntimeEnvironment.getApplication()));
    }
}
