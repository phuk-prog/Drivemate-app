package com.drivemate.app;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import android.net.Uri;

import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
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
        public Instruction(double routeOffsetMetres, String message) {
            this.routeOffsetMetres = routeOffsetMetres;
            this.message = message;
        }
    }

    public static final class Route {
        public final List<GeoMath.Point> points;
        public final List<Instruction> instructions;
        public final double lengthMetres;
        public final int timeSeconds;
        public final int trafficDelaySeconds;
        Route(List<GeoMath.Point> points, List<Instruction> instructions,
              double lengthMetres, int timeSeconds, int trafficDelaySeconds) {
            this.points = points;
            this.instructions = instructions;
            this.lengthMetres = lengthMetres;
            this.timeSeconds = timeSeconds;
            this.trafficDelaySeconds = trafficDelaySeconds;
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
        JSONObject response = requestJson(url);
        JSONArray routes = response.optJSONArray("routes");
        if (routes == null || routes.length() == 0) {
            throw new IOException("TomTom did not return a drivable route.");
        }
        JSONObject route = routes.getJSONObject(0);
        JSONObject summary = route.getJSONObject("summary");
        List<GeoMath.Point> points = new ArrayList<>();
        JSONArray legs = route.getJSONArray("legs");
        for (int l = 0; l < legs.length(); l++) {
            JSONArray coords = legs.getJSONObject(l).getJSONArray("points");
            for (int i = 0; i < coords.length(); i++) {
                JSONObject p = coords.getJSONObject(i);
                GeoMath.Point point = new GeoMath.Point(p.getDouble("latitude"),
                        p.getDouble("longitude"));
                if (points.isEmpty()
                        || GeoMath.distance(points.get(points.size() - 1), point) > 0.1) {
                    points.add(point);
                }
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
                            instruction.optDouble("routeOffsetInMeters", 0), text));
                }
            }
        }
        steps.sort(Comparator.comparingDouble(item -> item.routeOffsetMetres));
        if (steps.isEmpty()) throw new IOException("Route has no voice guidance instructions.");
        return new Route(points, steps, summary.getDouble("lengthInMeters"),
                summary.getInt("travelTimeInSeconds"),
                summary.optInt("trafficDelayInSeconds", 0));
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
