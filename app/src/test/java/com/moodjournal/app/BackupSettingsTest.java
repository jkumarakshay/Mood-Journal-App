package com.moodjournal.app;

import static org.junit.Assert.*;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import java.util.function.Consumer;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class) @Config(sdk=35)
public class BackupSettingsTest {
    private Context context;
    @Before public void reset() {
        context=RuntimeEnvironment.getApplication();context.deleteDatabase("journal.db");
        for(String name:new String[]{"draft","journal_lock","appearance","backup_notices"})context.getSharedPreferences(name,0).edit().clear().commit();
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS);
        try(JournalStore store=new JournalStore(context)){store.add(4,"Private saved reflection","","Rest");}
    }

    @Test public void settingsEntryPreservesCheckInDraftAndDoesNotExposeSavedJournals() {
        try(ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity a=c.get();JournalTest.chooseMood(a,R.id.mood_3);((EditText)a.findViewById(R.id.entry_event)).setText("An unfinished check-in");
            open(a);assertEquals("Backup/Restore",title(a));assertNull(a.findViewById(R.id.backup_restore));assertNotNull(a.findViewById(R.id.unlock_submit));
            JournalTest.unlockJournal(a);assertNotNull(a.findViewById(R.id.backup_export));assertNotNull(a.findViewById(R.id.backup_restore));
            assertNull(a.findViewById(R.id.journal_add));assertFalse(contains(a.getWindow().getDecorView(),"Private saved reflection"));
            a.findViewById(R.id.checkin_tab).performClick();assertEquals("An unfinished check-in",((EditText)a.findViewById(R.id.entry_event)).getText().toString());
            try(JournalStore store=new JournalStore(context)){assertEquals(1,store.all().size());}
        }
    }

    @Test public void emptyInstallationCanRestoreWhileBackupIsDisabled() {
        context.deleteDatabase("journal.db");
        try(ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup()) {
            open(c.get());JournalTest.unlockJournal(c.get());
            assertFalse(c.get().findViewById(R.id.backup_export).isEnabled());assertTrue(c.get().findViewById(R.id.backup_restore).isEnabled());
            assertTrue(contains(c.get().getWindow().getDecorView(),"No saved journals or Heaps"));
            c.get().findViewById(R.id.backup_restore).performClick();
            assertEquals(Intent.ACTION_OPEN_DOCUMENT,Shadows.shadowOf(c.get()).getNextStartedActivityForResult().intent.getAction());
        }
    }

    @Test public void anEmptyHeapIsStillBackupData() {
        context.deleteDatabase("journal.db");
        try(JournalStore store=new JournalStore(context)){store.addHeap("A quiet space","",null,"A description worth keeping");}
        try(ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup()) {
            open(c.get());JournalTest.unlockJournal(c.get());assertTrue(c.get().findViewById(R.id.backup_export).isEnabled());
            assertFalse(contains(c.get().getWindow().getDecorView(),"A quiet space"));
        }
    }

    @Test public void backClosesPassphraseAndReturningRequiresFreshAuthentication() {
        try(ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity a=c.get();open(a);JournalTest.unlockJournal(a);a.findViewById(R.id.backup_export).performClick();
            AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();EditText password=dialog.findViewById(R.id.backup_password);
            password.setText("a private backup passphrase");Button stale=dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            a.findViewById(R.id.settings_back).performClick();idle();assertEquals("Settings",title(a));assertFalse(dialog.isShowing());assertEquals("",password.getText().toString());
            a.findViewById(R.id.settings_backup).performClick();assertNull(a.findViewById(R.id.backup_export));JournalTest.unlockJournal(a);
            password.setText("a private backup passphrase");stale.performClick();idle();assertNull(Shadows.shadowOf(a).peekNextStartedActivityForResult());
            a.findViewById(R.id.settings_back).performClick();a.findViewById(R.id.settings_back).performClick();assertNotNull(a.findViewById(R.id.save_entry));
        }
    }

    @Test public void backgroundAndRecreationKeepBackupSubpageButNeverItsUnlockedState() {
        try(ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity a=c.get();open(a);JournalTest.unlockJournal(a);a.findViewById(R.id.backup_export).performClick();
            AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();EditText password=dialog.findViewById(R.id.backup_password);password.setText("private passphrase");
            c.pause().stop();idle();assertFalse(dialog.isShowing());assertEquals("",password.getText().toString());assertNull(a.findViewById(R.id.backup_export));
            c.start().resume().visible();assertEquals("Backup/Restore",title(a));assertNotNull(a.findViewById(R.id.unlock_submit));JournalTest.unlockJournal(a);
            assertNotNull(a.findViewById(R.id.backup_restore));c.recreate();assertEquals("Backup/Restore",title(c.get()));
            assertNull(c.get().findViewById(R.id.backup_restore));assertNotNull(c.get().findViewById(R.id.unlock_submit));
        }
    }

    @Test public void backupUnlockDoesNotUnlockJournalOrAnalyticsAndSettingsBackDoesNotBypassThem() {
        try(ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity a=c.get();a.findViewById(R.id.journal_tab).performClick();JournalTest.unlockJournal(a);assertNotNull(a.findViewById(R.id.journal_add));
            open(a);assertNotNull(a.findViewById(R.id.unlock_submit));JournalTest.unlockJournal(a);assertNotNull(a.findViewById(R.id.backup_restore));
            a.findViewById(R.id.settings_back).performClick();a.findViewById(R.id.settings_back).performClick();
            assertNotNull(a.findViewById(R.id.unlock_submit));assertNull(a.findViewById(R.id.journal_add));
            open(a);JournalTest.unlockJournal(a);AnalyticsTestSupport.graph(a);assertNotNull(a.findViewById(R.id.unlock_submit));assertNull(a.findViewById(R.id.mood_chart));
        }
    }

    @Test @Config(sdk=26) public void backupSettingsCanBeOpenedAndRelockedOnAndroidEight() {
        try(ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup()) {
            open(c.get());JournalTest.unlockJournal(c.get());assertNotNull(c.get().findViewById(R.id.backup_restore));
            c.pause().stop().start().resume();assertEquals("Backup/Restore",title(c.get()));assertNull(c.get().findViewById(R.id.backup_restore));
            JournalTest.unlockJournal(c.get());assertNotNull(c.get().findViewById(R.id.backup_export));
        }
    }

    @Test public void biometricPrimaryAutomaticallyUnlocksOnlyTheBackupContext() throws Exception {
        PinLock lock=new PinLock(context);lock.setPin("642081".toCharArray());lock.setBiometricPrimary(true);
        try(ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity a=c.get();JournalGate original=ReflectionHelpers.getField(a,"gate");Runnable accepted=ReflectionHelpers.getField(original,"unlocked");original.close();
            FakeBiometrics bio=new FakeBiometrics();JournalGate gate=new JournalGate(a,lock,bio,accepted);ReflectionHelpers.setField(a,"gate",gate);
            open(a);idle();assertEquals(1,bio.prompts);assertNull(a.findViewById(R.id.backup_export));Runnable old=bio.success;bio.success.run();
            assertNotNull(a.findViewById(R.id.backup_export));assertNull(a.findViewById(R.id.journal_add));
            a.findViewById(R.id.journal_tab).performClick();idle();assertEquals(2,bio.prompts);old.run();assertNull(a.findViewById(R.id.journal_add));
            bio.success.run();assertNotNull(a.findViewById(R.id.journal_add));assertNull(a.findViewById(R.id.backup_export));
        }
    }

    private static final class FakeBiometrics implements JournalGate.Biometrics {
        int prompts;Runnable success;
        public boolean available(){return true;}
        public void authenticate(Runnable accepted,Consumer<JournalGate.Failure> error){prompts++;success=accepted;}
        public void cancel(){}
    }
    private void open(MainActivity a){a.findViewById(R.id.settings_button).performClick();a.findViewById(R.id.settings_backup).performClick();}
    private String title(MainActivity a){return ((TextView)a.findViewById(R.id.settings_title)).getText().toString();}
    private void idle(){Shadows.shadowOf(Looper.getMainLooper()).idle();}
    private boolean contains(View view,String value){if(view instanceof TextView text&&text.getText().toString().contains(value))return true;if(view instanceof ViewGroup group)for(int i=0;i<group.getChildCount();i++)if(contains(group.getChildAt(i),value))return true;return false;}
}
