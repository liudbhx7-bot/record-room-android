package com.recordroom.vinyl;

import android.app.Activity;
import android.widget.FrameLayout;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

/** Catalog artist IDs are scoped to their provider; equal names do not prove equal artists. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=34)
public class ArtistIdentityIsolationTest {
    private static final String NAME="同名歌手";
    private static Track song(String key,String provider,String artistId,String albumArtistId){
        return new Track(key,key,NAME,"专辑 "+key,provider.isEmpty()?"本地音乐":provider,
                provider.isEmpty()?"content://local/"+key:provider+"://song/"+key,"",180000,1,
                NAME,"","",provider,"album-"+key,artistId,albumArtistId);
    }
    private static ArtistLibraryData data(Track... tracks){return new ArtistLibraryData(NAME,"qq","B",Arrays.asList(tracks),null,null);}
    @Test public void sameProviderKnownDifferentArtistNeverEntersSongsRecommendationsOrSeparateAlbums(){
        Track wrong=song("wrong","qq","A","A"),right=song("right","qq","B","B");
        ArtistLibraryData data=data(wrong,right);assertEquals(Collections.singletonList(right),data.songs);assertEquals(Collections.singletonList(right),data.recommended);
        assertFalse(data.matches(wrong));assertFalse(data.performs(wrong));assertEquals(1,data.albums.size());assertEquals("album-right",data.albums.get(0).remoteId);
        MusicProvider.Artist remote=new MusicProvider.Artist("B",NAME,"","",Arrays.asList(wrong,right),Collections.emptyList());
        ArtistLibraryData loaded=new ArtistLibraryData(NAME,"qq","B",Arrays.asList(wrong,right),null,remote);assertEquals(Collections.singletonList(right),loaded.songs);assertEquals(1,loaded.albums.size());
        MusicProvider.Artist wrongOnly=new MusicProvider.Artist("B",NAME,"","",Collections.singletonList(wrong),Collections.emptyList());
        assertFalse(new ArtistLibraryData(NAME,"qq","B",Collections.emptyList(),null,wrongOnly).providerSongs);
    }
    @Test public void sameNameOrSameIdStringInOtherProvidersIsNotAnIdentityMapping(){
        Track right=song("qq","qq","B","B"),net=song("net","netease","B","B"),apple=song("apple","applemusic","B","B"),unknown=song("unknown","netease","",""),local=song("local","","","");
        ArtistLibraryData data=data(net,apple,unknown,right,local);assertEquals(Arrays.asList(right,local),data.songs);assertEquals(2,data.albums.size());assertFalse(data.matches(net));assertFalse(data.matches(apple));assertFalse(data.matches(unknown));
    }
    @Test public void albumArtistMembershipDoesNotFalselyClaimTheTrackPerformerAndLocalFallbackStillWorks(){
        Track albumMember=song("compilation","qq","A","B"),local=song("local","","",""),legacy=song("legacy","qq","","");
        ArtistLibraryData data=data(albumMember,local,legacy);assertTrue(data.matches(albumMember));assertFalse(data.performs(albumMember));assertTrue(data.matches(local));assertTrue(data.performs(local));assertTrue(data.matches(legacy));assertEquals(3,data.songs.size());
        ArtistLibraryData nameOnly=new ArtistLibraryData(NAME,"","",Arrays.asList(local),local,null);assertEquals(Collections.singletonList(local),nameOnly.songs);
    }
    @Test public void realArtistEntryFiltersTheFullCacheBeforeRemoteMetadataArrives(){
        org.robolectric.android.controller.ActivityController<Activity> owner=Robolectric.buildActivity(Activity.class).setup();
        try{
            Track wrong=song("A","qq","A","A"),right=song("B","qq","B","B"),otherSource=song("net","netease","B","B");
            MusicProvider.Artist identity=new MusicProvider.Artist("B",NAME,"","",Collections.emptyList(),Collections.emptyList());
            FrameLayout page=ArtistPage.create(owner.get(),Arrays.asList(wrong,otherSource,right),"qq",identity,new ArtistPage.Actions(){public void back(){}public void play(ArrayList<Track> songs,int index,boolean shuffle){}public void album(ArrayList<Track> songs){}public void share(String title,String subtitle,ArrayList<Track> songs){}});
            assertNull(ReflectionHelpers.getField(page,"remote"));ArtistLibraryData data=ReflectionHelpers.getField(page,"data");assertEquals(Collections.singletonList(right),data.songs);assertEquals(1,data.albums.size());assertEquals("album-B",data.albums.get(0).remoteId);
        }finally{owner.pause().stop().destroy();}
    }
}
