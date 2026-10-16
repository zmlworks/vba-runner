package com.zmlworks.vbarunner.vba;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/** VBA Date: days since 1899-12-30 as a double (fraction = time of day). */
public final class VDate {
    static final LocalDateTime EPOCH = LocalDateTime.of(1899, 12, 30, 0, 0);
    public final double serial;

    public VDate(double serial) { this.serial = serial; }

    public static VDate of(LocalDateTime t) {
        long ms = ChronoUnit.MILLIS.between(EPOCH, t);
        return new VDate(ms / 86_400_000.0);
    }

    public LocalDateTime toLocal() {
        long ms = Math.round(serial * 86_400_000.0);
        return EPOCH.plus(ms, ChronoUnit.MILLIS);
    }

    @Override public boolean equals(Object o) { return o instanceof VDate && ((VDate) o).serial == serial; }
    @Override public int hashCode() { return Double.hashCode(serial); }
    @Override public String toString() { return Values.toStr(this); }
}
