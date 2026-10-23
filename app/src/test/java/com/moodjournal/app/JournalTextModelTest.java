package com.moodjournal.app;

import static org.junit.Assert.*;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class JournalTextModelTest {
    private JournalTextModel model;
    @Before public void load() throws Exception { model = new JournalTextModel(RuntimeEnvironment.getApplication().getAssets().open("journal-text-model.tsv")); }
    @Test public void detectsWorkInAHeldOutSentenceIncludingNegation() {
        assertTrue(model.analyze("My boss piled on another deadline and scheduled overtime.", "", "").topics().contains("work"));
        assertTrue(model.analyze("I did not enjoy my job or the office meetings.", "", "").topics().contains("work"));
    }
    @Test public void moreThanOneThemeCanBePresentInAnEntry() {
        JournalTextModel.Reading reading = model.analyze("My manager scheduled a client meeting.", "I painted a picture and played guitar afterward.", "");
        assertTrue(reading.topics().contains("work")); assertTrue(reading.topics().contains("hobbies"));
    }
    @Test public void recognizesSocialSleepExerciseAndMoneyThemes() {
        assertTrue(model.analyze("My friends called me and we had a conversation.", "", "").topics().contains("social"));
        assertTrue(model.analyze("I lay awake and could not sleep last night.", "", "").topics().contains("sleep"));
        assertTrue(model.analyze("I completed a workout at the gym.", "", "").topics().contains("exercise"));
        assertTrue(model.analyze("The rent and credit card bills are overdue.", "", "").topics().contains("money"));
    }
    @Test public void simplePositiveAndNegativeToneAreDistinguished() {
        assertEquals(Double.valueOf(1), model.analyze("I am happy and grateful for this lovely day.", "I feel hopeful and supported.", "").sentiment());
        assertEquals(Double.valueOf(-1), model.analyze("I am overwhelmed, stressed and worried.", "I feel miserable and discouraged.", "").sentiment());
    }
    @Test public void negatedPositiveLanguageIsNotMistakenForPositiveTone() {
        assertNotEquals(Double.valueOf(1), model.analyze("I am not happy or pleased. I did not enjoy it.", "", "").sentiment());
    }
    @Test public void unknownAndNonEnglishTextRemainUnclassified() {
        JournalTextModel.Reading unknown = model.analyze("zqxv blort flimb", "", "");
        assertTrue(unknown.topics().isEmpty()); assertNull(unknown.sentiment());
        assertTrue(model.analyze("今天我有很多想法", "", "").topics().isEmpty());
    }
    @Test public void futureNeedsDoNotBecomeAnExperiencedSentiment() {
        assertNull(model.analyze("", "", "I wish I felt happy, joyful, calm and grateful.").sentiment());
        assertTrue(model.analyze("", "", "I need more sleep and an earlier bedtime.").topics().contains("sleep"));
    }
}
