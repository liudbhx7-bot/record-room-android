package com.recordroom.vinyl;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.lang.ref.WeakReference;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** One session and adapter per app process, shared by browsing and background playback. */
public final class OnlineMusic {
    private static Context owner;
    private static MusicProvider netease;
    private static QQProvider qq;
    private static QishuiProvider qishui;
    private static AppleMusicProvider apple;
    private static AppleMusicSessionStore appleSession;
    private static ProviderSession qqSession,qishuiSession;
    private static Context additionalOwner;
    private static ProviderSession session;
    private static final Handler MAIN=new Handler(Looper.getMainLooper());
    private static final ExecutorService identityWorker=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"vinyl-account-changes");t.setDaemon(true);return t;});
    private static final CopyOnWriteArrayList<WeakReference<Runnable>> logoutListeners=new CopyOnWriteArrayList<>();
    interface LogoutCallback{void complete(boolean committed,ProviderException failure);}
    static void addLogoutListener(Runnable listener){logoutListeners.add(new WeakReference<>(listener));}
    static void removeLogoutListener(Runnable listener){logoutListeners.removeIf(ref->ref.get()==null||ref.get()==listener);}
    /** Once requested, logout survives page/Activity cancellation and cleans app-owned playback too. */
    static void logout(Context context,MusicProvider provider,LogoutCallback callback){
        Context app=context.getApplicationContext();
        identityWorker.execute(()->{
            boolean committed=false;ProviderException error=null;
            try{provider.logout();committed=true;OnlineCatalog.clear(app,provider.id());}
            catch(ProviderException failure){error=failure;}
            catch(RuntimeException failure){error=new ProviderException(ProviderException.Kind.STORAGE,"退出登录未完成，请重试");}
            final boolean cleared=committed;final ProviderException failure=error;
            MAIN.post(()->{
                if(cleared){
                    PlayerService.removeProviderAfterLogout(app,provider.id());
                    if("netease".equals(provider.id()))for(WeakReference<Runnable> ref:logoutListeners){Runnable listener=ref.get();if(listener==null)logoutListeners.remove(ref);else listener.run();}
                }
                callback.complete(cleared,failure);
            });
        });
    }
    private OnlineMusic(){}
    public static synchronized MusicProvider netease(Context context){
        Context app=context.getApplicationContext();
        if(owner!=app||netease==null){owner=app;session=new SecureSessionStore(app);netease=new NeteaseProvider(session);}
        return netease;
    }
    public static String providerId(Track track){
        if(track==null)return "";
        for(String id:new String[]{"netease","qq","qishui","applemusic"})if(id.equals(track.provider)||track.uri.startsWith(id+"://"))return id;
        if(!track.provider.isEmpty())return "unsupported";
        String scheme=android.net.Uri.parse(track.uri).getScheme();
        if(scheme!=null&&!java.util.Arrays.asList("content","file","asset","http","https","android.resource").contains(scheme))return "unsupported";
        return "";
    }
    public static boolean isNetease(Track track){return "netease".equals(providerId(track));}
    public static boolean isApple(Track track){return "applemusic".equals(providerId(track));}
    public static boolean isRemote(Track track){return !providerId(track).isEmpty();}
    /** Local credential hints only; called by the search worker, never an account-sync network request. */
    static synchronized boolean hasSearchAuthorization(Context context,String id){
        provider(context,id);
        try{
            if("netease".equals(id))return !session.readCookies().isEmpty();
            if("qq".equals(id))return !qqSession.readCookies().isEmpty();
            if("applemusic".equals(id))return appleSession.getUserToken()!=null&&(appleSession.getDeveloperToken()!=null||!appleSession.tokenServiceUrl().isEmpty());
        }catch(ProviderException ignored){}
        return false;
    }
    public static synchronized MusicProvider provider(Context context,String id){
        if("netease".equals(id))return netease(context);
        Context app=context.getApplicationContext();
        if(additionalOwner!=app){additionalOwner=app;qq=null;qishui=null;apple=null;appleSession=null;qqSession=null;qishuiSession=null;}
        if("qq".equals(id)){if(qq==null){qqSession=new QQSessionStore(app);qq=new QQProvider(qqSession);}return qq;}
        if("qishui".equals(id)){if(qishui==null){qishuiSession=new QishuiSessionStore(app);qishui=new QishuiProvider(qishuiSession);}return qishui;}
        if("applemusic".equals(id)){if(apple==null){appleSession=new AppleMusicSessionStore(app);apple=new AppleMusicProvider(appleSession);}return apple;}
        throw new IllegalArgumentException("Unsupported music provider");
    }
    public static PlaybackResource resolve(Context context,Track track) throws ProviderException {
        String id=providerId(track);if("qishui".equals(id))throw new ProviderException(ProviderException.Kind.UNAVAILABLE,"汽水音乐现仅支持官方 App 联动，请在音乐来源选择汽水联动");if(id.isEmpty()||"unsupported".equals(id))throw new ProviderException(ProviderException.Kind.UNAVAILABLE,"不支持此音乐来源");
        return provider(context,id).resolve(track);
    }
    static synchronized AppleMusicSessionStore appleSession(Context context){provider(context,"applemusic");return appleSession;}
    static synchronized long sessionGeneration(Context context,String providerId){
        if("unsupported".equals(providerId))return -1;
        provider(context,providerId);return "netease".equals(providerId)?session.generation():"qq".equals(providerId)?qqSession.generation():"qishui".equals(providerId)?qishuiSession.generation():appleSession.generation();
    }
    static synchronized long sessionGeneration(Context context){netease(context);return session.generation();}
    static MusicProvider.Account acceptOfficialWebCookies(Context context,String cookies) throws ProviderException {
        // Validate with the provider before replacing any current account. Never accept a page's JS message as proof.
        final String candidate=cookies==null?"":cookies;
        ProviderSession memory=new ProviderSession(){private String value=candidate;private long epoch;
            public synchronized String readCookies(){return value;}public synchronized void writeCookies(String next){value=next;}
            public synchronized void clear(){value="";epoch++;}public synchronized long generation(){return epoch;}};
        MusicProvider.Account account=new NeteaseProvider(memory).account();
        if(!account.loggedIn)throw new ProviderException(ProviderException.Kind.LOGIN_REQUIRED,"尚未完成网易云登录，请继续在官方页面操作");
        if(Thread.currentThread().isInterrupted())throw new ProviderException(ProviderException.Kind.LOGIN_REQUIRED,"登录操作已取消");
        ProviderSession target;synchronized(OnlineMusic.class){netease(context);target=session;}
        synchronized(target){target.clear();target.writeCookies(memory.readCookies());}
        return account;
    }
    static synchronized void useProviderForTests(Context context,MusicProvider provider,ProviderSession store){owner=context.getApplicationContext();netease=provider;session=store;}
}
