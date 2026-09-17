package com.rronin.financialagent.memory;

import java.util.regex.Pattern;

/** Front matter is runtime-owned; revision and cursor always travel with the exact body snapshot. */
public record SessionMemorySnapshot(long revisionEdition, String coveredThroughMessageId, String body) {
    public static SessionMemorySnapshot parse(String text) {
        String header = "", body = text;
        if (text.startsWith("---\n")) {
            int end = text.indexOf("\n---", 4);
            if (end >= 0) { header = text.substring(4, end); body = text.substring(end + 4).stripLeading(); }
        }
        long revision;
        try { revision = Long.parseLong(field(header, "revisionEdition", "0")); }
        catch (NumberFormatException error) { throw new IllegalArgumentException("Invalid session memory revision", error); }
        return new SessionMemorySnapshot(revision, field(header, "coveredThroughMessageId", ""), body);
    }
    public String serialize() {
        if (coveredThroughMessageId == null || !coveredThroughMessageId.matches("[A-Za-z0-9_-]*")) throw new IllegalArgumentException("Invalid memory cursor");
        return "---\nschemaVersion: 1\nrevisionEdition: " + revisionEdition + "\ncoveredThroughMessageId: \"" + coveredThroughMessageId
                + "\"\nupdatedAt: " + java.time.Instant.now() + "\n---\n\n" + body;
    }
    private static String field(String header, String key, String fallback) {
        var match = Pattern.compile("(?m)^" + Pattern.quote(key) + ":\\s*([^\\r\\n]*)$").matcher(header);
        return match.find() ? match.group(1).trim().replaceAll("^\"|\"$", "") : fallback;
    }
}
