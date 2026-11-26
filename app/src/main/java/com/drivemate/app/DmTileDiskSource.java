package com.drivemate.app;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Verified persistent, bounded cache. Remote archive not yet published. */
public final class DmTileDiskSource implements DmTileEngine.Source {
    private final File folder;
    private final Map<String,JSONObject> byName=new HashMap<>();
    private final long budget;
    private final Set<String> protectedNames=new HashSet<>();
    private final DmTileEngine.Source upstream;
    public DmTileDiskSource(File root,byte[] manifest,String hash,long budget,DmTileEngine.Source upstream)throws Exception{
        if(!hash.matches("[a-f0-9]{64}")||!DmTileEngine.sha(manifest).equals(hash)||budget<=0)throw new IOException("Invalid cache identity");
        folder=new File(root,hash);if(!folder.isDirectory()&&!folder.mkdirs())throw new IOException("Cannot create tile cache");
        JSONObject tiles=new JSONObject(new String(manifest,StandardCharsets.UTF_8)).getJSONObject("tiles");
        for(Iterator<String> i=tiles.keys();i.hasNext();){
            String k=i.next();JSONObject info=tiles.getJSONObject(k);String n=info.getString("name");
            if(!k.matches("-?[0-9]+_-?[0-9]+")||!n.equals(k+".dmt.gz")||info.getLong("bytes")<=0||info.getLong("bytes")>8000000||!info.getString("sha256").matches("[a-f0-9]{64}"))throw new IOException("Invalid tile catalog");
            byName.put(n,info);
        }this.budget=budget;this.upstream=upstream;
        File pins=new File(folder,"journey-pins.json");if(pins.isFile()){
            JSONArray saved=new JSONArray(new String(DmTileEngine.read(new FileInputStream(pins),2_000_000),StandardCharsets.UTF_8));Set<String> cells=new HashSet<>();
            for(int i=0;i<saved.length();i++)cells.add(saved.getString(i));protect(cells);
        }
    }
    private boolean verified(byte[] raw,JSONObject info)throws Exception{return raw.length==info.getInt("bytes")&&DmTileEngine.sha(raw).equals(info.getString("sha256"));}
    public synchronized void protect(Set<String> cells)throws IOException{
        Set<String> next=new HashSet<>();long required=0;
        for(String k:cells){String name=k+".dmt.gz";JSONObject info=byName.get(name);if(info==null)throw new IOException("Coverage not in catalog");required+=info.optLong("bytes");next.add(name);}
        if(required>budget)throw new IOException("Journey coverage exceeds cache budget");
        protectedNames.clear();protectedNames.addAll(next);
    }
    public synchronized InputStream open(String name)throws Exception{
        JSONObject info=byName.get(name);if(info==null)throw new IOException("Unknown routing tile");
        File file=new File(folder,name);byte[] raw=null;
        if(file.isFile()&&file.length()==info.getInt("bytes")){
            raw=DmTileEngine.read(new FileInputStream(file),info.getInt("bytes"));
            if(!verified(raw,info))raw=null;
        }
        if(raw==null){
            file.delete();if(upstream==null)throw new DmTileEngine.CoverageException("Offline road coverage unavailable");
            raw=DmTileEngine.read(upstream.open(name),info.getInt("bytes"));
            if(!verified(raw,info))throw new IOException("Remote tile checksum mismatch");
            makeSpace(raw.length);
            File stage=new File(folder,name+".tmp");try{
                try(FileOutputStream out=new FileOutputStream(stage)){out.write(raw);out.getFD().sync();}
                if(!stage.renameTo(file))throw new IOException("Cannot commit tile");
            }finally{stage.delete();}
        }file.setLastModified(System.currentTimeMillis());return new ByteArrayInputStream(raw);
    }
    private void makeSpace(long additional)throws IOException{
        File[] files=folder.listFiles((dir,n)->byName.containsKey(n));if(files==null)throw new IOException("Cache unavailable");
        Arrays.sort(files,Comparator.comparingLong(File::lastModified));long used=0;for(File f:files)used+=f.length();
        for(File f:files){if(used+additional<=budget)break;if(protectedNames.contains(f.getName()))continue;long size=f.length();if(f.delete())used-=size;}
        if(used+additional>budget)throw new IOException("Not enough unpinned cache storage");
    }
    public synchronized Set<String> availableCells()throws Exception{
        Set<String> out=new HashSet<>();
        for(Map.Entry<String,JSONObject> item:byName.entrySet()){
            File file=new File(folder,item.getKey());JSONObject meta=item.getValue();
            if(file.isFile()&&file.length()==meta.getLong("bytes"))out.add(item.getKey().substring(0,item.getKey().length()-7));
        }return out;
    }
    public synchronized void prefetch(Set<String> cells)throws Exception{
        Set<String> prior=new HashSet<>(protectedNames);protect(cells);
        try{
            protectedNames.addAll(prior);
            for(String k:new TreeSet<>(cells))try(InputStream in=open(k+".dmt.gz")){}
            protect(cells);
            android.util.AtomicFile pins=new android.util.AtomicFile(new File(folder,"journey-pins.json"));
            FileOutputStream out=pins.startWrite();
            try{out.write(new JSONArray(new TreeSet<>(cells)).toString().getBytes(StandardCharsets.UTF_8));pins.finishWrite(out);}
            catch(Exception err){pins.failWrite(out);throw err;}
        }catch(Exception err){protectedNames.clear();protectedNames.addAll(prior);throw err;}
    }
    public static DmTileEngine.Source rangeArchive(String address,byte[] manifest,String expectedHash)throws Exception{
        if(!DmTileEngine.sha(manifest).equals(expectedHash))throw new IOException("Manifest checksum mismatch");
        URL root=new URL(address);
        if(!"https".equals(root.getProtocol())||!"github.com".equals(root.getHost())||!root.getPath().matches("/phuk-prog/Drivemate-map/releases/download/dmtiles-[A-Za-z0-9._-]+/routing\\.dma")||root.getQuery()!=null||root.getRef()!=null)throw new IOException("Unpinned DriveMate archive URL");
        JSONObject doc=new JSONObject(new String(manifest,StandardCharsets.UTF_8)),tiles=doc.getJSONObject("tiles");long total=doc.getJSONObject("archive").getLong("bytes");
        if(total<=0)throw new IOException("Invalid archive size");
        return name->{
            if(!name.matches("-?[0-9]+_-?[0-9]+\\.dmt\\.gz"))throw new IOException("Invalid routing tile");
            JSONObject meta=tiles.getJSONObject(name.substring(0,name.length()-7));
            long offset=meta.getLong("offset");int len=meta.getInt("bytes");
            if(offset<0||len<=0||len>8000000||offset>total-len)throw new IOException("Invalid archive range");
            URL url=root;String range="bytes="+offset+"-"+(offset+len-1);
            for(int i=0;i<6;i++){
                if(!"https".equals(url.getProtocol())||!(url.getHost().equals("github.com")||url.getHost().endsWith(".githubusercontent.com")))throw new IOException("Unexpected map host");
                HttpURLConnection c=(HttpURLConnection)url.openConnection();c.setInstanceFollowRedirects(false);c.setConnectTimeout(10000);c.setReadTimeout(15000);c.setRequestProperty("Range",range);c.setRequestProperty("Accept-Encoding","identity");
                try{int status=c.getResponseCode();if(status>=300&&status<400){String next=c.getHeaderField("Location");if(next==null)throw new IOException("Missing redirect");url=new URL(url,next);continue;}
                    if(status!=206||!("bytes "+offset+"-"+(offset+len-1)+"/"+total).equals(c.getHeaderField("Content-Range")))throw new IOException("Archive range not honored");
                    byte[] raw=DmTileEngine.read(c.getInputStream(),len);if(raw.length!=len||!DmTileEngine.sha(raw).equals(meta.getString("sha256")))throw new IOException("Range digest mismatch");return new ByteArrayInputStream(raw);
                }finally{c.disconnect();}
            }throw new IOException("Too many archive redirects");
        };
    }
}
