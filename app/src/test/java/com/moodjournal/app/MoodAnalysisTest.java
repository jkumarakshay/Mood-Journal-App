package com.moodjournal.app;

import static org.junit.Assert.*;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class MoodAnalysisTest {
    private JournalTextModel model;
    private final ZoneId zone = ZoneId.of("America/New_York");
    private final LocalDate today = LocalDate.of(2026, 10, 23);
    private final long now = today.atTime(22, 0).atZone(zone).toInstant().toEpochMilli();
    @Before public void load() throws Exception { model = new JournalTextModel(RuntimeEnvironment.getApplication().getAssets().open("journal-text-model.tsv")); }
    private JournalStore.Entry entry(long id, LocalDate day, int mood, String text, String condition) {
        WeatherSnapshot weather = condition == null ? null : new WeatherSnapshot(day.toString(), condition.equals("Rainy") ? 15 : 29, condition.equals("Rainy") ? 7 : 19, condition, "Test weather", now, 0);
        return new JournalStore.Entry(id, day.atTime(12, 0).atZone(zone).toInstant().toEpochMilli(), mood, text, "", "", null, 0, weather);
    }
    private List<JournalStore.Entry> flat(int days, int mood) {
        List<JournalStore.Entry> entries = new ArrayList<>();
        for (int i = 0; i < days; i++) entries.add(entry(i + 1, today.minusDays(i), mood, "", null)); return entries;
    }
    private MoodAnalysis.Report report(List<JournalStore.Entry> entries) { return MoodAnalysis.build(entries, Collections.emptyList(), now, zone, model); }

    @Test public void firstReportRequiresThirtyDistinctRecentDays() {
        assertFalse(report(flat(29, 3)).eligible()); assertTrue(report(flat(30, 3)).eligible());
        List<JournalStore.Entry> sameDay = new ArrayList<>(); for (int i = 0; i < 100; i++) sameDay.add(entry(i + 1, today, 3, "", null));
        assertFalse(report(sameDay).eligible()); assertEquals(1, report(sameDay).recordedDays());
    }
    @Test public void missingFutureAndOldDaysCannotFillTheMinimum() {
        List<JournalStore.Entry> entries = flat(29, 3);
        entries.add(entry(100, today.plusDays(1), 5, "", null)); entries.add(entry(101, today.minusDays(100), 5, "", null));
        assertFalse(report(entries).eligible()); assertEquals(29, report(entries).recordedDays());
    }
    @Test public void strongWeatherAndResponseAssociationsHaveCountsAndNoncausalEvidence() {
        List<JournalStore.Entry> entries = new ArrayList<>();
        for (int i = 0; i < 40; i++) entries.add(entry(i + 1, today.minusDays(i), i < 12 ? 2 : 4,
                i < 12 ? "My boss gave me another work deadline. I feel stressed and overwhelmed." : "I enjoyed painting and felt grateful and happy.", i < 12 ? "Rainy" : "Sunny"));
        MoodAnalysis.Report report = report(entries); assertEquals(3, report.sections().size()); assertEquals(40, report.weatherDays());
        assertFalse(report.sections().get(0).insights().isEmpty());
        MoodAnalysis.Insight work = report.sections().get(1).insights().stream().filter(row -> row.title().contains("work")).findFirst().orElseThrow();
        assertEquals(12, work.days()); assertEquals(28, work.comparisonDays()); assertEquals(-2, work.difference(), .0001);
        assertTrue(work.detail().contains("association")); assertFalse(work.detail().contains("causes"));
    }
    @Test public void noVariationOrInsufficientGroupsDoNotCreatePatterns() {
        MoodAnalysis.Report flat = report(flat(40, 3)); for (MoodAnalysis.Section section : flat.sections()) assertTrue(section.insights().isEmpty());
        List<JournalStore.Entry> entries = flat(39, 4); entries.add(entry(100, today.minusDays(39), 1, "My boss gave me a deadline at work.", "Rainy"));
        assertTrue(report(entries).sections().get(1).insights().isEmpty());
    }
    @Test public void unknownTextCannotBecomeEvidenceOfAnotherTheme() {
        List<JournalStore.Entry> entries = new ArrayList<>();
        for(int i=0;i<40;i++) entries.add(entry(i+1,today.minusDays(i),i<12?1:5,i<12?"My boss gave me a deadline at work.":"zqxv blort flimb",null));
        assertTrue(report(entries).sections().get(1).insights().isEmpty());
    }
    @Test public void repeatedEntriesCannotOverweightOneDayOrDuplicateEvidence() {
        List<JournalStore.Entry> entries = new ArrayList<>();
        for (int i = 0; i < 35; i++) entries.add(entry(i + 1, today.minusDays(i), 3, "", null));
        for (int i = 0; i < 50; i++) entries.add(entry(100 + i, today, 1, "work deadline boss office", "Rainy"));
        MoodAnalysis.Report report = report(entries); assertEquals(35, report.recordedDays()); assertEquals(1, report.weatherDays());
        assertTrue(report.sections().get(1).insights().isEmpty());
    }
    @Test public void mismatchedWeatherDatesAndConflictingForecastsAreExcluded() {
        List<JournalStore.Entry> entries = flat(30, 3);
        WeatherSnapshot wrongDay = new WeatherSnapshot(today.minusDays(1).toString(), 25, 10, "Sunny", "Test", now, 0);
        entries.set(0, new JournalStore.Entry(1, now - 1000, 3, "", "", "", null, 0, wrongDay));
        assertEquals(0, report(entries).weatherDays());
        entries.add(entry(101, today.minusDays(1), 3, "", "Sunny")); entries.add(entry(102, today.minusDays(1), 3, "", "Rainy"));
        assertEquals(0, report(entries).weatherDays());
    }
    @Test public void weekdayAssociationsUseDaysAndMonthPatternsWaitForRepeatedHistory() {
        List<JournalStore.Entry> entries = new ArrayList<>();
        for (int i = 0; i < 70; i++) {
            LocalDate day = today.minusDays(i); entries.add(entry(i + 1, day, day.getDayOfWeek() == java.time.DayOfWeek.MONDAY ? 1 : 4, "", null));
        }
        MoodAnalysis.Section time = report(entries).sections().get(2);
        assertTrue(time.insights().stream().anyMatch(row -> row.title().contains("Monday")));
        assertFalse(time.insights().stream().anyMatch(row -> row.title().startsWith("Mood in ")));
        assertTrue(time.context().contains("more than a year"));
    }
    @Test public void writtenSentimentCanVaryByWeekdayWhileMoodRatingsStayStable() {
        List<JournalStore.Entry> entries=new ArrayList<>();
        for(int i=0;i<70;i++) {
            LocalDate day=today.minusDays(i);boolean monday=day.getDayOfWeek()==java.time.DayOfWeek.MONDAY;
            entries.add(entry(i+1,day,3,monday?"I am overwhelmed, stressed and worried. I feel miserable and discouraged.":"I am happy and grateful for this lovely day. I feel hopeful and supported.",null));
        }
        MoodAnalysis.Insight tone=report(entries).sections().get(2).insights().stream().filter(row->row.title().equals("Written tone on Mondays")).findFirst().orElseThrow();
        assertTrue(tone.detail().contains("more negative"));assertTrue(tone.detail().contains("selected moods did not show"));
    }
    @Test public void weekOfMonthRequiresRepeatedMonthsAndFindsConsistentDips() {
        List<JournalStore.Entry> entries = new ArrayList<>();
        for (int i=0;i<90;i++) {
            LocalDate day=today.minusDays(i); entries.add(entry(i+1,day,day.getDayOfMonth()<=7?1:4,"",null));
        }
        assertTrue(report(entries).sections().get(2).insights().stream().anyMatch(row->row.title().equals("Week 1 of the month")));
        assertFalse(report(entries.subList(0,30)).sections().get(2).insights().stream().anyMatch(row->row.title().startsWith("Week ")));
    }
    @Test public void yearlyPatternRequiresTheSameMonthInTwoYears() {
        List<JournalStore.Entry> entries=flat(40,4);
        for(int year:new int[]{2025,2026}) for(int day=1;day<=15;day++) entries.add(entry(year*100+day,LocalDate.of(year,1,day),1,"",null));
        assertTrue(report(entries).sections().get(2).insights().stream().anyMatch(row->row.title().equals("Mood in January")));
        entries.removeIf(row->Instant.ofEpochMilli(row.createdAt()).atZone(zone).getYear()==2025);
        assertFalse(report(entries).sections().get(2).insights().stream().anyMatch(row->row.title().startsWith("Mood in ")));
    }
    @Test public void unrecordedWeatherIsNotUsedAsAWeatherComparisonGroup() {
        List<JournalStore.Entry> entries=flat(40,4);
        for(int i=0;i<10;i++) entries.set(i,entry(i+1,today.minusDays(i),1,"","Rainy"));
        assertEquals(10,report(entries).weatherDays());assertTrue(report(entries).sections().get(0).insights().isEmpty());
    }
    @Test public void nextReviewPreservesLocalWeeklyTimeAcrossDaylightSaving() {
        LocalDate day = LocalDate.of(2026, 10, 30); long start = day.atTime(21, 0).atZone(zone).toInstant().toEpochMilli();
        MoodAnalysis.Report report = MoodAnalysis.build(flat(40, 3), Collections.emptyList(), start, zone, model);
        assertEquals(day.plusWeeks(1).atTime(21, 0).atZone(zone).toInstant().toEpochMilli(), report.nextReviewAt());
        assertEquals(169 * 60 * 60_000L, report.nextReviewAt() - start);
    }
    @Test public void statisticsMatchKnownWelchValuesAndHandleZeroVariance() {
        double t = 2.22813885196; // df=10, two-sided probability .05
        assertEquals(.05, AnalysisStatistics.probability(t * Math.sqrt(2.0 / 6), 1, 6, 0, 1, 6), .00001);
        assertEquals(1, AnalysisStatistics.probability(3, 1, 10, 3, 1, 10), .00001);
        assertEquals(0, AnalysisStatistics.probability(1, 0, 10, 4, 0, 10), 0);
        assertEquals(1, AnalysisStatistics.probability(3, 0, 10, 3, 0, 10), 0);
    }
}
