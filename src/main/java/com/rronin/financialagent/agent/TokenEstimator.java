package com.rronin.financialagent.agent;

/** Conservative fallback only; provider usage is authoritative once available. */
public final class TokenEstimator {
    private TokenEstimator() {}
    public static long text(String text) {
        if (text == null || text.isEmpty()) return 0;
        long[] counts = new long[2];
        text.codePoints().forEach(cp -> {
            if (Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN || cp > 0x7f) counts[0]++;
            else counts[1]++;
        });
        return counts[0] + (counts[1] + 3) / 4;
    }
    public static long calibrated(long currentEstimate,long previousEstimate,long previousUsage) {
        return previousUsage>0&&previousEstimate>0?Math.max(0,previousUsage+currentEstimate-previousEstimate):currentEstimate;
    }
    public static long toolJson(String json) { return Math.max(text(json), json == null ? 0 : (json.length() + 1L) / 2); }
}
