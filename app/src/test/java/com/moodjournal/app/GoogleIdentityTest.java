package com.moodjournal.app;

import static org.junit.Assert.*;
import android.app.Application;
import android.content.Context;
import android.util.Base64;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class) @Config(sdk=35,application=Application.class)
public class GoogleIdentityTest {
    static final String PROJECT="daily-pause-a0aa2", UID="owner-firebase-uid", SUBJECT="12345678901", EMAIL="owner@example.org", NONCE="a-fresh-random-request-nonce";
    Context context;String clientId,key;JSONObject google,firebase,user;List<String> actions;
    @Before public void reset()throws Exception{
        context=RuntimeEnvironment.getApplication();context.getSharedPreferences(GoogleAccountStore.PREFS,0).edit().clear().commit();
        clientId=context.getString(R.string.google_web_client_id);key=context.getString(R.string.firebase_web_api_key);actions=new ArrayList<>();
        long now=System.currentTimeMillis()/1000;
        google=new JSONObject().put("iss","https://accounts.google.com").put("aud",clientId).put("sub",SUBJECT).put("nonce",NONCE).put("iat",now).put("exp",now+3600);
        firebase=new JSONObject().put("iss","https://securetoken.google.com/"+PROJECT).put("aud",PROJECT).put("sub",UID).put("exp",now+3600);
        user=new JSONObject().put("localId",UID).put("email",EMAIL).put("emailVerified",true)
                .put("providerUserInfo",new JSONArray().put(new JSONObject().put("providerId","google.com").put("rawId",SUBJECT)));
    }
    static GoogleAccountStore.Profile profile(){return new GoogleAccountStore.Profile(PROJECT,UID,SUBJECT,EMAIL);}
    @Test public void identityComesFromFirebaseAndAuthenticatedUserLookup()throws Exception{
        FirebaseGoogleVerifier client=client();assertTrue(client.configured());GoogleAccountStore.Profile profile=client.verify(jwt(google),NONCE);
        assertEquals(profile(),profile);assertEquals(List.of("signInWithIdp","lookup"),actions);
    }
    @Test public void decodingAClaimedIdentityCannotBypassServerVerification()throws Exception{
        FirebaseGoogleVerifier client=new FirebaseGoogleVerifier(context,PROJECT,key,clientId,(action,body)->{throw new IOException("offline");});
        assertThrows(IOException.class,()->client.verify(jwt(google),NONCE));
    }
    @Test public void wrongNonceAudienceIssuerAndExpiredTokenAreRejectedBeforeTheNetwork()throws Exception{
        for(String field:new String[]{"nonce","aud","iss","exp"}){
            JSONObject invalid=new JSONObject(google.toString()).put(field,field.equals("exp")?1:"not-the-expected-value");
            assertThrows(IOException.class,()->client().verify(jwt(invalid),NONCE));
        }
        assertEquals(0,actions.size());
    }
    @Test public void wrongFirebaseProjectOrUidDoesNotAuthenticate()throws Exception{
        for(String field:new String[]{"aud","iss","sub"}){
            JSONObject saved=firebase;firebase=new JSONObject(saved.toString()).put(field,"different-project-or-user");
            assertThrows(IOException.class,()->client().verify(jwt(google),NONCE));firebase=saved;
        }
        assertFalse(actions.contains("lookup"));
    }
    @Test public void unverifiedDisabledOrDifferentProviderUserIsRejected()throws Exception{
        JSONObject saved=user;
        for(String scenario:new String[]{"unverified","disabled","uid","provider","subject"}){
            user=new JSONObject(saved.toString());
            switch(scenario){
                case "unverified"->user.put("emailVerified",false);
                case "disabled"->user.put("disabled",true);
                case "uid"->user.put("localId","someone-else");
                case "provider"->user.getJSONArray("providerUserInfo").getJSONObject(0).put("providerId","password");
                case "subject"->user.getJSONArray("providerUserInfo").getJSONObject(0).put("rawId","99999999999");
            }
            assertThrows(IOException.class,()->client().verify(jwt(google),NONCE));
        }
    }
    @Test public void linkedOwnerCannotBeReplacedEvenIfEmailIsTheSame()throws Exception{
        GoogleAccountStore store=new GoogleAccountStore(context);assertFalse(store.linked());store.link(profile());assertTrue(store.linked());
        assertThrows(GeneralSecurityException.class,()->store.link(new GoogleAccountStore.Profile(PROJECT,"another-uid","99999999999",EMAIL)));
        assertEquals(UID,store.owner().uid());assertTrue(store.matches(profile()));
    }
    @Test public void emailChangeDoesNotChangeTheStableOwnerButUidAndGoogleSubjectMustMatch()throws Exception{
        GoogleAccountStore store=new GoogleAccountStore(context);store.link(profile());
        assertTrue(store.matches(new GoogleAccountStore.Profile(PROJECT,UID,SUBJECT,"renamed@example.org")));
        assertFalse(store.matches(new GoogleAccountStore.Profile(PROJECT,UID,"99999999999",EMAIL)));
        assertFalse(store.matches(new GoogleAccountStore.Profile(PROJECT,"different",SUBJECT,EMAIL)));
    }
    @Test public void malformedPartialAndDifferentProjectBindingsFailClosed()throws Exception{
        GoogleAccountStore store=new GoogleAccountStore(context);context.getSharedPreferences(GoogleAccountStore.PREFS,0).edit().putString("uid",UID).commit();
        assertFalse(store.linked());assertThrows(GeneralSecurityException.class,()->store.link(profile()));
        context.getSharedPreferences(GoogleAccountStore.PREFS,0).edit().clear().commit();
        assertThrows(GeneralSecurityException.class,()->store.link(new GoogleAccountStore.Profile("other-project",UID,SUBJECT,EMAIL)));
    }
    @Test public void ownerPersistenceContainsNoAuthenticationTokens()throws Exception{
        GoogleAccountStore store=new GoogleAccountStore(context);store.link(client().verify(jwt(google),NONCE));
        String persisted=context.getSharedPreferences(GoogleAccountStore.PREFS,0).getAll().toString();
        assertFalse(persisted.contains(jwt(google)));assertFalse(persisted.contains(jwt(firebase)));assertFalse(persisted.contains("refreshToken"));assertFalse(persisted.contains("idToken"));
    }
    @Test public void resetVerifierIsSingleUseRejectsChangedPinsAndPreservesBiometricPreference()throws Exception{
        context.getSharedPreferences("journal_lock",0).edit().clear().commit();PinLock lock=new PinLock(context);lock.setPin("642081".toCharArray());lock.setBiometricPrimary(true);
        String original=lock.verifierIdentity();PinLock.PreparedReset first=lock.prepareReset("915307".toCharArray(),original),second=lock.prepareReset("222222".toCharArray(),original);
        context.getSharedPreferences("journal_lock",0).edit().putInt("failures",5).putLong("retry_after",System.currentTimeMillis()+300000).commit();
        lock.commitReset(first);assertNotEquals(original,lock.verifierIdentity());assertEquals(0,lock.remainingLockMillis());
        assertThrows(GeneralSecurityException.class,()->lock.commitReset(first));assertThrows(GeneralSecurityException.class,()->lock.commitReset(second));
        assertThrows(GeneralSecurityException.class,()->lock.prepareReset("333333".toCharArray(),original));
        assertTrue(lock.verify("915307".toCharArray()));assertTrue(lock.biometricPrimary());
    }
    FirebaseGoogleVerifier client(){return new FirebaseGoogleVerifier(context,PROJECT,key,clientId,(action,body)->{
        actions.add(action);
        if(action.equals("signInWithIdp")){assertEquals("http://localhost",body.getString("requestUri"));assertTrue(body.getString("postBody").endsWith("providerId=google.com"));return new JSONObject().put("idToken",jwt(firebase)).put("localId",UID).put("providerId","google.com").put("refreshToken","discard-this-test-token");}
        assertEquals("lookup",action);assertEquals(jwt(firebase),body.getString("idToken"));return new JSONObject().put("users",new JSONArray().put(user));
    });}
    static String jwt(JSONObject claims){return "e30."+Base64.encodeToString(claims.toString().getBytes(StandardCharsets.UTF_8),Base64.URL_SAFE|Base64.NO_WRAP|Base64.NO_PADDING)+".test-signature";}
}
