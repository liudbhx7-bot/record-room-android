package com.recordroom.vinyl;

import android.content.Context;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** A bounded metadata cache, separate from imported local files and short-lived audio URLs. */
final class OnlineCatalog {
    private static final java.util.concurrent.ConcurrentHashMap<String,Snapshot> memory=new java.util.concurrent.ConcurrentHashMap<>();
    private static final Object writes=new Object();
    private static final class Snapshot {final String account;final ArrayList<Track> tracks;Snapshot(String account,List<Track> tracks){this.account=account;this.tracks=new ArrayList<>(tracks);}}
    private static File file(Context context,String provider){if(!java.util.Arrays.asList("netease","qq","qishui","applemusic").contains(provider))throw new IllegalArgumentException("Unknown provider");return new File(context.getFilesDir(),provider+"-catalog-v1.json");}
    private static String key(Context context,String provider){return file(context,provider).getAbsolutePath();}
    private static Snapshot snapshot(Context context,String provider){
        String key=key(context,provider);Snapshot found=memory.get(key);if(found!=null)return found;
        JSONObject disk=read(context,provider);Snapshot loaded=new Snapshot(disk.optString("account",""),tracks(disk,provider));
        Snapshot raced=memory.putIfAbsent(key,loaded);return raced==null?loaded:raced;
    }
    private static JSONObject read(Context context,String provider){
        File file=file(context,provider);if(!file.exists())return new JSONObject();
        try(InputStream in=new FileInputStream(file)){
            ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int count;while((count=in.read(buffer))!=-1){if(out.size()+count>16*1024*1024)return new JSONObject();out.write(buffer,0,count);}return new JSONObject(new String(out.toByteArray(),StandardCharsets.UTF_8));
        }catch(Exception ignored){return new JSONObject();}
    }
    static ArrayList<Track> load(Context context){return load(context,"netease");}
    static ArrayList<Track> load(Context context,String provider){return new ArrayList<>(snapshot(context,provider).tracks);}
    static String accountId(Context context){return accountId(context,"netease");}
    static String accountId(Context context,String provider){return snapshot(context,provider).account;}
    private static ArrayList<Track> tracks(JSONObject data,String provider){ArrayList<Track> result=new ArrayList<>();JSONArray values=data.optJSONArray("tracks");if(values!=null)for(int i=0;i<values.length();i++){JSONObject item=values.optJSONObject(i);if(item!=null){Track t=Track.fromJson(item);if(provider.equals(OnlineMusic.providerId(t)))result.add(t);}}return result;}
    /** Immediate read-your-writes for Activity recreation; no disk I/O on this path. */
    static void stage(Context context,String accountId,List<Track> values){stage(context,"netease",accountId,values);}
    static void stage(Context context,String provider,String accountId,List<Track> values){
        memory.compute(key(context,provider),(key,previous)->{
        LinkedHashMap<String,Track> all=new LinkedHashMap<>();
        if(previous!=null&&accountId.equals(previous.account))for(Track t:previous.tracks)all.put(LibraryStore.identity(t),t);
        for(Track t:values)if(provider.equals(OnlineMusic.providerId(t))){all.remove(LibraryStore.identity(t));all.put(LibraryStore.identity(t),t);}
        while(all.size()>10000)all.remove(all.keySet().iterator().next()); // Browsing/queues still retain the complete provider result.
        return new Snapshot(accountId,new ArrayList<>(all.values()));});
    }
    static void resetMemory(Context context){resetMemory(context,"netease");}
    static void resetMemory(Context context,String provider){memory.put(key(context,provider),new Snapshot("",new ArrayList<>()));}
    static void merge(Context context,String accountId,List<Track> values) throws ProviderException {snapshot(context,"netease");stage(context,accountId,values);persist(context);}
    static void persist(Context context) throws ProviderException {persist(context,"netease");}
    static void persist(Context context,String provider) throws ProviderException {synchronized(writes){
        Snapshot current=snapshot(context,provider);
        File temporary=null;
        try {
            JSONObject data=new JSONObject();data.put("account",current.account);JSONArray a=new JSONArray();for(Track t:current.tracks)a.put(t.toJson());data.put("tracks",a);
            temporary=File.createTempFile(provider+"-catalog-",".tmp",context.getFilesDir());
            try(FileOutputStream out=new FileOutputStream(temporary)){out.write(data.toString().getBytes(StandardCharsets.UTF_8));out.getFD().sync();}
            Files.move(temporary.toPath(),file(context,provider).toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        }catch(Exception failure){throw new ProviderException(ProviderException.Kind.STORAGE,"无法保存音乐资料库缓存，请检查存储空间");}
        finally{if(temporary!=null)temporary.delete();}
    }}
    static void clear(Context context) throws ProviderException {clear(context,"netease");}
    static void clear(Context context,String provider) throws ProviderException {resetMemory(context,provider);persist(context,provider);}
}
