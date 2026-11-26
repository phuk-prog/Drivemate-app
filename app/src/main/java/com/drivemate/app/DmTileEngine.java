package com.drivemate.app;

import org.json.*;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.GZIPInputStream;
import android.os.SystemClock;

/** Original tiled routing proof; bounded A*, not road validated. */
public final class DmTileEngine {
    public interface Source { InputStream open(String name)throws Exception; }
    private final JSONObject catalog,globalRules;
    /** Fastest edge in the catalog; A* heuristic divides by it so it stays a true lower bound. */
    private final double maxKph;
    private final Source source;
    private final DmCellPlanner planner;
    private final LinkedHashMap<String,Tile> cache=new LinkedHashMap<>(16,.75f,true);
    private final Set<String> pinned=new HashSet<>();
    private static final int CELL=50000,MAX_TILES=24,MAX_NODES=80000;
    public static final class CoverageException extends IOException{CoverageException(String s){super(s);}}
    static byte[] read(InputStream input,int max)throws IOException{
        try(InputStream in=input;ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){if(out.size()+n>max)throw new IOException("Tile size exceeds limit");out.write(b,0,n);}return out.toByteArray();}
    }
    static String sha(byte[] b)throws Exception{
        StringBuilder s=new StringBuilder();for(byte v:MessageDigest.getInstance("SHA-256").digest(b))s.append(String.format(Locale.ROOT,"%02x",v&255));return s.toString();
    }
    public DmTileEngine(byte[] manifest,String expected,Source source)throws Exception{
        if(!sha(manifest).equals(expected))throw new IOException("Manifest checksum mismatch");
        JSONObject m=new JSONObject(new String(manifest,java.nio.charset.StandardCharsets.UTF_8));
        if(!"drivemate-tiles-v1".equals(m.getString("format"))||m.getInt("cell_microdegrees")!=CELL||!"ODbL-1.0".equals(m.getString("license")))throw new IOException("Unsupported map format");
        catalog=m.getJSONObject("tiles");globalRules=m.getJSONObject("global_rules");
        double declared=m.optDouble("max_speed_kph",160);maxKph=declared>0&&declared<160?declared:160;this.source=source;planner=new DmCellPlanner(catalog);
    }
    static String key(double lat,double lon){return Math.floorDiv((int)Math.round(lat*1e6),CELL)+"_"+Math.floorDiv((int)Math.round(lon*1e6),CELL);}
    public Set<String> keys(){Set<String> out=new TreeSet<>();catalog.keys().forEachRemaining(out::add);return out;}
    static final class Node{
        final long id;final GeoMath.Point point;final JSONArray edges,rules;final String cell;
        /** False for nodes in a tiny isolated road network; never used to snap a journey end. Older tiles omit field 5. */
        final boolean snappable;
        Node(JSONArray a,String cell)throws JSONException{id=a.getLong(0);point=new GeoMath.Point(a.getInt(1)/1e6,a.getInt(2)/1e6);edges=a.getJSONArray(3);rules=a.getJSONArray(4);snappable=a.length()<=5||a.getInt(5)!=0;this.cell=cell;}
    }
    static final class Tile{
        final Map<Long,Node> nodes=new HashMap<>();final JSONArray features,places;
        Tile(JSONObject data,String key)throws Exception{
            JSONArray a=data.getJSONArray("nodes");if(a.length()>MAX_NODES)throw new IOException("Tile graph exceeds pilot bounds");
            for(int i=0;i<a.length();i++){Node n=new Node(a.getJSONArray(i),key);if(!key.equals(DmTileEngine.key(n.point.lat,n.point.lon))||nodes.put(n.id,n)!=null)throw new IOException("Wrong tile node");}
            features=data.getJSONArray("features");places=data.getJSONArray("places");
        }
    }
    private Tile load(String k)throws Exception{
        Tile old=cache.get(k);if(old!=null)return old;
        JSONObject meta=catalog.optJSONObject(k);if(meta==null)throw new CoverageException("Road data outside downloaded coverage");
        if(!k.matches("-?[0-9]+_-?[0-9]+")||!(k+".dmt.gz").equals(meta.getString("name")))throw new IOException("Unsafe tile name");
        int bytes=meta.getInt("bytes");if(bytes<=0||bytes>8_000_000)throw new IOException("Oversized tile");
        byte[] raw=read(source.open(meta.getString("name")),bytes);
        if(raw.length!=bytes||!sha(raw).equals(meta.getString("sha256")))throw new IOException("Tile checksum mismatch");
        Tile t=new Tile(new JSONObject(new String(read(new GZIPInputStream(new ByteArrayInputStream(raw)),24_000_000),java.nio.charset.StandardCharsets.UTF_8)),k);
        if(cache.size()>=MAX_TILES){Iterator<String> it=cache.keySet().iterator();boolean removed=false;while(it.hasNext()){if(!pinned.contains(it.next())){it.remove();removed=true;break;}}if(!removed)throw new CoverageException("Pilot cache limit");}
        cache.put(k,t);return t;
    }
    public void retainJourney(Set<String> cells)throws Exception{
        if(cells.size()>MAX_TILES)throw new CoverageException("Journey exceeds pilot cache");
        Set<String> prior=new HashSet<>(pinned);pinned.clear();
        try{for(String k:cells){load(k);pinned.add(k);}}catch(Exception e){pinned.clear();pinned.addAll(prior);throw e;}
    }
    public int cachedTiles(){return cache.size();}
    public DmCellPlanner.Coverage coverage(Route route,double radiusKm,long budget)throws Exception{return planner.coverage(route.points,route.cells,radiusKm,budget);}
    private Node nearest(GeoMath.Point p,Set<String> allowed,boolean origin)throws Exception{
        int y=Math.floorDiv((int)Math.round(p.lat*1e6),CELL),x=Math.floorDiv((int)Math.round(p.lon*1e6),CELL);
        Node best=null;double dist=350;
        if(!catalog.has(key(p.lat,p.lon))||(allowed!=null&&!allowed.contains(key(p.lat,p.lon))))throw new CoverageException("Position outside pilot map");
        Map<String,Tile> nearby=new HashMap<>();
        for(int dy=-1;dy<=1;dy++)for(int dx=-1;dx<=1;dx++){
            String k=(y+dy)+"_"+(x+dx);if(!catalog.has(k)||(allowed!=null&&!allowed.contains(k)))continue;
            nearby.put(k,load(k));
        }
        Set<Long> incoming=new HashSet<>();
        if(!origin)for(Tile tile:nearby.values())for(Node n:tile.nodes.values())for(int i=0;i<n.edges.length();i++){
            JSONArray e=n.edges.getJSONArray(i);if(nearby.containsKey(e.getString(2)))incoming.add(e.getLong(1));
        }
        for(Tile tile:nearby.values())for(Node n:tile.nodes.values()){
            if(!n.snappable)continue;
            if(origin){
                boolean hasExit=false;for(int i=0;i<n.edges.length();i++){JSONArray e=n.edges.getJSONArray(i);if(allowed==null||allowed.contains(e.getString(2))){hasExit=true;break;}}
                if(!hasExit)continue;
            }else if(!incoming.contains(n.id))continue;
            double d=GeoMath.distance(p,n.point);if(d<dist||(d==dist&&best!=null&&n.id<best.id)){dist=d;best=n;}
        }
        if(best==null)throw new CoverageException("No eligible directed road node nearby");
        return best;
    }
    static final class State{
        final Node node;final long edge,way,prev;final double cost,distance,priority;final State parent;
        State(Node n,long e,long w,long p,double c,double d,double f,State prevState){node=n;edge=e;way=w;prev=p;cost=c;distance=d;priority=f;parent=prevState;}
    }
    public static final class Route{
        public final List<GeoMath.Point> points;public final List<Long> ways,nodeIds,edgeIds;public final Set<String> cells;public final double metres,seconds;public final int expansions;public final long ms;
        Route(State end,int count,long elapsed){List<GeoMath.Point> p=new ArrayList<>();List<Long>w=new ArrayList<>(),n=new ArrayList<>(),e=new ArrayList<>();Set<String> c=new HashSet<>();metres=end.distance;seconds=end.cost;expansions=count;ms=elapsed;
            for(State s=end;s!=null;s=s.parent){p.add(s.node.point);n.add(s.node.id);c.add(s.node.cell);if(s.edge!=0){w.add(s.way);e.add(s.edge);}}Collections.reverse(p);Collections.reverse(w);Collections.reverse(n);Collections.reverse(e);points=Collections.unmodifiableList(p);ways=Collections.unmodifiableList(w);nodeIds=Collections.unmodifiableList(n);edgeIds=Collections.unmodifiableList(e);cells=Collections.unmodifiableSet(c);}
    }
    private List<long[]> rules(State s)throws JSONException{
        List<long[]> out=new ArrayList<>();JSONArray g=globalRules.optJSONArray(Long.toString(s.way));
        if(g!=null)for(int i=0;i<g.length();i++){JSONArray r=g.getJSONArray(i);out.add(new long[]{r.getLong(0),r.getLong(1)});}
        for(int i=0;i<s.node.rules.length();i++){JSONArray r=s.node.rules.getJSONArray(i);if(r.getLong(0)==s.way)out.add(new long[]{r.getLong(1),r.getLong(2)});}
        return out;
    }
    public Route route(GeoMath.Point start,GeoMath.Point finish)throws Exception{return route(start,finish,null);}
    public Route route(GeoMath.Point start,GeoMath.Point finish,Set<String> allowed)throws Exception{
        long began=SystemClock.elapsedRealtime();Node a=nearest(start,allowed,true),b=nearest(finish,allowed,false);
        Map<String,Long> lower=planner.lowerBounds(b.cell);
        if(a.id==b.id)throw new IOException("Same snapped junction");
        PriorityQueue<State> q=new PriorityQueue<>(Comparator.comparingDouble(s->s.priority));Map<Long,Double> best=new HashMap<>();
        q.add(new State(a,0,0,0,0,0,0,null));best.put(0L,0.0);int count=0;
        while(!q.isEmpty()&&count<MAX_NODES&&best.size()<180000&&q.size()<160000&&SystemClock.elapsedRealtime()-began<25000){
            State s=q.poll();if(s.cost>best.getOrDefault(s.edge,Double.POSITIVE_INFINITY)+1e-7)continue;
            if(s.node.id==b.id)return new Route(s,count,SystemClock.elapsedRealtime()-began);
            count++;List<long[]> rs=rules(s);
            for(int i=0;i<s.node.edges.length();i++){
                JSONArray e=s.node.edges.getJSONArray(i);long id=e.getLong(0),dst=e.getLong(1),way=e.getLong(3);
                if(s.edge!=0&&!LocalRoutingPilot.allowed(s.prev,dst,way,rs))continue;
                double length=e.getLong(4)/1000.0,seconds=e.getLong(5)/1000.0;if(length<=0||seconds<=0)throw new IOException("Invalid edge");
                double cost=s.cost+seconds;if(cost+1e-7>=best.getOrDefault(id,Double.POSITIVE_INFINITY))continue;
                String nextCell=e.getString(2);if(allowed!=null&&!allowed.contains(nextCell))continue;
                Node next=load(nextCell).nodes.get(dst);if(next==null)throw new IOException("Broken cross-tile connection");
                best.put(id,cost);q.add(new State(next,id,way,s.node.id,cost,s.distance+length,cost+Math.max(GeoMath.distance(next.point,b.point)*3.6/maxKph,lower.getOrDefault(next.cell,0L)/1000.0),s));
            }
        }throw new CoverageException("No connected route inside pilot limits");
    }
    public List<JSONArray> search(String query,Set<String> cells)throws Exception{
        String q=query.trim().toLowerCase(Locale.ROOT);List<JSONArray> out=new ArrayList<>();
        if(q.length()<2)return out;
        for(String k:new TreeSet<>(cells)){JSONArray p=load(k).places;for(int i=0;i<p.length()&&out.size()<20;i++){JSONArray row=p.getJSONArray(i);if(row.getString(0).toLowerCase(Locale.ROOT).startsWith(q)||(row.length()>4&&row.optString(4).replace(" ","").toLowerCase(Locale.ROOT).startsWith(q.replace(" ",""))))out.add(row);}}return out;
    }
    public List<JSONArray> features(Set<String> cells)throws Exception{
        Map<Long,JSONArray> out=new LinkedHashMap<>();for(String k:new TreeSet<>(cells)){JSONArray a=load(k).features;for(int i=0;i<a.length();i++){JSONArray row=a.getJSONArray(i);out.put(row.getLong(0),row);}}return new ArrayList<>(out.values());
    }
    public JSONObject wayTags(long id,Set<String> cells)throws Exception{
        for(JSONArray f:features(cells))if(f.getLong(0)==id)return f.getJSONObject(1);
        return new JSONObject();
    }
}
