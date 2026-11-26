package com.drivemate.app;
import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
public class MappedSignalsTest {
    byte[] fixture(boolean bad)throws Exception{
        ByteArrayOutputStream b=new ByteArrayOutputStream();DataOutputStream d=new DataOutputStream(b);d.writeInt(0x444d5331);
        String h="2c31f7d0c5c7280aee2130f38f7ebabb990626a8f5a208dba3c0279ada6c3164";for(int i=0;i<64;i+=2)d.writeByte(Integer.parseInt(h.substring(i,i+2),16));
        d.writeInt(3);d.writeInt(534000000);d.writeInt(-21700000);d.writeInt(bad?533000000:534010000);d.writeInt(-21600000);d.writeInt(535000000);d.writeInt(-21000000);return b.toByteArray();
    }
    @Test public void boundedViewportFindsOnlyMappedNodes()throws Exception{
        MappedSignals s=MappedSignals.read(new ByteArrayInputStream(fixture(false)));assertEquals(2,s.nearby(53.39,-2.18,53.42,-2.15,20).size());assertEquals(0,s.nearby(53.39,-2.18,53.42,-2.15,1).size());assertEquals(0,s.nearby(51,-3,55,-1,20).size());
    }
    @Test(expected=IOException.class) public void unorderedInputRejected()throws Exception{MappedSignals.read(new ByteArrayInputStream(fixture(true)));}
    @Test public void noRasterTrafficOverIndependentRoads(){assertTrue(RouteMapPolicy.useTomTomVector("TomTom",true));assertFalse(RouteMapPolicy.useTomTomVector("DriveMate",true));assertFalse(RouteMapPolicy.useTomTomVector("TomTom",false));}
}
