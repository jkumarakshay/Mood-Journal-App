package com.moodjournal.app;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputFilter;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.fragment.app.FragmentActivity;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

final class WeatherController {
    private final FragmentActivity activity;
    private final WeatherRepository repository;
    private final WeatherClient client;
    private final Consumer<AlertDialog> showDialog;
    private final BooleanSupplier visible;
    private final Runnable refreshSettings, openSettings;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ActivityResultLauncher<String> permission;
    private final LocationManager locations;
    private TextView header;
    private LocationListener locationListener;
    private Runnable locationTimeout;
    private Runnable dayChange;
    private boolean closed, loading;
    private int generation, searches;
    private int status;

    WeatherController(FragmentActivity activity, Consumer<AlertDialog> showDialog, BooleanSupplier visible,
                      Runnable refreshSettings, Runnable openSettings) {
        this(activity, showDialog, visible, refreshSettings, openSettings, new WeatherClient());
    }
    WeatherController(FragmentActivity activity, Consumer<AlertDialog> showDialog, BooleanSupplier visible,
                      Runnable refreshSettings, Runnable openSettings, WeatherClient client) {
        this.activity = activity; this.showDialog = showDialog; this.visible = visible;
        this.refreshSettings = refreshSettings; this.openSettings = openSettings; this.client = client;
        repository = new WeatherRepository(activity); locations = activity.getSystemService(LocationManager.class);
        permission = activity.registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
            if (!visible.getAsBoolean()) return;
            if (granted) locate(); else { status = R.string.weather_permission_denied; update(); }
        });
    }

    View header() {
        header = label("", 12); header.setId(R.id.weather_summary); header.setMinHeight(dp(48));
        header.setPadding(0, dp(4), dp(12), dp(4)); header.setOnClickListener(v -> openSettings.run());
        header.setBackgroundResource(android.R.drawable.list_selector_background); renderHeader(); refresh(false); scheduleDayChange(); return header;
    }
    private void renderHeader() {
        if (header == null) return;
        WeatherSnapshot snapshot = repository.current(System.currentTimeMillis());
        String value = snapshot == null ? activity.getString(repository.place() == null ? R.string.weather_setup : loading ? R.string.weather_loading : R.string.weather_unavailable)
                : snapshot.summary(WeatherReflection.fahrenheit(activity)) + "\nOpen-Meteo";
        header.setText(value); header.setContentDescription(value + ". " + activity.getString(R.string.weather_open_settings));
    }

    void show(LinearLayout page) {
        add(page, label(activity.getString(R.string.weather_units_description), 16), 14);
        RadioGroup units = new RadioGroup(activity); units.setOrientation(LinearLayout.VERTICAL);
        int[] ids = {R.id.weather_celsius, R.id.weather_fahrenheit};
        int[] labels = {R.string.weather_celsius, R.string.weather_fahrenheit};
        for (int i = 0; i < ids.length; i++) {
            RadioButton option = new RadioButton(activity); option.setId(ids[i]); option.setText(labels[i]); option.setTextSize(17);
            option.setMinHeight(dp(56)); option.setTextColor(Appearance.color(activity, 0xFF312B35));
            option.setButtonTintList(ColorStateList.valueOf(Appearance.color(activity, 0xFF705180))); units.addView(option);
        }
        units.check(WeatherReflection.fahrenheit(activity) ? R.id.weather_fahrenheit : R.id.weather_celsius);
        units.setOnCheckedChangeListener((group, id) -> { WeatherReflection.setFahrenheit(activity, id == R.id.weather_fahrenheit); renderHeader(); update(); });
        add(page, units, 10);
        WeatherClient.Place place = repository.place();
        TextView location = label(place == null ? activity.getString(R.string.weather_no_location) : place.name(), 18);
        location.setId(R.id.weather_location); add(page, location, 22);
        add(page, button(R.string.weather_use_location, R.id.weather_use_location, true, android.R.drawable.ic_menu_mylocation, () -> {
            if (!visible.getAsBoolean()) return;
            if (hasLocationPermission()) locate(); else permission.launch(Manifest.permission.ACCESS_COARSE_LOCATION);
        }), 12);
        add(page, button(R.string.weather_choose_city, R.id.weather_choose_city, false, android.R.drawable.ic_menu_search, this::chooseCity), 12);
        Button refresh = button(R.string.weather_refresh, R.id.weather_refresh, false, android.R.drawable.ic_popup_sync, () -> refresh(true)); refresh.setEnabled(place != null && !loading);
        add(page, refresh, 12);
        WeatherSnapshot snapshot = repository.current(System.currentTimeMillis());
        if (snapshot != null) add(page, label(snapshot.summary(WeatherReflection.fahrenheit(activity)), 16), 18);
        TextView message = label(status == 0 ? "" : activity.getString(status), 14); message.setId(R.id.weather_status);
        message.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE); add(page, message, 12);
        add(page, label(activity.getString(R.string.weather_privacy), 13), 18);
        TextView attribution = label(activity.getString(R.string.weather_attribution), 13); attribution.setOnClickListener(v -> {
            try { activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://open-meteo.com/en/terms"))); }
            catch (android.content.ActivityNotFoundException ignored) { /* Attribution remains readable. */ }
        });
        add(page, attribution, 16);
    }

    private boolean hasLocationPermission() { return activity.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED; }
    void onResume() { renderHeader(); refresh(false); scheduleDayChange(); }
    private void scheduleDayChange() {
        if (dayChange != null) main.removeCallbacks(dayChange);
        WeatherClient.Place place = repository.place();
        if (place == null || closed) return;
        java.time.ZonedDateTime now = java.time.ZonedDateTime.now(ZoneId.of(place.timezone()));
        long delay = java.time.Duration.between(now, now.toLocalDate().plusDays(1).atStartOfDay(now.getZone())).toMillis() + 1000;
        dayChange = () -> { renderHeader(); refresh(false); scheduleDayChange(); };
        main.postDelayed(dayChange, delay);
    }
    private void update() {
        if (closed) return;
        renderHeader();
        if (visible.getAsBoolean() && activity.getLifecycle().getCurrentState().isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) refreshSettings.run();
    }
    private void refresh(boolean force) {
        WeatherClient.Place place = repository.place(); long now = System.currentTimeMillis();
        if (closed || loading || place == null || !force && !repository.needsRefresh(now)) return;
        loading = true; status = R.string.weather_loading; repository.attempted(now); final int token = ++generation; update();
        worker.execute(() -> {
            try {
                WeatherSnapshot snapshot = client.forecast(place, now);
                main.post(() -> {
                    if (closed || token != generation || !place.equals(repository.place())) return;
                    repository.cache(snapshot); loading = false; status = R.string.weather_updated; update();
                });
            } catch (java.io.IOException e) {
                main.post(() -> {
                    if (closed || token != generation) return;
                    loading = false; status = repository.current(System.currentTimeMillis()) == null ? R.string.weather_fetch_failed : R.string.weather_cached; update();
                });
            }
        });
    }

    private void chooseCity() {
        if (!visible.getAsBoolean()) return;
        LinearLayout form = new LinearLayout(activity); form.setOrientation(LinearLayout.VERTICAL); form.setPadding(dp(24), dp(8), dp(24), dp(8));
        EditText query = new EditText(activity); query.setId(R.id.weather_city_query); query.setHint(R.string.weather_city_hint);
        query.setSingleLine(true); query.setSaveEnabled(false); query.setFilters(new InputFilter[]{new InputFilter.LengthFilter(100)});
        query.setTextColor(Appearance.color(activity, 0xFF312B35)); query.setHintTextColor(Appearance.color(activity, 0xFF6D6470));
        query.setMinHeight(dp(52)); form.addView(query);
        TextView error = label("", 14); error.setId(R.id.weather_city_error); form.addView(error);
        AlertDialog dialog = AppDialogs.builder(activity).setTitle(R.string.weather_choose_city).setView(form)
                .setNegativeButton(android.R.string.cancel, null).setPositiveButton(R.string.weather_search, null).create();
        showDialog.accept(dialog);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String name = query.getText().toString().trim(); if (name.length() < 2) { error.setText(R.string.weather_city_hint); return; }
            final int token = ++searches; error.setText(R.string.weather_searching); dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
            worker.execute(() -> {
                try {
                    List<WeatherClient.Place> matches = client.search(name);
                    main.post(() -> {
                        if (closed || token != searches || !visible.getAsBoolean() || !dialog.isShowing()) return;
                        if (matches.isEmpty()) { error.setText(R.string.weather_no_matches); dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true); return; }
                        dialog.dismiss(); String[] names = new String[matches.size()]; for (int i = 0; i < names.length; i++) names[i] = matches.get(i).name();
                        showDialog.accept(AppDialogs.builder(activity).setTitle(R.string.weather_choose_city)
                                .setItems(names, (d, which) -> { if (visible.getAsBoolean()) select(matches.get(which)); })
                                .setNegativeButton(android.R.string.cancel, null).create());
                    });
                } catch (java.io.IOException e) {
                    main.post(() -> { if (!closed && token == searches && dialog.isShowing()) { error.setText(R.string.weather_city_failed); dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true); } });
                }
            });
        });
    }

    private void select(WeatherClient.Place place) {
        stopLocation(); generation++; loading = false; repository.setPlace(place); refresh(true); scheduleDayChange();
    }
    private void locate() {
        if (!visible.getAsBoolean()) return;
        stopLocation();
        try {
            Criteria criteria = new Criteria(); criteria.setAccuracy(Criteria.ACCURACY_COARSE);
            String provider = locations.isProviderEnabled(LocationManager.NETWORK_PROVIDER) ? LocationManager.NETWORK_PROVIDER : locations.getBestProvider(criteria, true);
            if (provider == null) { status = R.string.weather_location_failed; update(); return; }
            Location cached = locations.getLastKnownLocation(provider);
            if (cached != null && System.currentTimeMillis() - cached.getTime() >= 0 && System.currentTimeMillis() - cached.getTime() <= 2 * 60 * 60_000L) { useLocation(cached); return; }
            status = R.string.weather_locating; update();
            locationListener = new LocationListener() {
                @Override public void onLocationChanged(Location location) { if (!closed && visible.getAsBoolean()) useLocation(location); }
                @Override public void onProviderDisabled(String provider) { stopLocation(); status = R.string.weather_location_failed; update(); }
                @Override public void onProviderEnabled(String provider) {}
                @Override public void onStatusChanged(String provider, int value, Bundle extras) {}
            };
            locations.requestSingleUpdate(provider, locationListener, Looper.getMainLooper());
            locationTimeout = () -> { stopLocation(); status = R.string.weather_location_failed; update(); }; main.postDelayed(locationTimeout, 15_000);
        } catch (SecurityException | IllegalArgumentException e) { stopLocation(); status = R.string.weather_location_failed; update(); }
    }
    private void useLocation(Location location) {
        select(new WeatherClient.Place(activity.getString(R.string.weather_current_location), location.getLatitude(), location.getLongitude(), ZoneId.systemDefault().getId()));
    }
    private void stopLocation() {
        if (locationListener != null) {
            try { locations.removeUpdates(locationListener); } catch (SecurityException ignored) { /* Permission may have been revoked. */ }
            locationListener = null;
        }
        if (locationTimeout != null) { main.removeCallbacks(locationTimeout); locationTimeout = null; }
    }
    void onPause() { searches++; stopLocation(); if (dayChange != null) { main.removeCallbacks(dayChange); dayChange = null; } }
    void close() { onPause(); closed = true; header = null; worker.shutdown(); }
    private TextView label(String text, int size) {
        TextView view = new TextView(activity); view.setText(text); view.setTextSize(size); view.setTextColor(Appearance.color(activity, 0xFF312B35)); return view;
    }
    private Button button(int text, int id, boolean primary, int icon, Runnable action) {
        Button button = new Button(activity); button.setId(id); button.setText(text); button.setAllCaps(false);
        button.setTextSize(16); button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setMinHeight(dp(60)); button.setMinimumHeight(dp(60)); button.setMinWidth(0); button.setMinimumWidth(0);
        button.setPadding(dp(18), dp(14), dp(18), dp(14)); button.setBackgroundTintList(null); button.setStateListAnimator(null);
        int accent = Appearance.color(activity, 0xFF705180), soft = Appearance.color(activity, 0xFFEDE6F1);
        button.setTextColor(new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled}, new int[]{}},
                new int[]{Appearance.color(activity, 0xFF6D6470), primary ? Appearance.onAccent(activity) : accent}));
        StateListDrawable surface = new StateListDrawable();
        surface.addState(new int[]{-android.R.attr.state_enabled}, surface(soft));
        surface.addState(new int[]{}, surface(primary ? accent : soft));
        int ripple = primary ? (Appearance.isDark(activity) ? 0x242C1737 : 0x33FFFFFF) : (accent & 0x00FFFFFF) | 0x22000000;
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(ripple), surface, surface(0xFFFFFFFF)));
        Drawable symbol = activity.getDrawable(icon).mutate(); symbol.setTintList(button.getTextColors());
        symbol.setBounds(0, 0, dp(24), dp(24)); button.setCompoundDrawablesRelative(symbol, null, null, null);
        button.setCompoundDrawablePadding(dp(12));
        button.setOnClickListener(v -> { if (visible.getAsBoolean()) action.run(); }); return button;
    }
    private GradientDrawable surface(int color) {
        GradientDrawable shape = new GradientDrawable(); shape.setColor(color); shape.setCornerRadius(dp(18)); return shape;
    }
    private void add(LinearLayout parent, View view, int margin) { LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.topMargin = dp(margin); parent.addView(view, params); }
    private int dp(int value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
}
