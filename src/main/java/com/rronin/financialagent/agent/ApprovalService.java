package com.rronin.financialagent.agent;

import com.rronin.financialagent.tools.AgentTool;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ApprovalService {
    public enum Decision { ALLOW_ONCE, ALLOW_SESSION, ALLOW_ALWAYS, DENY }

    private final Map<String, PendingApproval> pending = new ConcurrentHashMap<>();
    private final Map<String, Sinks.One<Decision>> sinks = new ConcurrentHashMap<>();
    private final ApprovalPolicyEngine policyEngine;
    private final UserApprovalRuleStore userRules;
    private final Map<String, List<ApprovalRule>> sessionRules = new ConcurrentHashMap<>();
    private com.rronin.financialagent.session.SessionStore sessions;
    private final Map<String, Decision> resolved = new ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public void setSessionStore(com.rronin.financialagent.session.SessionStore sessions) { this.sessions = sessions; }

    public ApprovalService(ApprovalPolicyEngine policyEngine, UserApprovalRuleStore userRules) {
        this.policyEngine = policyEngine;
        this.userRules = userRules;
    }

    public ApprovalPolicyEngine.Evaluation evaluate(String runId, String toolName, com.fasterxml.jackson.databind.JsonNode arguments,
                                                     AgentTool.Mode mode, List<ApprovalRule> contextualRules) {
        List<ApprovalRule> rules = new ArrayList<>(userRules.load());
        rules.addAll(sessionRules.getOrDefault(runId, List.of()));
        if (contextualRules != null) rules.addAll(contextualRules);
        return policyEngine.evaluate(toolName, arguments, mode, rules);
    }

    public void clearSession(String runId) {
        sessionRules.remove(runId);
    }

    public ApprovalPolicyEngine.Evaluation evaluate(AgentTool tool, com.fasterxml.jackson.databind.JsonNode arguments,
                                                    AgentTool.ApprovalContext context) {
        return policyEngine.evaluate(tool, arguments, context, rulesForSession(context.sessionId()));
    }

    public List<ApprovalRule> rulesForSession(String sessionId) {
        List<ApprovalRule> rules = new ArrayList<>(userRules.load());
        if (sessions != null) {
            try {
                var values = sessions.metadata(sessionId).path("approvalRules");
                if (values.isArray()) for (var value : values) rules.add(new com.fasterxml.jackson.databind.ObjectMapper().treeToValue(value, ApprovalRule.class));
            } catch (Exception error) { throw new IllegalStateException("Unable to load session approval rules", error); }
        } else rules.addAll(sessionRules.getOrDefault(sessionId, List.of()));
        return List.copyOf(rules);
    }

    public PendingApproval requestForCall(String sessionId, String runId, String toolCallId, AgentTool tool,
                                          com.fasterxml.jackson.databind.JsonNode arguments, String summary) {
        PendingApproval request = new PendingApproval(UUID.randomUUID().toString(), runId, ApprovalRuleSource.USER_SETTINGS,
                sessionId, new ApprovalRule(ApprovalRuleSource.SESSION, ApprovalBehaviour.ASK, tool.name(), argumentRuleContent(arguments)),
                tool.mode(), toMap(arguments), summary, Instant.now(), sessionId, toolCallId);
        if (sessions != null) {
            try { sessions.append(sessionId, runId, "approval.requested", request); }
            catch (Exception error) { throw new IllegalStateException("Unable to persist approval request", error); }
        }
        pending.put(request.id(), request);
        sinks.put(request.id(), Sinks.one());
        return request;
    }

    public void restore(PendingApproval request) {
        pending.putIfAbsent(request.id(), request);
        sinks.computeIfAbsent(request.id(), ignored -> Sinks.one());
    }

    public List<PendingApproval> pendingForSession(String sessionId) {
        return pending.values().stream().filter(p -> sessionId.equals(p.sessionId())).toList();
    }

    public PendingApproval request(String runId, String toolName, com.fasterxml.jackson.databind.JsonNode arguments,
                                   AgentTool.Mode mode, String summary, ApprovalRuleSource targetSource,
                                   String scopeId) {
        String id = UUID.randomUUID().toString();
        ApprovalRuleSource source = targetSource == null ? ApprovalRuleSource.USER_SETTINGS : targetSource;
        ApprovalRule requestedRule = new ApprovalRule(source, ApprovalBehaviour.ASK, toolName,
                argumentRuleContent(arguments));
        PendingApproval approval = new PendingApproval(id, runId, source, scopeId, requestedRule, mode,
                arguments == null ? Map.of() : toMap(arguments), summary, Instant.now(), null, null);
        pending.put(id, approval);
        sinks.put(id, Sinks.one());
        return approval;
    }

    public Mono<Decision> await(String id) {
        Sinks.One<Decision> sink = sinks.get(id);
        if (sink == null) return Mono.just(Decision.DENY);
        return sink.asMono()
                .doFinally(signal -> {
                    pending.remove(id);
                    sinks.remove(id);
                    resolved.remove(id);
                });
    }

    public synchronized boolean decide(String id, Decision decision) {
        if (resolved.containsKey(id)) return false;
        PendingApproval approval = pending.get(id);
        Sinks.One<Decision> sink = sinks.get(id);
        if (approval == null || sink == null) return false;
        if (decision == Decision.ALLOW_ALWAYS) {
            if (approval.targetSource() == ApprovalRuleSource.USER_SETTINGS) {
                userRules.add(asAllowRule(ApprovalRuleSource.USER_SETTINGS, approval.rule()));
            }
        } else if (decision == Decision.ALLOW_SESSION) {
            String scope = approval.sessionId() == null ? approval.runId() : approval.sessionId();
            sessionRules.compute(scope, (runId, rules) -> {
                List<ApprovalRule> updated = new ArrayList<>(rules == null ? List.of() : rules);
                updated.add(new ApprovalRule(ApprovalRuleSource.SESSION, ApprovalBehaviour.ALLOW,
                        approval.rule().toolName(), approval.rule().ruleContent()));
                return List.copyOf(updated);
            });
            if (sessions != null && approval.sessionId() != null) {
                try {
                    sessions.updateMetadata(scope, metadata -> {
                        var array = metadata.withArray("approvalRules");
                        array.add(new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(asAllowRule(ApprovalRuleSource.SESSION, approval.rule())));
                    });
                } catch (Exception error) { throw new IllegalStateException("Unable to persist session approval", error); }
            }
        }
        if (sessions != null && approval.sessionId() != null) {
            try { sessions.append(approval.sessionId(), approval.runId(), "approval.decided", Map.of("approvalId", id, "toolCallId", approval.toolCallId(), "decision", decision)); }
            catch (Exception error) { throw new IllegalStateException("Unable to persist approval decision", error); }
        }
        resolved.put(id, decision);
        return sink.tryEmitValue(decision).isSuccess();
    }

    private static ApprovalRule asAllowRule(ApprovalRuleSource source, ApprovalRule requestedRule) {
        return new ApprovalRule(source, ApprovalBehaviour.ALLOW,
                requestedRule.toolName(), requestedRule.ruleContent());
    }

    private static Map<String, Object> argumentRuleContent(com.fasterxml.jackson.databind.JsonNode arguments) {
        if (arguments == null || !arguments.isObject()) return Map.of();
        if (arguments.hasNonNull("path")) return Map.of("path", arguments.path("path").asText());
        if (arguments.hasNonNull("command")) return Map.of("command", arguments.path("command").asText());
        return toMap(arguments);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> toMap(com.fasterxml.jackson.databind.JsonNode arguments) {
        return new com.fasterxml.jackson.databind.ObjectMapper().convertValue(arguments, Map.class);
    }

    public record PendingApproval(String id, String runId, ApprovalRuleSource targetSource, String scopeId,
                                  ApprovalRule rule, AgentTool.Mode toolMode, Map<String, Object> arguments,
                                  String summary, Instant createdAt, String sessionId, String toolCallId) { }
}
