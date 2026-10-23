package com.moodjournal.app;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.time.LocalDate;
import java.util.Base64;
import java.util.Locale;

/** An entry keeps its own dated weather; changing display units never changes its temperatures. */
record WeatherSnapshot(String date, double highC, double lowC, String description, String attribution,
                       long fetchedAt, long expiresAt) {
    WeatherSnapshot {
        LocalDate.parse(date);
        if (!Double.isFinite(highC) || !Double.isFinite(lowC) || highC < lowC || lowC < -150 || highC > 100
                || description == null || description.trim().isEmpty() || description.length() > 200
                || attribution == null || attribution.trim().isEmpty() || attribution.length() > 200
                || fetchedAt < 0 || expiresAt != 0 && expiresAt <= fetchedAt) throw new IllegalArgumentException("Invalid weather");
        description = description.trim(); attribution = attribution.trim();
    }

    boolean availableAt(long now) { return expiresAt == 0 || now < expiresAt; }
    String summary(boolean fahrenheit) {
        double high = fahrenheit ? highC * 9 / 5 + 32 : highC;
        double low = fahrenheit ? lowC * 9 / 5 + 32 : lowC;
        String unit = fahrenheit ? "F" : "C";
        return String.format(Locale.getDefault(), "High %.0f°%s · Low %.0f°%s · %s", high, unit, low, unit, description);
    }

    String encode() {
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(buffer)) {
                out.writeByte(1); out.writeUTF(date); out.writeDouble(highC); out.writeDouble(lowC);
                out.writeUTF(description); out.writeUTF(attribution); out.writeLong(fetchedAt); out.writeLong(expiresAt);
            }
            return Base64.getEncoder().encodeToString(buffer.toByteArray());
        } catch (java.io.IOException impossible) { throw new IllegalStateException(impossible); }
    }

    static WeatherSnapshot decode(String value) {
        if (value == null || value.isEmpty() || value.length() > 3000) return null;
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(value)))) {
            if (in.readUnsignedByte() != 1) return null;
            WeatherSnapshot result = new WeatherSnapshot(in.readUTF(), in.readDouble(), in.readDouble(), in.readUTF(), in.readUTF(), in.readLong(), in.readLong());
            return in.read() == -1 ? result : null;
        } catch (java.io.IOException | RuntimeException invalid) { return null; }
    }
}
