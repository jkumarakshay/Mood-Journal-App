package com.moodjournal.app;

import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.fragment.app.FragmentActivity;
import java.io.File;
import java.io.IOException;
import java.text.DateFormat;
import java.util.Date;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Release checks are foreground-only; Android owns downloads across backgrounding and process death. */
final class UpdateController {
    static final String PREFS = "updates";
    static final long CHECK_INTERVAL = 24L * 60 * 60 * 1000;
    private final FragmentActivity activity;
    private final SharedPreferences prefs;
    private final UpdateClient client;
    private final UpdateInstaller installer;
    private final DownloadManager downloads;
    private final BooleanSupplier visible, canPrompt;
    private final Runnable refresh, openUpdates;
    private final Consumer<AlertDialog> showDialog;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ActivityResultLauncher<Intent> sourcePermission;
    private Future<?> task;
    private boolean working, automaticWork, closed;
    private int generation;
    private int message = R.string.updates_idle;
    private UpdateRelease release;
    private long downloadId;
    private String filename;
    private int downloadState;
    private long downloadedBytes;
    private Runnable poll;

    UpdateController(FragmentActivity activity, BooleanSupplier visible, BooleanSupplier canPrompt, Runnable refresh,
                     Runnable openUpdates, Consumer<AlertDialog> showDialog) {
        this(activity, visible, canPrompt, refresh, openUpdates, showDialog, new UpdateClient());
    }
    UpdateController(FragmentActivity activity, BooleanSupplier visible, BooleanSupplier canPrompt, Runnable refresh,
                     Runnable openUpdates, Consumer<AlertDialog> showDialog, UpdateClient client) {
        this.activity = activity; this.visible = visible; this.canPrompt = canPrompt; this.refresh = refresh;
        this.openUpdates = openUpdates; this.showDialog = showDialog; this.client = client;
        prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        installer = new UpdateInstaller(activity); downloads = (DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE);
        release = UpdateRelease.decode(prefs.getString("release", null));
        downloadId = prefs.getLong("download_id", 0); filename = prefs.getString("download_file", null);
        if (release == null || release.versionCode() <= installer.installedCode() || downloadFile() == null) {
            clearDownload();
            if (release != null && release.versionCode() <= installer.installedCode()) {
                release = null; prefs.edit().remove("release").apply();
            }
        }
        sourcePermission = activity.registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (active()) resumeSourcePermission();
        });
        if (release != null) message = R.string.updates_available;
        cleanOldFiles();
    }

    private void resumeSourcePermission() {
        if (!active() || !prefs.getBoolean("awaiting_source_permission", false)) return;
        prefs.edit().remove("awaiting_source_permission").apply();
        if (installer.canInstall()) verifyAndInstall();
        else { message = R.string.updates_permission_needed; refresh.run(); }
    }
    boolean automaticEnabled() { return prefs.getBoolean("automatic", true); }
    static boolean due(long now, long last) { return last <= 0 || now < last || now - last >= CHECK_INTERVAL; }
    private boolean resumed() { return !closed && activity.getLifecycle().getCurrentState().isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED); }
    private boolean active() { return resumed() && visible.getAsBoolean(); }

    void onResume() {
        // FragmentActivity reaches RESUMED after onResume returns on some Android versions.
        if (resumed()) resumeNow();
        else main.post(() -> { if (resumed()) resumeNow(); });
    }
    private void resumeNow() {
        if (active()) {
            readDownload(); refresh.run(); schedulePoll();
            resumeSourcePermission();
        }
        maybeCheckAutomatically();
    }
    void maybeCheckAutomatically() {
        if (resumed() && canPrompt.getAsBoolean() && automaticEnabled() && !working && downloadId == 0
                && due(System.currentTimeMillis(), prefs.getLong("last_auto_attempt", 0))) check(true);
    }
    void checkManually() { if (active()) check(false); }
    private void check(boolean automatic) {
        if (working || downloadId != 0 || closed) return;
        working = true; automaticWork = automatic; message = R.string.updates_checking;
        int token = ++generation; refreshIfVisible();
        task = worker.submit(() -> {
            UpdateRelease found = null; int error = 0;
            try { found = client.latest(); }
            catch (UpdateClient.HttpFailure e) { error = e.status == 403 || e.status == 429 ? R.string.updates_rate_limited : R.string.updates_check_failed; }
            catch (IOException | RuntimeException e) { error = R.string.updates_check_failed; }
            UpdateRelease result = found; int failure = error;
            main.post(() -> {
                if (closed || token != generation || !resumed()) return;
                working = false; automaticWork = false; task = null;
                prefs.edit().putLong("last_auto_attempt", System.currentTimeMillis()).apply();
                if (failure != 0) message = failure;
                else {
                    release = result != null && result.versionCode() > installer.installedCode() ? result : null;
                    message = release == null ? R.string.updates_current : R.string.updates_available;
                    prefs.edit().putLong("last_checked", System.currentTimeMillis())
                            .putString("release", release == null ? null : release.encode()).apply();
                }
                refreshIfVisible();
                if (automatic && failure == 0 && release != null && release.minSdk() <= Build.VERSION.SDK_INT
                        && canPrompt.getAsBoolean() && automaticEnabled() && prefs.getLong("prompted_version", 0) != release.versionCode()) {
                    prefs.edit().putLong("prompted_version", release.versionCode()).apply();
                    showDialog.accept(AppDialogs.builder(activity).setTitle(R.string.updates_available)
                            .setMessage(activity.getString(R.string.updates_prompt, release.versionName()))
                            .setNegativeButton(R.string.updates_later, null)
                            .setPositiveButton(R.string.updates_view, (d, w) -> { if (resumed()) openUpdates.run(); }).create());
                }
            });
        });
    }

    void show(LinearLayout page) {
        readDownload();
        add(page, label(activity.getString(R.string.updates_installed, installer.installedName(), installer.installedCode()), 17), 16);
        Switch automatic = new Switch(activity); automatic.setId(R.id.updates_automatic);
        automatic.setText(R.string.updates_automatic); automatic.setTextSize(17); automatic.setMinHeight(dp(64));
        automatic.setTextColor(Appearance.color(activity, 0xFF312B35)); automatic.setSwitchPadding(dp(16));
        automatic.setThumbTintList(ColorStateList.valueOf(Appearance.color(activity, 0xFF705180)));
        automatic.setChecked(automaticEnabled());
        automatic.setOnCheckedChangeListener((button, enabled) -> {
            prefs.edit().putBoolean("automatic", enabled).apply();
            if (!enabled && automaticWork) { cancelWork(); message = R.string.updates_idle; refreshIfVisible(); }
        });
        add(page, automatic, 20);
        add(page, label(activity.getString(R.string.updates_automatic_description), 14), 8);
        Button check = button(R.string.updates_check, R.id.updates_check);
        check.setEnabled(!working && downloadId == 0); check.setOnClickListener(v -> checkManually()); add(page, check, 20);
        TextView status = label(status(), 15); status.setId(R.id.updates_status);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE); add(page, status, 16);
        long last = prefs.getLong("last_checked", 0);
        if (last > 0) add(page, label(activity.getString(R.string.updates_last_checked,
                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(new Date(last))), 13), 10);
        if (release != null) {
            prefs.edit().putLong("prompted_version", release.versionCode()).apply();
            add(page, label(activity.getString(R.string.updates_release, release.versionName(), release.size() / (1024.0 * 1024.0)), 22), 24);
            if (!release.notes().isEmpty()) add(page, label(release.notes(), 15), 12);
            if (release.minSdk() > Build.VERSION.SDK_INT) add(page, label(activity.getString(R.string.updates_requires_android, release.minSdk()), 15), 16);
            else {
                boolean ready = downloadState == DownloadManager.STATUS_SUCCESSFUL;
                Button download = button(ready ? R.string.updates_install : R.string.updates_download, R.id.updates_download);
                download.setEnabled(!working && (downloadId == 0 || ready || downloadState == DownloadManager.STATUS_FAILED));
                download.setOnClickListener(v -> { if (active()) { if (ready) verifyAndInstall(); else startDownload(); } });
                add(page, download, 18);
            }
            if (downloadId != 0) {
                Button cancel = button(downloadState == DownloadManager.STATUS_SUCCESSFUL ? R.string.updates_remove_download : R.string.updates_cancel_download, R.id.updates_cancel_download);
                cancel.setEnabled(!working); cancel.setOnClickListener(v -> {
                    if (!active()) return; clearDownload(); message = R.string.updates_available; refresh.run();
                }); add(page, cancel, 10);
            }
        }
        Button releases = button(R.string.updates_releases, R.id.updates_releases);
        releases.setOnClickListener(v -> {
            if (!active()) return;
            try { activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(UpdateRelease.RELEASES_URL))); }
            catch (ActivityNotFoundException | SecurityException e) { message = R.string.updates_no_browser; refresh.run(); }
        }); add(page, releases, 24);
        add(page, label(activity.getString(R.string.updates_install_note), 13), 16);
        schedulePoll();
    }
    private String status() {
        if (working || message == R.string.updates_permission_needed || message == R.string.updates_install_failed)
            return activity.getString(message);
        if (downloadId != 0) {
            if (downloadState == DownloadManager.STATUS_SUCCESSFUL) return activity.getString(R.string.updates_download_ready);
            if (downloadState == DownloadManager.STATUS_FAILED) return activity.getString(R.string.updates_download_failed);
            if (downloadState == DownloadManager.STATUS_PAUSED) return activity.getString(R.string.updates_download_waiting);
            if (release != null && release.size() > 0) return activity.getString(R.string.updates_downloading,
                    Math.max(0, Math.min(100, downloadedBytes * 100 / release.size())));
        }
        return activity.getString(message);
    }
    private void startDownload() {
        if (!active() || release == null || working || release.minSdk() > Build.VERSION.SDK_INT) return;
        clearDownload();
        File directory = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (directory == null) { message = R.string.updates_storage_failed; refresh.run(); return; }
        filename = "update-" + release.versionCode() + "-" + UUID.randomUUID() + ".apk";
        try {
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(release.apkUrl()))
                    .setTitle(activity.getString(R.string.updates_download_title, release.versionName()))
                    .setDescription(activity.getString(R.string.updates_download_description))
                    .setMimeType("application/vnd.android.package-archive")
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalFilesDir(activity, Environment.DIRECTORY_DOWNLOADS, "updates/" + filename);
            downloadId = downloads.enqueue(request);
            if (downloadId <= 0) throw new IllegalStateException("Download was not queued");
            prefs.edit().putLong("download_id", downloadId).putString("download_file", filename)
                    .putString("release", release.encode()).apply();
            message = R.string.updates_available; readDownload(); refresh.run(); schedulePoll();
        } catch (RuntimeException e) { clearDownload(); message = R.string.updates_download_failed; refresh.run(); }
    }
    private void readDownload() {
        if (downloadId == 0) return;
        try (Cursor cursor = downloads.query(new DownloadManager.Query().setFilterById(downloadId))) {
            if (cursor == null || !cursor.moveToFirst()) { clearDownload(); message = R.string.updates_download_failed; return; }
            downloadState = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
            downloadedBytes = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
        } catch (RuntimeException e) { downloadState = DownloadManager.STATUS_FAILED; }
    }
    private void schedulePoll() {
        stopPoll();
        if (!active() || downloadId == 0 || downloadState == DownloadManager.STATUS_SUCCESSFUL || downloadState == DownloadManager.STATUS_FAILED) return;
        poll = () -> {
            poll = null;
            if (!active()) return;
            readDownload();
            if (downloadId == 0 || downloadState == DownloadManager.STATUS_SUCCESSFUL || downloadState == DownloadManager.STATUS_FAILED) refresh.run();
            else {
                TextView status = activity.findViewById(R.id.updates_status);
                if (status != null) status.setText(status());
                schedulePoll();
            }
        };
        main.postDelayed(poll, 1000);
    }
    private void stopPoll() { if (poll != null) { main.removeCallbacks(poll); poll = null; } }
    private File downloadFile() {
        if (filename == null || !filename.matches("update-[0-9]+-[a-f0-9-]{36}\\.apk")) return null;
        File base = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        return base == null ? null : new File(new File(base, "updates"), filename);
    }
    private void verifyAndInstall() {
        if (!active() || working || release == null || downloadState != DownloadManager.STATUS_SUCCESSFUL) return;
        File file = downloadFile(); if (file == null) { clearDownload(); message = R.string.updates_verify_failed; refresh.run(); return; }
        working = true; message = R.string.updates_verifying; int token = ++generation;
        UpdateRelease expected = release; refresh.run();
        task = worker.submit(() -> {
            boolean valid;
            try { installer.verify(file, expected); valid = true; }
            catch (IOException | RuntimeException e) { valid = false; }
            boolean verified = valid;
            main.post(() -> {
                if (closed || token != generation || !active()) return;
                working = false; task = null;
                if (!verified) { clearDownload(); message = R.string.updates_verify_failed; refresh.run(); return; }
                if (!installer.canInstall()) {
                    showDialog.accept(AppDialogs.builder(activity).setTitle(R.string.updates_permission_title)
                            .setMessage(R.string.updates_permission_description)
                            .setNegativeButton(R.string.updates_later, null)
                            .setPositiveButton(R.string.updates_open_settings, (d, w) -> {
                                if (!active()) return;
                                prefs.edit().putBoolean("awaiting_source_permission", true).apply();
                                try { sourcePermission.launch(installer.sourcePermission()); }
                                catch (ActivityNotFoundException | SecurityException e) { prefs.edit().remove("awaiting_source_permission").apply(); message = R.string.updates_permission_needed; refresh.run(); }
                            }).create());
                } else {
                    try { activity.startActivity(installer.installIntent(file)); }
                    catch (ActivityNotFoundException | SecurityException | IllegalArgumentException e) { message = R.string.updates_install_failed; refresh.run(); }
                }
            });
        });
    }
    private void clearDownload() {
        stopPoll();
        if (downloadId != 0) { try { downloads.remove(downloadId); } catch (RuntimeException ignored) { /* The user can also remove it in Downloads. */ } }
        File file = downloadFile(); if (file != null && file.exists()) file.delete();
        downloadId = 0; filename = null; downloadState = 0; downloadedBytes = 0;
        prefs.edit().remove("download_id").remove("download_file").remove("awaiting_source_permission").apply();
    }
    private void cleanOldFiles() {
        File root = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        File[] files = root == null ? null : new File(root, "updates").listFiles();
        if (files != null) for (File file : files) if (!file.getName().equals(filename) && file.getName().startsWith("update-") && file.getName().endsWith(".apk")) file.delete();
    }
    private void refreshIfVisible() { if (active()) refresh.run(); }
    void onLeave() { cancelWork(); stopPoll(); }
    private void cancelWork() {
        generation++;
        if (working) message = release == null ? R.string.updates_idle : R.string.updates_available;
        working = false; automaticWork = false;
        if (task != null) { task.cancel(true); task = null; }
    }
    void close() { closed = true; onLeave(); worker.shutdownNow(); }
    private TextView label(String value, int size) {
        TextView view = new TextView(activity); view.setText(value); view.setTextSize(size);
        view.setTextColor(Appearance.color(activity, 0xFF312B35)); view.setLineSpacing(dp(4), 1); return view;
    }
    private Button button(int text, int id) {
        Button view = new Button(activity); view.setId(id); view.setText(text); view.setAllCaps(false); view.setTextSize(16); view.setMinHeight(dp(56));
        view.setTextColor(Appearance.color(activity, 0xFF705180));
        GradientDrawable background = new GradientDrawable(); background.setColor(Appearance.color(activity, 0xFFFFFFFF)); background.setCornerRadius(dp(18));
        view.setBackground(background); return view;
    }
    private void add(LinearLayout parent, View child, int top) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.topMargin = dp(top); parent.addView(child, params);
    }
    private int dp(int value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
}
