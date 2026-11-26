package com.drivemate.app;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Synthetic TomTom Routing v1 response fixtures; never use guessed data. */
public class TomTomRouteIntelligenceTest {
    private JSONObject fixture(String sections) throws Exception {
        String body = "{\"routes\":[{\"summary\":{\"lengthInMeters\":220,"
                + "\"travelTimeInSeconds\":40,\"trafficDelayInSeconds\":15},"
                + "\"legs\":[{\"points\":["
                + "{\"latitude\":51.5000,\"longitude\":-0.1000},"
                + "{\"latitude\":51.5000,\"longitude\":-0.1000},"
                + "{\"latitude\":51.5005,\"longitude\":-0.1000},"
                + "{\"latitude\":51.5010,\"longitude\":-0.1000}]}],"
                + "\"guidance\":{\"instructions\":[{\"message\":\"Go straight\","
                + "\"routeOffsetInMeters\":70,\"maneuver\":\"STRAIGHT\"}]}"
                + (sections == null ? "" : ",\"sections\":" + sections)
                + "}]}";
        return new JSONObject(body);
    }

    @Test public void parsesIndexedLaneSpeedAndTrafficSections() throws Exception {
        String sections = "["
                + "{\"sectionType\":\"LANES\",\"startPointIndex\":2,\"endPointIndex\":3,"
                + "\"lanes\":[{\"directions\":[\"LEFT\"]},"
                + "{\"directions\":[\"STRAIGHT\"],\"follow\":\"STRAIGHT\"}]},"
                + "{\"sectionType\":\"SPEED_LIMIT\",\"startPointIndex\":0,"
                + "\"endPointIndex\":3,\"maxSpeedLimitInKmh\":48},"
                + "{\"sectionType\":\"TRAFFIC\",\"startPointIndex\":2,"
                + "\"endPointIndex\":3,\"simpleCategory\":\"ROAD_WORK\","
                + "\"delayInSeconds\":145}]";
        TomTomApi.Route route = TomTomApi.parseRoute(fixture(sections));
        assertEquals(3, route.points.size()); // Duplicate raw vertex removed.
        assertEquals(1, route.laneSections.size());
        assertEquals("LANES  ↰   [↑]", route.laneSections.get(0).display());
        assertEquals(1, route.speedSections.size());
        assertEquals(48, route.speedSections.get(0).kmh);
        assertEquals(1, route.trafficSections.size());
        assertEquals("Roadworks", route.trafficSections.get(0).label());
        assertEquals(145, route.trafficSections.get(0).delaySeconds);
        assertTrue(route.laneSections.get(0).start > 50);
    }

    @Test public void absentAndInvalidMetadataIsNotInvented() throws Exception {
        TomTomApi.Route basic = TomTomApi.parseRoute(fixture(null));
        assertTrue(basic.speedSections.isEmpty());
        assertTrue(basic.laneSections.isEmpty());
        assertTrue(basic.trafficSections.isEmpty());
        String malformed = "["
                + "{\"sectionType\":\"SPEED_LIMIT\",\"startPointIndex\":0,"
                + "\"endPointIndex\":900,\"maxSpeedLimitInKmh\":50},"
                + "{\"sectionType\":\"LANES\",\"startPointIndex\":1,"
                + "\"endPointIndex\":2,\"lanes\":[{\"directions\":[\"LEFT\",\"RIGHT\"]}]}]";
        TomTomApi.Route invalid = TomTomApi.parseRoute(fixture(malformed));
        assertTrue(invalid.speedSections.isEmpty());
        assertTrue(invalid.laneSections.isEmpty());
        assertTrue(invalid.trafficSections.isEmpty());
    }

    @Test public void rejectsContradictoryLaneFollowRecommendation() throws Exception {
        String incorrect = "[{\"sectionType\":\"LANES\","
                + "\"startPointIndex\":2,\"endPointIndex\":3,"
                + "\"lanes\":[{\"directions\":[\"LEFT\"],\"follow\":\"RIGHT\"}]}]";
        TomTomApi.Route route = TomTomApi.parseRoute(fixture(incorrect));
        assertTrue("Contradictory lane arrows must not be displayed",
                route.laneSections.isEmpty());
    }


    @Test public void preservesMultipleMarkedDirectionsAndValidRecommendation() throws Exception {
        String sections = "[{\"sectionType\":\"LANES\","
                + "\"startPointIndex\":2,\"endPointIndex\":3,"
                + "\"lanes\":[{\"directions\":[\"LEFT\",\"STRAIGHT\"]},"
                + "{\"directions\":[\"STRAIGHT\",\"RIGHT\"],\"follow\":\"RIGHT\"}]}]";
        TomTomApi.Route route = TomTomApi.parseRoute(fixture(sections));
        assertEquals(1,route.laneSections.size());
        assertEquals(2,route.laneSections.get(0).lanes.get(0).directions.size());
        assertTrue(route.laneSections.get(0).lanes.get(1).recommended);
        assertEquals("RIGHT",route.laneSections.get(0).lanes.get(1).direction);
    }
}
