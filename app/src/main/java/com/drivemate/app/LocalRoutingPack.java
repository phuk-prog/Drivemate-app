package com.drivemate.app;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.util.AtomicFile;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.GZIPInputStream;

/** Explicit unmetered download of an immutable verified UK graph. No API key. */
public final class LocalRoutingPack {
    public static final String TAG="routes-20261124T044450Z-4f30f06120b2-ba57998035b63";
    static final String ROOT="https://github.com/phuk-prog/Drivemate-map/releases/download/"+TAG+"/";
    static final String MANIFEST_SHA="19e668dc43d0abdc09e22af2a09672ac0d5dc6b8b12dd39b2b1c74d3a97f5b51";
    static final long MAX_RAW=20_000_000_000L;
    private final Context app;
    private final File folder,db,manifest;
    public interface Progress {void update(String message);}
    public LocalRoutingPack(Context context){
        app=context.getApplicationContext();folder=new File(app.getFilesDir(),"offline-routing/"+TAG);
        db=new File(folder,"roads.sqlite");manifest=new File(folder,"verified-manifest.json");
    }
    public boolean installed(){return db.isFile()&&manifest.isFile();}
    public boolean unmetered(){
        ConnectivityManager cm=(ConnectivityManager)app.getSystemService(Context.CONNECTIVITY_SERVICE);
        if(cm==null)return false;
        android.net.Network n=cm.getActiveNetwork();NetworkCapabilities c=n==null?null:cm.getNetworkCapabilities(n);
        return c!=null&&c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)&&c.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)&&c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }
    static MessageDigest digest()throws Exception{return MessageDigest.getInstance("SHA-256");}
    static String hex(byte[] b){StringBuilder s=new StringBuilder();for(byte x:b)s.append(String.format(Locale.ROOT,"%02x",x&255));return s.toString();}
    static boolean sha(String s){return s.matches("[a-f0-9]{64}");}
    static JSONObject parseManifest(byte[] raw)throws Exception{
        if(!MANIFEST_SHA.equals(hex(digest().digest(raw))))throw new IOException("Manifest hash mismatch");
        JSONObject d=new JSONObject(new String(raw,StandardCharsets.UTF_8));
        if(!"drivemate-uk-routing-v1".equals(d.optString("format"))||!"ODbL-1.0".equals(d.optString("licence"))||!d.optString("source_tag").startsWith("uk-map-")
            ||!sha(d.optString("source_sha256"))||!sha(d.optString("database_sha256"))||!sha(d.optString("gzip_sha256")))throw new IOException("Invalid routing manifest");
        long bytes=d.getLong("database_bytes"),compressed=d.getLong("gzip_bytes"),total=0;
        JSONArray parts=d.getJSONArray("parts");if(bytes<=0||bytes>MAX_RAW||compressed<=0||compressed>MAX_RAW||parts.length()<1||parts.length()>100)throw new IOException("Invalid graph sizes");
        for(int i=0;i<parts.length();i++){JSONObject part=parts.getJSONObject(i);long size=part.getLong("bytes");
            if(!part.getString("name").equals(String.format(Locale.ROOT,"uk-routing.sqlite.gz.part-%03d",i))||size<=0||size>850_000_000L||!sha(part.optString("sha256")))throw new IOException("Invalid part manifest");total+=size;}
        if(total!=compressed)throw new IOException("Invalid compressed total");
        return d;
    }
    static byte[] small(File file)throws IOException{
        if(file.length()>65536)throw new IOException("Manifest too large");
        try(InputStream in=new FileInputStream(file);ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1){if(out.size()+n>65536)throw new IOException("Manifest too large");out.write(b,0,n);}return out.toByteArray();}
    }
    private HttpURLConnection open(String name)throws Exception{return openUrl(ROOT+name);}
    private HttpURLConnection openUrl(String address)throws Exception{
        URL url=new URL(address);
        for(int i=0;i<6;i++){
            if(!unmetered())throw new IOException("Download paused: unmetered Wi-Fi required");
            String host=url.getHost();
            if(!"https".equals(url.getProtocol())||!(host.equals("github.com")||host.endsWith(".githubusercontent.com")))throw new IOException("Unexpected map download host");
            HttpURLConnection c=(HttpURLConnection)url.openConnection();c.setInstanceFollowRedirects(false);c.setConnectTimeout(15000);c.setReadTimeout(30000);c.setRequestProperty("Accept-Encoding","identity");
            int status=c.getResponseCode();
            if(status==200)return c;
            String next=c.getHeaderField("Location");c.disconnect();
            if(status<300||status>=400||next==null)throw new IOException("GitHub map download failed: HTTP "+status);
            url=new URL(url,next);
        }throw new IOException("Too many map redirects");
    }
    private byte[] remoteManifest()throws Exception{
        HttpURLConnection c=open("uk-routing-manifest.json");
        try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1){if(out.size()+n>65536)throw new IOException("Manifest too large");out.write(b,0,n);}return out.toByteArray();}finally{c.disconnect();}
    }
    public boolean displayInstalled(){
        File f=new File(app.getFilesDir(),"drivemate-map/"+DriveTileServer.MAP_RELEASE+"/offline-verified.pmtiles");
        return f.isFile()&&f.length()==DriveTileServer.SOURCE_SIZE;
    }
    public synchronized void downloadDisplay(Progress progress)throws Exception{
        if(!unmetered())throw new IOException("Connect to unmetered Wi-Fi first");
        File dir=new File(app.getFilesDir(),"drivemate-map/"+DriveTileServer.MAP_RELEASE);
        if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("Cannot create map storage");
        File dest=new File(dir,"offline-verified.pmtiles"),stage=new File(dir,"offline-download.tmp");
        final String expected="a621614b72a64a677684790ece1e51c4cb0931599764e134b8274970488efbfd";
        if(dest.isFile()&&dest.length()==DriveTileServer.SOURCE_SIZE&&expected.equals(LocalRoutingPilot.sha256(dest))){progress.update("UK visual map already verified");return;}
        stage.delete();if(dir.getUsableSpace()<DriveTileServer.SOURCE_SIZE+128_000_000L)throw new IOException("Need approximately 2 GB free for visual map");
        HttpURLConnection c=openUrl(DriveTileServer.SOURCE);MessageDigest md=digest();long count=0,check=0;
        try{
            try(InputStream in=c.getInputStream();FileOutputStream out=new FileOutputStream(stage)){
                byte[] b=new byte[128*1024];int n;while((n=in.read(b))!=-1){count+=n;if(count>DriveTileServer.SOURCE_SIZE)throw new IOException("Oversized map");
                    md.update(b,0,n);out.write(b,0,n);if(count-check>=8*1024*1024){check=count;if(!unmetered())throw new IOException("Wi-Fi disconnected");
                        progress.update("Downloading UK visual map "+(100*count/DriveTileServer.SOURCE_SIZE)+"%");}}
                out.getFD().sync();
            }
            if(count!=DriveTileServer.SOURCE_SIZE||!expected.equals(hex(md.digest())))throw new IOException("Visual map hash mismatch");
            if(!stage.renameTo(dest))throw new IOException("Cannot activate verified visual map");
            progress.update("UK visual map available offline");
        }finally{c.disconnect();stage.delete();}
    }
    public synchronized void download(Progress progress)throws Exception{
        if(!unmetered())throw new IOException("Connect to unmetered Wi-Fi first");
        if(!folder.isDirectory()&&!folder.mkdirs())throw new IOException("Cannot create routing storage");
        byte[] raw=remoteManifest();JSONObject d=parseManifest(raw);
        if(installed()){validateInstalled(d);progress.update("UK graph already verified");return;}
        long rawSize=d.getLong("database_bytes"),compressed=d.getLong("gzip_bytes");
        if(folder.getUsableSpace()<rawSize+compressed+256_000_000L)throw new IOException("Insufficient storage: need about "+((rawSize+compressed+999999999L)/1000000000L)+" GB free for installation");
        JSONArray parts=d.getJSONArray("parts");List<File> files=new ArrayList<>();
        for(int i=0;i<parts.length();i++){
            JSONObject part=parts.getJSONObject(i);String name=part.getString("name");File file=new File(folder,name),tmp=new File(folder,name+".tmp");
            long expected=part.getLong("bytes");String hash=part.getString("sha256");
            if(file.isFile()&&file.length()==expected&&LocalRoutingPilot.sha256(file).equals(hash)){files.add(file);continue;}
            file.delete();tmp.delete();progress.update("Downloading graph part "+(i+1)+" of "+parts.length());
            HttpURLConnection c=open(name);MessageDigest md=digest();long total=0;
            try(InputStream in=c.getInputStream();FileOutputStream out=new FileOutputStream(tmp)){byte[] b=new byte[128*1024];int n;long check=0;
                while((n=in.read(b))!=-1){total+=n;if(total>expected)throw new IOException("Oversized map part");md.update(b,0,n);out.write(b,0,n);
                    if(total-check>=8*1024*1024){check=total;if(!unmetered())throw new IOException("Wi-Fi disconnected; retry to resume verified parts");}}
                out.getFD().sync();
            }finally{c.disconnect();}
            if(total!=expected||!hash.equals(hex(md.digest()))){tmp.delete();throw new IOException("Map part checksum mismatch");}
            if(!tmp.renameTo(file))throw new IOException("Cannot install verified part");files.add(file);
        }
        progress.update("Verifying complete archive");
        MessageDigest gz=digest();long gzBytes=0;for(File file:files){try(InputStream in=new FileInputStream(file)){byte[] b=new byte[128*1024];int n;while((n=in.read(b))!=-1){gz.update(b,0,n);gzBytes+=n;}}}
        if(gzBytes!=compressed||!d.getString("gzip_sha256").equals(hex(gz.digest())))throw new IOException("Compressed graph hash mismatch");
        File stage=new File(folder,"roads.sqlite.tmp");stage.delete();long total=0;MessageDigest md=digest();
        List<InputStream> streams=new ArrayList<>();
        try{
            for(File file:files)streams.add(new FileInputStream(file));
            progress.update("Installing local UK routing graph");
            try(GZIPInputStream in=new GZIPInputStream(new SequenceInputStream(Collections.enumeration(streams)),128*1024);FileOutputStream out=new FileOutputStream(stage)){
                byte[] b=new byte[128*1024];int n;while((n=in.read(b))!=-1){total+=n;if(total>rawSize)throw new IOException("Decompressed graph too large");md.update(b,0,n);out.write(b,0,n);}out.getFD().sync();}
            if(total!=rawSize||!d.getString("database_sha256").equals(hex(md.digest())))throw new IOException("Installed graph checksum mismatch");
            validateSchema(stage,d);
            if(db.exists()&&!db.delete())throw new IOException("Cannot replace incomplete graph");
            if(!stage.renameTo(db))throw new IOException("Cannot activate local graph");
            AtomicFile atomic=new AtomicFile(manifest);FileOutputStream out=atomic.startWrite();try{out.write(raw);atomic.finishWrite(out);}catch(Exception e){atomic.failWrite(out);throw e;}
            for(File file:files)file.delete();
        }finally{for(InputStream in:streams)try{in.close();}catch(IOException ignored){}stage.delete();}
        progress.update("UK routing graph installed · experimental previews");
    }
    private static void validateSchema(File file,JSONObject d)throws Exception{
        try(SQLiteDatabase sql=SQLiteDatabase.openDatabase(file.getPath(),null,SQLiteDatabase.OPEN_READONLY|SQLiteDatabase.NO_LOCALIZED_COLLATORS)){
            Map<String,String> meta=new HashMap<>();try(Cursor c=sql.rawQuery("SELECT key,value FROM meta",null)){while(c.moveToNext())meta.put(c.getString(0),c.getString(1));}
            if(!"drivemate-uk-routing-v1".equals(meta.get("format"))||!"motorcar".equals(meta.get("vehicle"))||!d.getString("source_sha256").equals(meta.get("source_sha256"))||!d.getString("source_tag").equals(meta.get("source_tag")))throw new IOException("Routing database provenance mismatch");
        }
    }
    private void validateInstalled(JSONObject d)throws Exception{
        if(db.length()!=d.getLong("database_bytes")||!LocalRoutingPilot.sha256(db).equals(d.getString("database_sha256")))throw new IOException("Local graph damaged; remove and reinstall");
        validateSchema(db,d);
    }
    public LocalRoutingPilot openGraph()throws Exception{
        if(!installed())throw new IOException("Download the UK routing graph in Settings first");
        JSONObject d=parseManifest(small(manifest));
        if(db.length()!=d.getLong("database_bytes"))throw new IOException("Local graph damaged");
        return new LocalRoutingPilot(db,d.getString("database_sha256"));
    }
    public List<TomTomApi.Destination> search(String query)throws Exception{
        if(!installed())throw new IOException("Download the UK routing graph first");
        String q=query.trim();if(q.length()<2||q.length()>100)return Collections.emptyList();
        JSONObject d=parseManifest(small(manifest));validateInstalled(d);
        List<TomTomApi.Destination> out=new ArrayList<>();
        try(SQLiteDatabase sql=SQLiteDatabase.openDatabase(db.getPath(),null,SQLiteDatabase.OPEN_READONLY|SQLiteDatabase.NO_LOCALIZED_COLLATORS);
            Cursor c=sql.rawQuery("SELECT name,lat,lon,kind FROM places WHERE name LIKE ? ESCAPE '\\' COLLATE NOCASE ORDER BY CASE WHEN name=? COLLATE NOCASE THEN 0 ELSE 1 END,name LIMIT 12",new String[]{q.replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%",q})){
            while(c.moveToNext())out.add(new TomTomApi.Destination(c.getString(0),c.getString(3),new GeoMath.Point(c.getDouble(1),c.getDouble(2))));
        }return out;
    }
    public TomTomApi.Route preview(GeoMath.Point start,GeoMath.Point end)throws Exception{
        try(LocalRoutingPilot engine=openGraph()){
            LocalRoutingPilot.Result r=engine.route(start,end);
            // Geometry-only candidate: do not invent lane advice, limits or turn instructions.
            List<TomTomApi.Instruction> steps=new ArrayList<>();
            steps.add(new TomTomApi.Instruction(r.metres,"End of experimental route preview","DESTINATION","",0,0,"",""));
            return new TomTomApi.Route(r.points,steps,r.metres,(int)Math.ceil(r.seconds),0,new ArrayList<>(),new ArrayList<>(),new ArrayList<>(),"DriveMate local preview");
        }
    }
}
