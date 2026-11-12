package com.recordroom.vinyl;

import androidx.media3.common.C;
import androidx.media3.exoplayer.audio.AudioOutput;
import androidx.media3.exoplayer.audio.ForwardingAudioOutput;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Packs the sink's PCM into the integer layout selected for the current USB route.
 * Keep partial writes in output bytes; report progress to Media3 in original input bytes. */
final class UsbPcmOutput extends ForwardingAudioOutput {
    private final int source, target, inputSize, outputSize;
    private ByteBuffer packed = ByteBuffer.allocateDirect(0);
    private ByteBuffer pending;
    private int inputStart;

    UsbPcmOutput(AudioOutput output, int source, int target) {
        super(output);
        this.source = source;
        this.target = target;
        inputSize = bytes(source);
        outputSize = bytes(target);
    }

    static int bytes(int enc) {
        switch(enc) {
            case C.ENCODING_PCM_16BIT: return 2;
            case C.ENCODING_PCM_24BIT: return 3;
            case C.ENCODING_PCM_32BIT: case C.ENCODING_PCM_FLOAT: return 4;
            default: return 0;
        }
    }

    // Media3's PCM sink emits either 16-bit or floating-point little-endian frames.
    static int priority(int source, int target) {
        if (source==target) return 0;
        if (source!=C.ENCODING_PCM_FLOAT && source!=C.ENCODING_PCM_16BIT) return Integer.MAX_VALUE;
        if (target==C.ENCODING_PCM_32BIT) return 1;
        if (target==C.ENCODING_PCM_24BIT) return 2;
        if (target==C.ENCODING_PCM_16BIT) return 3;
        return Integer.MAX_VALUE;
    }

    @Override public boolean write(ByteBuffer buffer, int units, long timeUs) throws AudioOutput.WriteException {
        if (buffer!=pending) {
            int capacity = Math.multiplyExact(buffer.remaining()/inputSize,outputSize);
            if (packed.capacity()<capacity) packed=ByteBuffer.allocateDirect(capacity);
            packed.clear();
            packed.order(ByteOrder.LITTLE_ENDIAN);
            pack(buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN),packed,source,target);
            packed.flip();
            pending=buffer;
            inputStart=buffer.position();
        }
        boolean consumed=super.write(packed,units,timeUs);
        buffer.position(inputStart+packed.position()/outputSize*inputSize);
        if(consumed) pending=null;
        return consumed;
    }

    static void pack(ByteBuffer input,ByteBuffer output,int source,int target) {
        int bits=bytes(target)*8;
        long scale=1L<<(bits-1);
        while(input.hasRemaining()) {
            long sample;
            if(source==C.ENCODING_PCM_FLOAT) {
                float value=input.getFloat();
                sample=Math.max(-scale,Math.min(scale-1,Math.round((double)value*scale)));
            } else {
                sample=(long)input.getShort()<<(bits-16);
            }
            output.put((byte)sample);
            output.put((byte)(sample>>8));
            if(bits>=24) output.put((byte)(sample>>16));
            if(bits==32) output.put((byte)(sample>>24));
        }
    }

    @Override public void flush() { pending=null;super.flush(); }
    @Override public void release() { pending=null;super.release(); }
}
