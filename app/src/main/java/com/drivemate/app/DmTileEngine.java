package com.drivemate.app;

import org.json.*;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.GZIPInputStream;

/** Original tiled routing proof; bounded A*, not road validated.
 *  With a road-hierarchy overlay in the catalog, only the start and end cells are
 *  searched road by road; other cells are crossed by exact precomputed jumps
 *  (map repository tools/dm_overlay.py), giving the same route cost. */
public final class DmTileEngine {
    public interface Source { InputStream open(String name)throws Exception; }
    /** Monotonic milliseconds; plain Java so the engine also runs in JVM unit tests. */
    private static long now(){return System.nanoTime()/1_000_000L;}
    private final JSONObject catalog,globalRules;
    /** Road hierarchy cells (manifest "overlay"."cells"), or null when absent. */
    private final JSONObject overlayCells;
    private final LinkedHashMap<String,Overlay> overlayCache=new LinkedHashMap<>(16,.75f,true);
    private static final int MAX_OVERLAYS=512;
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
        JSONObject ov=m.optJSONObject("overlay");
        if(ov!=null&&!"drivemate-overlay-v1".equals(ov.optString("format")))throw new IOException("Unsupported road hierarchy");
        overlayCells=ov==null?null:ov.getJSONObject("cells");
    }
    public boolean hasOverlay(){return overlayCells!=null;}
    /** Precomputed exact in-cell costs from each entering road to each leaving road. */
    static final class Overlay{
        final long[][] entries,exits;final int[][] cost;final Map<Long,Integer> row=new HashMap<>();final String[] exitCell;
        Overlay(JSONObject d)throws JSONException{
            JSONArray en=d.getJSONArray("entries"),ex=d.getJSONArray("exits"),c=d.getJSONArray("cost");
            entries=new long[en.length()][];for(int i=0;i<en.length();i++){JSONArray a=en.getJSONArray(i);entries[i]=new long[]{a.getLong(0),a.getLong(1),a.getLong(2),a.getLong(3),a.getLong(4),a.getLong(5)};row.put(entries[i][0],i);}
            exits=new long[ex.length()][];exitCell=new String[ex.length()];
            for(int i=0;i<ex.length();i++){JSONArray a=ex.getJSONArray(i);exits[i]=new long[]{a.getLong(0),a.getLong(1),a.getLong(2),a.getLong(4),a.getLong(5),a.getLong(6)};exitCell[i]=a.getString(3);}
            if(c.length()!=entries.length)throw new JSONException("Overlay cost rows");
            cost=new int[c.length()][];for(int i=0;i<c.length();i++){JSONArray r=c.getJSONArray(i);if(r.length()!=exits.length)throw new JSONException("Overlay cost columns");cost[i]=new int[r.length()];for(int j=0;j<r.length();j++)cost[i][j]=r.getInt(j);}
        }
    }
    private Overlay overlay(String k)throws Exception{
        Overlay old=overlayCache.get(k);if(old!=null)return old;
        JSONObject meta=overlayCells.optJSONObject(k);if(meta==null)throw new CoverageException("Road hierarchy missing cell");
        if(!k.matches("-?[0-9]+_-?[0-9]+")||!(k+".dmo.gz").equals(meta.getString("name")))throw new IOException("Unsafe overlay name");
        int bytes=meta.getInt("bytes");if(bytes<=0||bytes>8_000_000)throw new IOException("Oversized overlay");
        byte[] raw=read(source.open(meta.getString("name")),bytes);
        if(raw.length!=bytes||!sha(raw).equals(meta.getString("sha256")))throw new IOException("Overlay checksum mismatch");
        Overlay o=new Overlay(new JSONObject(new String(read(new GZIPInputStream(new ByteArrayInputStream(raw)),24_000_000),java.nio.charset.StandardCharsets.UTF_8)));
        if(overlayCache.size()>=MAX_OVERLAYS){Iterator<String> it=overlayCache.keySet().iterator();it.next();it.remove();}
        overlayCache.put(k,o);return o;
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
        Route(List<GeoMath.Point> p,List<Long> w,List<Long> n,List<Long> e,Set<String> c,double metres,double seconds,int count,long elapsed){
            points=Collections.unmodifiableList(p);ways=Collections.unmodifiableList(w);nodeIds=Collections.unmodifiableList(n);edgeIds=Collections.unmodifiableList(e);cells=Collections.unmodifiableSet(c);this.metres=metres;this.seconds=seconds;expansions=count;ms=elapsed;
        }
        Route(State end,int count,long elapsed){List<GeoMath.Point> p=new ArrayList<>();List<Long>w=new ArrayList<>(),n=new ArrayList<>(),e=new ArrayList<>();Set<String> c=new HashSet<>();metres=end.distance;seconds=end.cost;expansions=count;ms=elapsed;
            for(State s=end;s!=null;s=s.parent){p.add(s.node.point);n.add(s.node.id);c.add(s.node.cell);if(s.edge!=0){w.add(s.way);e.add(s.edge);}}Collections.reverse(p);Collections.reverse(w);Collections.reverse(n);Collections.reverse(e);points=Collections.unmodifiableList(p);ways=Collections.unmodifiableList(w);nodeIds=Collections.unmodifiableList(n);edgeIds=Collections.unmodifiableList(e);cells=Collections.unmodifiableSet(c);}
    }
    private List<long[]> rules(State s)throws JSONException{return rules(s.node,s.way);}
    /** Turn rules when standing on node having arrived on way (same as tools/dm_overlay.state_rules). */
    private List<long[]> rules(Node node,long way)throws JSONException{
        List<long[]> out=new ArrayList<>();JSONArray g=globalRules.optJSONArray(Long.toString(way));
        if(g!=null)for(int i=0;i<g.length();i++){JSONArray r=g.getJSONArray(i);out.add(new long[]{r.getLong(0),r.getLong(1)});}
        for(int i=0;i<node.rules.length();i++){JSONArray r=node.rules.getJSONArray(i);if(r.getLong(0)==way)out.add(new long[]{r.getLong(1),r.getLong(2)});}
        return out;
    }
    public Route route(GeoMath.Point start,GeoMath.Point finish)throws Exception{return route(start,finish,null);}
    public Route route(GeoMath.Point start,GeoMath.Point finish,Set<String> allowed)throws Exception{
        return overlayCells!=null?routeOverlay(start,finish,allowed):routeFlat(start,finish,allowed);
    }
    /** Search state for the hierarchy: a road by road step in a detail cell, or an overlay entry. */
    private static final class HState{
        final long edge,node,prev,way;final String cell;final double cost,priority;
        HState(long e,String c,long n,long p,long w,double g,double f){edge=e;cell=c;node=n;prev=p;way=w;cost=g;priority=f;}
    }
    /** How the search reached an edge: road by road from a node, or by jumping across a cell. */
    private static final class Step{final long parent;final boolean jump;final String cell;final long fromNode;Step(long p,boolean j,String c,long n){parent=p;jump=j;cell=c;fromNode=n;}}
    Route routeOverlay(GeoMath.Point start,GeoMath.Point finish,Set<String> allowed)throws Exception{
        long began=now();Node a=nearest(start,allowed,true),b=nearest(finish,allowed,false);
        if(a.id==b.id)throw new IOException("Same snapped junction");
        Set<String> detail=new HashSet<>(Arrays.asList(a.cell,b.cell));
        PriorityQueue<HState> q=new PriorityQueue<>(Comparator.comparingDouble(s->s.priority));
        Map<Long,Double> best=new HashMap<>();Map<Long,Step> steps=new HashMap<>();
        q.add(new HState(0,a.cell,a.id,0,0,0,heuristic(a.point,b.point)));best.put(0L,0.0);int count=0;
        while(!q.isEmpty()&&count<MAX_NODES&&best.size()<180000&&q.size()<160000&&now()-began<25000){
            HState s=q.poll();if(s.cost>best.getOrDefault(s.edge,Double.POSITIVE_INFINITY)+1e-7)continue;
            if(detail.contains(s.cell)){
                Node n=load(s.cell).nodes.get(s.node);
                if(n.id==b.id&&s.edge!=0)return unpack(s.edge,s.cost,steps,a,count,now()-began);
                count++;List<long[]> rs=rules(n,s.way);
                for(int i=0;i<n.edges.length();i++){
                    JSONArray e=n.edges.getJSONArray(i);long id=e.getLong(0),dst=e.getLong(1),way=e.getLong(3);String dest=e.getString(2);
                    if(allowed!=null&&!allowed.contains(dest))continue;
                    if(s.edge!=0&&!LocalRoutingPilot.allowed(s.prev,dst,way,rs))continue;
                    double seconds=e.getLong(5)/1000.0;if(e.getLong(4)<=0||seconds<=0)throw new IOException("Invalid edge");
                    double cost=s.cost+seconds;if(cost+1e-7>=best.getOrDefault(id,Double.POSITIVE_INFINITY))continue;
                    GeoMath.Point at;
                    if(detail.contains(dest)){Node next=load(dest).nodes.get(dst);if(next==null)throw new IOException("Broken cross-tile connection");at=next.point;}
                    else{Overlay o=overlay(dest);Integer r=o.row.get(id);if(r==null)throw new IOException("Road hierarchy missing entry");at=new GeoMath.Point(o.entries[r][4]/1e6,o.entries[r][5]/1e6);}
                    best.put(id,cost);steps.put(id,new Step(s.edge,false,s.cell,n.id));
                    q.add(new HState(id,dest,dst,n.id,way,cost,cost+heuristic(at,b.point)));
                }
            }else{
                Overlay o=overlay(s.cell);Integer r=o.row.get(s.edge);if(r==null)throw new IOException("Road hierarchy missing entry");count++;
                int[] row=o.cost[r];
                for(int j=0;j<row.length;j++){
                    if(row[j]<0)continue;long[] x=o.exits[j];String dest=o.exitCell[j];
                    if(allowed!=null&&!allowed.contains(dest))continue;
                    double cost=s.cost+row[j]/1000.0;if(cost+1e-7>=best.getOrDefault(x[0],Double.POSITIVE_INFINITY))continue;
                    best.put(x[0],cost);steps.put(x[0],new Step(s.edge,true,s.cell,0));
                    q.add(new HState(x[0],dest,x[2],x[1],x[3],cost,cost+heuristic(new GeoMath.Point(x[4]/1e6,x[5]/1e6),b.point)));
                }
            }
        }throw new CoverageException("No connected route inside pilot limits");
    }
    private double heuristic(GeoMath.Point p,GeoMath.Point goal){return GeoMath.distance(p,goal)*3.6/maxKph;}
    /** Edge-based Dijkstra inside one cell after driving entry edge (id, src, dst, way); integer ms like the map compiler.
     *  Returns, for each reached edge, {previous edge, edge index at its source node, source node}. */
    private Map<Long,long[]> cellSearch(Tile tile,String cell,long entry,long src,long dst,long way)throws Exception{
        Map<Long,Long> best=new HashMap<>();Map<Long,long[]> parent=new HashMap<>();
        PriorityQueue<long[]> q=new PriorityQueue<>(Comparator.comparingLong(v->v[0]));
        best.put(entry,0L);q.add(new long[]{0,entry,dst,src,way});
        while(!q.isEmpty()){
            long[] s=q.poll();if(s[0]>best.get(s[1]))continue;
            Node n=tile.nodes.get(s[2]);if(n==null)throw new IOException("Broken overlay entry");List<long[]> rs=rules(n,s[4]);
            for(int i=0;i<n.edges.length();i++){
                JSONArray e=n.edges.getJSONArray(i);long id=e.getLong(0),to=e.getLong(1),nw=e.getLong(3);
                if(!LocalRoutingPilot.allowed(s[3],to,nw,rs))continue;
                long cost=s[0]+e.getLong(5);if(cost>=best.getOrDefault(id,Long.MAX_VALUE))continue;
                best.put(id,cost);parent.put(id,new long[]{s[1],i,n.id});
                if(cell.equals(e.getString(2)))q.add(new long[]{cost,id,to,n.id,nw});
            }
        }return parent;
    }
    /** Rebuild the full road-by-road route, expanding each cell jump into its roads. */
    private Route unpack(long goalEdge,double seconds,Map<Long,Step> steps,Node start,int count,long elapsed)throws Exception{
        List<Long> chain=new ArrayList<>();for(long e=goalEdge;e!=0;e=steps.get(e).parent)chain.add(e);Collections.reverse(chain);
        List<GeoMath.Point> p=new ArrayList<>();List<Long> w=new ArrayList<>(),n=new ArrayList<>(),ids=new ArrayList<>();Set<String> cells=new HashSet<>();
        p.add(start.point);n.add(start.id);cells.add(start.cell);double metres=0;long prevEdge=0;
        for(long edge:chain){
            Step st=steps.get(edge);List<long[]> hops=new ArrayList<>();// {edge, source node, index at source}
            if(!st.jump){
                Node from=findNode(st.fromNode,st.cell);int at=-1;
                for(int i=0;i<from.edges.length();i++)if(from.edges.getJSONArray(i).getLong(0)==edge){at=i;break;}
                if(at<0)throw new IOException("Broken route edge");
                hops.add(new long[]{edge,from.id,at});
            }else{
                Overlay o=overlay(st.cell);long[] en=o.entries[o.row.get(prevEdge)];
                Map<Long,long[]> parent=cellSearch(load(st.cell),st.cell,en[0],en[1],en[2],en[3]);
                for(long e=edge;e!=en[0];){long[] pr=parent.get(e);if(pr==null)throw new IOException("Road hierarchy unpack failed");hops.add(new long[]{e,pr[2],pr[1]});e=pr[0];}
                Collections.reverse(hops);
            }
            for(long[] h:hops){
                Node from=findNode(h[1],st.cell);JSONArray e=from.edges.getJSONArray((int)h[2]);
                Node to=load(e.getString(2)).nodes.get(e.getLong(1));if(to==null)throw new IOException("Broken cross-tile connection");
                p.add(to.point);n.add(to.id);w.add(e.getLong(3));ids.add(h[0]);cells.add(to.cell);metres+=e.getLong(4)/1000.0;
            }
            prevEdge=edge;
        }
        return new Route(p,w,n,ids,cells,metres,seconds,count,elapsed);
    }
    private Node findNode(long id,String cell)throws Exception{Node x=load(cell).nodes.get(id);if(x==null)throw new IOException("Broken route node");return x;}
    Route routeFlat(GeoMath.Point start,GeoMath.Point finish,Set<String> allowed)throws Exception{
        long began=now();Node a=nearest(start,allowed,true),b=nearest(finish,allowed,false);
        Map<String,Long> lower=planner.lowerBounds(b.cell);
        if(a.id==b.id)throw new IOException("Same snapped junction");
        PriorityQueue<State> q=new PriorityQueue<>(Comparator.comparingDouble(s->s.priority));Map<Long,Double> best=new HashMap<>();
        q.add(new State(a,0,0,0,0,0,0,null));best.put(0L,0.0);int count=0;
        while(!q.isEmpty()&&count<MAX_NODES&&best.size()<180000&&q.size()<160000&&now()-began<25000){
            State s=q.poll();if(s.cost>best.getOrDefault(s.edge,Double.POSITIVE_INFINITY)+1e-7)continue;
            if(s.node.id==b.id)return new Route(s,count,now()-began);
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
