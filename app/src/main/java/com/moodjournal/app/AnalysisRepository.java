package com.moodjournal.app;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/** Private, derived reports. Inserts wait for the next review; deletions/edits clear stale reports. */
final class AnalysisRepository {
    private final Context context;
    AnalysisRepository(Context context) { this.context = context.getApplicationContext(); }

    MoodAnalysis.Report review(long now, ZoneId zone) throws IOException {
        for (int attempt = 0; attempt < 2; attempt++) {
            try (JournalStore store = new JournalStore(context)) {
                SQLiteDatabase db = store.getWritableDatabase();
                List<JournalStore.Entry> entries; List<JournalHeap> heaps; long revision, destructive;
                db.beginTransaction();
                try {
                    try (Cursor meta = db.rawQuery("SELECT revision,destructive_revision FROM analytics_meta WHERE id=1", null)) {
                        if (!meta.moveToFirst()) throw new IOException("Missing analysis state"); revision = meta.getLong(0); destructive = meta.getLong(1);
                    }
                    try (Cursor cached = db.rawQuery("SELECT report,revision,zone FROM analytics_cache WHERE id=1", null)) {
                        if (cached.moveToFirst() && !cached.isNull(0) && cached.getLong(1) == destructive && zone.getId().equals(cached.getString(2))) {
                            MoodAnalysis.Report report = decode(cached.getString(0));
                            if (report != null && report.generatedAt() <= now && now < report.nextReviewAt()) return report;
                        }
                    }
                    entries = store.all(); heaps = store.heaps(); db.setTransactionSuccessful();
                } finally { db.endTransaction(); }
                JournalTextModel model = new JournalTextModel(context.getAssets().open("journal-text-model.tsv"));
                MoodAnalysis.Report report = MoodAnalysis.build(entries, heaps, now, zone, model);
                if (!report.eligible()) return report;
                db.beginTransaction();
                try {
                    long current;
                    try (Cursor meta = db.rawQuery("SELECT revision FROM analytics_meta WHERE id=1", null)) { meta.moveToFirst(); current = meta.getLong(0); }
                    if (current != revision) continue; // A concurrent delete/restore must never resurrect old insights.
                    ContentValues values = new ContentValues(); values.put("id", 1); values.put("report", encode(report));
                    values.put("revision", destructive); values.put("zone", zone.getId()); values.put("next_due", report.nextReviewAt());
                    db.insertWithOnConflict("analytics_cache", null, values, SQLiteDatabase.CONFLICT_REPLACE); db.setTransactionSuccessful();
                    return report;
                } finally { db.endTransaction(); }
            }
        }
        throw new IOException("Journal changed while reviewing");
    }

    private static String encode(MoodAnalysis.Report report) throws IOException {
        try {
            JSONObject root = new JSONObject().put("version", MoodAnalysis.VERSION).put("model", JournalTextModel.VERSION)
                    .put("days", report.recordedDays()).put("entries", report.totalEntries()).put("weather", report.weatherDays()).put("classified", report.classifiedDays())
                    .put("from", report.from().toString()).put("through", report.through().toString()).put("generated", report.generatedAt()).put("next", report.nextReviewAt());
            JSONArray sections = new JSONArray();
            for (MoodAnalysis.Section section : report.sections()) {
                JSONArray insights = new JSONArray();
                for (MoodAnalysis.Insight insight : section.insights()) insights.put(new JSONObject().put("title", insight.title()).put("detail", insight.detail())
                        .put("days", insight.days()).put("comparison", insight.comparisonDays()).put("difference", insight.difference()).put("evidence", insight.evidence()));
                sections.put(new JSONObject().put("title", section.title()).put("context", section.context()).put("insights", insights));
            }
            return root.put("sections", sections).toString();
        } catch (JSONException invalid) { throw new IOException("Could not store analysis", invalid); }
    }

    private static MoodAnalysis.Report decode(String text) {
        if (text == null || text.length() > 100_000) return null;
        try {
            JSONObject root = new JSONObject(text);
            if (root.getInt("version") != MoodAnalysis.VERSION || !JournalTextModel.VERSION.equals(root.getString("model"))) return null;
            List<MoodAnalysis.Section> sections = new ArrayList<>(); JSONArray values = root.getJSONArray("sections");
            if (values.length() != 3) return null;
            for (int i = 0; i < values.length(); i++) {
                JSONObject section = values.getJSONObject(i); List<MoodAnalysis.Insight> insights = new ArrayList<>(); JSONArray rows = section.getJSONArray("insights");
                if (rows.length() > 4) return null;
                for (int j = 0; j < rows.length(); j++) {
                    JSONObject row = rows.getJSONObject(j);
                    insights.add(new MoodAnalysis.Insight(row.getString("title"), row.getString("detail"), row.getInt("days"), row.getInt("comparison"), row.getDouble("difference"), row.getDouble("evidence")));
                }
                sections.add(new MoodAnalysis.Section(section.getString("title"), section.getString("context"), java.util.Collections.unmodifiableList(insights)));
            }
            return new MoodAnalysis.Report(true, root.getInt("days"), root.getInt("entries"), root.getInt("weather"), root.getInt("classified"),
                    LocalDate.parse(root.getString("from")), LocalDate.parse(root.getString("through")), root.getLong("generated"), root.getLong("next"), java.util.Collections.unmodifiableList(sections));
        } catch (JSONException | RuntimeException invalid) { return null; }
    }
}
