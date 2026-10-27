package com.moodjournal.app;

import static org.junit.Assert.*;
import android.app.Application;
import android.app.AlertDialog;
import android.content.Context;
import android.os.Looper;
import android.widget.EditText;
import android.widget.TextView;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowSystemClock;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class) @Config(sdk=35,application=Application.class)
public class GoogleAccountScreenTest {
    Context context;
    @Before public void reset(){
        context=RuntimeEnvironment.getApplication();context.deleteDatabase("journal.db");
        for(String name:new String[]{GoogleAccountStore.PREFS,"journal_lock","draft","appearance","reminder","weather",OnboardingState.PREFS})context.getSharedPreferences(name,0).edit().clear().commit();
        context.getSharedPreferences(OnboardingState.PREFS,0).edit().putInt("step",OnboardingState.COMPLETE).commit();
        context.getSharedPreferences(UpdateController.PREFS,0).edit().putBoolean("automatic",false).commit();
    }
    @Test public void initialGoogleSignInIsRequiredBeforeAnyAppTabIsAvailable(){
        try(Session s=new Session()){
            assertNotNull(s.a.findViewById(R.id.google_sign_in));assertNull(s.a.findViewById(R.id.checkin_tab));assertNull(s.a.findViewById(R.id.settings_button));
            s.a.findViewById(R.id.google_sign_in).performClick();s.auth.succeed(GoogleIdentityTest.profile());
            assertTrue(new GoogleAccountStore(context).linked());assertNotNull(s.a.findViewById(R.id.entry_event));assertNull(s.a.findViewById(R.id.account_screen));
        }
    }
    @Test public void firstInstallWelcomeAndTourFollowGoogleSignIn(){
        context.getSharedPreferences(OnboardingState.PREFS,0).edit().clear().commit();
        try(Session s=new Session()){
            assertNull(s.a.findViewById(R.id.welcome_title));s.signIn();assertNotNull(s.a.findViewById(R.id.welcome_title));assertNull(s.a.findViewById(R.id.journal_tab));
            s.a.findViewById(R.id.welcome_thanks).performClick();assertNotNull(s.a.findViewById(R.id.entry_event));
        }
    }
    @Test public void existingPinMustBeUnlockedBeforeAnAccountCanOwnExistingData()throws Exception{
        setupPin();try(JournalStore store=new JournalStore(context)){store.add(3,"Existing private journal","","Rest");}
        try(Session s=new Session()){
            s.signIn();assertFalse(new GoogleAccountStore(context).linked());assertNotNull(s.a.findViewById(R.id.unlock_pin));assertNull(s.a.findViewById(R.id.journal_tab));
            ((EditText)s.a.findViewById(R.id.unlock_pin)).setText("111111");s.a.findViewById(R.id.unlock_submit).performClick();JournalTest.await(()->s.a.findViewById(R.id.unlock_submit).isEnabled());
            assertFalse(new GoogleAccountStore(context).linked());assertNull(s.a.findViewById(R.id.pin_reset));
            JournalTest.unlockJournal(s.a);assertTrue(new GoogleAccountStore(context).linked());
            try(JournalStore store=new JournalStore(context)){assertEquals("Existing private journal",store.all().get(0).event());}
        }
    }
    @Test public void signedInReturningUserWorksOfflineAndJournalStillNeedsLocalUnlock()throws Exception{
        setupOwner();try(Session s=new Session()){
            assertNotNull(s.a.findViewById(R.id.checkin_tab));assertEquals(0,s.auth.starts);
            s.a.findViewById(R.id.journal_tab).performClick();assertNotNull(s.a.findViewById(R.id.unlock_submit));assertNull(s.a.findViewById(R.id.journal_add));
            JournalTest.unlockJournal(s.a);assertNotNull(s.a.findViewById(R.id.journal_add));assertEquals(0,s.auth.starts);
        }
    }
    @Test public void differentGoogleAccountCannotResetPinOrReplaceOwner()throws Exception{
        setupOwner();try(Session s=new Session()){
            s.recover();s.a.findViewById(R.id.google_sign_in).performClick();s.auth.succeed(new GoogleAccountStore.Profile(GoogleIdentityTest.PROJECT,"someone-else","99999999999",GoogleIdentityTest.EMAIL));
            assertNull(showing());assertTrue(((TextView)s.a.findViewById(R.id.account_status)).getText().toString().contains("isn’t"));
            assertEquals(GoogleIdentityTest.UID,new GoogleAccountStore(context).owner().uid());assertTrue(new PinLock(context).verify("642081".toCharArray()));
        }
    }
    @Test public void verifiedOriginalAccountCanResetPinWithoutLosingJournalsHeapsOrBiometricPreference()throws Exception{
        setupOwner();try(JournalStore store=new JournalStore(context)){JournalHeap heap=store.addHeap("Life","Personal",0xFF123456,"A purpose");store.add(3,"Keep me","Feeling","Rest",heap.id());}
        try(Session s=new Session()){
            AlertDialog popup=s.newPin();enter(popup,"915307","915307");popup.findViewById(R.id.pin_reset_save).performClick();JournalTest.await(()->!popup.isShowing());
            PinLock lock=new PinLock(context);assertFalse(lock.verify("642081".toCharArray()));assertTrue(lock.verify("915307".toCharArray()));assertFalse(lock.biometricPrimary());
            assertNotNull(s.a.findViewById(R.id.unlock_submit));assertNull(s.a.findViewById(R.id.journal_add));
            ((EditText)s.a.findViewById(R.id.unlock_pin)).setText("915307");s.a.findViewById(R.id.unlock_submit).performClick();JournalTest.await(()->s.a.findViewById(R.id.journal_add)!=null);
            try(JournalStore store=new JournalStore(context)){assertEquals("Keep me",store.all().get(0).event());assertEquals("A purpose",store.heaps().get(0).description());}
        }
    }
    @Test public void cancelAndBackgroundClearNewPinAndInvalidateOldSaveControls()throws Exception{
        setupOwner();try(Session s=new Session()){
            AlertDialog popup=s.newPin();EditText input=popup.findViewById(R.id.unlock_pin);enter(popup,"915307","915307");
            s.c.pause().stop();idle();assertFalse(popup.isShowing());assertEquals(0,input.length());
            s.c.start().resume().visible();enter(popup,"915307","915307");popup.findViewById(R.id.pin_reset_save).performClick();idle();
            assertTrue(new PinLock(context).verify("642081".toCharArray()));assertNotNull(s.a.findViewById(R.id.google_sign_in));
        }
    }
    @Test public void cancelWhileHashingDoesNotReplaceThePin()throws Exception{
        setupOwner();try(Session s=new Session()){
            AlertDialog popup=s.newPin();ExecutorService worker=ReflectionHelpers.getField(s.screen,"worker");CountDownLatch release=new CountDownLatch(1);
            worker.execute(()->{try{release.await(15,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}});
            enter(popup,"915307","915307");popup.findViewById(R.id.pin_reset_save).performClick();popup.dismiss();idle();release.countDown();worker.submit(()->{}).get(15,TimeUnit.SECONDS);idle();
            assertTrue(new PinLock(context).verify("642081".toCharArray()));
        }
    }
    @Test public void googleVerificationExpiresAndNewPinRequiresMatchingSixDigits()throws Exception{
        setupOwner();try(Session s=new Session()){
            AlertDialog popup=s.newPin();enter(popup,"123","123");popup.findViewById(R.id.pin_reset_save).performClick();assertTrue(popup.isShowing());
            enter(popup,"915307","123456");popup.findViewById(R.id.pin_reset_save).performClick();assertEquals(0,((EditText)popup.findViewById(R.id.confirm_pin)).length());
            enter(popup,"915307","915307");ShadowSystemClock.advanceBy(Duration.ofMinutes(3));popup.findViewById(R.id.pin_reset_save).performClick();idle();
            assertFalse(popup.isShowing());assertTrue(new PinLock(context).verify("642081".toCharArray()));
        }
    }
    @Test public void recreationDiscardsResetVerificationAndKeepsPrivateTabsLocked()throws Exception{
        setupOwner();try(Session s=new Session()){
            AlertDialog popup=s.newPin();enter(popup,"915307","915307");s.c.recreate();assertFalse(popup.isShowing());
            assertTrue(new PinLock(context).verify("642081".toCharArray()));assertNull(s.c.get().findViewById(R.id.journal_add));assertNotNull(s.c.get().findViewById(R.id.unlock_submit));
        }
    }
    @Test @Config(sdk=26) public void accountSetupAndPinResetWorkOnAndroidEight()throws Exception{
        setupOwner();try(Session s=new Session()){
            AlertDialog popup=s.newPin();enter(popup,"915307","915307");popup.findViewById(R.id.pin_reset_save).performClick();JournalTest.await(()->!popup.isShowing());assertTrue(new PinLock(context).verify("915307".toCharArray()));
        }
    }
    void setupPin()throws Exception{PinLock lock=new PinLock(context);lock.setPin("642081".toCharArray());lock.setBiometricPrimary(false);}
    void setupOwner()throws Exception{setupPin();new GoogleAccountStore(context).link(GoogleIdentityTest.profile());}
    static AlertDialog showing(){AlertDialog p=ShadowAlertDialog.getLatestAlertDialog();return p!=null&&p.isShowing()?p:null;}
    static void enter(AlertDialog popup,String pin,String confirm){((EditText)popup.findViewById(R.id.unlock_pin)).setText(pin);((EditText)popup.findViewById(R.id.confirm_pin)).setText(confirm);}
    static void idle(){Shadows.shadowOf(Looper.getMainLooper()).idle();}
    static final class FakeSignIn implements GoogleSignInSession{
        int starts;boolean busy;Consumer<GoogleAccountStore.Profile> success;Runnable failure;
        public boolean configured(){return true;}public boolean busy(){return busy;}
        public void start(Consumer<GoogleAccountStore.Profile> success,Runnable failure){starts++;busy=true;this.success=success;this.failure=failure;}
        void succeed(GoogleAccountStore.Profile profile){busy=false;success.accept(profile);}
        public void resume(){}public void cancel(){busy=false;}public void close(){cancel();}
    }
    final class Session implements AutoCloseable{
        final ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup().visible();final MainActivity a=c.get();
        final FakeSignIn auth=new FakeSignIn();final GoogleAccountScreen screen;
        Session(){GoogleAccountScreen old=ReflectionHelpers.getField(a,"accounts");old.close();JournalGate gate=ReflectionHelpers.getField(a,"gate");
            screen=new GoogleAccountScreen(a,new GoogleAccountStore(a),auth,gate::show,()->ReflectionHelpers.callInstanceMethod(a,"resumeAfterAccount"));
            ReflectionHelpers.setField(a,"accounts",screen);if(screen.blocking())screen.render();}
        void signIn(){a.findViewById(R.id.google_sign_in).performClick();auth.succeed(GoogleIdentityTest.profile());}
        void recover(){a.findViewById(R.id.journal_tab).performClick();((EditText)a.findViewById(R.id.unlock_pin)).setText("111111");a.findViewById(R.id.unlock_submit).performClick();JournalTest.await(()->a.findViewById(R.id.unlock_submit).isEnabled());a.findViewById(R.id.pin_reset).performClick();assertNull(a.findViewById(R.id.journal_tab));}
        AlertDialog newPin(){recover();signIn();return showing();}
        public void close(){c.close();}
    }
}
