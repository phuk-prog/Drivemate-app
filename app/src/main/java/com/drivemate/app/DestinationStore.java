package com.drivemate.app;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

/** Small, local-only list of favourites and recent destinations. */
public final class DestinationStore {
    private static final String FAVORITES = "dest_favorites";
    private static final String RECENTS = "dest_recents";
    private static final String HOME = "dest_home";
    private static final String WORK = "dest_work";
    private final SharedPreferences preferences;

    public DestinationStore(Context context) {
        preferences = context.getSharedPreferences("drivemate_places", Context.MODE_PRIVATE);
    }

    public List<TomTomApi.Destination> favorites() { return load(FAVORITES); }
    public List<TomTomApi.Destination> recent() { return load(RECENTS); }

    public TomTomApi.Destination home() { return first(HOME); }
    public TomTomApi.Destination work() { return first(WORK); }
    public void setHome(TomTomApi.Destination place) { saveSingle(HOME, place); }
    public void setWork(TomTomApi.Destination place) { saveSingle(WORK, place); }
    public void clearRecent() { preferences.edit().remove(RECENTS).apply(); }

    private TomTomApi.Destination first(String key) {
        List<TomTomApi.Destination> result = load(key);
        return result.isEmpty() ? null : result.get(0);
    }

    private void saveSingle(String key, TomTomApi.Destination place) {
        List<TomTomApi.Destination> items = new ArrayList<>();
        if (place != null) items.add(place);
        store(key, items);
    }


    public boolean isFavorite(TomTomApi.Destination target) {
        for (TomTomApi.Destination place : favorites()) if (same(place, target)) return true;
        return false;
    }

    public void addFavorite(TomTomApi.Destination target) { prepend(FAVORITES, target, 15); }
    public void addRecent(TomTomApi.Destination target) { prepend(RECENTS, target, 8); }

    public void removeFavorite(TomTomApi.Destination target) {
        List<TomTomApi.Destination> places = favorites();
        places.removeIf(item -> same(item, target));
        store(FAVORITES, places);
    }

    private void prepend(String key, TomTomApi.Destination target, int limit) {
        List<TomTomApi.Destination> places = load(key);
        places.removeIf(item -> same(item, target));
        places.add(0, target);
        while (places.size() > limit) places.remove(places.size() - 1);
        store(key, places);
    }

    private static boolean same(TomTomApi.Destination a, TomTomApi.Destination b) {
        return Math.abs(a.point.lat - b.point.lat) < 0.00001
                && Math.abs(a.point.lon - b.point.lon) < 0.00001;
    }

    private List<TomTomApi.Destination> load(String key) {
        List<TomTomApi.Destination> places = new ArrayList<>();
        try {
            JSONArray data = new JSONArray(preferences.getString(key, "[]"));
            for (int i = 0; i < data.length(); i++) {
                JSONObject place = data.getJSONObject(i);
                double lat = place.getDouble("lat");
                double lon = place.getDouble("lon");
                if (!Double.isFinite(lat) || !Double.isFinite(lon)
                        || Math.abs(lat) > 90 || Math.abs(lon) > 180) continue;
                places.add(new TomTomApi.Destination(
                        place.optString("title"), place.optString("address"),
                        new GeoMath.Point(lat, lon)));
            }
        } catch (Exception ignored) {
            // Malformed old data must not prevent navigation.
        }
        return places;
    }

    private void store(String key, List<TomTomApi.Destination> places) {
        JSONArray data = new JSONArray();
        for (TomTomApi.Destination place : places) {
            try {
                JSONObject object = new JSONObject();
                object.put("title", place.title);
                object.put("address", place.address);
                object.put("lat", place.point.lat);
                object.put("lon", place.point.lon);
                data.put(object);
            } catch (Exception ignored) { }
        }
        preferences.edit().putString(key, data.toString()).apply();
    }
}
