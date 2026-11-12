package com.recordroom.vinyl;

import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=34)
public class MusicSourcesQishuiLinkTest {
    static final class Actions implements MusicSourcesPage.Actions {
        final List<String> direct=new ArrayList<>(),selected=new ArrayList<>(),opened=new ArrayList<>();
        public void importAudio(){} public void back(){} public void local(){} public void netease(){} public void authorize(){}
        public void direct(String id){direct.add(id);}public void select(String id){selected.add(id);}public void open(String id){opened.add(id);}
    }
    static final class Access implements ExternalMusicBridge.Access {
        boolean installed;
        public boolean hasAccess(){return false;}public boolean isConnected(){return false;}
        public boolean isInstalled(String name){return installed&&"com.luna.music".equals(name);}
        public void start(Runnable changed){}public void stop(){}public void stopSessionWatch(){}
        public List<ExternalMusicBridge.Session> activeSessions(){return Collections.emptyList();}
        public Intent launchIntent(String name){return installed?new Intent("fixture"):null;}
    }
    static View find(View root,String text){if(text.contentEquals(root.getContentDescription()==null?"":root.getContentDescription()))return root;if(root instanceof TextView&&text.contentEquals(((TextView)root).getText()))return root;if(root instanceof ViewGroup){ViewGroup group=(ViewGroup)root;for(int i=0;i<group.getChildCount();i++){View hit=find(group.getChildAt(i),text);if(hit!=null)return hit;}}return null;}
    @Test public void qishuiHasNoDirectLoginButOtherAccountEntriesStillDispatch(){
        Actions actions=new Actions();MusicSourcesPage page=new MusicSourcesPage(RuntimeEnvironment.getApplication(),actions);
        assertNull(find(page,"汽水音乐 · 账号与曲库"));assertNull(find(page,"汽水音乐"));assertNotNull(find(page,"官方 App 联动"));
        find(page,"QQ 音乐 · 账号与曲库").performClick();find(page,"Apple Music · 账号与曲库").performClick();
        assertEquals(java.util.Arrays.asList("qq","applemusic"),actions.direct);assertFalse(actions.direct.contains("qishui"));
    }
    @Test public void installedQishuiDispatchesOnlyOfficialSessionAndOpenAppActions(){
        Actions actions=new Actions();MusicSourcesPage page=new MusicSourcesPage(RuntimeEnvironment.getApplication(),actions);Access access=new Access();access.installed=true;
        ExternalMusicBridge bridge=new ExternalMusicBridge(access,new Handler(Looper.getMainLooper()),page::update);bridge.start();page.update(bridge.getState());
        find(page,"官方 App 联动").performClick();ViewGroup panel=(ViewGroup)find(page,"汽水音乐").getParent();View select=find(panel,"联动播放");assertTrue(select.isEnabled());select.performClick();find(panel,"打开官方 App").performClick();
        assertEquals(Collections.singletonList("qishui_music"),actions.selected);assertEquals(Collections.singletonList("qishui_music"),actions.opened);assertTrue(actions.direct.isEmpty());assertFalse(bridge.getState().connected);bridge.close();
    }
    @Test public void missingQishuiIsDisabledAndDoesNotClaimAuthorizationOrPlayback(){
        MusicSourcesPage page=new MusicSourcesPage(RuntimeEnvironment.getApplication(),new Actions());ExternalMusicBridge bridge=new ExternalMusicBridge(new Access(),new Handler(Looper.getMainLooper()),page::update);bridge.start();page.update(bridge.getState());
        find(page,"官方 App 联动").performClick();ViewGroup panel=(ViewGroup)find(page,"汽水音乐").getParent();assertFalse(((Button)find(panel,"联动播放")).isEnabled());
        assertNotNull(find(panel,"未检测到汽水音乐，请先安装官方 App"));assertNotNull(find(page,"先开启播放联动权限"));assertFalse(bridge.getState().connected);bridge.close();
    }
}
