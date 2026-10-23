package com.moodjournal.app;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.Predicate;

/** Daily observations and conservative comparisons; missing data is never a zero mood. */
final class MoodAnalysis {
    static final int VERSION = 1, REQUIRED_DAYS = 30;
    record Insight(String title, String detail, int days, int comparisonDays, double difference, double evidence) {}
    record Section(String title, String context, List<Insight> insights) {}
    record Report(boolean eligible, int recordedDays, int totalEntries, int weatherDays, int classifiedDays,
                  LocalDate from, LocalDate through, long generatedAt, long nextReviewAt, List<Section> sections) {}
    private static final class Day {
        final LocalDate date;
        double mood, sentiment, high, low;
        int entries, sentiments, weatherSamples;
        final Map<String, Integer> weather = new HashMap<>();
        final Set<String> topics = new HashSet<>();
        String condition;
        double minHigh = Double.POSITIVE_INFINITY, maxHigh = Double.NEGATIVE_INFINITY;
        Day(LocalDate date) { this.date = date; }
        double mean() { return mood / entries; }
        Double tone() { return sentiments == 0 ? null : sentiment / sentiments; }
    }

    static Report build(List<JournalStore.Entry> entries, List<JournalHeap> heaps, long now, ZoneId zone, JournalTextModel model) {
        LocalDate today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate(), from = today.minusDays(89);
        TreeMap<LocalDate, Day> all = new TreeMap<>();
        Map<String, JournalHeap> heapById = new HashMap<>(); for (JournalHeap heap : heaps) heapById.put(heap.id(), heap);
        int total = 0;
        for (JournalStore.Entry entry : entries) {
            if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException("Review stopped");
            if (entry.createdAt() > now || entry.mood() < 1 || entry.mood() > 5) continue;
            LocalDate date = Instant.ofEpochMilli(entry.createdAt()).atZone(zone).toLocalDate();
            if (date.isAfter(today)) continue;
            Day day = all.computeIfAbsent(date, Day::new); day.mood += entry.mood(); day.entries++; total++;
            if (date.isBefore(from)) continue;
            JournalTextModel.Reading reading = model.analyze(entry.event(), entry.thoughts(), entry.needs());
            day.topics.addAll(reading.topics());
            if (reading.sentiment() != null) { day.sentiment += reading.sentiment(); day.sentiments++; }
            // Heap/category mentions provide structured evidence alongside the text model.
            JournalHeap heap = heapById.get(entry.heapId());
            if (heap != null) day.topics.addAll(model.analyze(heap.name() + ". " + heap.category(), "", "").topics());
            WeatherSnapshot snapshot = entry.weather();
            if (snapshot != null && snapshot.availableAt(now) && snapshot.date().equals(date.toString())) {
                String condition = condition(snapshot.description());
                if (condition != null) {
                    day.weather.merge(condition, 1, Integer::sum); day.high += snapshot.highC(); day.low += snapshot.lowC(); day.weatherSamples++;
                    day.minHigh = Math.min(day.minHigh, snapshot.highC()); day.maxHigh = Math.max(day.maxHigh, snapshot.highC());
                }
            }
        }
        List<Day> recent = new ArrayList<>(all.tailMap(from, true).values());
        int weatherDays = 0, classified = 0;
        for (Day day : recent) {
            if (day.tone() != null) classified++;
            List<Map.Entry<String, Integer>> conditions = new ArrayList<>(day.weather.entrySet());
            conditions.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
            if (!conditions.isEmpty() && day.maxHigh - day.minHigh <= 5 && (conditions.size() == 1 || conditions.get(0).getValue() > conditions.get(1).getValue())) {
                day.condition = conditions.get(0).getKey(); day.high /= day.weatherSamples; day.low /= day.weatherSamples; weatherDays++;
            }
        }
        boolean eligible = recent.size() >= REQUIRED_DAYS;
        long next = Instant.ofEpochMilli(now).atZone(zone).plusWeeks(1).toInstant().toEpochMilli();
        List<Section> sections = new ArrayList<>();
        if (eligible) {
            sections.add(weather(recent, weatherDays)); sections.add(responses(recent)); sections.add(time(recent, all, today));
        }
        return new Report(eligible, recent.size(), total, weatherDays, classified, from, today, now, next, Collections.unmodifiableList(sections));
    }

