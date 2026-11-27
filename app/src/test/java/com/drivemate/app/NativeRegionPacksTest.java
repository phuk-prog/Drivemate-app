package com.drivemate.app;
import org.junit.Test;
import org.json.*;
import static org.junit.Assert.*;
public class NativeRegionPacksTest {
    private JSONObject entry()throws Exception{return new JSONObject().put("id","south-manchester").put("tag","dmregion-test").put("asset","south-manchester.zip").put("bytes",10000)
        .put("sha256","a".repeat(64)).put("manifestSha256","b".repeat(64)).put("parentSourceSha256",NativeRegionPacks.SOURCE)
        .put("bounds",new JSONArray().put(-2.333).put(53.382).put(-2.237).put(53.443));}
    @Test public void catalogRejectsTraversalAndUnpinnedSource()throws Exception{
        NativeRegionPacks.Entry e=new NativeRegionPacks.Entry(entry());
        assertTrue(e.covers(new GeoMath.Point(53.4,-2.28)));assertFalse(e.covers(new GeoMath.Point(53.5,-2.28)));
        try{new NativeRegionPacks.Entry(entry().put("asset","../bad.zip"));fail("unsafe asset");}catch(java.io.IOException expected){}
        try{new NativeRegionPacks.Entry(entry().put("parentSourceSha256","c".repeat(64)));fail("wrong source");}catch(java.io.IOException expected){}
        try{NativeRegionPacks.validateManifest(e,new byte[]{1,2,3});fail("bad manifest");}catch(java.io.IOException expected){}
    }
}
