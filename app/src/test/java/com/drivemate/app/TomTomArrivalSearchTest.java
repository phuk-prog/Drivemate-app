package com.drivemate.app;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

/** Offline fixtures for Search API entry-points and parking POI filtering. */
public class TomTomArrivalSearchTest {
    private JSONObject parking(String name, String category, double lat,
                               double lon, boolean entrance) throws Exception {
        JSONObject place = new JSONObject();
        place.put("position", new JSONObject().put("lat", lat).put("lon", lon));
        place.put("poi", new JSONObject().put("name", name)
                .put("categories", new JSONArray().put(category)));
        place.put("address", new JSONObject().put("freeformAddress", "Example Street"));
        if (entrance) {
            place.put("entryPoints", new JSONArray().put(new JSONObject()
                    .put("type", "main")
                    .put("position", new JSONObject()
                            .put("lat", lat + 0.00015).put("lon", lon))));
        }
        return place;
    }

    @Test public void parkingResultsRequireExplicitParkingCategory() throws Exception {
        JSONObject response = new JSONObject().put("results", new JSONArray()
                .put(parking("Public Car Park", "Parking Garage", 51.5, -0.1, true))
                .put(parking("Restaurant", "Restaurant", 51.501, -0.1, false))
                .put(new JSONObject().put("poi", new JSONObject().put("name", "Missing GPS"))));
        List<TomTomApi.Destination> results =
                TomTomApi.parsePlaces(response, "Parking", true);
        assertEquals(1, results.size());
        assertEquals("Public Car Park", results.get(0).title);
        assertNotNull(results.get(0).mainEntrance);
        assertEquals("Example Street", results.get(0).address);
    }

    @Test public void invalidMainEntranceIsNeverClaimed() throws Exception {
        JSONObject place = parking("Address", "Parking", 51.5, -0.1, false);
        place.put("entryPoints", new JSONArray().put(new JSONObject()
                .put("type", "main")
                .put("position", new JSONObject().put("lat", 52.0).put("lon", -0.1))));
        JSONObject response = new JSONObject().put("results", new JSONArray().put(place));
        List<TomTomApi.Destination> results =
                TomTomApi.parsePlaces(response, "Parking", false);
        assertEquals(1, results.size());
        assertNull(results.get(0).mainEntrance);
    }

    @Test public void noEntranceOrParkingReturnsUnknownNotGuessed() throws Exception {
        JSONObject response = new JSONObject().put("results", new JSONArray()
                .put(parking("Café", "Cafe", 51.5, -0.1, false)));
        assertTrue(TomTomApi.parsePlaces(response, "Parking", true).isEmpty());
        List<TomTomApi.Destination> places =
                TomTomApi.parsePlaces(response, "Café", false);
        assertEquals(1, places.size());
        assertNull(places.get(0).mainEntrance);
        assertTrue(TomTomApi.parsePlaces(new JSONObject(), "Parking", true).isEmpty());
    }
}
