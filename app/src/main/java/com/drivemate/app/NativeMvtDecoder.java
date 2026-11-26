package com.drivemate.app;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Original bounded Mapbox Vector Tile v2 wire decoder for DriveMate Shortbread data.
 * No protobuf or map SDK. Display only: tile geometry is never routing evidence. */
final class NativeMvtDecoder {
    private static final int MAX_BYTES=16*1024*1024,MAX_FEATURES=20000,MAX_VERTICES=160000;
    private static final class Wire {
        final byte[] b;int p;final int end;
        Wire(byte[] b,int p,int end){this.b=b;this.p=p;this.end=end;}
        boolean more(){return p<end;}
        long v()throws IOException{long n=0;for(int i=0;i<10;i++){if(p>=end)throw new IOException("Truncated varint");int x=b[p++]&255;if(i==9&&(x&254)!=0)throw new IOException("Overflow varint");n|=(long)(x&127)<<(7*i);if(x<128)return n;}throw new IOException("Long varint");}
        int count()throws IOException{long n=v();if(n<0||n>Integer.MAX_VALUE)throw new IOException("Invalid integer");return (int)n;}
        Wire sub()throws IOException{int n=count();if(n>end-p)throw new IOException("Truncated field");Wire w=new Wire(b,p,p+n);p+=n;return w;}
        String string()throws IOException{Wire w=sub();if(w.end-w.p>4096)throw new IOException("Oversized string");return new String(b,w.p,w.end-w.p,StandardCharsets.UTF_8);}
        long fixed(int n)throws IOException{if(end-p<n)throw new IOException("Truncated fixed field");long value=0;for(int i=0;i<n;i++)value|=(long)(b[p++]&255)<<(i*8);return value;}
        void skip(int type)throws IOException{switch(type){case 0:v();break;case 1:fixed(8);break;case 2:sub();break;case 5:fixed(4);break;default:throw new IOException("Unsupported wire type");}}
    }
    private static final class F {long id;int type;Wire tags,geometry;}
    private static long zig(long n){return (n>>>1)^-(n&1);}
    private static String value(Wire w)throws IOException{
        String out="";while(w.more()){int k=w.count();switch(k){case 10:out=w.string();break;case 21:out=Float.toString(Float.intBitsToFloat((int)w.fixed(4)));break;case 25:out=Double.toString(Double.longBitsToDouble(w.fixed(8)));break;case 32:case 40:out=Long.toString(w.v());break;case 48:out=Long.toString(zig(w.v()));break;case 56:out=w.v()==0?"false":"true";break;default:w.skip(k&7);}}return out;
    }
    static List<NativeMapScene.Shape> decode(byte[] data,int z,int tx,int ty)throws IOException{
        if(data==null)return Collections.emptyList();if(data.length>MAX_BYTES||z<0||z>14||tx<0||ty<0||tx>=(1<<z)||ty>=(1<<z))throw new IOException("Tile outside bounds");
        Wire w=new Wire(data,0,data.length);ArrayList<NativeMapScene.Shape> out=new ArrayList<>();int[] budget={0,0};
        while(w.more()){int k=w.count();if(k==26)layer(w.sub(),z,tx,ty,out,budget);else w.skip(k&7);}
        return out;
    }
    private static void layer(Wire w,int z,int tx,int ty,List<NativeMapScene.Shape> out,int[] budget)throws IOException{
        String name="";int extent=4096,version=1;List<String> keys=new ArrayList<>(),values=new ArrayList<>();List<Wire> features=new ArrayList<>();
        while(w.more()){int k=w.count();switch(k){case 10:name=w.string();break;case 18:if(++budget[0]>MAX_FEATURES)throw new IOException("Tile feature limit");features.add(w.sub());break;case 26:keys.add(w.string());break;case 34:values.add(value(w.sub()));break;case 40:extent=w.count();break;case 120:version=w.count();break;default:w.skip(k&7);}if(keys.size()>10000||values.size()>20000)throw new IOException("Tile dictionary limit");}
        if(extent<1||extent>65536||version<1||version>2)throw new IOException("Unsupported tile layer");
        if(!Arrays.asList("streets","buildings","land","water_polygons","water_lines","railways","place_labels","street_labels","street_labels_points").contains(name))return;
        for(Wire f:features){
            F row=new F();while(f.more()){int k=f.count();switch(k){case 8:row.id=f.v();break;case 18:row.tags=f.sub();break;case 24:row.type=f.count();break;case 34:row.geometry=f.sub();break;default:f.skip(k&7);}}
            if(row.geometry==null||row.type<1||row.type>3)continue;
            Map<String,String> tags=new HashMap<>();if(row.tags!=null)while(row.tags.more()){int a=row.tags.count(),b=row.tags.count();if(a>=keys.size()||b>=values.size())throw new IOException("Bad tile tags");tags.put(keys.get(a),values.get(b));}
            String kind=tags.getOrDefault("kind",""),label=tags.getOrDefault("name",tags.getOrDefault("ref",""));if(label.length()>120)label="";
            int type=-1;
            if(name.equals("streets")&&row.type==2){type=Arrays.asList("rail","light_rail","tram","subway","narrow_gauge","funicular","monorail","disused").contains(kind)?NativeMapScene.RAIL:kind.startsWith("motorway")||kind.startsWith("trunk")?NativeMapScene.MOTORWAY:kind.startsWith("primary")||kind.startsWith("secondary")||kind.startsWith("tertiary")?NativeMapScene.MAIN:Arrays.asList("footway","path","cycleway","steps","pedestrian","bridleway").contains(kind)?NativeMapScene.PATH:NativeMapScene.LOCAL;}
            else if(name.equals("buildings")&&row.type==3)type=NativeMapScene.BUILDING;
            else if(name.equals("land")&&row.type==3)type=NativeMapScene.LAND;
            else if(name.startsWith("water_")&&row.type>=2)type=NativeMapScene.WATER;
            else if(name.equals("railways")&&row.type==2)type=NativeMapScene.RAIL;
            else if(name.equals("place_labels")&&row.type==1&&!label.isEmpty())type=NativeMapScene.LABEL;
            if(type<0)continue;
            List<Double> coords=new ArrayList<>();List<Integer> parts=new ArrayList<>();long x=0,y=0;int start=-1;boolean closed=false;
            Wire g=row.geometry;
            while(g.more()){
                long command=g.v();int id=(int)(command&7);long n=command>>>3;
                if(n<1||n>MAX_VERTICES)throw new IOException("Geometry command count");
                if(id==7){if(n!=1||row.type!=3||start<0||closed||coords.size()-start<6)throw new IOException("Invalid close path");closed=true;continue;}
                if(id!=1&&id!=2)throw new IOException("Unsupported geometry command");
                if(id==2&&(start<0||closed||row.type==1))throw new IOException("Invalid line command");
                if(id==1&&row.type!=1&&n!=1)throw new IOException("Invalid move command");
                for(int i=0;i<n;i++){
                    if(++budget[1]>MAX_VERTICES)throw new IOException("Tile vertex limit");
                    if(id==1){if(row.type==3&&start>=0&&!closed)throw new IOException("Open polygon");start=coords.size();parts.add(start);closed=false;}
                    x+=zig(g.v());y+=zig(g.v());if(Math.abs(x)>extent*8L||Math.abs(y)>extent*8L)throw new IOException("Tile coordinate limit");
                    double scale=1L<<z;coords.add((tx+x/(double)extent)/scale);coords.add((ty+y/(double)extent)/scale);
                }
            }
            if(coords.isEmpty()||row.type==3&&!closed)continue;
            double[] xy=new double[coords.size()];for(int i=0;i<xy.length;i++)xy[i]=coords.get(i);
            int[] offsets=new int[parts.size()];for(int i=0;i<offsets.length;i++)offsets[i]=parts.get(i);
            double height=type==NativeMapScene.BUILDING?MapDetailPolicy.heightMetres(tags.get("height"),tags.get("building:levels")):0;
            out.add(new NativeMapScene.Shape(row.id,type,xy,label,"true".equals(tags.get("bridge")),"true".equals(tags.get("tunnel")),row.type==3,height,offsets));
        }
    }
}
