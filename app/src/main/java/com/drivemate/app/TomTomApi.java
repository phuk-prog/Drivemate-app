package com.drivemate.app;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import android.net.Uri;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * TomTom REST client. All methods are blocking: call on a worker thread only.
 * The key is provided at runtime; it is never logged or embedded in source.
 */
public final class TomTomApi {
    private TomTomApi() {}

    public static final class Destination {
        public final String title;
        public final String address;
        public final GeoMath.Point point;
        public Destination(String title, String address, GeoMath.Point point) {
            this.title = title;
            this.address = address;
            this.point = point;
        }
        public String toString() {
            return address.isEmpty() || address.equals(title) ? title : title + " — " + address;
        }
    }

    public static final class Instruction {
        public final double routeOffsetMetres;
        public final String message;
        public final String maneuver;
        public final String junctionType;
        public final int exitNumber;
        public final double turnAngleDegrees;
        public final String street;
        public final String signpost;
        public Instruction(double routeOffsetMetres, String message, String maneuver,
                           String junctionType, int exitNumber, double turnAngleDegrees,
                           String street, String signpost) {
            this.routeOffsetMetres = routeOffsetMetres;
            this.message = message;
            this.maneuver = maneuver;
            this.junctionType = junctionType;
            this.exitNumber = exitNumber;
            this.turnAngleDegrees = turnAngleDegrees;
            this.street = street;
            this.signpost = signpost;
        }
    }

    public static final class Route {
        public final List<GeoMath.Point> points;
        public final List<Instruction> instructions;
        public final double lengthMetres;
        public final int timeSeconds;
        public final int trafficDelaySeconds;
        public final long fetchedAtMs;
        public final List<RouteIntelligence.LaneSection> laneSections;
        public final List<RouteIntelligence.SpeedSection> speedSections;
        public final List<RouteIntelligence.TrafficSection> trafficSections;
        Route(List<GeoMath.Point> points, List<Instruction> instructions,
              double lengthMetres, int timeSeconds, int trafficDelaySeconds,
              List<RouteIntelligence.LaneSection> laneSections,
              List<RouteIntelligence.SpeedSection> speedSections,
              List<RouteIntelligence.TrafficSection> trafficSections) {
            this.points = points;
            this.instructions = instructions;
            this.lengthMetres = lengthMetres;
            this.timeSeconds = timeSeconds;
            this.trafficDelaySeconds = trafficDelaySeconds;
            this.fetchedAtMs = System.currentTimeMillis();
            this.laneSections = laneSections;
            this.speedSections = speedSections;
            this.trafficSections = trafficSections;
        }
    }

    public static List<Destination> search(String key, String query, GeoMath.Point near)
            throws IOException, JSONException {
        String url = "https://api.tomtom.com/search/2/search/" + encode(query) + ".json"
                + "?key=" + encode(key) + "&limit=6&language=en-GB&typeahead=false";
        if (near != null) {
            url += "&lat=" + coordinate(near.lat) + "&lon=" + coordinate(near.lon);
        }
        JSONObject response = requestJson(url);
        JSONArray hits = response.optJSONArray("results");
        List<Destination> results = new ArrayList<>();
        if (hits == null) return results;
        for (int i = 0; i < hits.length(); i++) {
            JSONObject result = hits.getJSONObject(i);
            JSONObject p = result.optJSONObject("position");
            if (p == null) continue;
            JSONObject poi = result.optJSONObject("poi");
            JSONObject address = result.optJSONObject("address");
            String name = poi != null ? poi.optString("name", "") : "";
            String street = address != null ? address.optString("freeformAddress", "") : "";
            if (name.isEmpty()) name = street.isEmpty() ? query : street;
            results.add(new Destination(name, street,
                    new GeoMath.Point(p.getDouble("lat"), p.getDouble("lon"))));
        }
        return results;
    }

