package com.moodjournal.app;

import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import androidx.core.content.ContextCompat;
import androidx.credentials.CredentialManager;
import androidx.credentials.CredentialManagerCallback;
import androidx.credentials.CustomCredential;
import androidx.credentials.GetCredentialRequest;
import androidx.credentials.GetCredentialResponse;
import androidx.credentials.exceptions.GetCredentialException;
import androidx.fragment.app.FragmentActivity;
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption;
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential;
import java.security.SecureRandom;
import java.util.function.Consumer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Each explicit Google flow has a fresh nonce; stale provider/network results cannot authorize another flow. */
final class GoogleSignIn implements GoogleSignInSession {
    interface Provider { void request(String clientId, String nonce, Consumer<String> token, Runnable failed); void cancel(); }
    interface Verifier { boolean configured(); String clientId(); GoogleAccountStore.Profile verify(String token, String nonce) throws Exception; }
    private final FragmentActivity activity;
    private final Provider provider;
    private final Verifier verifier;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private int generation;
    private boolean closed, busy;
    private long deadline;
    private GoogleAccountStore.Profile result;
    private boolean failed;
    private Consumer<GoogleAccountStore.Profile> accepted;
    private Runnable rejected;
    GoogleSignIn(FragmentActivity activity) {
        this(activity, provider(activity), verifier(new FirebaseGoogleVerifier(activity)));
    }
    GoogleSignIn(FragmentActivity activity, Provider provider, Verifier verifier) { this.activity = activity; this.provider = provider; this.verifier = verifier; }
    public boolean configured() { return verifier.configured(); }
    public boolean busy() { return busy; }
    public void start(Consumer<GoogleAccountStore.Profile> accepted, Runnable rejected) {
        cancel(); if (closed) return;
        this.accepted = accepted; this.rejected = rejected;
        if (!configured()) { cancel(); rejected.run(); return; }
        int token = generation; busy = true; deadline = android.os.SystemClock.elapsedRealtime() + 120_000;
        byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes);
        String nonce = Base64.encodeToString(bytes, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        try { provider.request(verifier.clientId(), nonce, idToken -> {
            if (closed || token != generation) return;
            // Consume the provider callback before queueing network work.
            int networkToken = ++generation;
            worker.execute(() -> {
                GoogleAccountStore.Profile profile = null;
                try { profile = verifier.verify(idToken, nonce); } catch (Exception ignored) { }
                GoogleAccountStore.Profile verified = profile;
                main.post(() -> {
                    if (closed || networkToken != generation) return;
                    result = verified; failed = verified == null; resume();
                });
            });
        }, () -> { if (!closed && token == generation) { generation++; failed = true; resume(); } }); }
        catch (RuntimeException e) { if (token == generation) { generation++; failed = true; resume(); } }
    }
    public void resume() {
        if (!busy || !activity.getLifecycle().getCurrentState().isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) return;
        if (android.os.SystemClock.elapsedRealtime() >= deadline) { failed = true; result = null; }
        if (result == null && !failed) return;
        Consumer<GoogleAccountStore.Profile> success = accepted; Runnable error = rejected; GoogleAccountStore.Profile profile = result;
        cancel(); if (profile != null) success.accept(profile); else error.run();
    }
    public void cancel() { generation++; busy = false; result = null; failed = false; accepted = null; rejected = null; provider.cancel(); }
    public void close() { closed = true; cancel(); worker.shutdown(); }
    private static Verifier verifier(FirebaseGoogleVerifier client) { return new Verifier() {
        public boolean configured() { return client.configured(); } public String clientId() { return client.clientId(); }
        public GoogleAccountStore.Profile verify(String token, String nonce) throws Exception { return client.verify(token, nonce); }
    }; }
    private static Provider provider(FragmentActivity activity) { return new Provider() {
        CancellationSignal cancellation;
        public void request(String clientId, String nonce, Consumer<String> accepted, Runnable failed) {
            cancellation = new CancellationSignal();
            GetSignInWithGoogleOption option = new GetSignInWithGoogleOption.Builder(clientId).setNonce(nonce).build();
            GetCredentialRequest request = new GetCredentialRequest.Builder().addCredentialOption(option).build();
            CredentialManager.create(activity).getCredentialAsync(activity, request, cancellation, ContextCompat.getMainExecutor(activity),
                    new CredentialManagerCallback<GetCredentialResponse, GetCredentialException>() {
                        public void onResult(GetCredentialResponse response) {
                            try {
                                if (!(response.getCredential() instanceof CustomCredential custom)
                                        || !GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL.equals(custom.getType())) { failed.run(); return; }
                                accepted.accept(GoogleIdTokenCredential.createFrom(custom.getData()).getIdToken());
                            } catch (RuntimeException e) { failed.run(); }
                        }
                        public void onError(GetCredentialException error) { failed.run(); }
                    });
        }
        public void cancel() { if (cancellation != null) { cancellation.cancel(); cancellation = null; } }
    }; }
}
