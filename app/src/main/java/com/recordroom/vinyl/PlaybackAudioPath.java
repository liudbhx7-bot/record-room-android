package com.recordroom.vinyl;

import android.content.Context;
import android.media.*;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import androidx.media3.exoplayer.audio.AudioOutput;
import androidx.media3.exoplayer.audio.AudioOutputProvider;
import androidx.media3.exoplayer.audio.AudioTrackAudioOutput;
import androidx.media3.exoplayer.audio.AudioTrackAudioOutputProvider;
import androidx.media3.exoplayer.audio.ForwardingAudioOutputProvider;
import androidx.media3.exoplayer.audio.ForwardingAudioOutput;
import android.net.Uri;
import java.util.LinkedHashMap;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.DefaultAudioSink;
import java.util.List;
import java.util.Locale;

/** One observation per player. An AudioTrack describes the stream sent to Android,
 * not the sample rate or analogue output of a speaker, headphones or DAC. */
final class PlaybackAudioPath {
    private static final class Details {
        final String name;final int rate,bits;
        Details(String name,int rate,int bits){this.name=name;this.rate=rate;this.bits=bits;}
    }
    private final LinkedHashMap<Uri,Details> sources=new LinkedHashMap<>(4,.75f,true);
    private Uri currentUri;
    private volatile AudioTrack track;
    private final boolean usbFollow;
    private final AudioManager manager;
    private boolean closed;
    private AudioDeviceInfo usb;
    private Object preference;
    private AudioAttributes preferredAttributes;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable routeChanged;
    private AudioRouting.OnRoutingChangedListener routingListener;
    private int initialUsbId;

