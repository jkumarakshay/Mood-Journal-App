package com.moodjournal.app;

import static org.junit.Assert.*;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class AnalysisRepositoryTest {
    private Context context;
    private final ZoneId zone = ZoneId.of("America/New_York");
    private final long now = LocalDate.of(2026, 10, 23).atTime(22, 0).atZone(zone).toInstant().toEpochMilli();
    @Before public void reset() {
        context = RuntimeEnvironment.getApplication(); context.deleteDatabase("journal.db");
        context.getSystemService(JobScheduler.class).cancelAll();
    }
    static void seed(Context context, long now, ZoneId zone, int count) {
        LocalDate today = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate();
        try (JournalStore store = new JournalStore(context)) {
            for (int i=0; i<count; i++) {
                LocalDate day = today.minusDays(i); ContentValues v = new ContentValues();
                v.put("created_at", Math.min(now - 1000, day.atTime(12,0).atZone(zone).toInstant().toEpochMilli()));
                v.put("mood", i < 12 ? 2 : 4); v.put("event", i < 12 ? "My boss gave me a work deadline. I feel stressed and overwhelmed." : "I enjoyed painting and felt grateful and happy.");
                v.put("thoughts", ""); v.put("needs", "");
                v.put("weather", new WeatherSnapshot(day.toString(), i < 12 ? 15 : 29, i < 12 ? 7 : 19, i < 12 ? "Rainy" : "Sunny", "Test", now, 0).encode());
                store.getWritableDatabase().insertOrThrow("entries", null, v);
            }
        }
    }
    @Test public void weeklyCacheWaitsForDueDateAfterNewEntries() throws Exception {
        seed(context, now, zone, 40); AnalysisRepository repository = new AnalysisRepository(context);
        MoodAnalysis.Report first = repository.review(now, zone);
        try (JournalStore store = new JournalStore(context)) {
            ContentValues v = new ContentValues(); v.put("created_at", now + 60_000); v.put("mood", 5); v.put("event", "Another entry"); v.put("thoughts", ""); v.put("needs", "");
            store.getWritableDatabase().insertOrThrow("entries", null, v);
        }
        MoodAnalysis.Report cached = repository.review(now + 120_000, zone);
        assertEquals(first, cached); assertEquals(40, cached.totalEntries());
        MoodAnalysis.Report refreshed = repository.review(first.nextReviewAt(), zone);
        assertEquals(41, refreshed.totalEntries()); assertEquals(first.nextReviewAt(), refreshed.generatedAt());
    }
    @Test public void deleteAndMoveClearDerivedPrivateReportImmediately() throws Exception {
        seed(context, now, zone, 30); AnalysisRepository repository = new AnalysisRepository(context);
        assertTrue(repository.review(now, zone).eligible());
        try (JournalStore store = new JournalStore(context)) { store.delete(store.all().get(0).id()); }
        assertNull(cachedText()); assertFalse(repository.review(now+1000, zone).eligible());
        seed(context, now, zone, 2); assertTrue(repository.review(now+2000, zone).eligible());
        try (JournalStore store = new JournalStore(context)) {
            JournalHeap heap = store.addHeap("Work", "Career", null); store.move(store.all().get(0).id(), heap.id());
        }
        assertNull(cachedText());
    }
    @Test public void timezoneClockRollbackAndModelVersionInvalidateCache() throws Exception {
        seed(context, now, zone, 40); AnalysisRepository repository = new AnalysisRepository(context);
        repository.review(now, zone);
        assertEquals(now+1000, repository.review(now+1000, ZoneId.of("UTC")).generatedAt());
        assertEquals(now, repository.review(now, zone).generatedAt());
        try (JournalStore store = new JournalStore(context)) {
            ContentValues v = new ContentValues(); v.put("report", cachedText().replace(JournalTextModel.VERSION,"obsolete-model"));
            store.getWritableDatabase().update("analytics_cache",v,"id=1",null);
        }
        assertEquals(now+2000, repository.review(now+2000,zone).generatedAt());
    }
    @Test public void editingJournalOrHeapInvalidatesTheWeeklyReportImmediately() throws Exception {
        seed(context,now,zone,30); AnalysisRepository repository=new AnalysisRepository(context);
        JournalHeap heap;
        try(JournalStore store=new JournalStore(context)) { heap=store.addHeap("Work","Career",null); }
        repository.review(now,zone); assertNotNull(cachedText());
        try(JournalStore store=new JournalStore(context)) {
            JournalStore.Entry entry=store.all().get(0);
            store.updateEntry(entry.id(),5,"An edited reflection","Feeling better","",entry.heapId());
        }
        assertNull(cachedText()); assertEquals(now+1000,repository.review(now+1000,zone).generatedAt());
        try(JournalStore store=new JournalStore(context)) { store.updateHeap(heap.id(),"Learning","Growth",null,"A new purpose"); }
        assertNull(cachedText()); assertEquals(now+2000,repository.review(now+2000,zone).generatedAt());
    }
    @Test public void jobsArePersistedBatteryAwareAndNeedNoNetwork() throws Exception {
        seed(context,now,zone,40); MoodAnalysis.Report report = new AnalysisRepository(context).review(now,zone);
        AnalysisScheduler.next(context,report); JobInfo job = context.getSystemService(JobScheduler.class).getPendingJob(AnalysisScheduler.JOB_ID);
        assertNotNull(job); assertTrue(job.isPersisted()); assertTrue(job.isRequireBatteryNotLow());
        assertEquals(JobInfo.NETWORK_TYPE_NONE,job.getNetworkType()); assertEquals(report.nextReviewAt(),job.getExtras().getLong("review_at"));
        assertEquals(AnalyticsJobService.class.getName(),job.getService().getClassName());
        context.deleteDatabase("journal.db");
        AnalysisScheduler.next(context,new AnalysisRepository(context).review(now,zone));
        assertNull(context.getSystemService(JobScheduler.class).getPendingJob(AnalysisScheduler.JOB_ID));
    }
    @Test public void upgradingV3PreservesHeapsWeatherAndLegacyAnswers() throws Exception {
        seed(context,now,zone,1);
        try (JournalStore store = new JournalStore(context)) {
            JournalHeap heap=store.addHeap("Work","Career",JournalHeap.COLORS[1]); store.move(store.all().get(0).id(),heap.id());
            SQLiteDatabase db=store.getWritableDatabase(); ContentValues v=new ContentValues();v.put("weather_answer",3);db.update("entries",v,null,null);
            for(String table:new String[]{"entries","heaps"}) for(String op:new String[]{"insert","update","delete"}) db.execSQL("DROP TRIGGER analytics_"+table+"_"+op);
            db.execSQL("DROP TABLE analytics_cache");db.execSQL("DROP TABLE analytics_meta");db.setVersion(3);
        }
        try (JournalStore upgraded = new JournalStore(context)) {
            assertEquals(5,upgraded.getReadableDatabase().getVersion()); assertEquals(1,upgraded.heaps().size());
            JournalStore.Entry entry=upgraded.all().get(0); assertEquals(3,entry.weatherAnswer()); assertNotNull(entry.weather()); assertNotNull(entry.heapId());
            assertEquals(1,upgraded.backupSnapshot().entries().size());
        }
        assertFalse(new AnalysisRepository(context).review(now,zone).eligible());
    }
    private String cachedText() {
        try (JournalStore store = new JournalStore(context); Cursor c=store.getReadableDatabase().rawQuery("SELECT report FROM analytics_cache WHERE id=1",null)) {return c.moveToFirst()?c.getString(0):null;}
    }
}
