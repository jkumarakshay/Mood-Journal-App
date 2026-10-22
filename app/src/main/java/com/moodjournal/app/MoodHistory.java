package com.moodjournal.app;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/** Calendar-day grouping. Missing observations never become numeric mood values. */
final class MoodHistory {
    enum Period {
        WEEK("1 week"), MONTH("1 month"), THREE_MONTHS("3 months"), YEAR("1 year"), CUSTOM("Custom dates");
        final String label;
        Period(String label) { this.label = label; }
        LocalDate start(LocalDate today) {
            return switch (this) {
                case WEEK -> today.minusDays(6);
                case MONTH, CUSTOM -> today.minusMonths(1).plusDays(1);
                case THREE_MONTHS -> today.minusMonths(3).plusDays(1);
                case YEAR -> today.minusYears(1).plusDays(1);
            };
        }
    }

    enum Grouping { DAILY, WEEKLY, MONTHLY }
    record Reading(long timestamp, int mood) {}
    record Bucket(LocalDate start, LocalDate end, Double average, int responses, int recordedDays) {}
    record Series(LocalDate start, LocalDate end, Grouping grouping, List<Bucket> buckets,
                  int responses, int recordedDays) {
        long totalDays() { return ChronoUnit.DAYS.between(start, end) + 1; }
    }

    static Series summarize(List<Reading> readings, LocalDate start, LocalDate end, ZoneId zone) {
        if (end.isBefore(start)) throw new IllegalArgumentException("End date must be on or after start date");
        Grouping grouping = !start.isBefore(end.minusMonths(1).plusDays(1)) ? Grouping.DAILY
                : !start.isBefore(end.minusMonths(3).plusDays(1)) ? Grouping.WEEKLY : Grouping.MONTHLY;
        TreeMap<LocalDate, long[]> days = new TreeMap<>();
        for (Reading reading : readings) {
            if (reading.mood() < 1 || reading.mood() > 5) throw new IllegalArgumentException("Invalid mood");
            LocalDate date = java.time.Instant.ofEpochMilli(reading.timestamp()).atZone(zone).toLocalDate();
            if (date.isBefore(start) || date.isAfter(end)) continue;
            long[] totals = days.computeIfAbsent(date, ignored -> new long[2]);
            totals[0] += reading.mood();
            totals[1]++;
        }
        List<Bucket> buckets = new ArrayList<>();
        int responseCount = 0;
        for (LocalDate first = start; !first.isAfter(end); ) {
            LocalDate last = switch (grouping) {
                case DAILY -> first;
                case WEEKLY -> first.plusDays(6);
                case MONTHLY -> first.withDayOfMonth(first.lengthOfMonth());
            };
            if (last.isAfter(end)) last = end;
            long sum = 0;
            int count = 0, recordedDays = 0;
            for (long[] totals : days.subMap(first, true, last, true).values()) {
                sum += totals[0];
                count += (int) totals[1];
                recordedDays++;
            }
            buckets.add(new Bucket(first, last, count == 0 ? null : (double) sum / count, count, recordedDays));
            responseCount += count;
            first = last.plusDays(1);
        }
        return new Series(start, end, grouping, java.util.Collections.unmodifiableList(buckets), responseCount, days.size());
    }
}
