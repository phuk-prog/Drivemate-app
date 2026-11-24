package com.drivemate.app;
import android.content.Context;
import android.content.SharedPreferences;
import org.json.*;
import java.util.*;
/** Private independently entered survey drafts; no reference-provider data persisted. */
public final class SurveyNotes {
    private final SharedPreferences prefs;
    public SurveyNotes(Context c){prefs=c.getSharedPreferences("independent_survey_notes",Context.MODE_PRIVATE);}
    public List<String> list(){
        List<String> out=new ArrayList<>();try{JSONArray a=new JSONArray(prefs.getString("notes","[]"));for(int i=0;i<a.length();i++)out.add(a.getString(i));}catch(JSONException ignored){}return out;
    }
    public boolean add(String value){
        String note=value==null?"":value.trim();if(note.isEmpty()||note.length()>2000)return false;
        List<String> notes=list();if(notes.size()>=100)return false;notes.add(note);save(notes);return true;
    }
    public void delete(int index){List<String> notes=list();if(index>=0&&index<notes.size()){notes.remove(index);save(notes);}}
    private void save(List<String> notes){prefs.edit().putString("notes",new JSONArray(notes).toString()).apply();}
}
