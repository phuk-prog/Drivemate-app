package com.drivemate.app;

/** Pure presentation rules. Never changes road matching or legal speed limits. */
final class RoadTestDisplayPolicy {
    private RoadTestDisplayPolicy(){}
    /** Driver-facing wording: "Mapped crossing" reads as jargon, "Crossing" does not. */
    static String plainLabel(String label){
        String t=label==null?"":label.trim();
        if(t.regionMatches(true,0,"Mapped ",0,7)&&t.length()>7)t=Character.toUpperCase(t.charAt(7))+t.substring(8);
        return t;
    }
    static boolean retainMetrics(long last,long now){return last>=0&&now>=last&&now-last<=30000;}
    static boolean atTurn(double boundary,double along,double distanceToTurn){
        return Double.isFinite(boundary)&&Double.isFinite(along)&&Double.isFinite(distanceToTurn)
                &&distanceToTurn>=0&&boundary>=along&&Math.abs(boundary-(along+distanceToTurn))<=5;
    }
    static boolean showManeuver(String glyph,double distance,float speed){
        if(glyph==null||!Double.isFinite(distance)||distance<0)return false;
        double s=Float.isFinite(speed)?Math.max(0,speed):0;
        return distance<=Math.max(300,Math.min(1100,200+s*25))
                &&("←".equals(glyph)||"→".equals(glyph)||"↰".equals(glyph)||"↱".equals(glyph)||"↖".equals(glyph)||"↗".equals(glyph)||"↻".equals(glyph)||"↶".equals(glyph)||"↷".equals(glyph)||"↓".equals(glyph));
    }
    static String trafficLabel(String label,double start,double end,double along,boolean metric){
        double ahead=start-along;
        if(ahead>10)return label+" in "+NavigationUnits.displayDistance(ahead,metric);
        if(along<start)return label+" ahead now";
        if(along<end)return "In "+label.toLowerCase(java.util.Locale.UK)+" zone";
        return label+" passed";
    }
}
