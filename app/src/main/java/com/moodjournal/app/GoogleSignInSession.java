package com.moodjournal.app;

import java.util.function.Consumer;

interface GoogleSignInSession {
    boolean configured();
    boolean busy();
    void start(Consumer<GoogleAccountStore.Profile> accepted, Runnable rejected);
    void resume();
    void cancel();
    void close();
}
