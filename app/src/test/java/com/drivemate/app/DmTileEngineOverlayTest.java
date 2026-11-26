package com.drivemate.app;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/** Road hierarchy port: same route cost as a full search, and as the map
 *  repository's tested Python engine. Fixture: Drivemate-map
 *  tools/make_app_overlay_fixture.py (synthetic grid across 9 cells, one-way
 *  streets, a banned turn). */
public class DmTileEngineOverlayTest{
    private static byte[] resource(String name)throws IOException{
        try(InputStream in=DmTileEngineOverlayTest.class.getResourceAsStream("/dm-overlay/"+name)){
            if(in==null)throw new FileNotFoundException(name);return DmTileEngine.read(in,8_000_000);
        }
    }
    private static DmTileEngine engine(DmTileEngine.Source source)throws Exception{
        byte[] m=resource("manifest.json");return new DmTileEngine(m,DmTileEngine.sha(m),source);
    }
    private static final DmTileEngine.Source FIXTURE=name->new ByteArrayInputStream(resource(name));

    @Test public void hierarchyMatchesFullSearchAndPythonEngine()throws Exception{
        DmTileEngine e=engine(FIXTURE);assertTrue(e.hasOverlay());
        JSONArray expected=new JSONArray(new String(resource("expected.json"),StandardCharsets.UTF_8));
        int checked=0;
        for(int i=0;i<expected.length();i++){
            JSONObject j=expected.getJSONObject(i);if(!j.getBoolean("ok"))continue;
            GeoMath.Point s=point(j.getJSONArray("start")),t=point(j.getJSONArray("end"));
            DmTileEngine.Route flat=e.routeFlat(s,t,null),over=e.routeOverlay(s,t,null);
            double want=j.getDouble("seconds");
            assertEquals("full search vs Python "+j,want,flat.seconds,0.015);
            assertEquals("hierarchy vs Python "+j,want,over.seconds,0.015);
            assertEquals("distance "+j,j.getDouble("km"),over.metres/1000,0.0015);
            // Unpacked route is continuous road geometry from start to end.
            assertEquals(over.points.size(),over.ways.size()+1);
            assertEquals(over.ways.size(),over.edgeIds.size());
            for(int k=1;k<over.points.size();k++)assertTrue(GeoMath.distance(over.points.get(k-1),over.points.get(k))<3000);
            assertTrue(GeoMath.distance(over.points.get(over.points.size()-1),t)<400);
            checked++;
        }
        assertTrue(checked>400);
    }
    @Test public void corruptHierarchyPieceRejected()throws Exception{
        DmTileEngine e=engine(name->{byte[] raw=resource(name);if(name.endsWith(".dmo.gz"))raw[raw.length/2]^=1;return new ByteArrayInputStream(raw);});
        try{e.routeOverlay(new GeoMath.Point(51.001,-2.199),new GeoMath.Point(51.148,-2.052),null);fail("accepted corrupt overlay");}
        catch(IOException expected){assertTrue(expected.getMessage().contains("Overlay checksum"));}
    }
    @Test public void cachedHierarchyPiecesAreNotMapCoverage()throws Exception{
        File root=Files.createTempDirectory("dmcache").toFile();
        byte[] m=resource("manifest.json");
        DmTileDiskSource cache=new DmTileDiskSource(root,m,DmTileEngine.sha(m),10_000_000,FIXTURE);
        JSONObject cells=new JSONObject(new String(m,StandardCharsets.UTF_8)).getJSONObject("overlay").getJSONObject("cells");
        String k=cells.keys().next();
        try(InputStream in=cache.open(k+".dmo.gz")){assertTrue(in.read()>=0);}
        assertFalse(cache.availableCells().contains(k));
        try(InputStream in=cache.open(k+".dmt.gz")){assertTrue(in.read()>=0);}
        assertTrue(cache.availableCells().contains(k));
    }
    private static GeoMath.Point point(JSONArray a)throws JSONException{return new GeoMath.Point(a.getDouble(0),a.getDouble(1));}
}
