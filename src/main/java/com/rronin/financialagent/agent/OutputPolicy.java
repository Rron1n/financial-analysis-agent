package com.rronin.financialagent.agent;

/**
 * Unified output contract for both interactive agent runs and scheduled runs.
 *
 * <p>The agent loop uses this policy during finalization to decide whether the
 * final product should be a normal chat answer, a saved file, a reminder, or no
 * visible output. Schedulers persist the same object as their outputPolicy, so
 * there is one vocabulary across foreground and background execution.</p>
 */
public record OutputPolicy(
        String type,
        String completeWhen,
        String pathPattern
) {
    public static final String TYPE_NONE = "none";
    public static final String TYPE_DIRECT_RESPONSE = "direct_response";
    public static final String TYPE_REMINDER = "reminder";
    public static final String TYPE_FILE_OUTPUT = "file_output";
    public static final String DEFAULT_FILE_OUTPUT_PATH_PATTERN = "downloads/*.md";

    public static OutputPolicy none() {
        return new OutputPolicy(TYPE_NONE, "default_agent_done", "");
    }

    public static OutputPolicy directResponse() {
        return new OutputPolicy(TYPE_DIRECT_RESPONSE, "model_final_answer", "");
    }

    public static OutputPolicy reminder() {
        return new OutputPolicy(TYPE_REMINDER, "model_final_answer", "");
    }

    public static OutputPolicy fileOutput(String pathPattern) {
        String normalizedPathPattern = safe(pathPattern).trim();
        if (normalizedPathPattern.isBlank()) normalizedPathPattern = DEFAULT_FILE_OUTPUT_PATH_PATTERN;
        return new OutputPolicy(TYPE_FILE_OUTPUT, "file_written", normalizedPathPattern);
    }

    public static OutputPolicy normalize(OutputPolicy policy) {
        if (policy == null || safe(policy.type()).isBlank()) return none();
        String type = safe(policy.type()).trim().toLowerCase();
        if (TYPE_DIRECT_RESPONSE.equals(type)) return directResponse();
        if (TYPE_REMINDER.equals(type)) return reminder();
        if (TYPE_FILE_OUTPUT.equals(type)) {
            return fileOutput(policy.pathPattern());
        }
        return none();
    }

    public boolean requiresFileOutput() {
        return TYPE_FILE_OUTPUT.equalsIgnoreCase(safe(type()))
                && "file_written".equalsIgnoreCase(safe(completeWhen()))
                && !safe(pathPattern()).isBlank();
    }

    public boolean isReminder() {
        return TYPE_REMINDER.equalsIgnoreCase(safe(type()));
    }

    public boolean isDirectResponse() {
        return TYPE_DIRECT_RESPONSE.equalsIgnoreCase(safe(type()));
    }

    public boolean isNone() {
        return TYPE_NONE.equalsIgnoreCase(safe(type()));
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
