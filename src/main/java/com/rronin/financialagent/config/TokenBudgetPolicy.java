package com.rronin.financialagent.config;

/** Project operating budgets, deliberately distinct from the provider's actual model capacity. */
public final class TokenBudgetPolicy {
    private TokenBudgetPolicy() { }
    public static final int RUN_OUTPUT=96_000;
    public static final int PRIMARY_OUTPUT=6_000;
    public static final int PRIMARY_OUTPUT_RECOVERY=10_000;
    public static final int CONTEXT_TRIGGER=32_000;
    public static final int TOOL_RESULT_CHARS=12_000;
    public static final int RUN_TOOL_RESULT_CHARS=80_000;
    public static final int GENERAL_OUTPUT=6_000;
    public static final int CLAIM_OUTPUT=7_000;
    public static final int FINANCIAL_OUTPUT=12_000;
    public static final int REVIEW_RESERVE=GENERAL_OUTPUT+CLAIM_OUTPUT+FINANCIAL_OUTPUT;
    public static final int AUDIT_EVIDENCE_CHARS=60_000;
    public static final int TOPIC_RECALL_TOKENS=4_000;
    public static final int MEMORY_OUTPUT=4_000;
}
