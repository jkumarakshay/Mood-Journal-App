package com.moodjournal.app;

import static org.junit.Assert.*;
import android.os.Looper;
import androidx.fragment.app.FragmentActivity;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowSystemClock;

@RunWith(RobolectricTestRunner.class) @Config(sdk=35)
public class GoogleSignInTest {
    @Test public void eachExplicitRequestHasAFreshNonceAndDuplicateOrOldCallbacksAreIgnored(){
        try(Session s=new Session()){
            s.start();String first=s.provider.nonce;Consumer<String> old=s.provider.token;s.signIn.cancel();s.start();assertNotEquals(first,s.provider.nonce);
            old.accept("stale");assertEquals(0,s.verifications.get());Consumer<String> current=s.provider.token;current.accept("current");current.accept("duplicate");
            JournalTest.await(()->s.accepted.get()==1);assertEquals(1,s.verifications.get());assertEquals(0,s.failed.get());
        }
    }
    @Test public void providerCancellationAndNetworkFailuresDoNotAuthenticate(){
        try(Session s=new Session()){
            s.start();Consumer<String> stale=s.provider.token;s.provider.failed.run();stale.accept("late");assertEquals(1,s.failed.get());assertEquals(0,s.accepted.get());
            s.reject=true;s.start();s.provider.token.accept("unverifiable");JournalTest.await(()->s.failed.get()==2);assertEquals(0,s.accepted.get());
        }
    }
    @Test public void providerActivityRoundTripDeliversOnlyWhenTheAppResumes()throws Exception{
        try(Session s=new Session()){
            s.start();s.controller.pause().stop();s.provider.token.accept("current");
            java.util.concurrent.ExecutorService worker=org.robolectric.util.ReflectionHelpers.getField(s.signIn,"worker");worker.submit(()->{}).get(10,java.util.concurrent.TimeUnit.SECONDS);idle();
            assertEquals(0,s.accepted.get());s.controller.start().resume();s.signIn.resume();assertEquals(1,s.accepted.get());
        }
    }
    @Test public void expiredOrClosedRequestsCannotAuthenticate(){
        try(Session s=new Session()){
            s.start();Consumer<String> stale=s.provider.token;ShadowSystemClock.advanceBy(Duration.ofMinutes(3));s.signIn.resume();stale.accept("expired");
            assertEquals(1,s.failed.get());assertEquals(0,s.accepted.get());s.start();Consumer<String> closing=s.provider.token;s.signIn.close();closing.accept("late");assertEquals(0,s.accepted.get());
        }
    }
    private static void idle(){Shadows.shadowOf(Looper.getMainLooper()).idle();}
    private static final class Provider implements GoogleSignIn.Provider{
        String nonce;Consumer<String> token;Runnable failed;
        public void request(String clientId,String nonce,Consumer<String> token,Runnable failed){this.nonce=nonce;this.token=token;this.failed=failed;}
        public void cancel(){}
    }
    private static final class Session implements AutoCloseable{
        final ActivityController<FragmentActivity> controller=Robolectric.buildActivity(FragmentActivity.class).setup();final Provider provider=new Provider();
        final AtomicInteger accepted=new AtomicInteger(),failed=new AtomicInteger(),verifications=new AtomicInteger();boolean reject;
        final GoogleSignIn signIn=new GoogleSignIn(controller.get(),provider,new GoogleSignIn.Verifier(){
            public boolean configured(){return true;}public String clientId(){return "test-client";}
            public GoogleAccountStore.Profile verify(String token,String nonce)throws Exception{verifications.incrementAndGet();if(reject)throw new java.io.IOException();return GoogleIdentityTest.profile();}
        });
        void start(){signIn.start(p->accepted.incrementAndGet(),failed::incrementAndGet);}
        public void close(){signIn.close();controller.close();}
    }
}
