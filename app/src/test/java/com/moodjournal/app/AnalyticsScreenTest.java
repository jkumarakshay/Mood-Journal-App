package com.moodjournal.app;

import static org.junit.Assert.*;
import android.content.Context;
import android.os.Looper;
import android.view.View;
import android.widget.PopupMenu;
import android.widget.TextView;
import java.time.ZoneId;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowPopupMenu;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35)
public class AnalyticsScreenTest {
    private Context context;
    @Before public void reset() {
        context=RuntimeEnvironment.getApplication();context.deleteDatabase("journal.db");
        for(String name:new String[]{"draft","journal_lock","appearance"}) context.getSharedPreferences(name,Context.MODE_PRIVATE).edit().clear().commit();
    }
    @Test public void tabShowsTwoMenuChoicesAndProtectsBothViews() {
        try(ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity a=c.get();assertEquals("Analytics",((TextView)a.findViewById(R.id.history_tab)).getText().toString());
            a.findViewById(R.id.history_tab).performClick();PopupMenu menu=ShadowPopupMenu.getLatestPopupMenu();
            assertEquals(2,menu.getMenu().size());assertEquals("Mood Graph",menu.getMenu().getItem(0).getTitle());assertEquals("Mood Analysis",menu.getMenu().getItem(1).getTitle());menu.dismiss();
            assertNotNull(a.findViewById(R.id.save_entry));
            AnalyticsTestSupport.analysis(a);assertNull(a.findViewById(R.id.analysis_title));assertNotNull(a.findViewById(R.id.unlock_submit));
            JournalTest.unlockJournal(a);assertNotNull(a.findViewById(R.id.analysis_title));
            AnalyticsTestSupport.graph(a);assertNotNull(a.findViewById(R.id.mood_chart));assertNull(a.findViewById(R.id.analysis_title));
            a.findViewById(R.id.checkin_tab).performClick();AnalyticsTestSupport.analysis(a);assertNotNull(a.findViewById(R.id.unlock_submit));assertNull(a.findViewById(R.id.analysis_title));
        }
    }
    @Test public void waitingStateCountsDistinctDaysAndDoesNotClaimPatterns() throws Exception {
        AnalysisRepositoryTest.seed(context,System.currentTimeMillis(),ZoneId.systemDefault(),12);
        try(ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity a=c.get();AnalyticsTestSupport.analysis(a);JournalTest.unlockJournal(a);await(a,R.id.analysis_status,"12 / 30");
            assertNull(a.findViewById(R.id.analysis_weather));assertNull(a.findViewById(R.id.mood_chart));
        }
    }
    @Test public void reportHasThreeSectionsAndLocksOnBackgroundAndRecreation() throws Exception {
        AnalysisRepositoryTest.seed(context,System.currentTimeMillis(),ZoneId.systemDefault(),40);
        try(ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity a=c.get();AnalyticsTestSupport.analysis(a);JournalTest.unlockJournal(a);await(a,R.id.analysis_weather,null);
            assertNotNull(a.findViewById(R.id.analysis_responses));assertNotNull(a.findViewById(R.id.analysis_time));
            assertTrue(((TextView)a.findViewById(R.id.analysis_status)).getText().toString().contains("Next weekly review"));
            c.pause().stop().start().resume().visible();assertNull(a.findViewById(R.id.analysis_weather));assertNotNull(a.findViewById(R.id.unlock_submit));
            JournalTest.unlockJournal(a);await(a,R.id.analysis_weather,null);c.recreate();
            assertNull(c.get().findViewById(R.id.analysis_title));assertNotNull(c.get().findViewById(R.id.unlock_submit));
            JournalTest.unlockJournal(c.get());await(c.get(),R.id.analysis_weather,null);
        }
    }
    @Test public void dismissedMenuCannotNavigateWhileBackgrounded() {
        try(ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity a=c.get();a.findViewById(R.id.history_tab).performClick();PopupMenu menu=ShadowPopupMenu.getLatestPopupMenu();c.pause();
            assertFalse(Shadows.shadowOf(menu).getOnMenuItemClickListener().onMenuItemClick(menu.getMenu().findItem(R.id.analytics_analysis)));
            assertNull(a.findViewById(R.id.analysis_title));
        }
    }
    static void await(MainActivity a,int id,String expected) throws Exception {
        long until=System.nanoTime()+10_000_000_000L;
        while(System.nanoTime()<until) {
            Shadows.shadowOf(Looper.getMainLooper()).idle();View v=a.findViewById(id);
            if(v!=null&&(expected==null||((TextView)v).getText().toString().contains(expected))) return;
            Thread.sleep(20);
        }
        View found=a.findViewById(id);
        fail("Analysis did not render "+id+" / "+expected+"; actual="+(found instanceof TextView?((TextView)found).getText():found));
    }
}
