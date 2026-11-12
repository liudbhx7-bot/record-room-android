package com.recordroom.vinyl;

import android.service.notification.NotificationListenerService;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.os.Build;
import android.provider.Settings;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.WeakHashMap;

/** Supplies the system-authorized media-session identity. Never reads notification contents. */
public final class ExternalMusicNotificationListener extends NotificationListenerService {
    private static final WeakHashMap<Runnable, Boolean> listeners = new WeakHashMap<>();
    private static boolean connected;
    private static WeakReference<ExternalMusicNotificationListener> owner=new WeakReference<>(null);

    static boolean isConnected() { return connected; }
    static void addConnectionListener(Runnable listener) { listeners.put(listener, Boolean.TRUE); }
    static void removeConnectionListener(Runnable listener) { listeners.remove(listener); }

    private static void connectionChanged(boolean value) {
        connected = value;
        for (Runnable listener : new ArrayList<>(listeners.keySet())) listener.run();
    }

    @Override public void onListenerConnected() {
        super.onListenerConnected();
        owner=new WeakReference<>(this);connectionChanged(true);
        VinylWidgetProvider.updateAll(this);
    }

    @Override public void onListenerDisconnected() {
        if(owner.get()==this||owner.get()==null){owner.clear();connectionChanged(false);}
        super.onListenerDisconnected();
        // A disconnection is recoverable; an enabled listener may ask the system to bind it again.
        ComponentName component=new ComponentName(this,ExternalMusicNotificationListener.class);
        try{
            boolean granted;
            if(Build.VERSION.SDK_INT>=27){NotificationManager manager=getSystemService(NotificationManager.class);granted=manager!=null&&manager.isNotificationListenerAccessGranted(component);}
            else{String values=Settings.Secure.getString(getContentResolver(),"enabled_notification_listeners");granted=false;if(values!=null)for(String value:values.split(":"))if(component.equals(ComponentName.unflattenFromString(value))){granted=true;break;}}
            if(granted)requestRebind(component);
        }catch(RuntimeException ignored){/* Foreground access checks also retry with bounded backoff. */}
    }

    @Override public void onDestroy() {
        if(owner.get()==this||owner.get()==null){owner.clear();connectionChanged(false);}
        super.onDestroy();
    }
}
