package com.drivemate.app;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A conservative shared wording layer: never creates an exit, lane, road or sign. */
final class IntelligentGuidance {
    private IntelligentGuidance() {}
    private static String clean(String s) { return s == null ? "" : s.trim(); }

    static String action(TomTomApi.Instruction step, String glyph, int exit) {
        String message = clean(step.message);
        String road = clean(step.street);
        if ("↻".equals(glyph)) return exit > 0 ? "Take " + GuidanceLogic.ordinal(exit) + " exit" : "Follow roundabout";
        // Preserve provider wording for ramps, motorway exits, merges and unusual actions.
        if (!road.isEmpty()) {
            String lower = message.toLowerCase(Locale.UK);
            String tail = road.toLowerCase(Locale.UK);
            for (String connector : new String[]{" onto ", " on to ", " into ", " on "}) {
                if (lower.endsWith(connector + tail)) return message.substring(0, message.length()-connector.length()-road.length()).trim();
            }
        }
        return message.isEmpty() ? "Follow highlighted route" : message;
    }

    static String direction(TomTomApi.Instruction step, String glyph, int exit) {
        if (!"↻".equals(glyph)) return clean(step.message).isEmpty() ? "Follow the highlighted route" : clean(step.message);
        String road = clean(step.street);
        if (exit > 0) return "At the roundabout, take the " + GuidanceLogic.ordinal(exit) + " exit" + (road.isEmpty() ? "" : " onto " + road);
        if (!road.isEmpty()) return "At the roundabout, take the exit onto " + road;
        // Provider text may contain an unbounded number; avoid that contradiction.
        return "At the roundabout, follow the highlighted route";
    }

    static String roadLine(String action, String road) {
        road = clean(road);
        return road.isEmpty() || clean(action).toLowerCase(Locale.UK).contains(road.toLowerCase(Locale.UK)) ? "" : road;
    }

    static String lanePhrase(RouteIntelligence.LaneSection section) {
        if (section == null || !section.trusted()) return "";
        int n = section.lanes.size(), first = -1, last = -1, count = 0;
        for (int i=0; i<n; i++) if (section.lanes.get(i).recommended) {
            if(first < 0) first=i;
            last=i; count++;
        }
        if(count==0 || count==n || last-first+1!=count) return "";
        if(count==1 && first==0) return "Use the left lane";
        if(count==1 && last==n-1) return "Use the right lane";
        if(count==1 && n==3 && first==1) return "Use the middle lane";
        if(first==0) return count==2 ? "Use either of the two left lanes" : "Use one of the " + count + " left lanes";
        if(last==n-1) return count==2 ? "Use either of the two right lanes" : "Use one of the " + count + " right lanes";
        return "";
    }

    static boolean closeFollowing(double gap, float speed) {
        return Double.isFinite(gap) && gap >= 0 && gap <= Math.max(90, Math.min(350, Math.max(0,speed)*10));
    }

    static String thenLine(TomTomApi.Instruction step, double gap, boolean metric) {
        String glyph = GuidanceLogic.symbol(step.maneuver, step.junctionType, step.turnAngleDegrees);
        int exit = "↻".equals(glyph) ? JunctionFraming.reportedExit(step.exitNumber) : 0;
        return "Then " + glyph + "  " + action(step,glyph,exit) + " · " + NavigationUnits.displayDistance(gap,metric);
    }

    static String voice(String direction, double distance, boolean metric, boolean immediate,
                        String lane, String following) {
        String text = immediate ? direction : "In " + NavigationUnits.spokenDistance(distance, metric) + ", " + direction;
        if (!clean(lane).isEmpty()) text += ". " + lane;
        if (!clean(following).isEmpty()) text += ". Then " + following;
        return pronounce(text);
    }

    /** Explicit UK route references are spoken as letters and individual digits. */
    static String pronounce(String text) {
        Matcher m = Pattern.compile("(?<![A-Za-z0-9])([MAB])([0-9]{1,4})(?![A-Za-z0-9])").matcher(clean(text));
        StringBuffer b = new StringBuffer();
        while(m.find()) {
            StringBuilder s=new StringBuilder(m.group(1));
            for(char c:m.group(2).toCharArray()) s.append(' ').append(c);
            m.appendReplacement(b,Matcher.quoteReplacement(s.toString()));
        }
        m.appendTail(b);
        return b.toString();
    }

    /**
     * A route-path-only icon for roundabouts when a curved section is supported by
     * the provider geometry. It is not a diagram of other exits. Ambiguous or
     * incomplete traces fall back to the ordinary roundabout symbol.
     */
    static float[] roundaboutTrace(List<GeoMath.Point> route, double offset, double previous, double next) {
        if(route==null || route.size()<4 || !Double.isFinite(offset)) return null;
        double length=GeoMath.length(route);
        double start=Math.max(0, Math.max(offset-30, previous+8));
        double end=Math.min(length, Math.min(offset+100,next-8));
        if(end-start<40 || offset-start<12 || end-offset<22) return null;
        List<GeoMath.Point> pts=GeoMath.slice(route,start,end);
        if(pts.size()<5) return null;
        // A curve must have substantial consistent heading change, not a single corner.
        double signed=0, total=0; int curves=0;
        for(int i=1;i<pts.size()-1;i++){
            if(GeoMath.distance(pts.get(i-1),pts.get(i))<2 || GeoMath.distance(pts.get(i),pts.get(i+1))<2) continue;
            double d=(GeoMath.bearingDegrees(pts.get(i),pts.get(i+1))-GeoMath.bearingDegrees(pts.get(i-1),pts.get(i))+540)%360-180;
            if(Math.abs(d)>80) return null;
            if(Math.abs(d)>8) { signed+=d;total+=Math.abs(d);curves++; }
        }
        if(curves<3 || total<55 || Math.abs(signed)<total*.72 || total>330) return null;
        GeoMath.Point origin=pts.get(0);
        double heading=Math.toRadians(GeoMath.bearingDegrees(origin,pts.get(1)));
        float[] xy=new float[pts.size()*2];float minX=0,maxX=0,minY=0,maxY=0;
        for(int i=0;i<pts.size();i++){
            GeoMath.Point pt=pts.get(i);
            double east=(pt.lon-origin.lon)*111000*Math.cos(Math.toRadians(origin.lat));
            double north=(pt.lat-origin.lat)*111000;
            float x=(float)(east*Math.cos(heading)-north*Math.sin(heading));
            float y=(float)(-east*Math.sin(heading)-north*Math.cos(heading));
            xy[2*i]=x;xy[2*i+1]=y;minX=Math.min(minX,x);maxX=Math.max(maxX,x);minY=Math.min(minY,y);maxY=Math.max(maxY,y);
        }
        float scale=42/Math.max(1,Math.max(maxX-minX,maxY-minY));
        for(int i=0;i<xy.length;i+=2){xy[i]=32+(xy[i]-(minX+maxX)/2)*scale;xy[i+1]=32+(xy[i+1]-(minY+maxY)/2)*scale;}
        return xy;
    }
}
