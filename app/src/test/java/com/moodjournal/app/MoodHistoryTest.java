package com.moodjournal.app;

import static org.junit.Assert.*;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

public class MoodHistoryTest {
    private static final ZoneId UTC = ZoneId.of("UTC");

    @Test public void dailyAveragesIgnoreMissingDaysAndCombineSameDayCheckIns() {
        LocalDate start = LocalDate.of(2026, 10, 1);
        MoodHistory.Series result = MoodHistory.summarize(Arrays.asList(reading(start, 1), reading(start, 5),
                reading(start.plusDays(2), 4)), start, start.plusDays(6), UTC);
        assertEquals(MoodHistory.Grouping.DAILY, result.grouping());
        assertEquals(7, result.buckets().size());
        assertEquals(3.0, result.buckets().get(0).average(), 0.001);
        assertNull(result.buckets().get(1).average());
        assertEquals(4.0, result.buckets().get(2).average(), 0.001);
        assertEquals(3, result.responses());
        assertEquals(2, result.recordedDays());
    }

    @Test public void fullCalendarMonthRemainsDailyAndLongerRangesBecomeWeekly() {
        LocalDate first = LocalDate.of(2026, 1, 1);
        assertEquals(MoodHistory.Grouping.DAILY, MoodHistory.summarize(Collections.emptyList(), first, first.plusMonths(1).minusDays(1), UTC).grouping());
        assertEquals(MoodHistory.Grouping.WEEKLY, MoodHistory.summarize(Collections.emptyList(), first, first.plusMonths(1), UTC).grouping());
    }

    @Test public void weeklyAverageUsesOnlyRecordedResponsesAndKeepsEmptyWeeksMissing() {
        LocalDate first = LocalDate.of(2026, 7, 1);
        MoodHistory.Series result = MoodHistory.summarize(Arrays.asList(reading(first, 1), reading(first, 5),
                reading(first.plusDays(6), 3), reading(first.plusDays(14), 5)), first, first.plusMonths(3).minusDays(1), UTC);
        assertEquals(MoodHistory.Grouping.WEEKLY, result.grouping());
        assertEquals(3.0, result.buckets().get(0).average(), 0.001);
        assertEquals(3, result.buckets().get(0).responses());
        assertEquals(2, result.buckets().get(0).recordedDays());
        assertNull(result.buckets().get(1).average());
        assertEquals(5.0, result.buckets().get(2).average(), 0.001);
    }

    @Test public void yearlyRangeUsesCalendarMonthsAndClipsPartialMonths() {
        LocalDate first = LocalDate.of(2025, 10, 23), last = LocalDate.of(2026, 10, 22);
        MoodHistory.Series result = MoodHistory.summarize(Arrays.asList(reading(first.minusDays(1), 1), reading(first, 4),
                reading(LocalDate.of(2025, 10, 31), 2), reading(last, 5), reading(last.plusDays(1), 1)), first, last, UTC);
        assertEquals(MoodHistory.Grouping.MONTHLY, result.grouping());
        assertEquals(first, result.buckets().get(0).start());
        assertEquals(LocalDate.of(2025, 10, 31), result.buckets().get(0).end());
        assertEquals(3.0, result.buckets().get(0).average(), 0.001);
        assertNull(result.buckets().get(1).average());
        assertEquals(last, result.buckets().get(result.buckets().size() - 1).end());
        assertEquals(3, result.responses());
    }

    @Test public void presetsIncludeTodayAndRespectLeapYears() {
        LocalDate today = LocalDate.of(2024, 2, 29);
        assertEquals(LocalDate.of(2024, 2, 23), MoodHistory.Period.WEEK.start(today));
        assertEquals(LocalDate.of(2024, 1, 30), MoodHistory.Period.MONTH.start(today));
        assertEquals(LocalDate.of(2023, 11, 30), MoodHistory.Period.THREE_MONTHS.start(today));
        assertEquals(LocalDate.of(2023, 3, 1), MoodHistory.Period.YEAR.start(today));
        assertEquals(MoodHistory.Grouping.DAILY, MoodHistory.summarize(Collections.emptyList(), MoodHistory.Period.MONTH.start(today), today, UTC).grouping());
        assertEquals(MoodHistory.Grouping.WEEKLY, MoodHistory.summarize(Collections.emptyList(), MoodHistory.Period.THREE_MONTHS.start(today), today, UTC).grouping());
    }

    @Test public void calendarDaysUseDeviceTimeZoneInsteadOfUtcDates() {
        LocalDate localDay = LocalDate.of(2026, 3, 8);
        ZoneId zone = ZoneId.of("America/New_York");
        long timestamp = java.time.Instant.parse("2026-03-09T03:30:00Z").toEpochMilli();
        MoodHistory.Series result = MoodHistory.summarize(Collections.singletonList(new MoodHistory.Reading(timestamp, 2)), localDay, localDay, zone);
        assertEquals(1, result.responses());
        assertEquals(2.0, result.buckets().get(0).average(), 0.001);
        assertEquals(1, result.totalDays());
    }

    @Test public void emptyHistoryContainsNoFabricatedMoodValues() {
        LocalDate first = LocalDate.of(2026, 10, 1);
        MoodHistory.Series result = MoodHistory.summarize(Collections.emptyList(), first, first.plusDays(6), UTC);
        assertEquals(0, result.responses());
        assertEquals(0, result.recordedDays());
        for (MoodHistory.Bucket bucket : result.buckets()) { assertNull(bucket.average()); assertEquals(0, bucket.responses()); }
    }

    @Test(expected = IllegalArgumentException.class) public void reversedRangeIsRejected() {
        MoodHistory.summarize(Collections.emptyList(), LocalDate.of(2026, 10, 2), LocalDate.of(2026, 10, 1), UTC);
    }

    private MoodHistory.Reading reading(LocalDate date, int mood) {
        return new MoodHistory.Reading(date.atTime(12, 0).atZone(UTC).toInstant().toEpochMilli(), mood);
    }
}
