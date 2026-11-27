package com.drivemate.app;
import org.json.JSONObject;
import java.util.*;
/** Independent explicit map-tag speed parsing; unknown and conditional values stay unknown. */
final class MappedSpeedLimits {
    private MappedSpeedLimits(){}
    static int explicitLimit(JSONObject tags){
        if(tags==null)return 0;
        Iterator<String> keys=tags.keys();
        while(keys.hasNext()){
            String k=keys.next();
            if(k.startsWith("maxspeed:") && !k.equals("maxspeed:type") && !k.equals("maxspeed:source"))return 0;
        }
        String raw=tags.optString("maxspeed","").trim().toLowerCase(Locale.UK);
        if(!raw.matches("[0-9]{1,3}(?:\\s*(?:mph|km/h|kmh))?"))return 0;
        try{boolean mph=raw.endsWith("mph");int v=Integer.parseInt(raw.replaceAll("[^0-9]",""));
            int kmh=mph?(int)Math.round(v*1.609344):v;return kmh>=5&&kmh<=140?kmh:0;
        }catch(NumberFormatException e){return 0;}
    }
}
