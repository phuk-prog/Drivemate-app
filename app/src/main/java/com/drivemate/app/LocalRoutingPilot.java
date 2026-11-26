package com.drivemate.app;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.SystemClock;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;

/** Experimental local-only engineering proof. Not connected to driving UI. */
public final class LocalRoutingPilot implements AutoCloseable {
    private final SQLiteDatabase db;
    private static final int MAX_EXPANSIONS=80000;
    private static final long MAX_MS=25000;
    private static final double SEARCH_WEIGHT=2.2; // approximate bounded search
    // Per-search read-only caches reduce repeated SQL work at junctions.
    // Entry counts AND edge counts are bounded; no change to the search budget.
    private static final int CACHE_NODES=4096, MAX_CACHED_EDGES_PER_NODE=64;
    private static final class Edge {
        final long id,way; final Node to; final double seconds,metres;
        Edge(long id,long way,Node to,double seconds,double metres){
            this.id=id;this.way=way;this.to=to;this.seconds=seconds;this.metres=metres;
        }
    }
    private static final class TurnKey {
        final long node,way;
        TurnKey(long node,long way){this.node=node;this.way=way;}
        @Override public boolean equals(Object other){
            if(!(other instanceof TurnKey))return false;
            TurnKey key=(TurnKey)other;return node==key.node&&way==key.way;
        }
        @Override public int hashCode(){return 31*Long.hashCode(node)+Long.hashCode(way);}
    }
    public static String sha256(File file)throws Exception{
        MessageDigest d=MessageDigest.getInstance("SHA-256");
        try(InputStream in=new FileInputStream(file)){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)d.update(b,0,n);}
        StringBuilder s=new StringBuilder();for(byte b:d.digest())s.append(String.format(Locale.ROOT,"%02x",b&255));return s.toString();
    }
    public LocalRoutingPilot(File file,String expectedSha)throws Exception{
        if(!expectedSha.matches("[a-f0-9]{64}")||!sha256(file).equals(expectedSha))throw new IOException("Unverified map");
        SQLiteDatabase candidate=SQLiteDatabase.openDatabase(file.getPath(),null,SQLiteDatabase.OPEN_READONLY|SQLiteDatabase.NO_LOCALIZED_COLLATORS);
        try(Cursor c=candidate.rawQuery("SELECT value FROM meta WHERE key='format'",null)){
            if(!c.moveToFirst()||!"drivemate-uk-routing-v1".equals(c.getString(0)))throw new IOException("Wrong map format");
        }catch(Exception e){candidate.close();throw e;}
        db=candidate;
    }
    public void close(){db.close();}
    static final class Node{
        final long id;final GeoMath.Point point;
        Node(long id,double lat,double lon){this.id=id;point=new GeoMath.Point(lat,lon);}
    }
    static final class State{
        final long edge,way,prev;final Node node;final double cost,priority,metres;final State parent;
        State(long e,long w,long p,Node n,double c,double f,double m,State parent){edge=e;way=w;prev=p;node=n;cost=c;priority=f;metres=m;this.parent=parent;}
    }
    public static final class Result{
        public final List<GeoMath.Point> points;public final List<Long> ways;public final double metres,seconds;public final int expanded;public final long elapsedMs;
        Result(State s,int e,long ms){List<GeoMath.Point> p=new ArrayList<>();List<Long>w=new ArrayList<>();metres=s.metres;seconds=s.cost;expanded=e;elapsedMs=ms;
            while(s!=null){p.add(s.node.point);if(s.edge!=0)w.add(s.way);s=s.parent;}Collections.reverse(p);Collections.reverse(w);points=Collections.unmodifiableList(p);ways=Collections.unmodifiableList(w);}
    }
    private Node nearest(GeoMath.Point p,boolean requireDeparture)throws IOException{
        double dy=350/111000.0,dx=dy/Math.max(.1,Math.cos(Math.toRadians(p.lat))),best=350;Node result=null;
        try(Cursor c=db.rawQuery("SELECT n.id,n.lat,n.lon FROM nodes n WHERE n.lat BETWEEN ? AND ? AND n.lon BETWEEN ? AND ? AND NOT EXISTS(SELECT 1 FROM blocked b WHERE b.id=n.id)",
            new String[]{""+(p.lat-dy),""+(p.lat+dy),""+(p.lon-dx),""+(p.lon+dx)})){
            while(c.moveToNext()){Node n=new Node(c.getLong(0),c.getDouble(1),c.getDouble(2));double d=GeoMath.distance(p,n.point);if(d<best&&(!requireDeparture||hasDeparture(n.id))){best=d;result=n;}}
        }if(result==null)throw new IOException("No nearby downloaded road");return result;
    }
    private boolean hasDeparture(long node){
        try(Cursor c=db.rawQuery("SELECT 1 FROM edges e WHERE e.src=? AND NOT EXISTS(SELECT 1 FROM blocked b WHERE b.id=e.dst) AND NOT EXISTS(SELECT 1 FROM blocked_ways b WHERE b.id=e.way) LIMIT 1",new String[]{""+node})){
            return c.moveToFirst();
        }
    }
    private List<long[]> rules(State s){
        List<long[]> r=new ArrayList<>();if(s.edge==0)return r;
        try(Cursor c=db.rawQuery("SELECT to_way,policy FROM restrictions WHERE (via=? OR via=0) AND from_way=?",new String[]{""+s.node.id,""+s.way})){
            while(c.moveToNext())r.add(new long[]{c.getLong(0),c.getInt(1)});
        }return r;
    }
    private List<Edge> outgoing(long node){
        List<Edge> edges=new ArrayList<>();
        try(Cursor c=db.rawQuery("SELECT e.id,e.dst,e.way,e.seconds,n.lat,n.lon,e.metres FROM edges e JOIN nodes n ON n.id=e.dst WHERE e.src=? AND NOT EXISTS(SELECT 1 FROM blocked b WHERE b.id=e.dst) AND NOT EXISTS(SELECT 1 FROM blocked_ways b WHERE b.id=e.way)",new String[]{""+node})){
            while(c.moveToNext()){
                double seconds=c.getDouble(3),metres=c.getDouble(6);
                if(!Double.isFinite(seconds)||seconds<=0||!Double.isFinite(metres)||metres<=0)continue;
                edges.add(new Edge(c.getLong(0),c.getLong(2),new Node(c.getLong(1),c.getDouble(4),c.getDouble(5)),seconds,metres));
            }
        }
        return edges;
    }
    static boolean allowed(long prev,long next,long way,List<long[]> rules){
        boolean only=false,matched=false,explicitU=false;
        for(long[] r:rules){long to=r[0],policy=r[1];
            if(policy==1&&to==way)return false;
            if(policy==3&&to==way&&next==prev)return false;
            if(policy==2||policy==4){only=true;if(to==way&&(policy==2||next==prev)){matched=true;if(policy==4)explicitU=true;}}
        }return (!only||matched)&&(next!=prev||explicitU);
    }
    public Result route(GeoMath.Point from,GeoMath.Point to)throws IOException{
        long began=SystemClock.elapsedRealtime();Node start=nearest(from,true),end=nearest(to,false);
        if(start.id==end.id)throw new IOException("Same snapped node");
        PriorityQueue<State> q=new PriorityQueue<>(Comparator.comparingDouble(s->s.priority));Map<Long,Double> best=new HashMap<>();
        android.util.LruCache<Long,List<Edge>> edgeCache=new android.util.LruCache<>(CACHE_NODES);
        android.util.LruCache<TurnKey,List<long[]>> turnCache=new android.util.LruCache<>(CACHE_NODES);
        q.add(new State(0,0,0,start,0,0,0,null));best.put(0L,0.0);int expanded=0;
        while(!q.isEmpty()&&expanded<MAX_EXPANSIONS&&q.size()<160000&&best.size()<180000&&SystemClock.elapsedRealtime()-began<MAX_MS){
            State s=q.poll();if(s.cost>best.getOrDefault(s.edge,Double.POSITIVE_INFINITY)+1e-7)continue;
            if(s.node.id==end.id)return new Result(s,expanded,SystemClock.elapsedRealtime()-began);
            expanded++;
            TurnKey turnKey=new TurnKey(s.node.id,s.way);
            List<long[]> r=s.edge==0?Collections.emptyList():turnCache.get(turnKey);
            if(r==null){r=rules(s);if(r.size()<=MAX_CACHED_EDGES_PER_NODE)turnCache.put(turnKey,r);}
            List<Edge> edges=edgeCache.get(s.node.id);
            if(edges==null){edges=outgoing(s.node.id);if(edges.size()<=MAX_CACHED_EDGES_PER_NODE)edgeCache.put(s.node.id,edges);}
            for(Edge e:edges){
                if(s.edge!=0&&!allowed(s.prev,e.to.id,e.way,r))continue;
                double g=s.cost+e.seconds;if(g+1e-7>=best.getOrDefault(e.id,Double.POSITIVE_INFINITY))continue;
                best.put(e.id,g);
                q.add(new State(e.id,e.way,s.node.id,e.to,g,g+SEARCH_WEIGHT*GeoMath.distance(e.to.point,end.point)*3.6/160,s.metres+e.metres,s));
            }
        }throw new IOException("No connected local route within pilot resource bounds");
    }
}
