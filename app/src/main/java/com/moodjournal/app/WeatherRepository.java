package com.moodjournal.app;

import android.content.Context;
import android.content.SharedPreferences;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/** Device preferences and a dated forecast cache; coordinates are never copied into journals. */
final class WeatherRepository {
    private final SharedPreferences prefs;
    WeatherRepository(Context context) { prefs = context.getSharedPreferences("weather", Context.MODE_PRIVATE); }
    WeatherClient.Place place() {
        if (!prefs.contains("latitude")) return null;
        try {
            return new WeatherClient.Place(prefs.getString("place", "Local weather"), Double.parseDouble(prefs.getString("latitude", "")),
                    Double.parseDouble(prefs.getString("longitude", "")), prefs.getString("timezone", ZoneId.systemDefault().getId()));
        } catch (RuntimeException invalid) { return null; }
    }
    void setPlace(WeatherClient.Place place) {
        prefs.edit().putString("place", place.name()).putString("latitude", Double.toString(round(place.latitude())))
                .putString("longitude", Double.toString(round(place.longitude()))).putString("timezone", place.timezone())
                .remove("forecast").remove("last_attempt").apply();
    }
    static double round(double coordinate) { return Math.round(coordinate * 100) / 100.0; }
    WeatherSnapshot current(long now) {
        WeatherClient.Place place = place(); WeatherSnapshot snapshot = WeatherSnapshot.decode(prefs.getString("forecast", ""));
        if (place == null || snapshot == null || !snapshot.availableAt(now)) return null;
        LocalDate today = Instant.ofEpochMilli(now).atZone(ZoneId.of(place.timezone())).toLocalDate();
        return today.toString().equals(snapshot.date()) ? snapshot : null;
    }
    void cache(WeatherSnapshot snapshot) { prefs.edit().putString("forecast", snapshot.encode()).apply(); }
    boolean needsRefresh(long now) {
        WeatherSnapshot current = current(now); long sinceAttempt = now - prefs.getLong("last_attempt", 0);
        return place() != null && (sinceAttempt < 0 || sinceAttempt >= 15 * 60_000L)
                && (current == null || now < current.fetchedAt() || now - current.fetchedAt() >= 3 * 60 * 60_000L);
    }
    void attempted(long now) { prefs.edit().putLong("last_attempt", now).apply(); }
}
