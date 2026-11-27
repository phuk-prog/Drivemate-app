package com.drivemate.app;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import java.io.*;
import java.util.*;

/** Viewport-bounded national display tiles, from pinned DriveMate GitHub range cache or bundled world.
 * No server process, no external map SDK. Raw disk cache is existing DriveTileServer range storage. */
final class NativeTileSource implements AutoCloseable {
    private final Context app;
    private volatile DriveTileServer uk;
    private volatile PmTilesReader world;
    private volatile PackTiles packs;
    private volatile CdnTiles cdn;
    private synchronized CdnTiles cdn(){if(cdn==null)cdn=new CdnTiles(app);return cdn;}
    private volatile int packGeneration=-1;
    private AssetFileDescriptor worldAsset;
    private FileInputStream worldStream;
    private final LinkedHashMap<String,List<NativeMapScene.Shape>> cache=new LinkedHashMap<>(16,.75f,true);
    private int cachedVertices;
    /** Tiles that could not be loaded in the latest {@link #load}; 0 means the view is complete. */
    private volatile int missing;
    int missingTiles(){return missing;}
    NativeTileSource(Context app){this.app=app.getApplicationContext();}
    /** Fetches and decodes tiles of one viewport in parallel; the network, not the CPU, is the slow part. */
    private static final java.util.concurrent.ThreadPoolExecutor POOL=newPool();
    private static java.util.concurrent.ThreadPoolExecutor newPool(){
        java.util.concurrent.ThreadPoolExecutor p=new java.util.concurrent.ThreadPoolExecutor(4,4,30,java.util.concurrent.TimeUnit.SECONDS,
                new java.util.concurrent.LinkedBlockingQueue<Runnable>(),r->{Thread t=new Thread(r,"drivemate-tiles");t.setDaemon(true);return t;});
        p.allowCoreThreadTimeOut(true);return p;
    }
    /** How long one viewport load waits for tiles; tiles still arriving are cached for the retry. */
    static final long LOAD_BUDGET_MS=12000;
    private synchronized DriveTileServer ukServer()throws IOException{if(uk==null)uk=new DriveTileServer(app,false);return uk;}
    /** Start a background fetch of the tiles around a point (a wide area on Wi-Fi, a small one on mobile data). */
    void warm(GeoMath.Point p){
        if(p==null||!Double.isFinite(p.lat)||!Double.isFinite(p.lon))return;
        try{ukServer().prefetchAround(p.lat,p.lon);}catch(IOException ignored){}
    }
    private synchronized void openWorld()throws IOException{
        if(world!=null)return;
        worldAsset=app.getAssets().openFd("world-z0-6.pmtiles");
        worldStream=worldAsset.createInputStream();final long start=worldAsset.getStartOffset(),length=worldAsset.getLength();
        world=PmTilesReader.open(new PmTilesReader.Source(){
            public long size(){return length;}
            public byte[] read(long offset,int count)throws IOException{
                if(offset<0||count<0||offset>length-count)throw new IOException("World asset range");
                java.nio.ByteBuffer b=java.nio.ByteBuffer.allocate(count);
                while(b.hasRemaining()){int n=worldStream.getChannel().read(b,start+offset+b.position());if(n<=0)throw new IOException("Truncated world asset");}return b.array();
            }
        });
    }
    /** Full-detail packs the user downloaded; reopened when one is installed or removed. */
    private synchronized PackTiles installedPacks(){
        int g=MapPacks.GENERATION.get();
        if(packs==null||packGeneration!=g){
            if(packs!=null)packs.close();
            PackTiles t=new PackTiles();
            for(java.io.File f:new MapPacks(app).installedFiles())try{t.add(f);}catch(IOException ignored){}
            packs=t;packGeneration=g;synchronized(cache){cache.clear();cachedVertices=0;}
        }
        return packs;
    }
    private byte[] raw(int z,int x,int y)throws IOException{
        if(z>6){byte[] local=installedPacks().tile(z,x,y);if(local!=null)return local;byte[] fast=cdn().tile(z,x,y);if(fast!=null)return fast;return ukServer().nativeTile(z,x,y);}
        if(world==null)openWorld();
        return world.tile(z,x,y);
    }
    private static int vertices(List<NativeMapScene.Shape> s){int n=0;for(NativeMapScene.Shape f:s)n+=f.xy.length/2;return n;}
    private List<NativeMapScene.Shape> tile(int z,int x,int y)throws IOException{
        String key=z+"/"+x+"/"+y;
        synchronized(cache){List<NativeMapScene.Shape> hit=cache.get(key);if(hit!=null)return hit;}
        List<NativeMapScene.Shape> s=NativeMvtDecoder.decode(raw(z,x,y),z,x,y);int count=vertices(s);
        synchronized(cache){
            while(!cache.isEmpty()&&(cache.size()>=32||cachedVertices+count>250000)){Iterator<List<NativeMapScene.Shape>> i=cache.values().iterator();cachedVertices-=vertices(i.next());i.remove();}
            if(count<=250000){cache.put(key,s);cachedVertices+=count;}
        }
        return s;
    }
    NativeMapScene load(GeoMath.Point p,double zoom,int width,int height)throws IOException{
        if(p==null||!Double.isFinite(p.lat)||!Double.isFinite(p.lon)||!Double.isFinite(zoom)||Math.abs(p.lat)>85||Math.abs(p.lon)>180)throw new IOException("Invalid viewport");
        int z=Math.max(2,Math.min(14,(int)Math.floor(zoom)));
        double cx=NativeMapProjection.x(p.lon),cy=NativeMapProjection.y(p.lat),scale=256*Math.pow(2,zoom);
        // Rotated viewport diagonal + margin; lower source zoom until tile count is bounded.
        double radius=Math.max(256,Math.hypot(Math.max(1,width),Math.max(1,height))*.85)/scale;
        int a,b,c,d,n;
        do{n=1<<z;a=Math.max(0,(int)Math.floor((cx-radius)*n));b=Math.min(n-1,(int)Math.floor((cx+radius)*n));c=Math.max(0,(int)Math.floor((cy-radius)*n));d=Math.min(n-1,(int)Math.floor((cy+radius)*n));if((long)(b-a+1)*(d-c+1)<=16||z<=2)break;z--;}while(true);
        ArrayList<NativeMapScene.Shape> shapes=new ArrayList<>();int total=0,loadedTiles=0;missing=0;IOException firstFailure=null;
        // Fetch every tile of the view at once (4 at a time). Draw the ones that arrive within the time
        // budget and report how many are missing; the rest keep downloading into the cache for the retry.
        ArrayList<java.util.concurrent.Future<List<NativeMapScene.Shape>>> pending=new ArrayList<>();
        for(int y=c;y<=d;y++)for(int x=a;x<=b;x++){final int tz=z,tx=x,ty=y;pending.add(POOL.submit(()->tile(tz,tx,ty)));}
        final long deadline=android.os.SystemClock.elapsedRealtime()+LOAD_BUDGET_MS;
        for(java.util.concurrent.Future<List<NativeMapScene.Shape>> f:pending){
            List<NativeMapScene.Shape> s;
            try{s=f.get(Math.max(1,deadline-android.os.SystemClock.elapsedRealtime()),java.util.concurrent.TimeUnit.MILLISECONDS);}
            catch(java.util.concurrent.TimeoutException|java.util.concurrent.ExecutionException unavailable){missing++;if(firstFailure==null)firstFailure=new IOException(String.valueOf(unavailable.getCause()!=null?unavailable.getCause().getMessage():"timed out"));continue;}
            catch(InterruptedException interrupted){Thread.currentThread().interrupt();missing++;if(firstFailure==null)firstFailure=new IOException("interrupted");continue;}
            loadedTiles++;total+=vertices(s);if(total>300000||shapes.size()+s.size()>32000)throw new IOException("Viewport exceeds display budget");shapes.addAll(s);
        }
        if(loadedTiles==0)throw firstFailure!=null?firstFailure:new IOException("No map tiles for this view");
        shapes.sort(Comparator.comparingInt(f->f.bridge?100+f.kind:f.kind));
        return NativeMapScene.tiles(shapes,new double[]{NativeMapProjection.lon(a/(double)n),NativeMapProjection.lat((d+1.0)/n),NativeMapProjection.lon((b+1.0)/n),NativeMapProjection.lat(c/(double)n)});
    }
    public void close(){synchronized(cache){cache.clear();}if(uk!=null)uk.close();if(packs!=null)packs.close();try{if(worldStream!=null)worldStream.close();if(worldAsset!=null)worldAsset.close();}catch(IOException ignored){}}
}
