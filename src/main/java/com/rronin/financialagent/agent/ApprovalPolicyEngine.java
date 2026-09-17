package com.rronin.financialagent.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.rronin.financialagent.tools.AgentTool;
import org.springframework.stereotype.Component;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Component
public class ApprovalPolicyEngine {
    public Evaluation evaluate(AgentTool tool, JsonNode arguments, AgentTool.ApprovalContext context, List<ApprovalRule> rules) {
        List<ApprovalRule> matching = (rules == null ? List.<ApprovalRule>of() : rules).stream()
                .filter(rule -> matchesTool(rule.toolName(), tool.name()))
                .filter(rule -> matchesContent(rule.ruleContent(), arguments)).toList();
        Optional<ApprovalBehaviour> advice;
        try { advice = tool.evaluateApproval(arguments, context); }
        catch (Exception invalidOrUnsafeInput) { return new Evaluation(ApprovalBehaviour.DENY, null, false); }
        if (advice.orElse(null) == ApprovalBehaviour.DENY) return new Evaluation(ApprovalBehaviour.DENY, null, false);
        Optional<ApprovalRule> deny = winner(matching, ApprovalBehaviour.DENY, tool.name());
        if (deny.isPresent()) return new Evaluation(ApprovalBehaviour.DENY, deny.get(), false);
        if (tool.requiresUserInteraction() || tool.isDestructive(arguments)) return new Evaluation(ApprovalBehaviour.ASK, null, false);
        if (advice.orElse(null) == ApprovalBehaviour.ASK) return new Evaluation(ApprovalBehaviour.ASK, null, false);
        Optional<ApprovalRule> ask = winner(matching, ApprovalBehaviour.ASK, tool.name());
        if (ask.isPresent()) return new Evaluation(ApprovalBehaviour.ASK, ask.get(), false);
        Optional<ApprovalRule> allow = winner(matching, ApprovalBehaviour.ALLOW, tool.name());
        if (allow.isPresent()) return new Evaluation(ApprovalBehaviour.ALLOW, allow.get(), false);
        if (advice.orElse(null) == ApprovalBehaviour.ALLOW) return new Evaluation(ApprovalBehaviour.ALLOW, null, false);
        return new Evaluation(ApprovalBehaviour.ASK, null, true);
    }

    private Optional<ApprovalRule> winner(List<ApprovalRule> rules, ApprovalBehaviour behaviour, String toolName) {
        return rules.stream().filter(rule -> rule.behaviour() == behaviour)
                .max(Comparator.comparingInt(rule -> specificity(rule, toolName)));
    }

    public Evaluation evaluate(String toolName, JsonNode arguments, AgentTool.Mode mode, List<ApprovalRule> rules) {
        List<ApprovalRule> matching = (rules == null ? List.<ApprovalRule>of() : rules).stream()
                .filter(rule -> matchesTool(rule.toolName(), toolName))
                .filter(rule -> matchesContent(rule.ruleContent(), arguments))
                .toList();
        for (ApprovalBehaviour behaviour : List.of(ApprovalBehaviour.DENY, ApprovalBehaviour.ASK, ApprovalBehaviour.ALLOW)) {
            Optional<ApprovalRule> winner = matching.stream().filter(rule -> rule.behaviour() == behaviour)
                    .max(Comparator.comparingInt(rule -> specificity(rule, toolName)));
            if (winner.isPresent()) return new Evaluation(behaviour, winner.get(), false);
        }
        return new Evaluation(mode == AgentTool.Mode.READ_ONLY ? ApprovalBehaviour.ALLOW : ApprovalBehaviour.ASK, null, true);
    }

    private boolean matchesTool(String pattern, String toolName) {
        if (pattern == null || pattern.isBlank() || "*".equals(pattern)) return true;
        if (pattern.equalsIgnoreCase(toolName)) return true;
        return pattern.startsWith("mcp__") && pattern.endsWith("__*")
                && toolName.toLowerCase(Locale.ROOT).startsWith(pattern.substring(0, pattern.length() - 1).toLowerCase(Locale.ROOT));
    }

    private boolean matchesContent(Map<String, Object> content, JsonNode arguments) {
        if (content == null || content.isEmpty()) return true;
        JsonNode args = arguments == null ? com.fasterxml.jackson.databind.node.MissingNode.getInstance() : arguments;
        for (Map.Entry<String, Object> entry : content.entrySet()) {
            String expected = String.valueOf(entry.getValue());
            String argumentKey = entry.getKey();
            if (argumentKey.toLowerCase(Locale.ROOT).endsWith("pattern")) {
                argumentKey = argumentKey.substring(0, argumentKey.length() - "Pattern".length());
            }
            JsonNode actualNode = valueAt(args, argumentKey);
            String actual = actualNode.isMissingNode() || actualNode.isNull() ? "" : actualNode.asText();
            if (entry.getKey().toLowerCase(Locale.ROOT).endsWith("pattern")) {
                if (!globMatches(actual, expected)) return false;
            } else if (!actualNode.equals(new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(entry.getValue()))) return false;
        }
        return true;
    }

    private JsonNode valueAt(JsonNode node, String dottedKey) {
        JsonNode current = node;
        for (String part : dottedKey.split("\\.")) current = current.path(part);
        return current;
    }

    private boolean globMatches(String value, String pattern) {
        try {
            return FileSystems.getDefault().getPathMatcher("glob:" + pattern.replace('\\', '/'))
                    .matches(Path.of(value.replace('\\', '/')));
        } catch (Exception ignored) { return false; }
    }

    private int specificity(ApprovalRule rule, String actualTool) {
        if (!rule.ruleContent().isEmpty()) return 400 + rule.ruleContent().size();
        if (rule.toolName().equalsIgnoreCase(actualTool) && actualTool.startsWith("mcp__")) return 300;
        if (rule.toolName().startsWith("mcp__") && rule.toolName().endsWith("__*")) return 200;
        if (rule.toolName().equalsIgnoreCase(actualTool)) return 100;
        return 0;
    }

    public record Evaluation(ApprovalBehaviour behaviour, ApprovalRule matchedRule, boolean fallback) {}
}
