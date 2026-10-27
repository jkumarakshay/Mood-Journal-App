package com.moodjournal.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/** Stores only a salted, slow PIN verifier. It does not encrypt journal entries. */
final class PinLock {
    private static final int ITERATIONS = 600_000;
    private final SharedPreferences prefs;

    PinLock(Context context) { prefs = context.getSharedPreferences("journal_lock", Context.MODE_PRIVATE); }

    boolean hasPin() { return prefs.contains("hash") || prefs.contains("salt"); }

    boolean biometricPrimary() { return hasPin() && prefs.getBoolean("biometric_primary", false); }

    boolean biometricChoiceMade() { return prefs.contains("biometric_primary"); }

    boolean hasFailedAttempt() { return prefs.getInt("failures", 0) > 0; }
    String verifierIdentity() { return prefs.getString("salt", "") + ":" + prefs.getString("hash", ""); }
    static final class PreparedReset implements AutoCloseable {
        private final String oldSalt, oldHash;
        private final byte[] salt, hash;
        private boolean consumed;
        private PreparedReset(String oldSalt, String oldHash, byte[] salt, byte[] hash) { this.oldSalt = oldSalt; this.oldHash = oldHash; this.salt = salt; this.hash = hash; }
        public void close() { consumed = true; Arrays.fill(salt, (byte) 0); Arrays.fill(hash, (byte) 0); }
    }
    synchronized PreparedReset prepareReset(char[] pin, String verifiedIdentity) throws GeneralSecurityException {
        String saltBefore = prefs.getString("salt", ""), hashBefore = prefs.getString("hash", "");
        if (!hasPin() || !validFormat(pin) || !(saltBefore + ":" + hashBefore).equals(verifiedIdentity)) throw new GeneralSecurityException("Authenticate again");
        byte[] salt = new byte[16]; new SecureRandom().nextBytes(salt);
        return new PreparedReset(saltBefore, hashBefore, salt, derive(pin, salt));
    }
    synchronized void commitReset(PreparedReset reset) throws GeneralSecurityException {
        try {
            if (reset.consumed || !hasPin() || !reset.oldSalt.equals(prefs.getString("salt", "")) || !reset.oldHash.equals(prefs.getString("hash", "")))
                throw new GeneralSecurityException("The lock changed; authenticate again");
            if (!prefs.edit().putString("salt", Base64.encodeToString(reset.salt, Base64.NO_WRAP))
                    .putString("hash", Base64.encodeToString(reset.hash, Base64.NO_WRAP)).remove("failures").remove("retry_after").commit())
                throw new GeneralSecurityException("Could not save PIN");
        } finally { reset.close(); }
    }

    synchronized void setBiometricPrimary(boolean enabled) throws GeneralSecurityException {
        if (!hasPin()) throw new GeneralSecurityException("Set a backup PIN first");
        if (!prefs.edit().putBoolean("biometric_primary", enabled).commit())
            throw new GeneralSecurityException("Could not save biometric preference");
    }

    synchronized void setPin(char[] pin) throws GeneralSecurityException {
        if (hasPin()) throw new GeneralSecurityException("A PIN is already configured");
        if (!validFormat(pin)) throw new GeneralSecurityException("Use six digits");
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        byte[] hash = derive(pin, salt);
        try {
            if (!prefs.edit().putString("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
                    .putString("hash", Base64.encodeToString(hash, Base64.NO_WRAP)).commit())
                throw new GeneralSecurityException("Could not save PIN");
        } finally { Arrays.fill(hash, (byte) 0); }
    }

    synchronized boolean verify(char[] pin) throws GeneralSecurityException {
        if (!hasPin() || remainingLockMillis() > 0 || !validFormat(pin)) return false;
        byte[] salt, expected;
        try {
            salt = Base64.decode(prefs.getString("salt", ""), Base64.NO_WRAP);
            expected = Base64.decode(prefs.getString("hash", ""), Base64.NO_WRAP);
        } catch (IllegalArgumentException e) { throw new GeneralSecurityException("Invalid lock data", e); }
        if (salt.length != 16 || expected.length != 32) throw new GeneralSecurityException("Invalid lock data");
        byte[] actual = derive(pin, salt);
        boolean matches = MessageDigest.isEqual(expected, actual);
        Arrays.fill(actual, (byte) 0);
        SharedPreferences.Editor edit = prefs.edit();
        if (matches) edit.remove("failures").remove("retry_after");
        else {
            int failures = Math.min(30, prefs.getInt("failures", 0) + 1);
            edit.putInt("failures", failures);
            if (failures >= 5) {
                long delay = Math.min(300_000L, 30_000L << Math.min(4, failures - 5));
                edit.putLong("retry_after", System.currentTimeMillis() + delay);
            }
        }
        if (!edit.commit()) throw new GeneralSecurityException("Could not update lock state");
        return matches;
    }

    long remainingLockMillis() { return Math.max(0, prefs.getLong("retry_after", 0) - System.currentTimeMillis()); }

    private static boolean validFormat(char[] pin) {
        if (pin.length != 6) return false;
        for (char c : pin) if (c < '0' || c > '9') return false;
        return true;
    }

    private static byte[] derive(char[] pin, byte[] salt) throws GeneralSecurityException {
        PBEKeySpec spec = new PBEKeySpec(pin, salt, ITERATIONS, 256);
        try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); }
        finally { spec.clearPassword(); }
    }
}
