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
        catalog=m.getJSONObject("tiles");globalRules=m.getJSONObject("global_rules");this.source=source;planner=new DmCellPlanner(catalog);
    }
    static String key(double lat,double lon){return Math.floorDiv((int)Math.round(lat*1e6),CELL)+"_"+Math.floorDiv((int)Math.round(lon*1e6),CELL);}
    public Set<String> keys(){Set<String> out=new TreeSet<>();catalog.keys().forEachRemaining(out::add);return out;}
    static final class Node{
        final long id;final GeoMath.Point point;final JSONArray edges,rules;final String cell;
        Node(JSONArray a,String cell)throws JSONException{id=a.getLong(0);point=new GeoMath.Point(a.getInt(1)/1e6,a.getInt(2)/1e6);edges=a.getJSONArray(3);rules=a.getJSONArray(4);this.cell=cell;}
    }
    static final class Tile{
        final Map<Long,Node> nodes=new HashMap<>();final JSONArray features,places,roadsideNodes,enforcement;
        Tile(JSONObject data,String key)throws Exception{
            JSONArray a=data.getJSONArray("nodes");if(a.length()>MAX_NODES)throw new IOException("Tile graph exceeds pilot bounds");
            for(int i=0;i<a.length();i++){Node n=new Node(a.getJSONArray(i),key);if(!key.equals(DmTileEngine.key(n.point.lat,n.point.lon))||nodes.put(n.id,n)!=null)throw new IOException("Wrong tile node");}
            features=data.getJSONArray("features");places=data.getJSONArray("places");
            roadsideNodes=data.optJSONArray("roadside_nodes");enforcement=data.optJSONArray("enforcement");
            if(roadsideNodes!=null&&roadsideNodes.length()>6000||enforcement!=null&&enforcement.length()>1000)throw new IOException("Road intelligence exceeds cell budget");
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
    private Node nearest(GeoMath.Point p,Set<String> allowed,boolean origin)throws Exception{return nearest(p,allowed,origin,Double.NaN);}
    /** Bearings from a junction to the far end of each of its usable exits. */
    private double[] exitBearings(Node n,Set<String> allowed)throws Exception{
        List<Double> out=new ArrayList<>();
        for(int i=0;i<n.edges.length();i++){
            JSONArray e=n.edges.getJSONArray(i);String cell=e.getString(2);
            if((allowed!=null&&!allowed.contains(cell))||!catalog.has(cell))continue;
            Node far=load(cell).nodes.get(e.getLong(1));if(far==null)continue;
            out.add(GeoMath.bearingDegrees(n.point,far.point));
        }
        double[] r=new double[out.size()];for(int i=0;i<r.length;i++)r[i]=out.get(i);return r;
    }
    private Node nearest(GeoMath.Point p,Set<String> allowed,boolean origin,double travelBearing)throws Exception{
        return nearestCandidates(p,allowed,origin,travelBearing,1).get(0);
    }
    /**
     * Up to {@code max} junctions within 350 m, best first. Several are returned because the nearest
     * one can sit in a small isolated pocket of the road graph that cannot reach anything else.
     */
    private List<Node> nearestCandidates(GeoMath.Point p,Set<String> allowed,boolean origin,double travelBearing,int max)throws Exception{
        int y=Math.floorDiv((int)Math.round(p.lat*1e6),CELL),x=Math.floorDiv((int)Math.round(p.lon*1e6),CELL);
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
        List<Object[]> found=new ArrayList<>();   // {score, node}
        for(Tile tile:nearby.values())for(Node n:tile.nodes.values()){
            if(origin){
                boolean hasExit=false;for(int i=0;i<n.edges.length();i++){JSONArray e=n.edges.getJSONArray(i);if(allowed==null||allowed.contains(e.getString(2))){hasExit=true;break;}}
                if(!hasExit)continue;
            }else if(!incoming.contains(n.id))continue;
            double d=GeoMath.distance(p,n.point);if(d>=SNAP_RADIUS_METRES)continue;double score=d;
            if(origin&&Double.isFinite(travelBearing))score+=StartSnap.penaltyMetres(travelBearing,exitBearings(n,allowed));
            if(score<SNAP_RADIUS_METRES)found.add(new Object[]{score,n});
        }
        found.sort((u,v)->{int c=Double.compare((Double)u[0],(Double)v[0]);return c!=0?c:Long.compare(((Node)u[1]).id,((Node)v[1]).id);});
        List<Node> out=new ArrayList<>();
        for(Object[] f:found){if(out.size()>=max)break;out.add((Node)f[1]);}
        if(out.isEmpty())throw new CoverageException("No eligible directed road node nearby");
        return out;
    }
    static final double SNAP_RADIUS_METRES=350;
    /** Junctions tried at each end before giving up; the nearest can be in an isolated pocket. */
    static final int START_CANDIDATES=12,END_CANDIDATES=6,MAX_ATTEMPTS=16;
    static final class State{
        final Node node;final long edge,way,prev;final double cost,distance,priority;final State parent;
        /** Real travel seconds along this path. cost may also include search-only penalties and is not shown to the driver. */
        double real;
        State(Node n,long e,long w,long p,double c,double d,double f,State prevState){node=n;edge=e;way=w;prev=p;cost=c;distance=d;priority=f;parent=prevState;}
    }
    public static final class Route{
        public final List<GeoMath.Point> points;public final List<Long> ways,nodeIds,edgeIds;public final Set<String> cells;public final double metres,seconds;public final int expansions;public final long ms;
        Route(State end,int count,long elapsed){List<GeoMath.Point> p=new ArrayList<>();List<Long>w=new ArrayList<>(),n=new ArrayList<>(),e=new ArrayList<>();Set<String> c=new HashSet<>();metres=end.distance;seconds=end.real;expansions=count;ms=elapsed;
            for(State s=end;s!=null;s=s.parent){p.add(s.node.point);n.add(s.node.id);c.add(s.node.cell);if(s.edge!=0){w.add(s.way);e.add(s.edge);}}Collections.reverse(p);Collections.reverse(w);Collections.reverse(n);Collections.reverse(e);points=Collections.unmodifiableList(p);ways=Collections.unmodifiableList(w);nodeIds=Collections.unmodifiableList(n);edgeIds=Collections.unmodifiableList(e);cells=Collections.unmodifiableSet(c);}
    }
    private List<long[]> rules(State s)throws JSONException{
        List<long[]> out=new ArrayList<>();JSONArray g=globalRules.optJSONArray(Long.toString(s.way));
        if(g!=null)for(int i=0;i<g.length();i++){JSONArray r=g.getJSONArray(i);out.add(new long[]{r.getLong(0),r.getLong(1)});}
        for(int i=0;i<s.node.rules.length();i++){JSONArray r=s.node.rules.getJSONArray(i);if(r.getLong(0)==s.way)out.add(new long[]{r.getLong(1),r.getLong(2)});}
        return out;
    }
    public Route route(GeoMath.Point start,GeoMath.Point finish)throws Exception{return route(start,finish,null);}
    public Route route(GeoMath.Point start,GeoMath.Point finish,Set<String> allowed)throws Exception{return route(start,finish,allowed,Double.NaN,null);}
    /** @param travelBearing car heading to prefer a start junction ahead of the car, or NaN
     *  @param penalisedWays ways made more expensive to find a different alternative, or null */
    public Route route(GeoMath.Point start,GeoMath.Point finish,Set<String> allowed,double travelBearing,Set<Long> penalisedWays)throws Exception{
        long began=SystemClock.elapsedRealtime();
        List<Node> starts=nearestCandidates(start,allowed,true,travelBearing,START_CANDIDATES),ends=nearestCandidates(finish,allowed,false,Double.NaN,END_CANDIDATES);
        // Try the nearest junctions first; if one end sits in an isolated pocket of the graph, try the next.
        CoverageException lastFailure=null;int attempts=0;
        for(int sum=0;sum<=START_CANDIDATES+END_CANDIDATES-2&&attempts<MAX_ATTEMPTS;sum++){
            for(int i=Math.min(sum,starts.size()-1);i>=0&&attempts<MAX_ATTEMPTS;i--){
                int j=sum-i;if(j<0||j>=ends.size())continue;
                if(SystemClock.elapsedRealtime()-began>=25000)throw lastFailure!=null?lastFailure:new CoverageException("No connected route inside pilot limits");
                Node a=starts.get(i),b=ends.get(j);if(a.id==b.id)continue;
                attempts++;
                try{return searchRoute(a,b,allowed,penalisedWays,began);}catch(CoverageException noPath){lastFailure=noPath;}
            }
        }
        if(lastFailure==null)throw new IOException("Same snapped junction");
        throw lastFailure;
    }
    private Route searchRoute(Node a,Node b,Set<String> allowed,Set<Long> penalisedWays,long began)throws Exception{
        Map<String,Long> lower=planner.lowerBounds(b.cell);
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
                double cost=s.cost+seconds*(penalisedWays!=null&&penalisedWays.contains(way)?AlternativeRoutes.PENALTY_FACTOR:1.0);if(cost+1e-7>=best.getOrDefault(id,Double.POSITIVE_INFINITY))continue;
                String nextCell=e.getString(2);if(allowed!=null&&!allowed.contains(nextCell))continue;
                Node next=load(nextCell).nodes.get(dst);if(next==null)throw new IOException("Broken cross-tile connection");
                if(s.parent!=null)cost+=TurnPenalty.seconds(s.parent.node.point,s.node.point,next.point);
                if(cost+1e-7>=best.getOrDefault(id,Double.POSITIVE_INFINITY))continue;
                best.put(id,cost);State ns=new State(next,id,way,s.node.id,cost,s.distance+length,cost+Math.max(GeoMath.distance(next.point,b.point)*3.6/160,lower.getOrDefault(next.cell,0L)/1000.0),s);ns.real=s.real+seconds;q.add(ns);
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
    /** Display-only bounded scan. Release graph and unrelated tile JSON before converting to GPU features.
     * Complete original ways are retained, never simplified or clipped to the viewport. */
    List<JSONArray> boundedFeatures(Set<String> cells,double[] bounds,Set<Long> required,boolean buildingsOnly,
                                    int maxFeatures,int maxVertices)throws Exception{
        pinned.clear();cache.clear();
        Map<Long,JSONArray> result=new LinkedHashMap<>();int vertices=0;
        try{
            for(String k:new TreeSet<>(cells)){
                JSONArray rows=load(k).features;
                for(int i=0;i<rows.length();i++){
                    JSONArray row=rows.getJSONArray(i);long id=row.getLong(0);
                    if(result.containsKey(id))continue;
                    JSONObject tags=row.getJSONObject(1);
                    if(buildingsOnly&&(!tags.has("building")||"no".equals(tags.optString("building"))))continue;
                    JSONArray coords=row.getJSONArray(2);
                    boolean mandatory=required.contains(id);
                    if(!mandatory&&(bounds==null||!intersects(coords,bounds)))continue;
                    if(coords.length()>maxVertices-vertices||result.size()>=maxFeatures)
                        throw new CoverageException("Display region exceeds bounded geometry budget");
                    result.put(id,row);vertices+=coords.length();
                }
                cache.remove(k);
            }
            if(!result.keySet().containsAll(required))throw new CoverageException("Route way outside available display region");
            return new ArrayList<>(result.values());
        }finally{cache.clear();}
    }
    static boolean intersects(JSONArray coords,double[] bounds)throws JSONException{
        double west=180,east=-180,south=90,north=-90;
        for(int i=0;i<coords.length();i++){
            JSONArray p=coords.getJSONArray(i);double y=p.getInt(0)/1e6,x=p.getInt(1)/1e6;
            west=Math.min(west,x);east=Math.max(east,x);south=Math.min(south,y);north=Math.max(north,y);
        }
        return coords.length()>0&&west<=bounds[2]&&east>=bounds[0]&&south<=bounds[3]&&north>=bounds[1];
    }
    /** Optional exact source records: deduplicate by original OSM identity, never nearest pixels. */
    NativeRouteIntelligence.Result routeIntelligence(Route route)throws Exception{
        Map<Long,JSONArray> nodes=new HashMap<>(),relations=new HashMap<>();
        pinned.clear();cache.clear();
        try{
            for(String key:new TreeSet<>(route.cells)){
                Tile t=load(key);collectIntelligence(t.roadsideNodes,nodes,6000);collectIntelligence(t.enforcement,relations,1000);cache.remove(key);
            }
            return NativeRouteIntelligence.match(route.points,route.nodeIds,route.ways,new JSONArray(nodes.values()),new JSONArray(relations.values()));
        }finally{cache.clear();}
    }
    private static void collectIntelligence(JSONArray input,Map<Long,JSONArray> out,int max)throws Exception{
        if(input==null)return;
        for(int i=0;i<input.length();i++){JSONArray row=input.getJSONArray(i);long id=row.getLong(0);JSONArray previous=out.putIfAbsent(id,row);
            if(previous!=null&&!previous.toString().equals(row.toString()))throw new IOException("Conflicting source intelligence");
            if(out.size()>max)throw new IOException("Route intelligence exceeds budget");}
    }
    /** Tags and legal outgoing choices for the actual directed route, not a proximity guess. */
    Map<Long,JSONObject> routeTags(Route route)throws Exception{
        Set<Long> required=new HashSet<>(route.ways);Map<Long,JSONObject> out=new HashMap<>();
        pinned.clear();cache.clear();
        try{
            for(String key:new TreeSet<>(route.cells)){
                JSONArray rows=load(key).features;
                for(int i=0;i<rows.length();i++){
                    JSONArray row=rows.getJSONArray(i);long id=row.getLong(0);
                    if(required.contains(id))out.put(id,row.getJSONObject(1));
                }
                cache.remove(key);
            }
            if(!out.keySet().containsAll(required))throw new CoverageException("Missing exact route way tags");
            return out;
        }finally{cache.clear();}
    }
    int[] legalChoices(Route route)throws Exception{
        int[] count=new int[route.points.size()];
        try{
            for(int i=1;i<route.points.size()-1;i++){
                GeoMath.Point p=route.points.get(i);Node n=load(key(p.lat,p.lon)).nodes.get(route.nodeIds.get(i));
                if(n==null)throw new CoverageException("Missing route junction");
                State state=new State(n,route.edgeIds.get(i-1),route.ways.get(i-1),route.nodeIds.get(i-1),0,0,0,null);
                List<long[]> restrictions=rules(state);Set<Long> exits=new HashSet<>();boolean actual=false;
                for(int j=0;j<n.edges.length();j++){
                    JSONArray e=n.edges.getJSONArray(j);long dst=e.getLong(1),way=e.getLong(3);
                    if(LocalRoutingPilot.allowed(state.prev,dst,way,restrictions)){
                        exits.add(dst);if(e.getLong(0)==route.edgeIds.get(i))actual=true;
                    }
                }
                if(!actual)throw new CoverageException("Route turn no longer permitted");
                count[i]=exits.size();
            }
            return count;
        }finally{cache.clear();}
    }
    /**
     * Per route node: how many other legal exits are ordinary roads (for roundabout exit
     * counts) and whether a real road forks off to the left or right. Ways the engine
     * cannot classify set {@code unknownExit}, which suppresses an exit count.
     */
    JunctionDetail[] junctionDetails(Route route)throws Exception{
        int n=route.points.size();JunctionDetail[] out=new JunctionDetail[n];
        // Pass 1: gather other legal exits at each interior node and the ways they use.
        class Alt{final long way;final double bearing;Alt(long w,double b){way=w;bearing=b;}}
        List<List<Alt>> alts=new ArrayList<>();Set<Long> wanted=new HashSet<>();Set<String> cells=new TreeSet<>(route.cells);
        double[] routeBearing=new double[n];
        // An exit whose road could not be loaded is unknown, never silently skipped (it would undercount).
        boolean[] unknownAt=new boolean[n];
        try{
            for(int i=0;i<n;i++)alts.add(new ArrayList<>());
            for(int i=1;i<n-1;i++){
                GeoMath.Point p=route.points.get(i);Node node=load(key(p.lat,p.lon)).nodes.get(route.nodeIds.get(i));
                if(node==null)throw new CoverageException("Missing route junction");
                routeBearing[i]=GeoMath.bearingDegrees(p,route.points.get(i+1));
                State state=new State(node,route.edgeIds.get(i-1),route.ways.get(i-1),route.nodeIds.get(i-1),0,0,0,null);
                List<long[]> restrictions=rules(state);
                for(int j=0;j<node.edges.length();j++){
                    JSONArray e=node.edges.getJSONArray(j);long dst=e.getLong(1),way=e.getLong(3);
                    if(e.getLong(0)==route.edgeIds.get(i))continue;
                    if(!LocalRoutingPilot.allowed(state.prev,dst,way,restrictions))continue;
                    String cell=e.getString(2);if(!catalog.has(cell)){unknownAt[i]=true;continue;}
                    Node far=load(cell).nodes.get(dst);if(far==null){unknownAt[i]=true;continue;}
                    alts.get(i).add(new Alt(way,GeoMath.bearingDegrees(node.point,far.point)));wanted.add(way);cells.add(cell);
                }
            }
            // Pass 2: highway class of every other way, from the cells involved.
            Map<Long,String> classes=new HashMap<>();
            for(String cell:cells){
                if(!catalog.has(cell))continue;
                JSONArray rows=load(cell).features;
                for(int k=0;k<rows.length();k++){
                    JSONArray row=rows.getJSONArray(k);long id=row.getLong(0);
                    if(wanted.contains(id)){
                        JSONObject tags=row.getJSONObject(1);
                        boolean roundaboutWay="roundabout".equals(tags.optString("junction"))||"circular".equals(tags.optString("junction"));
                        classes.put(id,roundaboutWay?"roundabout":tags.optString("highway",""));
                    }
                }
            }
            for(int i=1;i<n-1;i++){
                int countable=0;boolean unknown=unknownAt[i],forkL=false,forkR=false;
                for(Alt a:alts.get(i)){
                    String cls=classes.get(a.way);
                    if(cls==null||cls.isEmpty()){unknown=true;continue;}
                    // Another roundabout way is not an exit; an ordinary road is; a driveway or path is ignored.
                    if(JunctionDetail.countableHighway(cls)){
                        countable++;
                        double rel=JunctionDetail.relative(routeBearing[i],a.bearing);
                        if(JunctionDetail.isFork(rel)){if(rel>0)forkR=true;else forkL=true;}
                    }else if(!"roundabout".equals(cls)&&!JunctionDetail.ignorableHighway(cls))unknown=true;
                }
                out[i]=new JunctionDetail(countable,unknown,forkL,forkR);
            }
            return out;
        }finally{cache.clear();}
    }
    /** Search one verified cell at a time, avoiding retaining the whole regional graph. */
    List<JSONArray> regionalSearch(String query)throws Exception{
        ArrayList<JSONArray> rows=new ArrayList<>();Set<String> seen=new HashSet<>();
        pinned.clear();cache.clear();
        try{
            for(String k:keys()){
                for(JSONArray row:search(query,Collections.singleton(k))){
                    String identity=row.optString(0)+"|"+row.optInt(1)+"|"+row.optInt(2);
                    if(seen.add(identity))rows.add(row);
                }
                cache.remove(k);
            }
            return rows;
        }finally{cache.clear();}
    }
    public JSONObject wayTags(long id,Set<String> cells)throws Exception{
        for(JSONArray f:features(cells))if(f.getLong(0)==id)return f.getJSONObject(1);
        return new JSONObject();
    }
}
