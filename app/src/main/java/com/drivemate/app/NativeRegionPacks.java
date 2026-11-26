package com.drivemate.app;

import android.content.Context;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;
import org.json.*;

/** Only app-pinned immutable GitHub packs, explicit Wi-Fi download, complete verified install.
 * No trust in a remote mutable catalog, no hosting, no cross-pack routing inference. */
final class NativeRegionPacks {
    static final String SOURCE="2c31f7d0c5c7280aee2130f38f7ebabb990626a8f5a208dba3c0279ada6c3164";
    static final int MAX_ARCHIVE=160_000_000,MAX_MANIFEST=2_000_000;
    static final class Entry {
        final String id,tag,asset,sha,manifestSha;final int bytes;final double[] bounds;
        Entry(JSONObject j)throws Exception{
            id=j.getString("id");tag=j.getString("tag");asset=j.getString("asset");sha=j.getString("sha256");manifestSha=j.getString("manifestSha256");bytes=j.getInt("bytes");
            if(!id.matches("[a-z0-9-]{1,64}")||!tag.matches("dmregion-[A-Za-z0-9._-]{1,100}")||!asset.equals(id+".zip")||
                !sha.matches("[a-f0-9]{64}")||!manifestSha.matches("[a-f0-9]{64}")||bytes<=0||bytes>MAX_ARCHIVE||
                !SOURCE.equals(j.getString("parentSourceSha256")))throw new IOException("Invalid regional pack identity");
            JSONArray a=j.getJSONArray("bounds");if(a.length()!=4)throw new IOException("Invalid regional bounds");
            bounds=new double[4];for(int i=0;i<4;i++){bounds[i]=a.getDouble(i);if(!Double.isFinite(bounds[i]))throw new IOException("Invalid regional bounds");}
            if(bounds[0]<-9||bounds[2]>3||bounds[1]<49||bounds[3]>61||bounds[0]>=bounds[2]||bounds[1]>=bounds[3]||
                bounds[2]-bounds[0]>.15||bounds[3]-bounds[1]>.10)throw new IOException("Unsupported regional bounds");
        }
        boolean covers(GeoMath.Point p){return RouteMapPolicy.insideBounds(p,bounds);}
    }
    private final Context app;private final File root;
    NativeRegionPacks(Context context){app=context.getApplicationContext();root=new File(app.getFilesDir(),"native-regions");}
    List<Entry> catalog()throws Exception{
        byte[] raw=DmTileEngine.read(app.getAssets().open("native-region-catalog.json"),65536);
        JSONArray rows=new JSONArray(new String(raw,StandardCharsets.UTF_8));if(rows.length()>16)throw new IOException("Too many regional pins");
        List<Entry> out=new ArrayList<>();Set<String> ids=new HashSet<>();
        for(int i=0;i<rows.length();i++){Entry e=new Entry(rows.getJSONObject(i));if(!ids.add(e.id))throw new IOException("Duplicate region");out.add(e);}return out;
    }
    File folder(Entry e){return new File(root,e.id+"-"+e.manifestSha);}
    boolean installed(Entry e){return new File(folder(e),"manifest.json").isFile();}
    private boolean wifi(){return new LocalRoutingPack(app).unmetered();}
    private static String hex(byte[] bytes){StringBuilder s=new StringBuilder();for(byte x:bytes)s.append(String.format(Locale.ROOT,"%02x",x&255));return s.toString();}
    static JSONObject validateManifest(Entry e,byte[] raw)throws Exception{
        if(raw.length>MAX_MANIFEST||!DmTileEngine.sha(raw).equals(e.manifestSha))throw new IOException("Regional manifest hash mismatch");
        JSONObject m=new JSONObject(new String(raw,StandardCharsets.UTF_8));
        if(!"drivemate-tiles-v1".equals(m.optString("format"))||!"way-node-segments-v1".equals(m.optString("geometry_contract"))||
            !SOURCE.equals(m.optString("parent_source_sha256"))||!e.id.equals(m.optString("region_id"))||
            !"ODbL-1.0".equals(m.optString("license"))||m.optInt("cell_microdegrees")!=50000)throw new IOException("Regional map contract mismatch");
        String[] b=m.getString("bbox").split(",");if(b.length!=4)throw new IOException("Regional manifest bounds missing");
        for(int i=0;i<4;i++){double expected=Double.parseDouble(b[i])+(i<2?.002:-.002);if(!Double.isFinite(expected)||Math.abs(expected-e.bounds[i])>1e-7)throw new IOException("Regional bounds mismatch");}
        JSONObject tiles=m.getJSONObject("tiles");if(tiles.length()<1||tiles.length()>128)throw new IOException("Regional cell count");
        long total=raw.length;Iterator<String> keys=tiles.keys();
        while(keys.hasNext()){String k=keys.next();JSONObject t=tiles.getJSONObject(k);
            if(!k.matches("-?[0-9]+_-?[0-9]+")||!t.getString("name").equals(k+".dmt.gz")||!t.getString("sha256").matches("[a-f0-9]{64}")||
                t.getLong("bytes")<=0||t.getLong("bytes")>8000000)throw new IOException("Invalid regional cell");
            total+=t.getLong("bytes");if(total>150_000_000)throw new IOException("Regional cell budget");
        }return m;
    }
    DmUnifiedRegion open(Entry e)throws Exception{
        File dir=folder(e);byte[] raw=DmTileEngine.read(new FileInputStream(new File(dir,"manifest.json")),MAX_MANIFEST);validateManifest(e,raw);
        return new DmUnifiedRegion(raw,e.manifestSha,n->new FileInputStream(new File(dir,n)),e.bounds);
    }
    DmUnifiedRegion select(GeoMath.Point from,GeoMath.Point to)throws Exception{
        return candidates(from,to).get(0);
    }
    List<DmUnifiedRegion> candidates(GeoMath.Point from,GeoMath.Point to)throws Exception{
        List<DmUnifiedRegion> regions=new ArrayList<>();
        if(RouteMapPolicy.insideStockport(from)&&(to==null||RouteMapPolicy.insideStockport(to)))regions.add(new DmUnifiedRegion(app,true));
        // An independently compiled corridor pack may cover a journey that spans two
        // smaller extracts. Never splice graph fragments or guess at a boundary.
        List<Entry> choices=new ArrayList<>();
        for(Entry e:catalog())if(installed(e)&&e.covers(from)&&(to==null||e.covers(to)))choices.add(e);
        choices.sort(Comparator.comparingDouble(e->(e.bounds[2]-e.bounds[0])*(e.bounds[3]-e.bounds[1])));
        for(Entry e:choices)regions.add(open(e));
        if(!regions.isEmpty())return regions;
        throw new IOException("No single verified downloaded region covers the journey. No external fallback.");
    }
    private HttpURLConnection connect(Entry e)throws Exception{
        URL url=new URL("https://github.com/phuk-prog/Drivemate-map/releases/download/"+e.tag+"/"+e.asset);
        for(int i=0;i<6;i++){
            if(!wifi())throw new IOException("Regional download requires validated unmetered Wi-Fi");
            if(!"https".equals(url.getProtocol())||!(url.getHost().equals("github.com")||url.getHost().endsWith(".githubusercontent.com")))throw new IOException("Unexpected map host");
            HttpURLConnection c=(HttpURLConnection)url.openConnection();c.setInstanceFollowRedirects(false);c.setConnectTimeout(15000);c.setReadTimeout(30000);c.setRequestProperty("Accept-Encoding","identity");
            int status=c.getResponseCode();if(status==200)return c;
            String next=c.getHeaderField("Location");c.disconnect();if(status<300||status>=400||next==null)throw new IOException("Regional download HTTP "+status);url=new URL(url,next);
        }throw new IOException("Too many regional redirects");
    }
    private static void remove(File f){File[] children=f.listFiles();if(children!=null)for(File child:children)remove(child);f.delete();}
    synchronized void install(Entry e,LocalRoutingPack.Progress progress)throws Exception{
        if(installed(e)){open(e);return;}
        if(!wifi())throw new IOException("Connect to unmetered Wi-Fi first");
        if(!root.isDirectory()&&!root.mkdirs())throw new IOException("Regional storage unavailable");
        if(root.getUsableSpace()<2L*e.bytes+32_000_000)throw new IOException("Insufficient regional map space");
        File zip=new File(root,e.id+".download"),stage=new File(root,e.id+".stage"),dest=folder(e);
        remove(stage);zip.delete();if(!stage.mkdir())throw new IOException("Cannot stage regional map");
        try{
            MessageDigest digest=MessageDigest.getInstance("SHA-256");long total=0;
            HttpURLConnection c=connect(e);
            try(InputStream in=c.getInputStream();FileOutputStream out=new FileOutputStream(zip)){
                byte[] b=new byte[65536];int n;long notified=0;
                while((n=in.read(b))!=-1){if(!wifi())throw new IOException("Wi-Fi lost; download cancelled");total+=n;if(total>e.bytes)throw new IOException("Archive too long");digest.update(b,0,n);out.write(b,0,n);
                    if(total-notified>=1_000_000){notified=total;progress.update("Downloading "+e.id+" · "+total/1_000_000+" MB");}}
                out.getFD().sync();
            }finally{c.disconnect();}
            if(total!=e.bytes||!e.sha.equals(hex(digest.digest())))throw new IOException("Regional archive hash mismatch");
            try(ZipFile z=new ZipFile(zip)){
                ZipEntry manifest=z.getEntry("manifest.json");if(manifest==null||manifest.isDirectory())throw new IOException("Manifest missing");
                byte[] raw=DmTileEngine.read(z.getInputStream(manifest),MAX_MANIFEST);JSONObject m=validateManifest(e,raw);
                JSONObject tiles=m.getJSONObject("tiles");if(z.size()!=tiles.length()+1)throw new IOException("Unexpected archive entries");
                Set<String> names=new HashSet<>();Enumeration<? extends ZipEntry> all=z.entries();
                while(all.hasMoreElements()){ZipEntry item=all.nextElement();if(item.isDirectory()||!names.add(item.getName()))throw new IOException("Unexpected ZIP item");}
                Iterator<String> it=tiles.keys();while(it.hasNext()){
                    JSONObject t=tiles.getJSONObject(it.next());String name=t.getString("name");ZipEntry item=z.getEntry(name);
                    if(item==null||item.getSize()!=t.getLong("bytes"))throw new IOException("Regional cell missing");
                    byte[] b=DmTileEngine.read(z.getInputStream(item),t.getInt("bytes"));if(b.length!=t.getInt("bytes")||!DmTileEngine.sha(b).equals(t.getString("sha256")))throw new IOException("Regional cell digest mismatch");
                    try(FileOutputStream out=new FileOutputStream(new File(stage,name))){out.write(b);out.getFD().sync();}
                }
                try(FileOutputStream out=new FileOutputStream(new File(stage,"manifest.json"))){out.write(raw);out.getFD().sync();}
            }
            if(dest.exists()||!stage.renameTo(dest))throw new IOException("Cannot atomically install regional pack");
            progress.update("Verified regional pack installed · "+e.id+" · preview only");
        }finally{zip.delete();remove(stage);}
    }
}
