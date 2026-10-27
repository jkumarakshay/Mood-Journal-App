package com.moodjournal.app;

import android.content.Context;
import android.content.SharedPreferences;
import java.security.GeneralSecurityException;

/** Installation owner only. Google/Firebase tokens and account passwords are never persisted. */
final class GoogleAccountStore {
    static final String PREFS = "google_account";
    record Profile(String project, String uid, String subject, String email) {
        boolean valid() { return project != null && project.matches("[a-z0-9-]{4,63}") && uid != null && uid.matches("[A-Za-z0-9_-]{1,128}")
                && subject != null && subject.matches("[0-9]{1,40}") && email != null && email.length() <= 254 && email.contains("@"); }
    }
    private final SharedPreferences prefs;
    private final String project;
    GoogleAccountStore(Context context) { prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE); project = context.getString(R.string.firebase_project_id); }
    boolean linked() { Profile owner = owner(); return owner.valid() && project.equals(owner.project()); }
    boolean matches(Profile profile) {
        Profile owner = owner(); return linked() && profile.valid() && owner.project().equals(profile.project())
                && owner.uid().equals(profile.uid()) && owner.subject().equals(profile.subject());
    }
    Profile owner() { return new Profile(prefs.getString("project", ""), prefs.getString("uid", ""), prefs.getString("subject", ""), prefs.getString("email", "")); }
    String identity() { Profile owner = owner(); return owner.project() + ":" + owner.uid() + ":" + owner.subject(); }
    void link(Profile profile) throws GeneralSecurityException {
        if (!profile.valid() || !project.equals(profile.project())) throw new GeneralSecurityException("Invalid account");
        // A new sign-in must never replace an installation's existing owner, even after configuration changes.
        if ((prefs.contains("uid") || prefs.contains("subject") || prefs.contains("project")) && !matches(profile))
            throw new GeneralSecurityException("This installation already belongs to another account");
        if (!prefs.edit().putString("project", profile.project()).putString("uid", profile.uid())
                .putString("subject", profile.subject()).putString("email", profile.email()).commit())
            throw new GeneralSecurityException("Could not save linked account");
    }
}