    private static Section weather(List<Day> days, int coverage) {
        List<Day> available = new ArrayList<>(); for (Day day : days) if (day.condition != null) available.add(day);
        List<Insight> candidates = new ArrayList<>();
        Map<String, String> groups = new LinkedHashMap<>(); groups.put("clear", "clear or partly sunny days"); groups.put("cloudy", "cloudy days");
        groups.put("rain", "rainy or drizzly days"); groups.put("snow", "snowy days"); groups.put("fog", "foggy days"); groups.put("storm", "stormy days");
        for (Map.Entry<String, String> group : groups.entrySet()) {
            compare(available, day -> group.getKey().equals(day.condition), "Mood on " + group.getValue(), "On " + group.getValue(), "other days with weather recorded", 5, candidates);
        }
        if (available.size() >= 20) {
            List<Double> highs = new ArrayList<>(); for (Day day : available) highs.add(day.high); Collections.sort(highs);
            double cool = highs.get((highs.size() - 1) / 3), warm = highs.get((highs.size() - 1) * 2 / 3);
            if (warm - cool >= 5) {
                compare(available, day -> day.high >= warm, "Mood on warmer days", "On relatively warmer forecast days", "other days with weather recorded", 5, candidates);
                compare(available, day -> day.high <= cool, "Mood on cooler days", "On relatively cooler forecast days", "other days with weather recorded", 5, candidates);
            }
            List<Double> lows = new ArrayList<>(); for (Day day : available) lows.add(day.low); Collections.sort(lows);
            double coldNight = lows.get((lows.size() - 1) / 3), warmNight = lows.get((lows.size() - 1) * 2 / 3);
            if (warmNight - coldNight >= 5) {
                compare(available, day -> day.low >= warmNight, "Mood with warmer forecast lows", "On days with relatively warmer forecast lows", "other days with weather recorded", 5, candidates);
                compare(available, day -> day.low <= coldNight, "Mood with cooler forecast lows", "On days with relatively cooler forecast lows", "other days with weather recorded", 5, candidates);
            }
        }
        List<Insight> found = supported(candidates, 3, 10);
        String context = coverage + " of " + days.size() + " recorded days have usable, same-day weather. Forecasts describe the day, not a cause of your feelings.";
        if (coverage < 10) context += " More days with weather are needed before making comparisons.";
        else if (found.isEmpty()) context += " No clear weather association stands out yet.";
        return new Section("Weather-related patterns", context, found);
    }

    private static Section responses(List<Day> days) {
        List<Insight> candidates = new ArrayList<>(); List<Day> recognized = new ArrayList<>();
        for (Day day : days) if (!day.topics.isEmpty()) recognized.add(day);
        for (Map.Entry<String, String> topic : JournalTextModel.TOPICS.entrySet()) {
            compare(recognized, day -> day.topics.contains(topic.getKey()), "Writing about " + topic.getValue(), "On days you wrote about " + topic.getValue(), "other days with recognized themes", 5, candidates);
        }
        List<Insight> found = supported(candidates, 4, JournalTextModel.TOPICS.size());
        String context = "Themes were recognized on " + recognized.size() + " of " + days.size() + " recorded days using your writing and Heap labels. Comparisons leave unclassified days out. Mentions can describe experiences, worries or wishes.";
        if (found.isEmpty()) context += " No recurring theme has enough consistent mood evidence yet.";
        return new Section("Response-related patterns", context, found);
    }

    private static Section time(List<Day> recent, TreeMap<LocalDate, Day> all, LocalDate today) {
        List<Insight> candidates = new ArrayList<>();
        for (DayOfWeek weekday : DayOfWeek.values()) {
            String name = weekday.getDisplayName(TextStyle.FULL, Locale.getDefault());
            compare(recent, day -> day.date.getDayOfWeek() == weekday, "Mood on " + name + "s", "On " + name + "s", "other days of the week", 4, candidates);
        }
        Set<YearMonth> months = new HashSet<>(); for (Day day : recent) months.add(YearMonth.from(day.date));
        if (months.size() >= 3) {
            for (int week = 1; week <= 5; week++) {
                final int part = week; Set<YearMonth> present = new HashSet<>();
                for (Day day : recent) if ((day.date.getDayOfMonth() - 1) / 7 + 1 == part) present.add(YearMonth.from(day.date));
                if (present.size() < 3) continue;
                String span = part == 5 ? "days 29 to month-end" : "days " + ((part - 1) * 7 + 1) + "–" + (part * 7);
                compare(recent, day -> (day.date.getDayOfMonth() - 1) / 7 + 1 == part, "Week " + part + " of the month", "During " + span, "other recorded days", 5, candidates);
            }
        }
        boolean yearly = !all.isEmpty() && !all.firstKey().plusYears(1).isAfter(today);
        if (yearly) {
            List<Day> annual = new ArrayList<>(all.tailMap(today.minusYears(3), true).values());
            for (int month = 1; month <= 12; month++) {
                int selected = month; Set<Integer> years = new HashSet<>();
                for (Day day : annual) if (day.date.getMonthValue() == selected) years.add(day.date.getYear());
                if (years.size() < 2) continue;
                String name = java.time.Month.of(month).getDisplayName(TextStyle.FULL, Locale.getDefault());
                compare(annual, day -> day.date.getMonthValue() == selected, "Mood in " + name, "In recorded " + name + " days", "other months", 8, candidates);
            }
        }
        List<Insight> found = supported(candidates, 4, 24);
        String context = "Each recorded day has equal weight. Missing days are left out. Week-of-month comparisons need three recorded months; month-of-year comparisons need repeated months across more than a year.";
        if (found.isEmpty()) context += " No consistent time-related lift or dip stands out yet.";
        return new Section("Time-related patterns", context, found);
    }

