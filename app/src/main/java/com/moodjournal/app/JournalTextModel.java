package com.moodjournal.app;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A compact TF-IDF centroid text model, trained from bundled, reviewable English examples.
 * No text leaves the device. Unknown and ambiguous text is allowed to remain unclassified. */
final class JournalTextModel {
    static final String VERSION = "english-centroids-1";
    static final Map<String, String> TOPICS;
    private static final Set<String> STOP = new HashSet<>(java.util.Arrays.asList(
            "a an the i me my we our you your it its to of in on at is are am was were be been being have has had this that these those today yesterday very really just some one felt feel feeling feels and or for with as from about after before do did does so than then there here will would can could should".split(" ")));
    private static final Pattern WORD = Pattern.compile("[a-z]+(?:'[a-z]+)?");
    private final Map<String, Double> idf = new HashMap<>();
    private final Map<String, Map<String, Double>> centroids = new LinkedHashMap<>();
    record Reading(Set<String> topics, Double sentiment) {}

    static {
        Map<String, String> names = new LinkedHashMap<>();
        names.put("work", "work and responsibilities"); names.put("relationships", "relationships");
        names.put("family", "family"); names.put("social", "social connection");
        names.put("sleep", "rest and sleep"); names.put("exercise", "exercise and movement");
        names.put("outdoors", "time outdoors"); names.put("health", "physical health");
        names.put("money", "money"); names.put("study", "study and learning");
        names.put("hobbies", "creativity and hobbies"); names.put("mindfulness", "mindfulness");
        TOPICS = Collections.unmodifiableMap(names);
    }

    JournalTextModel(InputStream source) throws IOException {
        List<Map<String, Integer>> documents = new ArrayList<>(); List<String> labels = new ArrayList<>();
        Map<String, Integer> frequencies = new HashMap<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(source, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty() || line.startsWith("#")) continue;
                int tab = line.indexOf('\t'); if (tab < 1) throw new IOException("Invalid text model data");
                String label = line.substring(0, tab);
                if (!TOPICS.containsKey(label) && !label.equals("positive") && !label.equals("negative") && !label.equals("neutral")) throw new IOException("Unknown model label");
                Map<String, Integer> terms = terms(line.substring(tab + 1), !TOPICS.containsKey(label)); documents.add(terms); labels.add(label);
                for (String word : terms.keySet()) frequencies.merge(word, 1, Integer::sum);
            }
        }
        if (documents.size() < 40) throw new IOException("Text model data is incomplete");
        for (Map.Entry<String, Integer> word : frequencies.entrySet()) idf.put(word.getKey(), Math.log((documents.size() + 1.0) / (word.getValue() + 1.0)) + 1);
        for (int i = 0; i < documents.size(); i++) {
            Map<String, Double> vector = vector(documents.get(i)); Map<String, Double> centroid = centroids.computeIfAbsent(labels.get(i), ignored -> new HashMap<>());
            for (Map.Entry<String, Double> term : vector.entrySet()) centroid.merge(term.getKey(), term.getValue(), Double::sum);
        }
        for (Map<String, Double> centroid : centroids.values()) normalize(centroid);
        for (String topic : TOPICS.keySet()) if (!centroids.containsKey(topic)) throw new IOException("Missing text topic");
        if (!centroids.keySet().containsAll(java.util.Arrays.asList("positive", "negative", "neutral"))) throw new IOException("Missing sentiment classes");
    }

    Reading analyze(String event, String thoughts, String needs) {
        Set<String> topics = new HashSet<>();
        String text = event + "\n" + thoughts + "\n" + needs;
        for (String sentence : text.split("[.!?;\\n]+|\\b(?:but|although|whereas)\\b")) {
            Map<String, Integer> terms = terms(sentence, false); Map<String, Double> vector = vector(terms);
            int known = 0; for (String term : terms.keySet()) if (idf.containsKey(term) && !term.startsWith("b:")) known++;
            if (known == 0) continue;
            double best = 0;
            for (String topic : TOPICS.keySet()) best = Math.max(best, score(vector, centroids.get(topic)));
            if (best < .12) continue;
            for (String topic : TOPICS.keySet()) {
                double similarity = score(vector, centroids.get(topic));
                if (similarity >= .12 && similarity >= best * .72) topics.add(topic);
            }
        }
        // Wishes in "what would help" do not count as the tone of an experienced event.
        Map<String, Integer> moodTerms = terms(event + "\n" + thoughts, true); Map<String, Double> moodVector = vector(moodTerms);
        double positive = score(moodVector, centroids.get("positive"));
        double negative = score(moodVector, centroids.get("negative"));
        double neutral = score(moodVector, centroids.get("neutral"));
        Double sentiment = null;
        if (Math.max(positive, negative) >= .20 && Math.abs(positive - negative) >= .075 && Math.max(positive, negative) > neutral + .025)
            sentiment = positive > negative ? 1.0 : -1.0;
        return new Reading(Collections.unmodifiableSet(topics), sentiment);
    }

    private Map<String, Double> vector(Map<String, Integer> terms) {
        Map<String, Double> result = new HashMap<>();
        for (Map.Entry<String, Integer> term : terms.entrySet()) {
            Double weight = idf.get(term.getKey());
            if (weight != null) result.put(term.getKey(), (1 + Math.log(term.getValue())) * weight);
        }
        normalize(result); return result;
    }
    private static void normalize(Map<String, Double> vector) {
        double magnitude = 0; for (double weight : vector.values()) magnitude += weight * weight;
        double norm = Math.sqrt(magnitude); if (norm != 0) vector.replaceAll((term, weight) -> weight / norm);
    }
    private static double score(Map<String, Double> vector, Map<String, Double> centroid) {
        double result = 0; for (Map.Entry<String, Double> term : vector.entrySet()) result += term.getValue() * centroid.getOrDefault(term.getKey(), 0.0);
        return result;
    }
    private static Map<String, Integer> terms(String text, boolean negationAware) {
        Map<String, Integer> result = new HashMap<>();
        String normalized = text.toLowerCase(Locale.ROOT).replace('’', '\'').replaceAll("n't\\b", " not");
        Matcher words = WORD.matcher(normalized); int negation = 0, end = 0; String previous = null;
        while (words.find()) {
            if (normalized.substring(end, words.start()).matches("(?s).*[.!?;\\n].*")) { negation = 0; previous = null; }
            end = words.end();
            String word = words.group();
            if (word.equals("not") || word.equals("never") || word.equals("no") || word.equals("without")) { negation = 3; previous = null; continue; }
            if (word.equals("but") || word.equals("however")) { negation = 0; previous = null; continue; }
            if (STOP.contains(word) || word.length() < 2) continue;
            if (word.length() > 5 && word.endsWith("ing")) word = word.substring(0, word.length() - 3);
            else if (word.length() > 4 && word.endsWith("ed")) word = word.substring(0, word.length() - 2);
            else if (word.length() > 4 && word.endsWith("s") && !word.endsWith("ss")) word = word.substring(0, word.length() - 1);
            String feature = negationAware && negation > 0 ? "not:" + word : word;
            result.merge(feature, 1, Integer::sum);
            if (previous != null) result.merge("b:" + previous + ":" + feature, 1, Integer::sum);
            previous = feature; if (negation > 0) negation--;
        }
        return result;
    }
}
