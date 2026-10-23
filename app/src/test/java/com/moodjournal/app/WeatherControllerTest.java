package com.moodjournal.app;

import static org.junit.Assert.*;
import android.app.AlertDialog;
import android.os.Bundle;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.fragment.app.FragmentActivity;
import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.Shadows;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class WeatherControllerTest {
    private static WeatherClient.Transport transport;
    @Before public void reset() {
        RuntimeEnvironment.getApplication().getSharedPreferences("weather", 0).edit().clear().commit();
        RuntimeEnvironment.getApplication().getSharedPreferences("appearance", 0).edit().clear().commit();
    }

    @Test public void citySearchChoosesMatchingLocationAndFetchesForecastWithoutLocationPermission() {
        AtomicInteger forecastCalls = new AtomicInteger();
        transport = url -> {
            if (url.contains("geocoding-api")) return "{\"results\":[{\"name\":\"London\",\"country\":\"United Kingdom\",\"latitude\":51.5,\"longitude\":-0.1,\"timezone\":\"Europe/London\"}]}";
            forecastCalls.incrementAndGet(); return WeatherDataTest.response(LocalDate.now(ZoneId.of("Europe/London")).toString());
        };
        try (ActivityController<Host> c = Robolectric.buildActivity(Host.class).setup()) {
            c.get().findViewById(R.id.weather_choose_city).performClick(); AlertDialog search = c.get().dialog;
            ((EditText) search.findViewById(R.id.weather_city_query)).setText("London"); search.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            JournalTest.await(() -> c.get().dialog != search);
            AlertDialog matches = c.get().dialog; assertTrue((matches.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0);
            Shadows.shadowOf(matches).clickOnItem(0);
            JournalTest.await(() -> c.get().status().contains("ready"));
            assertEquals(1, forecastCalls.get());
            WeatherRepository repository = new WeatherRepository(c.get()); assertEquals("London, United Kingdom", repository.place().name());
            assertEquals("Partly sunny", repository.current(System.currentTimeMillis()).description());
        }
    }

    @Test public void refreshFailureKeepsTodaysCachedForecastAndDoesNotReplaceItWithZeros() {
        WeatherRepository repository = new WeatherRepository(RuntimeEnvironment.getApplication());
        repository.setPlace(new WeatherClient.Place("London", 51.5, -0.1, "Europe/London"));
        WeatherSnapshot saved = new WeatherSnapshot(LocalDate.now(ZoneId.of("Europe/London")).toString(), 24, 14, "Sunny", WeatherClient.ATTRIBUTION, System.currentTimeMillis(), 0);
        repository.cache(saved); repository.attempted(System.currentTimeMillis());
        transport = url -> { throw new IOException("Offline"); };
        try (ActivityController<Host> c = Robolectric.buildActivity(Host.class).setup()) {
            c.get().findViewById(R.id.weather_refresh).performClick();
            JournalTest.await(() -> c.get().status().contains("saved forecast"));
            assertEquals(saved, repository.current(System.currentTimeMillis()));
        }
    }

    @Test public void emptySearchAndNetworkFailureAreActionableWithoutChangingLocation() {
        transport = url -> "{}";
        try (ActivityController<Host> c = Robolectric.buildActivity(Host.class).setup()) {
            c.get().findViewById(R.id.weather_choose_city).performClick(); AlertDialog search = c.get().dialog;
            ((EditText) search.findViewById(R.id.weather_city_query)).setText("Nobodytown"); search.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            JournalTest.await(() -> ((TextView) search.findViewById(R.id.weather_city_error)).getText().toString().contains("No matching"));
            assertTrue(search.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled()); assertNull(new WeatherRepository(c.get()).place());
            c.pause().stop(); assertFalse(search.isShowing());
        }
    }

    public static class Host extends FragmentActivity {
        WeatherController weather; AlertDialog dialog;
        @Override public void onCreate(Bundle state) {
            Appearance.apply(this); super.onCreate(state);
            weather = new WeatherController(this, value -> { dialog = value; value.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE); value.show(); },
                    () -> true, this::show, () -> {}, new WeatherClient(transport)); show();
        }
        private void show() { LinearLayout page = new LinearLayout(this); page.setOrientation(LinearLayout.VERTICAL); setContentView(page); weather.show(page); }
        @Override public void onResume() { super.onResume(); weather.onResume(); }
        @Override public void onPause() { weather.onPause(); if (dialog != null) dialog.dismiss(); super.onPause(); }
        @Override public void onDestroy() { weather.close(); super.onDestroy(); }
        String status() { return ((TextView) findViewById(R.id.weather_status)).getText().toString(); }
    }
}
