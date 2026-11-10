package com.recordroom.vinyl;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Public catalog metadata only: no real session, token, or media. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=34)
public class QQPlaybackAccessTest {
    private static JSONObject row(String pay,String action)throws Exception {
        JSONObject result=new JSONObject("{\"mid\":\"0029Zemv0kR1ur\",\"name\":\"葡萄成熟时\"}");
        if(pay!=null)result.put("pay",new JSONObject(pay));
        if(action!=null)result.put("action",new JSONObject(action));
        return result;
    }
    private static Track mapped(String pay,String action)throws Exception{return QQProvider.track(row(pay,action));}
    private static void access(Track.PlaybackAccess expected,String pay,String action)throws Exception{assertEquals(expected,mapped(pay,action).playbackAccess);}
    @Test public void currentQqSearchVipRequirementSurvivesMapperAndPersistence()throws Exception {
        Track mapped=mapped("{\"pay_down\":1,\"pay_month\":1,\"pay_play\":1,\"pay_status\":0,\"price_album\":0,\"price_track\":200,\"time_free\":0}","{\"alert\":21}");
        assertEquals("VIP",mapped.toJson().optString("playbackAccess"));
        assertEquals("VIP",Track.fromJson(mapped.toJson()).toJson().optString("playbackAccess"));
        assertEquals("需 VIP",mapped.accessLabel());
    }
    @Test public void actualDownloadOnlyPayFieldsNeverBecomeVipStreaming()throws Exception {
        // Live anonymous search: 天空之城 / 001hskFn1KsULf. price_track is also nonzero for download-only fees.
        access(Track.PlaybackAccess.STANDARD,"{\"pay_play\":0,\"pay_down\":1,\"pay_month\":1,\"price_track\":200,\"price_album\":0}",null);
        access(Track.PlaybackAccess.STANDARD,"{\"pay_play\":0,\"price_album\":3000}",null);
        assertEquals("",mapped("{\"pay_play\":0}",null).accessLabel());
    }
    @Test public void albumPriceDoesNotOverrideActualMembershipPlaybackAlert()throws Exception {
        // Live 最伟大的作品 has price_album=3000, but official alert 41 explicitly requests membership.
        access(Track.PlaybackAccess.VIP,"{\"pay_play\":1,\"pay_month\":1,\"price_album\":3000,\"price_track\":400}","{\"alert\":41}");
        access(Track.PlaybackAccess.UNKNOWN,"{\"pay_play\":1,\"price_album\":3000,\"price_track\":400}",null);
    }
    @Test public void verifiedSeparatePurchaseAlertsRemainDistinctFromMembership()throws Exception {
        for(int alert:new int[]{6,7,17,28,63,64,65,68}) {
            Track value=mapped("{\"pay_play\":1,\"pay_month\":1,\"price_album\":3000}","{\"alert\":"+alert+"}");
            assertEquals("alert "+alert,Track.PlaybackAccess.PURCHASE,value.playbackAccess);assertEquals("需单独购买",value.accessLabel());
        }
        access(Track.PlaybackAccess.UNKNOWN,"{\"pay_play\":1,\"price_album\":3000}","{\"alert\":39}"); // Purchase does not enable streaming.
    }
    @Test public void malformedMissingAndNewPermissionValuesRemainUnknown()throws Exception {
        for(String pay:new String[]{null,"{}","{\"pay_down\":1}","{\"pay_play\":null}","{\"pay_play\":true}","{\"pay_play\":\"unexpected\"}","{\"pay_play\":-1}","{\"pay_play\":2}","{\"pay_play\":1.5}","{\"pay_play\":1}"})access(Track.PlaybackAccess.UNKNOWN,pay,null);
        access(Track.PlaybackAccess.UNKNOWN,"{\"pay_play\":1,\"pay_month\":1,\"price_album\":0}","{\"alert\":999}");
        access(Track.PlaybackAccess.UNKNOWN,null,"{\"alert\":21,\"icons\":262144}");
        access(Track.PlaybackAccess.UNKNOWN,"{\"pay_play\":1,\"pay_month\":1,\"price_album\":0}","{\"alert\":\"new-code\"}");
    }
    @Test public void publishedLegacyAliasesAndWrappedRowsRetainAccess()throws Exception {
        JSONObject old=row("{\"payplay\":\"1\",\"paytrackmouth\":1,\"payalbumprice\":0}",null);
        for(String wrapper:new String[]{"songInfo","songinfo","track_info","song"})assertEquals(Track.PlaybackAccess.VIP,QQProvider.track(new JSONObject().put(wrapper,old)).playbackAccess);
        old.put("alertid",7);assertEquals(Track.PlaybackAccess.PURCHASE,QQProvider.track(old).playbackAccess);
    }
    @Test public void oldAndFutureJsonRemainUnknownWhileCopyPreservesIdentity()throws Exception {
        Track base=new Track("id","Title","Singer","Album","Local","content://music/1","content://art/1",123456,4,"Album singer","Jazz","2026","qq","Album01","Singer01","AlbumSinger01");
        assertEquals(Track.PlaybackAccess.UNKNOWN,base.playbackAccess);assertEquals("",base.accessLabel());
        for(Track.PlaybackAccess access:Track.PlaybackAccess.values()){
            Track changed=base.withPlaybackAccess(access);assertEquals(base.uri,changed.uri);assertEquals(base.id,changed.id);assertEquals(base.albumKey(),changed.albumKey());assertEquals(base.albumArtistId,changed.albumArtistId);assertEquals(base.durationMs,changed.durationMs);
            JSONObject expected=base.toJson();expected.put("playbackAccess",access.name());assertEquals(expected.toString(),changed.toJson().toString());assertEquals(access,Track.fromJson(changed.toJson()).playbackAccess);
        }
        JSONObject old=base.toJson();old.remove("playbackAccess");assertEquals(Track.PlaybackAccess.UNKNOWN,Track.fromJson(old).playbackAccess);
        for(Object invalid:new Object[]{"unknown-new-tier","vip",1,true,JSONObject.NULL}){old.put("playbackAccess",invalid);assertEquals(Track.PlaybackAccess.UNKNOWN,Track.fromJson(old).playbackAccess);}
        assertEquals(Track.PlaybackAccess.UNKNOWN,base.withPlaybackAccess(null).playbackAccess);
    }
    @Test public void searchAndCanonicalFullAlbumKeepEachSongsRequirement()throws Exception {
        JSONObject vip=row("{\"pay_play\":1}","{\"alert\":21}");
        JSONObject purchase=row("{\"pay_play\":1}","{\"alert\":7}").put("mid","SongBuy");
        QQProviderTest.Http http=new QQProviderTest.Http((url,body,cookies)->{
            try{
            if(body.has("req"))return new JSONObject().put("code",0).put("req",new JSONObject().put("code",0).put("data",new JSONObject().put("body",new JSONObject().put("item_song",new JSONArray().put(vip).put(purchase))).put("meta",new JSONObject().put("sum",2))));
            if(body.has("album"))return new JSONObject().put("code",0).put("album",new JSONObject().put("code",0).put("data",new JSONObject().put("basicInfo",new JSONObject().put("albumMid","Album01").put("albumName","Canonical Album"))));
            return new JSONObject().put("code",0).put("tracks",new JSONObject().put("code",0).put("data",new JSONObject().put("albumMid","Album01").put("totalNum",2).put("songList",new JSONArray().put(vip).put(purchase))));
            }catch(org.json.JSONException failure){throw new AssertionError(failure);}
        });
        QQProvider provider=new QQProvider(new QQProviderTest.Session(),http,new QQProviderTest.Audio());
        assertEquals(Track.PlaybackAccess.VIP,provider.searchTracks("query",0,2).items.get(0).playbackAccess);
        MusicProvider.Album album=provider.album("Album01");assertEquals("Canonical Album",album.tracks.get(0).album);assertEquals(Track.PlaybackAccess.VIP,album.tracks.get(0).playbackAccess);assertEquals(Track.PlaybackAccess.PURCHASE,album.tracks.get(1).playbackAccess);
    }
}
