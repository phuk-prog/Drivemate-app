package com.drivemate.app;

import android.content.Context;
import java.io.*;
import java.util.*;
import org.maplibre.geojson.*;

/** Mapped locations only; not evidence of operating status or route/direction applicability. */
final class MappedRoadside {
    static final String ASSET="uk-roadside-points.bin";
    private static final String SHA="2c31f7d0c5c7280aee2130f38f7ebabb990626a8f5a208dba3c0279ada6c3164";
    private final int[] lat,lon;
    private final byte[] kind;
    private MappedRoadside(int[] a,int[] o,byte[] k){lat=a;lon=o;kind=k;}
    static MappedRoadside load(Context context)throws IOException{
        try(InputStream input=context.getAssets().open(ASSET)){return read(input);}
    }
    static MappedRoadside read(InputStream input)throws IOException {
        DataInputStream in=new DataInputStream(new BufferedInputStream(input));
        if(in.readInt()!=0x444d4831)throw new IOException("Bad roadside format");
        byte[] sha=new byte[32];in.readFully(sha);
        StringBuilder hash=new StringBuilder();
        for(byte b:sha)hash.append(String.format(Locale.ROOT,"%02x",b&255));
        if(!SHA.equals(hash.toString()))throw new IOException("Roadside source mismatch");
        int n=in.readInt();if(n<0||n>2000000)throw new IOException("Roadside count outside bounds");
        int[] a=new int[n],o=new int[n];byte[] k=new byte[n];
        for(int i=0;i<n;i++){
            a[i]=in.readInt();o[i]=in.readInt();k[i]=in.readByte();
            if(a[i]<490000000||a[i]>610000000||o[i]<-90000000||o[i]>30000000||k[i]<1||k[i]>4
                ||i>0&&(a[i]<a[i-1]||a[i]==a[i-1]&&(o[i]<o[i-1]||o[i]==o[i-1]&&k[i]<=k[i-1])))
                throw new IOException("Invalid roadside point");
        }
        if(in.read()!=-1)throw new IOException("Unexpected roadside bytes");
        return new MappedRoadside(a,o,k);
    }
    private int first(int v){int l=0,r=lat.length;while(l<r){int m=(l+r)>>>1;if(lat[m]<v)l=m+1;else r=m;}return l;}
    FeatureCollection features(double south,double west,double north,double east,boolean detailed){
        ArrayList<Feature> out=new ArrayList<>();
        if(!Double.isFinite(south)||!Double.isFinite(west)||!Double.isFinite(north)||!Double.isFinite(east)
            ||south>north||west>east||north-south>.25||east-west>.25)return FeatureCollection.fromFeatures(out);
        int a=(int)Math.floor(south*1e7),b=(int)Math.ceil(north*1e7),x=(int)Math.floor(west*1e7),y=(int)Math.ceil(east*1e7);
        for(int i=first(a);i<lat.length&&lat[i]<=b;i++){
            if(lon[i]<x||lon[i]>y||kind[i]==4||(!detailed&&kind[i]!=1))continue;
            if(out.size()>=1500)break;
            Feature f=Feature.fromGeometry(Point.fromLngLat(lon[i]/1e7,lat[i]/1e7));
            f.addStringProperty("icon",kind[i]==1?"dm-mapped-camera":kind[i]==2?"dm-mapped-crossing":"dm-mapped-calming");
            out.add(f);
        }
        return FeatureCollection.fromFeatures(out);
    }
}
