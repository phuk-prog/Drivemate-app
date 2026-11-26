package com.drivemate.app;
import org.json.*;
import java.io.IOException;
import java.util.*;

/** Directed coarse costs are lower bounds only, never actual shortcuts. */
public final class DmCellPlanner {
    private final JSONObject tiles;
    private final Map<String,List<Arc>> reverse=new HashMap<>();
    private boolean complete=true;
    private static final class Arc {
        final String from; final long ms;
        Arc(String f,long m){from=f;ms=m;}
    }
    private static final class Entry {
        final String cell;final long cost;
        Entry(String c,long v){cell=c;cost=v;}
    }
    public DmCellPlanner(JSONObject tiles)throws JSONException{
        this.tiles=tiles;
        for(Iterator<String> it=tiles.keys();it.hasNext();){
            String from=it.next();JSONObject info=tiles.getJSONObject(from),cross=info.optJSONObject("crossing_ms");
            if(cross==null){complete=false;continue;}
            for(Iterator<String> j=cross.keys();j.hasNext();){
                String to=j.next();long ms=cross.getLong(to);
                if(ms<=0||!tiles.has(to)||from.equals(to)){complete=false;continue;}
                reverse.computeIfAbsent(to,k->new ArrayList<>()).add(new Arc(from,ms));
            }
        }
    }
    public Map<String,Long> lowerBounds(String target){
        Map<String,Long> best=new HashMap<>();if(!complete||!tiles.has(target))return best;
        PriorityQueue<Entry> q=new PriorityQueue<>(Comparator.comparingLong(a->a.cost));q.add(new Entry(target,0));best.put(target,0L);
        while(!q.isEmpty()){
            Entry e=q.poll();if(e.cost!=best.getOrDefault(e.cell,Long.MAX_VALUE))continue;
            for(Arc a:reverse.getOrDefault(e.cell,Collections.emptyList())){
                if(e.cost>Long.MAX_VALUE-a.ms)continue;
                long cost=e.cost+a.ms;
                if(cost<best.getOrDefault(a.from,Long.MAX_VALUE)){best.put(a.from,cost);q.add(new Entry(a.from,cost));}
            }
        }return best;
    }
    public static final class Coverage{
        public final Set<String> cells;public final long bytes;public final double radiusKm;public final boolean complete;
        Coverage(Set<String> c,long b,double r,boolean all){cells=Collections.unmodifiableSet(c);bytes=b;radiusKm=r;complete=all;}
    }
    private static GeoMath.Point center(String cell){
        String[] p=cell.split("_");return new GeoMath.Point((Integer.parseInt(p[0])+.5)*.05,(Integer.parseInt(p[1])+.5)*.05);
    }
    /** Rank cells by distance to the sampled actual route, then retain within budget.
     * A partial result is explicitly not a guarantee of the requested radius. */
    public Coverage coverage(List<GeoMath.Point> route,Set<String> mandatory,double radiusKm,long budget)throws Exception{
        if(route.size()<2||!Double.isFinite(radiusKm)||radiusKm<0||radiusKm>100||budget<=0)throw new IOException("Invalid coverage request");
        Set<String> selected=new LinkedHashSet<>();long used=0;
        for(String k:new TreeSet<>(mandatory)){
            JSONObject info=tiles.optJSONObject(k);if(info==null)throw new IOException("Missing mandatory route cell");
            long n=info.getLong("bytes");if(n<=0||n>budget-used)throw new IOException("Route cells exceed cache budget");selected.add(k);used+=n;
        }
        double length=GeoMath.length(route),step=Math.max(1000,length/512);
        List<GeoMath.Point> sample=new ArrayList<>();for(double at=0;at<length;at+=step)sample.add(GeoMath.pointAtDistance(route,at));sample.add(route.get(route.size()-1));
        Map<String,Double> distances=new HashMap<>();
        for(Iterator<String> it=tiles.keys();it.hasNext();){
            String k=it.next();if(selected.contains(k))continue;
            double nearest=Double.POSITIVE_INFINITY;GeoMath.Point p=center(k);
            for(GeoMath.Point s:sample)nearest=Math.min(nearest,GeoMath.distance(p,s));
            // 5 km conservative cell radius plus sampling error.
            if(nearest<=radiusKm*1000+5000+step/2)distances.put(k,nearest);
        }
        List<String> ranked=new ArrayList<>(distances.keySet());ranked.sort(Comparator.comparingDouble((String k)->distances.get(k)).thenComparing(k->k));
        boolean all=true;for(String k:ranked){long n=tiles.getJSONObject(k).getLong("bytes");if(n<=0||n>budget-used){all=false;continue;}selected.add(k);used+=n;}
        return new Coverage(selected,used,radiusKm,all);
    }
}
