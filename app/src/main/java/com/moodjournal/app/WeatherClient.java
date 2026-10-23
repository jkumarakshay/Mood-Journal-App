package com.moodjournal.app;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Only a location goes to Open-Meteo. Journal text, moods and answers never enter requests. */
final class WeatherClient {
    static final String ATTRIBUTION = "Open-Meteo (https://open-meteo.com/), CC BY 4.0 (https://creativecommons.org/licenses/by/4.0/). Descriptions simplified.";
    interface Transport { String get(String url) throws IOException; }
    record Place(String name, double latitude, double longitude, String timezone) {
        Place {
            if (name == null || name.trim().isEmpty() || name.length() > 200 || !Double.isFinite(latitude) || !Double.isFinite(longitude)
                    || latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180) throw new IllegalArgumentException("Invalid location");
            ZoneId.of(timezone);
        }
    }
    private final Transport transport;
    WeatherClient() { this(WeatherClient::get); }
    WeatherClient(Transport transport) { this.transport = transport; }

    WeatherSnapshot forecast(Place place, long now) throws IOException {
        String url = String.format(Locale.ROOT, "https://api.open-meteo.com/v1/forecast?latitude=%.2f&longitude=%.2f&daily=temperature_2m_max,temperature_2m_min,weather_code&temperature_unit=celsius&timezone=%s&forecast_days=1",
                place.latitude(), place.longitude(), URLEncoder.encode(place.timezone(), "UTF-8"));
        try {
            JSONObject root = new JSONObject(transport.get(url));
            JSONObject daily = root.getJSONObject("daily");
            JSONObject units = root.getJSONObject("daily_units");
            if (!"°C".equals(units.getString("temperature_2m_max")) || !"°C".equals(units.getString("temperature_2m_min"))) throw new IOException("Unexpected temperature units");
            String date = daily.getJSONArray("time").getString(0);
            if (!LocalDate.parse(date).equals(Instant.ofEpochMilli(now).atZone(ZoneId.of(place.timezone())).toLocalDate())) throw new IOException("Weather is not for today");
            return new WeatherSnapshot(date, daily.getJSONArray("temperature_2m_max").getDouble(0),
                    daily.getJSONArray("temperature_2m_min").getDouble(0), condition(daily.getJSONArray("weather_code").getInt(0)), ATTRIBUTION, now, 0);
        } catch (JSONException | RuntimeException invalid) { throw new IOException("Invalid weather response", invalid); }
    }

    List<Place> search(String name) throws IOException {
        if (name.trim().length() < 2 || name.length() > 100) throw new IOException("Enter a city name");
        String url = "https://geocoding-api.open-meteo.com/v1/search?name=" + URLEncoder.encode(name.trim(), "UTF-8") + "&count=5&language=en&format=json";
        try {
            JSONArray results = new JSONObject(transport.get(url)).optJSONArray("results");
            List<Place> places = new ArrayList<>();
            if (results == null) return places;
            for (int i = 0; i < Math.min(5, results.length()); i++) {
                JSONObject result = results.getJSONObject(i);
                String label = result.getString("name");
                String region = result.optString("admin1", ""), country = result.optString("country", "");
                if (!region.isEmpty() && !region.equals(label)) label += ", " + region;
                if (!country.isEmpty()) label += ", " + country;
                places.add(new Place(label, result.getDouble("latitude"), result.getDouble("longitude"), result.getString("timezone")));
            }
            return places;
        } catch (JSONException | RuntimeException invalid) { throw new IOException("Invalid city response", invalid); }
    }

    static String condition(int code) {
        switch (code) {
            case 0: return "Sunny";
            case 1: case 2: return "Partly sunny";
            case 3: return "Cloudy";
            case 45: case 48: return "Foggy";
            case 51: case 53: case 55: return "Drizzly";
            case 56: case 57: case 66: case 67: return "Freezing rain";
            case 61: case 63: case 65: case 80: case 81: case 82: return "Rainy";
            case 71: case 73: case 75: case 77: case 85: case 86: return "Snowy";
            case 95: case 96: case 97: case 99: return "Thunderstorms";
            default: return "Conditions unavailable";
        }
    }

    private static String get(String address) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setConnectTimeout(8000); connection.setReadTimeout(8000); connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("Accept", "application/json"); connection.setRequestProperty("User-Agent", "DailyPause/1.4.1");
        try {
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) throw new IOException("Weather service unavailable");
            try (InputStream in = connection.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] bytes = new byte[4096]; int length;
                while ((length = in.read(bytes)) != -1) {
                    if (out.size() + length > 200_000) throw new IOException("Weather response too large");
                    out.write(bytes, 0, length);
                }
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally { connection.disconnect(); }
    }
}
