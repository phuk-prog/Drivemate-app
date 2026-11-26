package com.drivemate.app;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import java.io.*;
import java.util.*;

/** Viewport-bounded national display tiles, from pinned DriveMate GitHub range cache or bundled world.
 * No server process, no external map SDK. Raw disk cache is existing DriveTileServer range storage. */
final class NativeTileSource implements AutoCloseable {
    private final Context app;
    private DriveTileServer uk;
    private PmTilesReader world;
    private AssetFileDescriptor worldAsset;
    private RandomAccessFile worldFile; // not used; asset stream uses bounded positional channel
    private FileInputStream worldStream;
    private final LinkedHashMap<String,List<NativeMapScene.Shape>> cache=new LinkedHashMap<>(16,.75f,true);
    private int cachedVertices;
    NativeTileSource(Context app){this.app=app.getApplicationContext();}
    private byte[] raw(int z,int x,int y)throws IOException{
        if(z>6){if(uk==null)uk=new DriveTileServer(app,false);return uk.nativeTile(z,x,y);}
        if(world==null){
            worldAsset=app.getAssets().openFd("world-z0-6.pmtiles");
            worldStream=worldAsset.createInputStream();long start=worldAsset.getStartOffset(),length=worldAsset.getLength();
            world=PmTilesReader.open(new PmTilesReader.Source(){
                public long size(){return length;}
                public byte[] read(long offset,int count)throws IOException{
                    if(offset<0||count<0||offset>length-count)throw new IOException("World asset range");
                    java.nio.ByteBuffer b=java.nio.ByteBuffer.allocate(count);
                    while(b.hasRemaining()){int n=worldStream.getChannel().read(b,start+offset+b.position());if(n<=0)throw new IOException("Truncated world asset");}return b.array();
                }
            });
        }
        return world.tile(z,x,y);
    }
    private static int vertices(List<NativeMapScene.Shape> s){int n=0;for(NativeMapScene.Shape f:s)n+=f.xy.length/2;return n;}
    private List<NativeMapScene.Shape> tile(int z,int x,int y)throws IOException{
        String key=z+"/"+x+"/"+y;List<NativeMapScene.Shape> s=cache.get(key);if(s!=null)return s;
        s=NativeMvtDecoder.decode(raw(z,x,y),z,x,y);int count=vertices(s);
        while(!cache.isEmpty()&&(cache.size()>=32||cachedVertices+count>250000)){Iterator<List<NativeMapScene.Shape>> i=cache.values().iterator();cachedVertices-=vertices(i.next());i.remove();}
        if(count<=250000){cache.put(key,s);cachedVertices+=count;}return s;
    }
    NativeMapScene load(GeoMath.Point p,double zoom,int width,int height)throws IOException{
        int z=Math.max(2,Math.min(14,(int)Math.floor(zoom)));
        double cx=NativeMapProjection.x(p.lon),cy=NativeMapProjection.y(p.lat),scale=256*Math.pow(2,zoom);
        // Rotated viewport diagonal + margin; lower source zoom until tile count is bounded.
        double radius=Math.max(256,Math.hypot(Math.max(1,width),Math.max(1,height))*.85)/scale;
        int a,b,c,d,n;
        do{n=1<<z;a=Math.max(0,(int)Math.floor((cx-radius)*n));b=Math.min(n-1,(int)Math.floor((cx+radius)*n));c=Math.max(0,(int)Math.floor((cy-radius)*n));d=Math.min(n-1,(int)Math.floor((cy+radius)*n));if((long)(b-a+1)*(d-c+1)<=16||z<=2)break;z--;}while(true);
        ArrayList<NativeMapScene.Shape> shapes=new ArrayList<>();int total=0;
        // Reject incomplete coverage rather than claiming a full viewport after missing chunks.
        for(int y=c;y<=d;y++)for(int x=a;x<=b;x++){
            List<NativeMapScene.Shape> s=tile(z,x,y);total+=vertices(s);if(total>300000||shapes.size()+s.size()>32000)throw new IOException("Viewport exceeds display budget");shapes.addAll(s);
        }
        shapes.sort(Comparator.comparingInt(f->f.bridge?100+f.kind:f.kind));
        return NativeMapScene.tiles(shapes,new double[]{NativeMapProjection.lon(a/(double)n),NativeMapProjection.lat((d+1.0)/n),NativeMapProjection.lon((b+1.0)/n),NativeMapProjection.lat(c/(double)n)});
    }
    public void close(){cache.clear();if(uk!=null)uk.close();try{if(worldStream!=null)worldStream.close();if(worldAsset!=null)worldAsset.close();}catch(IOException ignored){}}
}
