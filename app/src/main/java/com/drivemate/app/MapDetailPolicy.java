package com.drivemate.app;
/** Rendering metadata only, never regulatory or navigation advice. */
final class MapDetailPolicy {
    private MapDetailPolicy(){}
    static double explicitMetres(String text){
        if(text==null)return -1;
        String v=text.trim().toLowerCase(java.util.Locale.ROOT);
        try{
            if(v.endsWith(" m"))v=v.substring(0,v.length()-2).trim();
            double value=Double.parseDouble(v);
            return Double.isFinite(value)&&value>=0&&value<=1000?value:-1;
        }catch(RuntimeException e){return -1;}
    }
    static double heightMetres(String height,String levels){
        double explicit=explicitMetres(height);if(explicit>0)return explicit;
        try{double n=Double.parseDouble(levels);if(Double.isFinite(n)&&n>0&&n<=100)return n*3;}catch(RuntimeException ignored){}
        return 6;
    }
}
