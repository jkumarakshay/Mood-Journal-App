package com.moodjournal.app;

import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.text.InputFilter;
import android.text.InputType;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.fragment.app.FragmentActivity;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Uses the system document picker. Google Drive, not this app, manages Google sign-in. */
final class BackupController {
    private enum Stage { IDLE, ENCRYPTING, SAVING, DECRYPTING, RESTORING }
    private final FragmentActivity activity;
    private final Context context;
    private final Consumer<AlertDialog> showDialog;
    private final BooleanSupplier authorized;
    private final Runnable refresh;
    private final SharedPreferences notices;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ActivityResultLauncher<String> savePicker;
    private final ActivityResultLauncher<String[]> restorePicker;
    private Stage stage = Stage.IDLE;
    private int generation;
    private boolean destroyed;
    private String pendingExportName;
    private Uri restoreUri;
    private BackupArchive.Snapshot pendingRestore;
    private EditText passwordField, confirmField;

    BackupController(FragmentActivity activity, Bundle state, Consumer<AlertDialog> showDialog,
                     BooleanSupplier authorized, Runnable refresh) {
        this.activity = activity;
        context = activity.getApplicationContext();
        this.showDialog = showDialog;
        this.authorized = authorized;
        this.refresh = refresh;
        notices = context.getSharedPreferences("backup_notices", Context.MODE_PRIVATE);
        if (state != null) {
            String filename = state.getString("pending_encrypted_backup");
            if (filename != null && filename.matches("daily-pause-[0-9a-f-]{36}\\.dpa")) pendingExportName = filename;
            String uri = state.getString("selected_restore_backup");
            if (uri != null && "content".equals(Uri.parse(uri).getScheme())) restoreUri = Uri.parse(uri);
        }
        cleanupExpiredFiles();
        savePicker = activity.registerForActivityResult(new ActivityResultContracts.CreateDocument("application/octet-stream"), this::saveSelected);
        restorePicker = activity.registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
            if (uri == null) { finish("Restore selection cancelled."); return; }
            if (!"content".equals(uri.getScheme())) { finish("Choose a backup using the system file picker."); return; }
            restoreUri = uri;
            finish("Backup selected. Unlock My Journal, then tap Restore encrypted backup to continue.");
        });
    }

    void addTo(LinearLayout page, int entryCount) {
        if (!authorized.getAsBoolean()) return;
        TextView heading = label("Your encrypted backup", 20);
        heading.setPadding(0, dp(24), 0, dp(8));
        page.addView(heading);
        page.addView(label("Choose Google Drive in the system save dialog. Install Drive and sign in there if needed. Saved entries and Heaps are backed up; drafts and your lock PIN stay on this device.", 14));
        Button export = button("Back up to Google Drive", R.id.backup_export);
        export.setEnabled(entryCount > 0 && stage == Stage.IDLE && pendingExportName == null);
        export.setOnClickListener(v -> {
            if (authorized.getAsBoolean() && stage == Stage.IDLE && pendingExportName == null) askPassphrase(false);
        });
        page.addView(export);
        Button restore = button("Restore encrypted backup", R.id.backup_restore);
        restore.setEnabled(stage == Stage.IDLE && pendingExportName == null);
        restore.setOnClickListener(v -> {
            if (!authorized.getAsBoolean() || stage != Stage.IDLE || pendingExportName != null) return;
            if (restoreUri == null) chooseRestore(); else askPassphrase(true);
        });
        page.addView(restore);
        if (restoreUri != null) {
            Button another = button("Choose a different backup", R.id.backup_choose_file);
            another.setEnabled(stage == Stage.IDLE);
            another.setOnClickListener(v -> { if (authorized.getAsBoolean() && stage == Stage.IDLE) chooseRestore(); });
            page.addView(another);
        }
        String message = switch (stage) {
            case ENCRYPTING -> "Encrypting your saved entries…";
            case SAVING -> "Saving the encrypted backup…";
            case DECRYPTING -> "Checking and decrypting the backup…";
            case RESTORING -> "Restoring missing entries…";
            case IDLE -> notices.getString("status", "Keep your backup passphrase safe. It is separate from your PIN and cannot be recovered.");
        };
        TextView status = label(message, 13);
        status.setId(R.id.backup_status);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        page.addView(status);
    }

    private void chooseRestore() {
        restoreUri = null;
        try { restorePicker.launch(new String[]{"*/*"}); }
        catch (ActivityNotFoundException e) { finish("No file picker is available. Install Google Drive and try again."); }
    }

    private void askPassphrase(boolean restoring) {
        final int token = generation;
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(24), dp(8), dp(24), dp(8));
        content.addView(label(restoring ? "Enter the backup passphrase. Your journal PIN cannot open a backup."
                : "Use a separate passphrase of at least 12 characters. Your saved entries will be encrypted before you choose Google Drive. This passphrase cannot be recovered.", 14));
        passwordField = password("Backup passphrase", R.id.backup_password);
        EditText password = passwordField;
        content.addView(password);
        confirmField = restoring ? null : password("Confirm backup passphrase", R.id.backup_password_confirm);
        EditText confirm = confirmField;
        if (confirm != null) content.addView(confirm);
        TextView error = label("", 14);
        error.setId(R.id.backup_password_error);
        error.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        content.addView(error);
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle(restoring ? "Open encrypted backup" : "Protect your backup")
                .setView(content).setNegativeButton("Cancel", null).setPositiveButton(restoring ? "Check backup" : "Encrypt and choose Drive", null).create();
        dialog.setOnDismissListener(d -> { password.setText(""); if (confirm != null) confirm.setText(""); });
        showDialog.accept(dialog);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (generation != token || !authorized.getAsBoolean()) { dialog.dismiss(); return; }
            char[] attempt = new char[password.length()];
            password.getText().getChars(0, password.length(), attempt, 0);
            if (!BackupArchive.validPassphrase(attempt)) {
                Arrays.fill(attempt, '\0'); error.setText(R.string.backup_passphrase_length); return;
            }
            if (confirm != null && !password.getText().toString().equals(confirm.getText().toString())) {
                Arrays.fill(attempt, '\0'); error.setText(R.string.backup_passphrase_mismatch); return;
            }
            dialog.dismiss();
            if (restoring) decrypt(attempt, token); else encrypt(attempt, token);
        });
    }

    private void encrypt(char[] password, int token) {
        stage = Stage.ENCRYPTING;
        refresh.run();
        worker.execute(() -> {
            File file = null;
            try (JournalStore store = new JournalStore(context)) {
                byte[] encrypted = BackupArchive.encrypt(store.backupSnapshot(), password);
                file = new File(context.getCacheDir(), "daily-pause-" + UUID.randomUUID() + ".dpa");
                if (!file.createNewFile()) throw new IOException("Could not create encrypted cache");
                try (OutputStream out = new FileOutputStream(file)) { out.write(encrypted); }
                File ready = file;
                main.post(() -> {
                    if (generation != token || destroyed || !authorized.getAsBoolean()) { ready.delete(); return; }
                    pendingExportName = ready.getName();
                    stage = Stage.IDLE;
                    try {
                        String name = "Daily-Pause-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".dpa";
                        savePicker.launch(name);
                    } catch (ActivityNotFoundException e) {
                        discardExport(); finish("No file picker is available. Install Google Drive and try again.");
                    }
                });
            } catch (Exception e) {
                if (file != null) file.delete();
                main.post(() -> { if (generation == token && !destroyed) finish("Couldn’t prepare the backup. Check device storage and try again. Backups are limited to 20 MB and 50,000 entries."); });
            } finally { Arrays.fill(password, '\0'); }
        });
    }

    private void saveSelected(Uri uri) {
        File source = pendingExportName == null ? null : new File(context.getCacheDir(), pendingExportName);
        pendingExportName = null;
        if (uri == null) { if (source != null) source.delete(); finish("Backup cancelled."); return; }
        if (!"content".equals(uri.getScheme()) || source == null || !source.isFile()) {
            if (source != null) source.delete();
            finish("Backup preparation expired or the app restarted. Unlock My Journal and try again.");
            return;
        }
        stage = Stage.SAVING;
        refreshIfAuthorized();
        worker.execute(() -> {
            String result;
            try (InputStream input = new FileInputStream(source); OutputStream output = context.getContentResolver().openOutputStream(uri, "wt")) {
                if (output == null) throw new IOException("No output stream");
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                output.flush();
                result = "Encrypted backup saved to the selected location. If you chose Google Drive, Drive may finish syncing when online.";
            } catch (Exception e) {
                try { DocumentsContract.deleteDocument(context.getContentResolver(), uri); } catch (Exception ignored) { /* The provider may require manual removal. */ }
                result = "Couldn’t save the backup. Remove any incomplete file in the selected location, check Drive and your connection, then try again.";
            } finally { source.delete(); }
            String message = result;
            main.post(() -> finish(message));
        });
    }

    private void decrypt(char[] password, int token) {
        Uri selected = restoreUri;
        stage = Stage.DECRYPTING;
        refresh.run();
        worker.execute(() -> {
            try (InputStream input = context.getContentResolver().openInputStream(selected)) {
                if (input == null) throw new IOException("No input stream");
                BackupArchive.Snapshot entries = BackupArchive.decryptSnapshot(BackupArchive.readBounded(input), password);
                main.post(() -> {
                    if (generation != token || destroyed || !authorized.getAsBoolean()) return;
                    stage = Stage.IDLE;
                    pendingRestore = entries;
                    confirmRestore(token);
                });
            } catch (Exception e) {
                main.post(() -> { if (generation == token && !destroyed) finish("Couldn’t open the backup. Check the passphrase, the file, and Drive connectivity. Nothing was restored."); });
            } finally { Arrays.fill(password, '\0'); }
        });
    }

    private void confirmRestore(int token) {
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle("Restore " + pendingRestore.entries().size() + " entries?")
                .setMessage("Missing entries and Heaps will be added. Existing entries, Heaps, drafts, and your PIN stay unchanged. Exact duplicates will be skipped.")
                .setNegativeButton("Cancel", null).setPositiveButton("Restore entries", (d, w) -> {
                    if (generation != token || !authorized.getAsBoolean() || pendingRestore == null) return;
                    BackupArchive.Snapshot approved = pendingRestore;
                    pendingRestore = null;
                    stage = Stage.RESTORING;
                    refresh.run();
                    worker.execute(() -> {
                        String result;
                        try (JournalStore store = new JournalStore(context)) {
                            int added = store.restoreSnapshot(approved);
                            result = "Restore complete: " + added + " entries added. Existing duplicates were skipped.";
                        } catch (Exception e) { result = "Restore failed. No entries were added. Check device storage and try again."; }
                        String message = result;
                        main.post(() -> { restoreUri = null; finish(message); });
                    });
                }).create();
        dialog.setOnDismissListener(d -> {
            pendingRestore = null;
            if (stage == Stage.IDLE) refreshIfAuthorized();
        });
        showDialog.accept(dialog);
    }

    void onPause() {
        generation++;
        if (passwordField != null) passwordField.setText("");
        if (confirmField != null) confirmField.setText("");
        pendingRestore = null;
        if (stage == Stage.ENCRYPTING || stage == Stage.DECRYPTING) stage = Stage.IDLE;
    }

    void saveState(Bundle state) {
        if (pendingExportName != null) state.putString("pending_encrypted_backup", pendingExportName);
        if (restoreUri != null) state.putString("selected_restore_backup", restoreUri.toString());
    }

    void close() { onPause(); destroyed = true; worker.shutdown(); }

    private void finish(String message) {
        stage = Stage.IDLE;
        notices.edit().putString("status", message).apply();
        refreshIfAuthorized();
    }

    private void refreshIfAuthorized() { if (!destroyed && authorized.getAsBoolean()) refresh.run(); }

    private void discardExport() {
        if (pendingExportName != null) new File(context.getCacheDir(), pendingExportName).delete();
        pendingExportName = null;
    }

    private void cleanupExpiredFiles() {
        File[] files = context.getCacheDir().listFiles();
        if (files != null) for (File file : files) {
            if (file.getName().matches("daily-pause-[0-9a-f-]{36}\\.dpa")
                    && System.currentTimeMillis() - file.lastModified() > 60 * 60 * 1000L) file.delete();
        }
        if (pendingExportName != null && !new File(context.getCacheDir(), pendingExportName).isFile()) pendingExportName = null;
    }

    private EditText password(String hint, int id) {
        EditText field = new EditText(activity);
        field.setId(id); field.setHint(hint); field.setContentDescription(hint);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        field.setImeOptions(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        field.setFilters(new InputFilter[]{new InputFilter.LengthFilter(256)});
        field.setSaveEnabled(false);
        field.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        field.setMinHeight(dp(56));
        return field;
    }

    private TextView label(String value, int size) {
        TextView view = new TextView(activity);
        view.setText(value); view.setTextSize(size); view.setTextColor(Appearance.color(activity, Color.rgb(49, 43, 53)));
        view.setPadding(0, dp(6), 0, dp(10));
        return view;
    }

    private Button button(String value, int id) {
        Button view = new Button(activity);
        view.setId(id); view.setText(value); view.setAllCaps(false); view.setMinHeight(dp(52));
        return view;
    }

    private int dp(int value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
}