    public static Route route(String key, GeoMath.Point start, GeoMath.Point destination)
            throws IOException, JSONException {
        String pair = coordinate(start.lat) + "," + coordinate(start.lon) + ":"
                + coordinate(destination.lat) + "," + coordinate(destination.lon);
        String url = "https://api.tomtom.com/routing/1/calculateRoute/" + pair + "/json"
                + "?key=" + encode(key)
                + "&travelMode=car&routeType=fastest&traffic=true"
                + "&instructionsType=text&language=en-GB&computeTravelTimeFor=all";
        JSONObject response;
        try {
            response = requestJson(url
                    + "&sectionType=lanes&sectionType=speedLimit&sectionType=traffic");
        } catch (IOException error) {
            // An older/limited key must not prevent normal navigation.
            // Only retry metadata-related 400/403 errors, not rate limiting,
            // connection failures or server errors.
            String message = error.getMessage();
            if (message == null || (!message.startsWith("TomTom HTTP 400.")
                    && !message.startsWith("TomTom HTTP 403."))) throw error;
            response = requestJson(url);
        }
        return parseRoute(response);
    }

    /** Package-private to test real response shapes without network/API keys. */
    static Route parseRoute(JSONObject response) throws IOException, JSONException {
        JSONArray routes = response.optJSONArray("routes");
        if (routes == null || routes.length() == 0) {
            throw new IOException("TomTom did not return a drivable route.");
        }
        JSONObject route = routes.getJSONObject(0);
        JSONObject summary = route.getJSONObject("summary");
        List<GeoMath.Point> points = new ArrayList<>();
        // TomTom section point indices refer to the original flattened
        // polyline. Track every index even where duplicate vertices are
        // removed from the drawing/guidance geometry.
        List<Double> originalPointOffsets = new ArrayList<>();
        double geometryMetres = 0;
        JSONArray legs = route.getJSONArray("legs");
        for (int l = 0; l < legs.length(); l++) {
            JSONArray coords = legs.getJSONObject(l).getJSONArray("points");
            for (int i = 0; i < coords.length(); i++) {
                JSONObject p = coords.getJSONObject(i);
                GeoMath.Point point = new GeoMath.Point(p.getDouble("latitude"),
                        p.getDouble("longitude"));
                if (points.isEmpty()) {
                    points.add(point);
                } else {
                    double distance = GeoMath.distance(points.get(points.size() - 1), point);
                    if (distance > 0.1) {
                        geometryMetres += distance;
                        points.add(point);
                    }
                }
                originalPointOffsets.add(geometryMetres);
            }
        }
        if (points.size() < 2) throw new IOException("TomTom route geometry is empty.");

        List<Instruction> steps = new ArrayList<>();
        JSONObject guidance = route.optJSONObject("guidance");
        JSONArray instructions = guidance == null ? null : guidance.optJSONArray("instructions");
        if (instructions != null) {
            for (int i = 0; i < instructions.length(); i++) {
                JSONObject instruction = instructions.getJSONObject(i);
                String text = instruction.optString("message", "").trim();
                if (!text.isEmpty()) {
                    steps.add(new Instruction(
                            instruction.optDouble("routeOffsetInMeters", 0), text,
                            instruction.optString("maneuver", ""),
                            instruction.optString("junctionType", ""),
                            instruction.optInt("roundaboutExitNumber", 0),
                            instruction.optDouble("turnAngleInDecimalDegrees", 0),
                            instruction.optString("street", ""),
                            instruction.optString("signpostText", "")));
                }
            }
        }
        steps.sort(Comparator.comparingDouble(item -> item.routeOffsetMetres));
        if (steps.isEmpty()) throw new IOException("Route has no voice guidance instructions.");

        List<RouteIntelligence.LaneSection> laneSections = new ArrayList<>();
        List<RouteIntelligence.SpeedSection> speedSections = new ArrayList<>();
        List<RouteIntelligence.TrafficSection> trafficSections = new ArrayList<>();
        JSONArray sections = route.optJSONArray("sections");
        if (sections != null) {
            for (int i = 0; i < sections.length(); i++) {
                JSONObject section = sections.optJSONObject(i);
                if (section == null) continue;
                int startIndex = section.optInt("startPointIndex", -1);
                int endIndex = section.optInt("endPointIndex", -1);
                if (startIndex < 0 || endIndex <= startIndex
                        || endIndex >= originalPointOffsets.size()) continue;
                double from = originalPointOffsets.get(startIndex);
                double to = originalPointOffsets.get(endIndex);
                if (to <= from) continue;
                switch (section.optString("sectionType", "")) {
                    case "LANES":
                        JSONArray sourceLanes = section.optJSONArray("lanes");
                        if (sourceLanes == null) break;
                        List<RouteIntelligence.Lane> lanes = new ArrayList<>();
                        for (int j = 0; j < sourceLanes.length(); j++) {
                            JSONObject lane = sourceLanes.optJSONObject(j);
                            if (lane == null) continue;
                            String follow = lane.optString("follow", "");
                            JSONArray directions = lane.optJSONArray("directions");
                            // Only display one unambiguous direction per lane.
                            String label = !follow.isEmpty() ? follow
                                    : directions != null && directions.length() == 1
                                            ? directions.optString(0, "") : "";
                            lanes.add(new RouteIntelligence.Lane(label, !follow.isEmpty()));
                        }
                        RouteIntelligence.LaneSection candidate =
                                new RouteIntelligence.LaneSection(from, to, lanes);
                        if (candidate.trusted()) laneSections.add(candidate);
                        break;
                    case "SPEED_LIMIT":
                        int limit = section.optInt("maxSpeedLimitInKmh", -1);
                        if (limit >= 5 && limit <= 140)
                            speedSections.add(new RouteIntelligence.SpeedSection(from, to, limit));
                        break;
                    case "TRAFFIC":
                        String category = section.optString("simpleCategory", "");
                        int delay = section.optInt("delayInSeconds", 0);
                        // Ignore empty/no-impact traffic sections. The route
                        // summary retains overall traffic information.
                        if (delay > 0 || "ROAD_CLOSURE".equals(category)
                                || "ROAD_WORK".equals(category))
                            trafficSections.add(new RouteIntelligence.TrafficSection(
                                    from, to, delay, category));
                        break;
                    default: break;
                }
            }
        }
        return new Route(points, steps, summary.getDouble("lengthInMeters"),
                summary.getInt("travelTimeInSeconds"),
                summary.optInt("trafficDelayInSeconds", 0),
                laneSections, speedSections, trafficSections);
    }

    private static String coordinate(double value) {
        return String.format(Locale.US, "%.6f", value);
    }

    private static String encode(String value) {
        return Uri.encode(value);
    }

    private static JSONObject requestJson(String url) throws IOException, JSONException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(15000);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", "DriveMateGPS/1.0");
        try {
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                String explanation;
                switch (status) {
                    case 401: case 403: explanation = "Check your TomTom API key and API permissions."; break;
                    case 429: explanation = "TomTom rate limit reached. Try again later."; break;
                    default: explanation = "Check your connection and TomTom API access.";
                }
                throw new IOException("TomTom HTTP " + status + ". " + explanation);
            }
            try (InputStream in = connection.getInputStream();
                 BufferedReader reader = new BufferedReader(
                         new InputStreamReader(in, StandardCharsets.UTF_8))) {
                StringBuilder data = new StringBuilder();
                char[] buffer = new char[4096];
                int count;
                while ((count = reader.read(buffer)) != -1) {
                    if (data.length() > 4_000_000) throw new IOException("Response too large.");
                    data.append(buffer, 0, count);
                }
                return new JSONObject(data.toString());
            }
        } finally {
            connection.disconnect();
        }
    }
}
