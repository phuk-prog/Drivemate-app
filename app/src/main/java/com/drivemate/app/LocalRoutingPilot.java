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
    private Node nearest(GeoMath.Point p)throws IOException{
        double dy=350/111000.0,dx=dy/Math.max(.1,Math.cos(Math.toRadians(p.lat))),best=350;Node result=null;
        try(Cursor c=db.rawQuery("SELECT n.id,n.lat,n.lon FROM nodes n WHERE n.lat BETWEEN ? AND ? AND n.lon BETWEEN ? AND ? AND NOT EXISTS(SELECT 1 FROM blocked b WHERE b.id=n.id)",
            new String[]{""+(p.lat-dy),""+(p.lat+dy),""+(p.lon-dx),""+(p.lon+dx)})){
            while(c.moveToNext()){Node n=new Node(c.getLong(0),c.getDouble(1),c.getDouble(2));double d=GeoMath.distance(p,n.point);if(d<best){best=d;result=n;}}
        }if(result==null)throw new IOException("No nearby downloaded road");return result;
    }
    private List<long[]> rules(State s){
        List<long[]> r=new ArrayList<>();if(s.edge==0)return r;
        try(Cursor c=db.rawQuery("SELECT to_way,policy FROM restrictions WHERE (via=? OR via=0) AND from_way=?",new String[]{""+s.node.id,""+s.way})){
            while(c.moveToNext())r.add(new long[]{c.getLong(0),c.getInt(1)});
        }return r;
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
        long began=SystemClock.elapsedRealtime();Node start=nearest(from),end=nearest(to);
        if(start.id==end.id)throw new IOException("Same snapped node");
        PriorityQueue<State> q=new PriorityQueue<>(Comparator.comparingDouble(s->s.priority));Map<Long,Double> best=new HashMap<>();
        q.add(new State(0,0,0,start,0,0,0,null));best.put(0L,0.0);int expanded=0;
        while(!q.isEmpty()&&expanded<MAX_EXPANSIONS&&q.size()<160000&&best.size()<180000&&SystemClock.elapsedRealtime()-began<MAX_MS){
            State s=q.poll();if(s.cost>best.getOrDefault(s.edge,Double.POSITIVE_INFINITY)+1e-7)continue;
            if(s.node.id==end.id)return new Result(s,expanded,SystemClock.elapsedRealtime()-began);
            expanded++;List<long[]> r=rules(s);
            try(Cursor c=db.rawQuery("SELECT e.id,e.dst,e.way,e.seconds,n.lat,n.lon,e.metres FROM edges e JOIN nodes n ON n.id=e.dst WHERE e.src=? AND NOT EXISTS(SELECT 1 FROM blocked b WHERE b.id=e.dst) AND NOT EXISTS(SELECT 1 FROM blocked_ways b WHERE b.id=e.way)",new String[]{""+s.node.id})){
                while(c.moveToNext()){
                    long edge=c.getLong(0),next=c.getLong(1),way=c.getLong(2);
                    if(s.edge!=0&&!allowed(s.prev,next,way,r))continue;
                    double seconds=c.getDouble(3),metres=c.getDouble(6);
                    if(!Double.isFinite(seconds)||seconds<=0||!Double.isFinite(metres)||metres<=0)continue;
                    double g=s.cost+seconds;if(g+1e-7>=best.getOrDefault(edge,Double.POSITIVE_INFINITY))continue;
                    Node n=new Node(next,c.getDouble(4),c.getDouble(5));best.put(edge,g);
                    q.add(new State(edge,way,s.node.id,n,g,g+GeoMath.distance(n.point,end.point)*3.6/160,s.metres+metres,s));
                }
            }
        }throw new IOException("No connected local route within pilot resource bounds");
    }
}
