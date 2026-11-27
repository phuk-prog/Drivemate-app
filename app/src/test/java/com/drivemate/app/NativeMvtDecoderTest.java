package com.drivemate.app;
import org.junit.Test;
import java.io.*;
import java.util.*;
import static org.junit.Assert.*;
public class NativeMvtDecoderTest {
    private static byte[] v(long n){ByteArrayOutputStream b=new ByteArrayOutputStream();while((n&~127L)!=0){b.write((int)(n&127)|128);n>>>=7;}b.write((int)n);return b.toByteArray();}
    private static byte[] concat(byte[]... all)throws Exception{ByteArrayOutputStream b=new ByteArrayOutputStream();for(byte[] a:all)b.write(a);return b.toByteArray();}
    private static byte[] field(int n,byte[] data)throws Exception{return concat(v(n*8+2),v(data.length),data);}
    @Test public void exactTileLineAndMalformedInput()throws Exception{
        byte[] geometry=concat(v(9),v(20),v(40),v(10),v(20),v(0));
        byte[] f=concat(v(24),v(2),field(4,geometry));
        byte[] layer=concat(field(1,"streets".getBytes("UTF-8")),field(2,f),v(40),v(4096),v(120),v(2));
        List<NativeMapScene.Shape> shapes=NativeMvtDecoder.decode(field(3,layer),14,8090,5300);
        assertEquals(1,shapes.size());assertEquals(4,shapes.get(0).xy.length);
        assertEquals((8090+10/4096.0)/16384,shapes.get(0).xy[0],1e-12);
        assertEquals((8090+20/4096.0)/16384,shapes.get(0).xy[2],1e-12);
        try{NativeMvtDecoder.decode(new byte[]{26,100},14,8090,5300);fail("truncated accepted");}catch(IOException expected){}
    }
}
