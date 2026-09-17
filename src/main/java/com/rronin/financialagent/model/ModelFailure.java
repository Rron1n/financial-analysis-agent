package com.rronin.financialagent.model;

/** Sanitized error information; upstream bodies and headers must not enter logs/transcripts. */
public final class ModelFailure extends RuntimeException {
    public enum Kind { RATE_LIMIT, AUTHENTICATION, QUOTA, MODEL_UNAVAILABLE, INVALID_REQUEST, CONTEXT_OVERFLOW, TRANSIENT_SERVER, TIMEOUT, TRANSPORT, INCOMPLETE_STREAM }
    private final Kind kind;
    private final int httpStatus;
    public ModelFailure(Kind kind, int httpStatus) {
        this(kind, httpStatus, "");
    }
    private ModelFailure(Kind kind, int httpStatus, String diagnostic) {
        super("Model request failed: " + kind + (httpStatus == 0 ? "" : " (HTTP " + httpStatus + ")") + diagnostic);
        this.kind = kind;
        this.httpStatus = httpStatus;
    }
    public Kind kind() { return kind; }
    public int httpStatus() { return httpStatus; }
    public boolean retryable() { return kind == Kind.RATE_LIMIT || kind == Kind.TRANSIENT_SERVER || kind == Kind.TIMEOUT || kind == Kind.TRANSPORT || kind == Kind.INCOMPLETE_STREAM; }
    public static ModelFailure http(int status, String code) {
        String value = code == null ? "" : code.toLowerCase(java.util.Locale.ROOT);
        Kind kind;
        if (value.contains("context_length") || value.contains("context_window")) kind = Kind.CONTEXT_OVERFLOW;
        else if (value.contains("quota") || value.contains("billing") || status == 402) kind = Kind.QUOTA;
        else if (status == 401 || status == 403 || value.contains("invalid_api_key") || value.contains("authentication")) kind = Kind.AUTHENTICATION;
        else if (status == 429 || value.contains("rate_limit")) kind = Kind.RATE_LIMIT;
        else if (status == 404 || value.contains("model_not_found")) kind = Kind.MODEL_UNAVAILABLE;
        else if (status == 408) kind = Kind.TIMEOUT;
        else if (status >= 500 || value.contains("server_error") || value.contains("service_unavailable")) kind = Kind.TRANSIENT_SERVER;
        else kind = Kind.INVALID_REQUEST;
        return new ModelFailure(kind, status);
    }
    public static ModelFailure http(int status, com.fasterxml.jackson.databind.JsonNode error) {
        var failure = http(status, error.path("code").asText());
        String code = error.path("code").asText().replaceAll("[^A-Za-z0-9_.-]", "");
        String parameter = error.path("param").asText().replaceAll("[^A-Za-z0-9_.\\[\\]-]", "");
        return new ModelFailure(failure.kind, status, (code.isBlank() ? "" : " code=" + code.substring(0, Math.min(code.length(), 80)))
                + (parameter.isBlank() ? "" : " parameter=" + parameter.substring(0, Math.min(parameter.length(), 120))));
    }
}
