package com.recordroom.vinyl;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.SoundPool;

final class NeedleSound {
    private SoundPool pool;
    private int sound;
    private boolean ready;
    NeedleSound(Context c){
        pool=new SoundPool.Builder().setMaxStreams(1).setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build();
        pool.setOnLoadCompleteListener((p,id,status)->ready=status==0);
        try(AssetFileDescriptor afd=c.getAssets().openFd("sfx/needle.wav")){sound=pool.load(afd,1);}catch(Exception ignored){}
    }
    void play(float volume){if(pool!=null&&ready)pool.play(sound,Math.min(.24f,volume*.24f),Math.min(.24f,volume*.24f),1,0,1);}
    void close(){if(pool!=null)pool.release();pool=null;ready=false;}
}
