package com.recordroom.vinyl;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.os.Bundle;
import android.view.ViewGroup;
import androidx.emoji2.text.EmojiCompatInitializer;
import androidx.lifecycle.ProcessLifecycleInitializer;
import androidx.profileinstaller.ProfileInstallerInitializer;
import androidx.startup.AppInitializer;
import androidx.startup.InitializationProvider;
import java.lang.reflect.Field;
import java.util.Arrays;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import static org.junit.Assert.*;

/** Android installs manifest ContentProviders before launching any Activity; Activity-only tests miss this path. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=34)
@LooperMode(LooperMode.Mode.PAUSED)
public class NativeStartupProviderTest {
    private static final String EMOJI=EmojiCompatInitializer.class.getName();
    private static final String PROCESS=ProcessLifecycleInitializer.class.getName();
    private static final String PROFILE=ProfileInstallerInitializer.class.getName();

    @Before public void resetProcessStartupSingleton()throws Exception{
        // Each variant must exercise cold metadata discovery, never reuse another test's initialized cache.
        Field singleton=AppInitializer.class.getDeclaredField("sInstance");singleton.setAccessible(true);singleton.set(null,null);
    }

    private ProviderInfo manifestProvider()throws Exception{
        Context app=RuntimeEnvironment.getApplication();
        ProviderInfo info=app.getPackageManager().getProviderInfo(new ComponentName(app,InitializationProvider.class),PackageManager.GET_META_DATA);
        assertNotNull(info.metaData);
        for(String initializer:Arrays.asList(EMOJI,PROCESS,PROFILE))assertEquals("Manifest must retain every automatic initializer","androidx.startup",info.metaData.getString(initializer));
        return info;
    }
    private void boot(ProviderInfo info){
        Context app=RuntimeEnvironment.getApplication();
        // attachInfo invokes the real library provider's onCreate, including manifest discovery and dependencies.
        new InitializationProvider().attachInfo(app,info);
        AppInitializer initialized=AppInitializer.getInstance(app);
        assertTrue(initialized.isEagerlyInitialized(EmojiCompatInitializer.class));
        assertTrue(initialized.isEagerlyInitialized(ProcessLifecycleInitializer.class));
        assertTrue(initialized.isEagerlyInitialized(ProfileInstallerInitializer.class));
        assertNotNull(initialized.initializeComponent(ProcessLifecycleInitializer.class));
    }
    private void bootWithDeclarationOrder(String... order)throws Exception{
        ProviderInfo info=manifestProvider();Bundle metadata=new Bundle();
        for(String name:order)metadata.putString(name,info.metaData.getString(name));
        info.metaData=metadata;
        Shadows.shadowOf(RuntimeEnvironment.getApplication().getPackageManager()).addOrUpdateProvider(info);
        boot(info);
    }

    @Test public void mergedManifestProvidersCompleteBeforeMainActivityLaunches()throws Exception{
        boot(manifestProvider());
        ServiceController<PlayerService> service=Robolectric.buildService(PlayerService.class).create();
        Context app=RuntimeEnvironment.getApplication();
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).setComponentNameAndServiceForBindService(new ComponentName(app,PlayerService.class),service.get().onBind(new Intent(app,PlayerService.class)));
        ActivityController<MainActivity> activity=Robolectric.buildActivity(MainActivity.class);
        try{activity.setup().visible();ViewGroup content=activity.get().findViewById(android.R.id.content);assertNotNull(content);assertTrue(content.getChildCount()>0);}
        finally{activity.pause().stop().destroy();service.destroy();}
    }
    @Test public void emojiDeclaredBeforeProcessStillDiscoversAllEagerInitializers()throws Exception{
        bootWithDeclarationOrder(EMOJI,PROCESS,PROFILE);
    }
    @Test public void changingManifestDeclarationOrderCannotHideTheStartupFailure()throws Exception{
        // Bundle iteration follows key ordering, so merely moving XML elements is not a valid repair.
        bootWithDeclarationOrder(PROFILE,PROCESS,EMOJI);
    }
}