    PlaybackAudioPath(Context c,boolean follow,Runnable routeChanged) {
        this.routeChanged=routeChanged;
        manager=(AudioManager)c.getSystemService(Context.AUDIO_SERVICE);
        usbFollow=follow;
    }
    synchronized void selectSource(Uri uri){currentUri=uri;}
    synchronized void source(Uri uri,String name,int rate,int bits){
        sources.put(uri,new Details(name,rate,bits));
        if(sources.size()>3){
            for(Uri candidate:new java.util.ArrayList<>(sources.keySet())){
                if(!candidate.equals(currentUri)){sources.remove(candidate);break;}
            }
        }
    }
    DefaultRenderersFactory renderers(Context c){
        return new DefaultRenderersFactory(c){
            @Override protected AudioSink buildAudioSink(Context context,boolean enableFloat,boolean params){
                AudioTrackAudioOutputProvider platform=new AudioTrackAudioOutputProvider.Builder(context).build();
                AudioOutputProvider provider=new ForwardingAudioOutputProvider(platform) {
                    @Override public AudioOutput getAudioOutput(OutputConfig config) throws InitializationException {
                        synchronized (PlaybackAudioPath.this) {
                            detachRoute();
                            clearUsb();
                            OutputConfig physical=config;
                            AudioAttributes attrs=config.audioAttributes.getPlatformAudioAttributes();
                            if(!closed && usbFollow && Build.VERSION.SDK_INT>=34) {
                                initialUsbId=Api34.routeId(PlaybackAudioPath.this,attrs);
                                physical=Api34.request(PlaybackAudioPath.this,config,attrs);
                            }
                            AudioTrackAudioOutput output;
                            try {
                                output=platform.getAudioOutput(physical);
                            } catch (InitializationException | RuntimeException failed) {
                                boolean retry=usb!=null;
                                clearUsb();
                                if(!retry) throw failed;
                                physical=config;
                                output=platform.getAudioOutput(config);
                            }
                            if(!closed) {
                                track=output.getAudioTrack();
                                if(usbFollow && Build.VERSION.SDK_INT>=34) observeRoute(track,attrs);
                            }
                            AudioTrack owned=output.getAudioTrack();
                            AudioOutput routed=new ForwardingAudioOutput(output) {
                                @Override public void release() {
                                    synchronized(PlaybackAudioPath.this) {
                                        if(track==owned){detachRoute();clearUsb();}
                                    }
                                    super.release();
                                }
                            };
                            return physical.encoding==config.encoding ? routed : new UsbPcmOutput(routed,config.encoding,physical.encoding);
                        }
                    }
                };
                return new DefaultAudioSink.Builder(context).setEnableFloatOutput(true)
                    .setEnableAudioOutputPlaybackParameters(params).setAudioOutputProvider(provider).build();
            }
        }.setEnableAudioFloatOutput(true);
    }
    private static String rate(int hz){return hz<=0?"":(hz%1000==0?Integer.toString(hz/1000):new java.text.DecimalFormat("0.###",java.text.DecimalFormatSymbols.getInstance(Locale.ROOT)).format(hz/1000d))+" kHz";}
    private static String encoding(int value){
        switch(value){
            case C.ENCODING_PCM_8BIT:return "8-bit PCM";
            case C.ENCODING_PCM_16BIT:return "16-bit PCM";
            case C.ENCODING_PCM_24BIT:return "24-bit PCM";
            case C.ENCODING_PCM_32BIT:return "32-bit PCM";
            case C.ENCODING_PCM_FLOAT:return "32-bit 浮点 PCM";
            default:return "";
        }
    }
    private static String append(String before,String after){return after.isEmpty()?before:before.isEmpty()?after:before+" · "+after;}
    String report(Format format){
        Details inspected;
        synchronized(this){inspected=sources.get(currentUri);}
        int inspectedRate=inspected==null?0:inspected.rate, inspectedBits=inspected==null?0:inspected.bits;
        String source=inspected==null?"":inspected.name;
        if(source.isEmpty() && format!=null && format.sampleMimeType!=null){
            String mime=format.sampleMimeType;
            source=mime.replace("audio/","").toUpperCase(Locale.ROOT);
            if(mime.equals("audio/raw"))source="PCM";
            if(mime.equals("audio/mp4a-latm"))source="AAC";
            if(mime.equals("audio/mpeg"))source="MP3";
        }
        int inputRate=inspectedRate>0?inspectedRate:format==null?0:format.sampleRate;
        source=append(source,rate(inputRate));
        source=append(source,inspectedBits>0?inspectedBits+"-bit":format==null?"":encoding(format.pcmEncoding));
        if(format!=null && format.averageBitrate>0 && inspectedBits==0 && encoding(format.pcmEncoding).isEmpty())
            source=append(source,Math.round(format.averageBitrate/1000f)+" kb/s");
        String osStream="",device="",mixer="";
        AudioTrack active=track;
        try{
            if(active!=null && active.getState()==AudioTrack.STATE_INITIALIZED){
                osStream=append(rate(active.getSampleRate()),encoding(active.getFormat().getEncoding()));
                AudioDeviceInfo routed=active.getRoutedDevice();
                if(routed!=null){
                    device=deviceName(routed);
                    synchronized(this){
                        if(Build.VERSION.SDK_INT>=34 && usb!=null && routed.getId()==usb.getId())
                            mixer=Api34.status(this);
                    }
                }
            }
        }catch(RuntimeException ignored){/* The route disappeared while reading it. */}
        return "音源\n"+or(source)+"\n\n送入 Android 的音频\n"+or(osStream)+"\n\n当前设备\n"+or(device)
            +(mixer.isEmpty()?"":"\n\nUSB 混音器首选设置\n"+mixer)+"\n\n硬件最终输出\n系统未报告";
    }
    private static String or(String s){return s.isEmpty()?"系统未报告":s;}
    private static String deviceName(AudioDeviceInfo d){
        String type;
        switch(d.getType()){
            case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER:type="扬声器";break;
            case AudioDeviceInfo.TYPE_USB_DEVICE:case AudioDeviceInfo.TYPE_USB_HEADSET:case AudioDeviceInfo.TYPE_USB_ACCESSORY:type="USB 音频";break;
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:case AudioDeviceInfo.TYPE_BLE_HEADSET:case AudioDeviceInfo.TYPE_BLE_SPEAKER:type="蓝牙音频";break;
            case AudioDeviceInfo.TYPE_WIRED_HEADSET:case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:type="有线耳机";break;
            default:type="音频设备";
        }
        CharSequence name=d.getProductName();
        return name==null||name.length()==0?type:type+" · "+name;
    }
    private void observeRoute(AudioTrack active,AudioAttributes attrs) {
        routingListener = router -> {
            synchronized (PlaybackAudioPath.this) {
                if(closed || track!=active || Build.VERSION.SDK_INT<34) return;
                AudioDeviceInfo actual=active.getRoutedDevice();
                if(actual==null) return;
                int id=Api34.usbId(actual);
                if(id==initialUsbId || Api34.routeId(PlaybackAudioPath.this,attrs)!=id) return;
                initialUsbId=id;
            }
            routeChanged.run();
        };
        active.addOnRoutingChangedListener(routingListener,main);
    }
    private void detachRoute(){
        if (track!=null && routingListener!=null) track.removeOnRoutingChangedListener(routingListener);
        track=null;
        routingListener=null;
    }
    synchronized void close(){closed=true;detachRoute();clearUsb();}
    private void clearUsb(){
        if(Build.VERSION.SDK_INT>=34 && usb!=null)Api34.clear(this);
        usb=null;preference=null;preferredAttributes=null;
    }
    private static final class Api34 {
        static int usbId(AudioDeviceInfo device) {
            switch (device.getType()) {
                case AudioDeviceInfo.TYPE_USB_DEVICE:
                case AudioDeviceInfo.TYPE_USB_HEADSET:
                case AudioDeviceInfo.TYPE_USB_ACCESSORY: return device.getId();
                default: return -1;
            }
        }
        static int routeId(PlaybackAudioPath path,AudioAttributes attrs){
            try {
                List<AudioDeviceInfo> routes=path.manager.getAudioDevicesForAttributes(attrs);
                return routes.size()==1 ? usbId(routes.get(0)) : -1;
            } catch (RuntimeException ignored) { return -1; }
        }
        static AudioOutputProvider.OutputConfig request(PlaybackAudioPath p,AudioOutputProvider.OutputConfig cfg,AudioAttributes attrs){
            if(cfg.isOffload || cfg.isTunneling || UsbPcmOutput.bytes(cfg.encoding)==0) return cfg;
            try {
                List<AudioDeviceInfo> routes=p.manager.getAudioDevicesForAttributes(attrs);
                if(routes.size()!=1 || usbId(routes.get(0))<0) return cfg;
                AudioDeviceInfo device=routes.get(0);
                AudioMixerAttributes selected=null;
                int best=Integer.MAX_VALUE;
                for(AudioMixerAttributes candidate:p.manager.getSupportedMixerAttributes(device)){
                    AudioFormat f=candidate.getFormat();
                    if(candidate.getMixerBehavior()!=AudioMixerAttributes.MIXER_BEHAVIOR_DEFAULT ||
                        f.getSampleRate()!=cfg.sampleRate || f.getChannelMask()!=cfg.channelMask) continue;
                    int rank=UsbPcmOutput.priority(cfg.encoding,f.getEncoding());
                    if(rank<best) { best=rank;selected=candidate; }
                }
                if(selected==null) return cfg;
                int target=selected.getFormat().getEncoding();
                AudioOutputProvider.OutputConfig physical=cfg;
                if(target!=cfg.encoding) {
                    int frameCount=Math.max(1,cfg.bufferSize/(Integer.bitCount(cfg.channelMask)*UsbPcmOutput.bytes(cfg.encoding)));
                    int frameBytes=Integer.bitCount(cfg.channelMask)*UsbPcmOutput.bytes(target);
                    int min=AudioTrack.getMinBufferSize(cfg.sampleRate,cfg.channelMask,target);
                    if(min<=0) return cfg;
                    int size=Math.max(frameCount,(min+frameBytes-1)/frameBytes)*frameBytes;
                    physical=cfg.buildUpon().setEncoding(target).setBufferSize(size).build();
                }
                if(p.manager.setPreferredMixerAttributes(attrs,device,selected)){
                    p.usb=device;
                    p.preference=selected;
                    p.preferredAttributes=attrs;
                    return physical;
                }
            } catch(RuntimeException ignored){/* Android keeps its normal output if the route disappeared. */}
            return cfg;
        }
        static void clear(PlaybackAudioPath p){
            try{if(p.preference!=null && p.preference.equals(p.manager.getPreferredMixerAttributes(p.preferredAttributes,p.usb)))
                p.manager.clearPreferredMixerAttributes(p.preferredAttributes,p.usb);}catch(RuntimeException ignored){}
        }
        static String status(PlaybackAudioPath p){
            AudioMixerAttributes current=p.manager.getPreferredMixerAttributes(p.preferredAttributes,p.usb);
            if(current==null||!current.equals(p.preference))return "";
            return append(rate(current.getFormat().getSampleRate()),encoding(current.getFormat().getEncoding()));
        }
    }
}
