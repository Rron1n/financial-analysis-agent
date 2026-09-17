package com.rronin.financialagent.tools.finance;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class TickerNormalizer {
    private static final Pattern SYMBOL = Pattern.compile("\\b[A-Z][A-Z0-9.\\-]{0,9}\\b");
    private static final List<String> STOP_WORDS = List.of(
            "NYSE", "NASDAQ", "AMEX", "OTC", "US", "USA", "INC", "CORP", "LTD", "PLC", "NV", "SA", "ADR"
    );

    private TickerNormalizer() {}

    static String normalize(String raw) {
        if (raw == null) return "";
        String cleaned = raw.trim().toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9.\\- ]", " ")
                .replaceAll("\\s+", " ");
        if (cleaned.isBlank()) return "";
        Matcher matcher = SYMBOL.matcher(cleaned);
        String best = "";
        while (matcher.find()) {
            String candidate = matcher.group();
            if (!STOP_WORDS.contains(candidate)) best = candidate;
        }
        return best.isBlank() ? cleaned.split(" ")[0] : best;
    }
}