    private static void compare(List<Day> days, Predicate<Day> belongs, String title, String lead, String baseline, int minimum, List<Insight> candidates) {
        List<Day> group = new ArrayList<>(), others = new ArrayList<>();
        for (Day day : days) (belongs.test(day) ? group : others).add(day);
        if (group.size() < minimum || others.size() < Math.max(8, minimum)) return;
        double a = mean(group, Day::mean), b = mean(others, Day::mean), difference = a - b;
        double probability = AnalysisStatistics.probability(a, variance(group, a, Day::mean), group.size(), b, variance(others, b, Day::mean), others.size());
        List<Day> withTone = new ArrayList<>(), otherTone = new ArrayList<>();
        for (Day day : group) if (day.tone() != null) withTone.add(day);
        for (Day day : others) if (day.tone() != null) otherTone.add(day);
        if (Math.abs(difference) >= .5 && probability <= .05) {
            String direction = difference > 0 ? "higher" : "lower";
            String detail = String.format(Locale.getDefault(), "%s, your daily mood averaged %.1f/5 across %d days, compared with %.1f/5 across %d %s. This is an association with %s recorded moods.",
                    lead, a, group.size(), b, others.size(), baseline, direction);
            if (withTone.size() >= 5 && otherTone.size() >= 8) {
                double tone = mean(withTone, day -> day.tone()) - mean(otherTone, day -> day.tone());
                if (Math.abs(tone) >= .5 && Math.signum(tone) == Math.signum(difference))
                    detail += " The wording on those days also tended to sound " + (tone > 0 ? "more positive" : "more negative") + "; written tone is an estimate.";
            }
            candidates.add(new Insight(title, detail, group.size(), others.size(), difference, probability));
        } else if (withTone.size() >= 5 && otherTone.size() >= 8) {
            // Written sentiment can vary even when a person's selected mood stays unchanged.
            double first = mean(withTone, day -> day.tone()), second = mean(otherTone, day -> day.tone());
            double toneDifference = first - second;
            double toneProbability = AnalysisStatistics.probability(first, variance(withTone, first, day -> day.tone()), withTone.size(), second, variance(otherTone, second, day -> day.tone()), otherTone.size());
            if (Math.abs(toneDifference) < .5 || toneProbability > .05) return;
            String toneTitle = title.startsWith("Mood ") ? "Written tone " + title.substring(5) : "Written tone: " + title;
            String detail = String.format(Locale.getDefault(), "%s, your writing tended to sound %s than on %s, comparing %d with %d days of confidently classified text. Your selected moods did not show a separate supported lift or dip for this comparison. Written tone is a language estimate, not a conclusion about how you felt.",
                    lead, toneDifference > 0 ? "more positive" : "more negative", baseline, withTone.size(), otherTone.size());
            candidates.add(new Insight(toneTitle, detail, withTone.size(), otherTone.size(), toneDifference, toneProbability));
        }
    }

    private static List<Insight> supported(List<Insight> candidates, int limit, int plannedComparisons) {
        candidates.removeIf(insight -> insight.evidence() > .05 / (plannedComparisons * 2));
        candidates.sort(Comparator.comparingDouble(Insight::evidence).thenComparing(insight -> -Math.abs(insight.difference())));
        return Collections.unmodifiableList(new ArrayList<>(candidates.subList(0, Math.min(limit, candidates.size()))));
    }
    private static double mean(List<Day> days, Function<Day, Double> value) { double sum = 0; for (Day day : days) sum += value.apply(day); return sum / days.size(); }
    private static double variance(List<Day> days, double average, Function<Day, Double> value) { double sum = 0; for (Day day : days) { double diff = value.apply(day) - average; sum += diff * diff; } return sum / (days.size() - 1); }
    private static String condition(String description) {
        String value = description.toLowerCase(Locale.ROOT);
        if (value.contains("thunder") || value.contains("storm")) return "storm";
        if (value.contains("snow")) return "snow";
        if (value.contains("rain") || value.contains("drizz")) return "rain";
        if (value.contains("fog")) return "fog";
        if (value.contains("sunny") || value.contains("clear")) return "clear";
        if (value.contains("cloud")) return "cloudy";
        return null;
    }
}
