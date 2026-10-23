package com.moodjournal.app;

import static org.junit.Assert.*;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class WeatherDataTest {
    private Context context;
    private final long now = Instant.parse("2026-10-23T18:00:00Z").toEpochMilli();
    private final WeatherClient.Place place = new WeatherClient.Place("London", 51.5074, -0.1278, "Europe/London");
    private WeatherSnapshot snapshot() { return new WeatherSnapshot("2026-10-23", 22, 12, "Partly sunny", WeatherClient.ATTRIBUTION, now, 0); }
    static String response(String date) {
        return "{\"daily_units\":{\"temperature_2m_max\":\"°C\",\"temperature_2m_min\":\"°C\"},\"daily\":{\"time\":[\"" + date
                + "\"],\"temperature_2m_max\":[22],\"temperature_2m_min\":[12],\"weather_code\":[2]}}";
    }
    @Before public void reset() {
        context = RuntimeEnvironment.getApplication(); context.deleteDatabase("journal.db");
        context.getSharedPreferences("weather", 0).edit().clear().commit();
    }

    @Test public void forecastParsesDailyHighLowAndConditionAndOnlyRequestsWeatherFields() throws Exception {
        AtomicReference<String> url = new AtomicReference<>();
        WeatherClient client = new WeatherClient(address -> { url.set(address); return response("2026-10-23"); });
        WeatherSnapshot result = client.forecast(place, now);
        assertEquals(snapshot(), result);
        assertTrue(url.get(), url.get().startsWith("https://api.open-meteo.com/"));
        assertTrue(url.get().contains("latitude=51.51")); assertTrue(url.get().contains("longitude=-0.13"));
        assertTrue(url.get().contains("forecast_days=1")); assertTrue(url.get().contains("timezone=Europe%2FLondon"));
        assertFalse(url.get().contains("mood")); assertFalse(url.get().contains("answer"));
    }

    @Test public void missingNullInvalidAndStaleWeatherCannotBecomeAFabricatedForecast() {
        for (String invalid : List.of("{}", response("2026-10-22"), response("2026-10-23").replace("[22]", "[null]"),
                response("2026-10-23").replace("[12]", "[30]"), response("2026-10-23").replace("°C", "°F"))) {
            assertThrows(IOException.class, () -> new WeatherClient(address -> invalid).forecast(place, now));
        }
        assertThrows(IOException.class, () -> new WeatherClient(address -> { throw new IOException("Offline"); }).forecast(place, now));
    }

    @Test public void weatherDatesFollowSelectedLocationRatherThanDeviceDate() throws Exception {
        WeatherClient.Place tokyo = new WeatherClient.Place("Tokyo", 35.68, 139.69, "Asia/Tokyo");
        long late = Instant.parse("2026-10-23T23:30:00Z").toEpochMilli();
        assertEquals("2026-10-24", new WeatherClient(address -> response("2026-10-24")).forecast(tokyo, late).date());
    }

    @Test public void conditionsCoverSunCloudRainFogSnowAndThunderstorms() {
        assertEquals("Sunny", WeatherClient.condition(0)); assertEquals("Partly sunny", WeatherClient.condition(2));
        assertEquals("Cloudy", WeatherClient.condition(3)); assertEquals("Rainy", WeatherClient.condition(61));
        assertEquals("Foggy", WeatherClient.condition(48)); assertEquals("Snowy", WeatherClient.condition(85));
        assertEquals("Thunderstorms", WeatherClient.condition(97)); assertEquals("Conditions unavailable", WeatherClient.condition(-1));
    }

    @Test public void citySearchReturnsDistinctRegionsAndTimezoneAndEncodesQuery() throws Exception {
        AtomicReference<String> url = new AtomicReference<>();
        WeatherClient client = new WeatherClient(address -> { url.set(address); return "{\"results\":[{\"name\":\"London\",\"admin1\":\"England\",\"country\":\"United Kingdom\",\"latitude\":51.5,\"longitude\":-0.1,\"timezone\":\"Europe/London\"}]}"; });
        WeatherClient.Place result = client.search("London, UK").get(0);
        assertEquals("London, England, United Kingdom", result.name()); assertEquals("Europe/London", result.timezone());
        assertTrue(url.get().contains("name=London%2C+UK"));
        assertTrue(new WeatherClient(address -> "{}").search("Nothing").isEmpty());
        assertThrows(IOException.class, () -> client.search("a"));
    }

    @Test public void snapshotCodecAndConversionsPreserveOriginalValues() {
        WeatherSnapshot result = snapshot(); assertEquals(result, WeatherSnapshot.decode(result.encode()));
        assertTrue(result.summary(false), result.summary(false).contains("22°C"));
        assertTrue(result.summary(true), result.summary(true).contains("72°F"));
        assertTrue(result.summary(true).contains("54°F")); assertEquals(22, result.highC(), 0);
        assertNull(WeatherSnapshot.decode("garbage")); assertNull(WeatherSnapshot.decode("a".repeat(3001)));
        assertThrows(IllegalArgumentException.class, () -> new WeatherSnapshot("2026-10-23", Double.NaN, 0, "Sunny", "Source", now, 0));
    }

    @Test public void locationAndCachePersistWithRoundedCoordinatesAndExpireAtLocalMidnight() {
        WeatherRepository repository = new WeatherRepository(context); repository.setPlace(place); repository.cache(snapshot()); repository.attempted(now);
        WeatherRepository reopened = new WeatherRepository(context);
        assertEquals(51.51, reopened.place().latitude(), 0); assertEquals(-0.13, reopened.place().longitude(), 0);
        assertEquals(snapshot(), reopened.current(now)); assertFalse(reopened.needsRefresh(now + 60_000));
        assertTrue(reopened.needsRefresh(now + 4 * 60 * 60_000L));
        assertNull(reopened.current(Instant.parse("2026-10-24T00:00:00Z").toEpochMilli()));
        repository.setPlace(new WeatherClient.Place("Paris", 48.85, 2.35, "Europe/Paris")); assertNull(repository.current(now));
        assertFalse(WeatherReflection.fahrenheit(context)); WeatherReflection.setFahrenheit(context, true); assertTrue(WeatherReflection.fahrenheit(context));
    }

    @Test public void migrationFromVersionTwoPreservesHeapsEntriesAndMoodHistory() {
        String id = "00000000-0000-0000-0000-000000000001";
        try (SQLiteDatabase db = context.openOrCreateDatabase("journal.db", 0, null)) {
            db.execSQL("CREATE TABLE heaps (id TEXT PRIMARY KEY NOT NULL,name TEXT NOT NULL,category TEXT NOT NULL,color INTEGER)");
            db.execSQL("INSERT INTO heaps VALUES ('" + id + "','Work','Life',NULL)");
            db.execSQL("CREATE TABLE entries (id INTEGER PRIMARY KEY AUTOINCREMENT,created_at INTEGER NOT NULL,mood INTEGER NOT NULL,event TEXT NOT NULL,thoughts TEXT NOT NULL,needs TEXT NOT NULL,heap_id TEXT REFERENCES heaps(id))");
            db.execSQL("INSERT INTO entries VALUES (7," + now + ",4,'Keep me','Feelings','Rest','" + id + "')"); db.setVersion(2);
        }
        try (JournalStore store = new JournalStore(context)) {
            JournalStore.Entry entry = store.all().get(0); assertEquals(7, entry.id()); assertEquals(id, entry.heapId());
            assertEquals("Keep me", entry.event()); assertEquals(0, entry.weatherAnswer()); assertNull(entry.weather());
            assertEquals(1, store.heaps().size());
            assertEquals(1, store.moodsBetween(LocalDate.parse("2026-10-23"), LocalDate.parse("2026-10-23"), ZoneId.of("UTC")).size());
        }
    }

    @Test public void snapshotsAndAnswersSurviveEncryptedBackupRestoreAndDoNotFollowLiveWeather() throws Exception {
        BackupArchive.Snapshot saved;
        try (JournalStore store = new JournalStore(context)) {
            JournalHeap heap = store.addHeap("Outdoors", "Life", JournalHeap.COLORS[2]);
            store.add(4, "A walk", "", "", heap.id(), 2, snapshot());
            saved = store.backupSnapshot();
            WeatherRepository live = new WeatherRepository(context); live.setPlace(place);
            live.cache(new WeatherSnapshot("2026-10-23", 10, 2, "Rainy", WeatherClient.ATTRIBUTION, now, 0));
            assertEquals(snapshot(), store.all().get(0).weather()); assertEquals(2, store.all().get(0).weatherAnswer());
        }
        char[] password = "a separate backup passphrase".toCharArray();
        byte[] encrypted = BackupArchive.encrypt(saved, password);
        assertEquals("DPAUSE03", new String(encrypted, 0, 8, StandardCharsets.US_ASCII));
        assertFalse(new String(encrypted, StandardCharsets.ISO_8859_1).contains("Partly sunny"));
        BackupArchive.Snapshot restored = BackupArchive.decryptSnapshot(encrypted, password); assertEquals(saved, restored);
        context.deleteDatabase("journal.db");
        try (JournalStore store = new JournalStore(context)) {
            assertEquals(1, store.restoreSnapshot(restored)); assertEquals(0, store.restoreSnapshot(restored));
            assertEquals(snapshot(), store.all().get(0).weather()); assertEquals(2, store.all().get(0).weatherAnswer());
        }
    }

    @Test public void invalidAnswersAreRejectedAndLegacyFormatsRemainReadable() throws Exception {
        try (JournalStore store = new JournalStore(context)) { assertThrows(IllegalArgumentException.class, () -> store.add(3, "", "", "", null, 6, null)); }
        char[] password = "a separate backup passphrase".toCharArray();
        assertThrows(IOException.class, () -> BackupArchive.encrypt(List.of(new BackupArchive.Entry(now, 3, "", "", "", null, 8, null)), password));
        List<BackupArchive.Entry> old = List.of(new BackupArchive.Entry(now, 3, "Old", "", ""));
        assertEquals(old, BackupArchive.decrypt(BackupArchive.encrypt(old, password), password));
        BackupArchive.Snapshot two = new BackupArchive.Snapshot(List.of(), old);
        assertEquals(two, BackupArchive.decryptSnapshot(BackupArchive.encrypt(two, password), password));
    }
}
