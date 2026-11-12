package com.recordroom.vinyl;

import androidx.media3.common.C;
import androidx.media3.exoplayer.audio.AudioOutput;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.Test;
import static org.junit.Assert.*;

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk=34)
public class UsbPcmOutputTest {
    private static ByteBuffer bytes(int length){return ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN);}

    @Test public void exactNativeFirstThenHighestIntegerPrecision(){
        assertEquals(0,UsbPcmOutput.priority(C.ENCODING_PCM_FLOAT,C.ENCODING_PCM_FLOAT));
        assertEquals(1,UsbPcmOutput.priority(C.ENCODING_PCM_FLOAT,C.ENCODING_PCM_32BIT));
        assertEquals(2,UsbPcmOutput.priority(C.ENCODING_PCM_FLOAT,C.ENCODING_PCM_24BIT));
        assertEquals(Integer.MAX_VALUE,UsbPcmOutput.priority(C.ENCODING_PCM_FLOAT,C.ENCODING_AC3));
    }

    @Test public void integerPcmIsPackedWithSignClippingAndLittleEndianChannels(){
        ByteBuffer in=bytes(24),out=bytes(18);
        for(float sample:new float[]{-2f,-.5f,0f,.5f,1f,Float.NaN})in.putFloat(sample);
        in.flip();UsbPcmOutput.pack(in,out,C.ENCODING_PCM_FLOAT,C.ENCODING_PCM_24BIT);
        assertArrayEquals(new byte[]{0,0,-128,0,0,-64,0,0,0,0,0,64,-1,-1,127,0,0,0},out.array());
        in=bytes(4);out=bytes(8);in.putShort((short)-12345).putShort((short)32767).flip();
        UsbPcmOutput.pack(in,out,C.ENCODING_PCM_16BIT,C.ENCODING_PCM_32BIT);out.flip();
        assertEquals(-12345<<16,out.getInt());assertEquals(32767<<16,out.getInt());
    }

    @Test public void nonBlockingPartialWritesReportPositionInSourceBytes()throws Exception{
        ByteBuffer captured=bytes(6);
        AudioOutput platform=(AudioOutput)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{AudioOutput.class},(proxy,method,args)->{
            if(!method.getName().equals("write"))return null;
            ByteBuffer tail=(ByteBuffer)args[0];for(int i=0;i<3;i++)captured.put(tail.get());
            return !tail.hasRemaining();
        });
        UsbPcmOutput converter=new UsbPcmOutput(platform,C.ENCODING_PCM_FLOAT,C.ENCODING_PCM_24BIT);
        ByteBuffer in=bytes(12);in.putFloat(.125f).putFloat(.5f).putFloat(-.5f).flip();in.position(4);
        assertFalse(converter.write(in,1,1000));assertEquals(8,in.position());
        assertTrue(converter.write(in,1,1000));assertEquals(in.limit(),in.position());
        assertArrayEquals(new byte[]{0,0,64,0,0,-64},captured.array());
        converter.flush();
    }
}
