package com.drivemate.app;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

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
        /** Main POI entrance only when explicitly returned by TomTom Search. */
        public final GeoMath.Point mainEntrance;
        public Destination(String title, String address, GeoMath.Point point) {
            this(title, address, point, null);
        }
        public Destination(String title, String address, GeoMath.Point point,
                           GeoMath.Point mainEntrance) {
            this.title = title;
            this.address = address;
            this.point = point;
            this.mainEntrance = ArrivalPolicy.trustedEntrance(mainEntrance, point)
                    ? mainEntrance : null;
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
        public final String highwayExit;
        public final String roadNumbers;
        public final String drivingSide;
        public Instruction(double routeOffsetMetres, String message, String maneuver,
                           String junctionType, int exitNumber, double turnAngleDegrees,
                           String street, String signpost) {
            this(routeOffsetMetres,message,maneuver,junctionType,exitNumber,turnAngleDegrees,street,signpost,"","","");
        }
        public Instruction(double routeOffsetMetres,String message,String maneuver,
                           String junctionType,int exitNumber,double turnAngleDegrees,
                           String street,String signpost,String highwayExit,String roadNumbers,String drivingSide) {
            this.routeOffsetMetres = routeOffsetMetres;
            this.message = message;
            this.maneuver = maneuver;
            this.junctionType = junctionType;
            this.exitNumber = exitNumber;
            this.turnAngleDegrees = turnAngleDegrees;
            this.street = street;
            this.signpost = signpost;
            this.highwayExit=highwayExit; this.roadNumbers=roadNumbers; this.drivingSide=drivingSide;
        }
    }

    private static String instructionRoadNumbers(JSONArray values) {
        if(values==null)return "";
        StringBuilder out=new StringBuilder();
        for(int i=0;i<Math.min(values.length(),4);i++){
            String value=values.optString(i,"").trim();
            if(value.isEmpty()||value.length()>24||!value.matches("[A-Za-z0-9 ()/-]+"))continue;
            if(out.length()>0)out.append(" / ");
            out.append(value);
        }
        return out.toString();
    }

    public static final class Route {
        public final List<GeoMath.Point> points;
        public final List<Instruction> instructions;
        public final double lengthMetres;
        public final int timeSeconds;
        public final int trafficDelaySeconds;
        public final long fetchedAtMs;
        public final String provider;
        // Optional proof from the exact hosted routing graph; never changes guidance.
        String geometrySourceSha = "";
        List<Long> geometryWays = java.util.Collections.emptyList();
        DmUnifiedGeometry sharedGeometry;
        public final List<RouteIntelligence.LaneSection> laneSections;
        public final List<RouteIntelligence.SpeedSection> speedSections;
        public final List<RouteIntelligence.TrafficSection> trafficSections;
        Route(List<GeoMath.Point> points, List<Instruction> instructions,
              double lengthMetres, int timeSeconds, int trafficDelaySeconds,
              List<RouteIntelligence.LaneSection> laneSections,
              List<RouteIntelligence.SpeedSection> speedSections,
              List<RouteIntelligence.TrafficSection> trafficSections) {
            this(points,instructions,lengthMetres,timeSeconds,trafficDelaySeconds,laneSections,speedSections,trafficSections,"TomTom");
        }
        Route(List<GeoMath.Point> points, List<Instruction> instructions, double lengthMetres, int timeSeconds, int trafficDelaySeconds, List<RouteIntelligence.LaneSection> laneSections, List<RouteIntelligence.SpeedSection> speedSections, List<RouteIntelligence.TrafficSection> trafficSections, String provider) {
            this.provider = provider;
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
        return parsePlaces(response, query, false);
    }

    /** Search for explicitly categorised parking POIs near the chosen destination. */
    public static List<Destination> parkingNearby(String key, GeoMath.Point near)
            throws IOException, JSONException {
        if (near == null) throw new IOException("Destination required for parking search.");
        String url = "https://api.tomtom.com/search/2/categorySearch/parking.json"
                + "?key=" + encode(key) + "&lat=" + coordinate(near.lat)
                + "&lon=" + coordinate(near.lon)
                + "&radius=1400&limit=10&language=en-GB";
        return parsePlaces(requestJson(url), "Parking", true);
    }

    /** Package-private, allows testing of provider fields without a real API key. */
    static List<Destination> parsePlaces(JSONObject response, String fallback,
                                          boolean parkingOnly) throws JSONException {
        List<Destination> results = new ArrayList<>();
        JSONArray hits = response.optJSONArray("results");
        if (hits == null) return results;
        for (int i = 0; i < hits.length(); i++) {
            JSONObject result = hits.optJSONObject(i);
            if (result == null) continue;
            JSONObject p = result.optJSONObject("position");
            if (p == null) continue;
            double lat = p.optDouble("lat", Double.NaN);
            double lon = p.optDouble("lon", Double.NaN);
            if (!Double.isFinite(lat) || !Double.isFinite(lon)
                    || Math.abs(lat) > 90 || Math.abs(lon) > 180) continue;
            JSONObject poi = result.optJSONObject("poi");
            JSONObject address = result.optJSONObject("address");
            if (parkingOnly) {
                if (poi == null) continue;
                JSONArray categories = poi.optJSONArray("categories");
                boolean categoryConfirmed = false;
                if (categories != null)
                    for (int j = 0; j < categories.length(); j++)
                        categoryConfirmed |= categories.optString(j, "")
                                .toLowerCase(Locale.UK).contains("parking");
                if (!categoryConfirmed) continue;
            }
            GeoMath.Point point = new GeoMath.Point(lat, lon);
            GeoMath.Point mainEntrance = null;
            JSONArray entries = result.optJSONArray("entryPoints");
            if (entries == null) entries = result.optJSONArray("entrypoints");
            if (entries != null) {
                for (int j = 0; j < entries.length(); j++) {
                    JSONObject entry = entries.optJSONObject(j);
                    if (entry == null || !"main".equalsIgnoreCase(
                            entry.optString("type", ""))) continue;
                    JSONObject entrancePoint = entry.optJSONObject("position");
                    if (entrancePoint == null) continue;
                    GeoMath.Point candidate = new GeoMath.Point(
                            entrancePoint.optDouble("lat", Double.NaN),
                            entrancePoint.optDouble("lon", Double.NaN));
                    if (ArrivalPolicy.trustedEntrance(candidate, point)) {
                        mainEntrance = candidate;
                        break;
                    }
                }
            }
            String name = poi != null ? poi.optString("name", "") : "";
            String street = address != null ? address.optString("freeformAddress", "") : "";
            if (name.isEmpty()) name = street.isEmpty() ? fallback : street;
            results.add(new Destination(name, street, point, mainEntrance));
        }
        return results;
    }

    static final class EmptyGeometryException extends IOException {
        final double providerLength;
        EmptyGeometryException(double providerLength) {
            super("TomTom route geometry is empty."); this.providerLength=providerLength;
        }
    }
    interface RouteRequest { JSONObject get(String url) throws IOException, JSONException; }
    public static Route route(String key, GeoMath.Point start, GeoMath.Point destination)
            throws IOException, JSONException {
        return route(key,start,destination,TomTomApi::requestJson);
    }
    static Route route(String key, GeoMath.Point start, GeoMath.Point destination, RouteRequest request)
            throws IOException, JSONException {
        String pair = coordinate(start.lat) + "," + coordinate(start.lon) + ":"
                + coordinate(destination.lat) + "," + coordinate(destination.lon);
        String url = "https://api.tomtom.com/routing/1/calculateRoute/" + pair + "/json"
                + "?key=" + encode(key)
                + "&travelMode=car&routeType=fastest&traffic=true&routeRepresentation=polyline"
                + "&instructionsType=text&language=en-GB&computeTravelTimeFor=all";
        try {
            return parseRoute(request.get(url
                    + "&sectionType=lanes&sectionType=speedLimit&sectionType=traffic"));
        } catch (IOException error) {
            String message=error.getMessage();
            if (!(error instanceof EmptyGeometryException) &&
                    (message==null||(!message.startsWith("TomTom HTTP 400.")&&!message.startsWith("TomTom HTTP 403.")))) throw error;
        }
        // Retry once without optional metadata. Never synthesize a road line,
        // invent instructions, or silently switch to another routing provider.
        try {
            return parseRoute(request.get(url));
        } catch (EmptyGeometryException error) {
            if (Double.isFinite(error.providerLength) && error.providerLength>=0
                    && error.providerLength<=1 && GeoMath.distance(start,destination)<=100) {
                throw new IOException("Destination is nearby, but TomTom returned no driving route. Check the entrance or select a nearby road.",error);
            }
            throw new IOException("TomTom returned no usable road route after retry. Try the destination entrance or another nearby road.",error);
        }
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
        JSONArray legs = route.optJSONArray("legs");
        if (legs == null) throw new EmptyGeometryException(summary.optDouble("lengthInMeters",Double.NaN));
        for (int l = 0; l < legs.length(); l++) {
            JSONArray coords = legs.getJSONObject(l).optJSONArray("points");
            if (coords == null) continue;
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
        if (points.size() < 2) throw new EmptyGeometryException(summary.optDouble("lengthInMeters",Double.NaN));

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
                            instruction.optString("signpostText", ""),
                            instruction.optString("exitNumber",""),
                            instructionRoadNumbers(instruction.optJSONArray("roadNumbers")),
                            instruction.optString("drivingSide","")));
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
                            if (lane == null) { lanes.clear(); break; }
                            String follow = lane.optString("follow", "");
                            JSONArray directions = lane.optJSONArray("directions");
                            // A TomTom recommendation must be one of this
                            // lane's actual marked directions. An inconsistent
                            // response is insufficient evidence for a lane arrow.
                            boolean confirmedFollow = false;
                            if (!follow.isEmpty() && directions != null) {
                                for (int d = 0; d < directions.length(); d++)
                                    if (follow.equals(directions.optString(d, "")))
                                        confirmedFollow = true;
                            }
                            if (directions == null || directions.length() == 0
                                    || (!follow.isEmpty() && !confirmedFollow)) { lanes.clear(); break; }
                            List<String> marked = new ArrayList<>();
                            for (int d=0; d<directions.length(); d++) marked.add(directions.optString(d, ""));
                            String label = confirmedFollow ? follow : marked.get(0);
                            lanes.add(new RouteIntelligence.Lane(label, confirmedFollow, marked));
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
        try { return java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20"); }
        catch (java.io.UnsupportedEncodingException impossible) { throw new AssertionError(impossible); }
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
