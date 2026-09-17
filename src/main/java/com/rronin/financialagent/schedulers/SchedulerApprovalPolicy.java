package com.rronin.financialagent.schedulers;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.rronin.financialagent.agent.ApprovalRule;
import com.rronin.financialagent.agent.ApprovalRuleSource;

import java.util.List;

/** Scheduler-scoped approval rules in the canonical ApprovalRule format. */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class SchedulerApprovalPolicy {
    private final List<ApprovalRule> rules;

    public SchedulerApprovalPolicy(List<ApprovalRule> rules) {
        this.rules = rules == null ? List.of() : List.copyOf(rules);
    }

    @JsonProperty("rules")
    public List<ApprovalRule> rules() {
        return rules;
    }

    public static SchedulerApprovalPolicy readOnlyOnly() {
        return new SchedulerApprovalPolicy(List.of());
    }

    public List<ApprovalRule> normalizedRules() {
        // Legacy scheduler-owned grants are deliberately not imported into user/session authorization.
        return List.of();
    }
}
