package com.drivemate.app;
import android.content.Context;
import java.io.*;
import java.util.*;

/** Verified-at-build OSM node positions; not live signal phases or a navigation instruction. */
final class MappedSignals {
    private static final String ASSET="uk-traffic-signals.bin";
    private static final String SOURCE="2c31f7d0c5c7280aee2130f38f7ebabb990626a8f5a208dba3c0279ada6c3164";
    private final int[] lat,lon;
    private MappedSignals(int[] lat,int[] lon){this.lat=lat;this.lon=lon;}
    static MappedSignals load(Context context)throws IOException{
        try(InputStream input=context.getAssets().open(ASSET)){return read(input);}
    }
    static MappedSignals read(InputStream input)throws IOException{
        DataInputStream in=new DataInputStream(new BufferedInputStream(input));
        if(in.readInt()!=0x444d5331)throw new IOException("Bad signal format");
        byte[] sha=new byte[32];in.readFully(sha);StringBuilder hash=new StringBuilder();
        for(byte b:sha)hash.append(String.format(java.util.Locale.ROOT,"%02x",b&255));
        if(!SOURCE.equals(hash.toString()))throw new IOException("Signal source mismatch");
        int count=in.readInt();if(count<0||count>1000000)throw new IOException("Signal count outside bounds");
        int[] a=new int[count],o=new int[count];
        for(int i=0;i<count;i++){a[i]=in.readInt();o[i]=in.readInt();
            if(a[i]<490000000||a[i]>610000000||o[i]<-90000000||o[i]>30000000||i>0&&(a[i]<a[i-1]||a[i]==a[i-1]&&o[i]<=o[i-1]))throw new IOException("Invalid ordered signal positions");}
        if(in.read()!=-1)throw new IOException("Unexpected signal bytes");
        return new MappedSignals(a,o);
    }
    private int first(int value){int l=0,r=lat.length;while(l<r){int m=(l+r)>>>1;if(lat[m]<value)l=m+1;else r=m;}return l;}
    List<int[]> nearby(double south,double west,double north,double east,int limit){
        if(!Double.isFinite(south)||!Double.isFinite(west)||!Double.isFinite(north)||!Double.isFinite(east)||south>north||west>east||north-south>.25||east-west>.25||limit<1||limit>2000)return Collections.emptyList();
        int a=(int)Math.floor(south*1e7),b=(int)Math.ceil(north*1e7),x=(int)Math.floor(west*1e7),y=(int)Math.ceil(east*1e7);
        ArrayList<int[]> out=new ArrayList<>();for(int i=first(a);i<lat.length&&lat[i]<=b;i++){if(lon[i]<x||lon[i]>y)continue;if(out.size()==limit)return Collections.emptyList();out.add(new int[]{lat[i],lon[i]});}return out;
    }
}
