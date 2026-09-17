package com.rronin.financialagent.model;

/** Reserves output capacity before concurrent role calls; reported usage settles each reservation once. */
final class RunOutputBudget {
    private final long cap;
    private long used, reserved;
    RunOutputBudget(long cap) { this.cap = cap; }
    synchronized Reservation reserve(int requested) {
        if (requested < 1) throw new IllegalArgumentException("Output limit must be positive");
        int granted = (int) Math.min(requested, Math.max(0, cap - used - reserved));
        if (granted == 0) throw new IllegalStateException("Run output token budget exhausted or reserved by active calls");
        reserved += granted;
        return new Reservation(granted);
    }
    synchronized long used() { return used; }
    synchronized void restore(long tokens) { used = Math.max(used, Math.max(0, tokens)); }
    final class Reservation {
        private final int limit;
        private boolean settled;
        Reservation(int limit) { this.limit = limit; }
        int limit() { return limit; }
        void settle(long actual) {
            synchronized (RunOutputBudget.this) {
                if (settled) return;
                settled = true;
                reserved -= limit;
                used += Math.max(0, actual);
            }
        }
    }
}
