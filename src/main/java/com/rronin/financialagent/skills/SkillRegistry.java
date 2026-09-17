package com.rronin.financialagent.skills;

import com.rronin.financialagent.agent.ApprovalBehaviour;
import com.rronin.financialagent.agent.ApprovalRule;
import com.rronin.financialagent.agent.ApprovalRuleSource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

@Component
public class SkillRegistry {
    private final ResourceLoader resourceLoader;
    private final Path skillsRoot;

    @org.springframework.beans.factory.annotation.Autowired
    public SkillRegistry(ResourceLoader resourceLoader) {
        this(resourceLoader,Path.of(System.getProperty("user.dir"), "src/main/java/com/rronin/financialagent/skills"));
    }
    SkillRegistry(ResourceLoader resourceLoader,Path root) {
        this.resourceLoader = resourceLoader;
        this.skillsRoot = root.toAbsolutePath().normalize();
    }

    public String instructionsFor(String skillId) {
        if (skillId == null || skillId.isBlank() || "default".equals(skillId)) return "";
        String[] ids = skillId.split(",");
        StringBuilder out = new StringBuilder();
        for (String raw : ids) {
            String id = normalizeId(raw);
            if (id.isBlank() || "default".equals(id)) continue;
            String content = load(id);
            if (content.isBlank()) continue;
            if (!out.isEmpty()) out.append("\n\n---\n\n");
            out.append("# Selected Skill: ").append(id).append("\n\n").append(content);
        }
        return out.toString();
    }

    public List<ApprovalRule> approvalRulesFor(String skillIds) {
        // Skill text supplies workflow, never an independent permission source.
        return List.of();
    }

    private List<String> csv(String value) {
        if (value == null || value.isBlank()) return List.of();
        return Stream.of(value.split(",")).map(String::trim).filter(s -> !s.isBlank()).distinct().toList();
    }

    public String resolveForPrompt(String selectedSkillIds, String prompt) {
        return normalizeSelected(selectedSkillIds);
    }

    public List<Map<String, Object>> list() {
        if (!Files.exists(skillsRoot)) return List.of();
        try (Stream<Path> stream = Files.list(skillsRoot)) {
            return stream.filter(Files::isDirectory)
                    .map(path -> path.getFileName().toString())
                    .filter(id -> Files.isRegularFile(skillsRoot.resolve(id).resolve("SKILL.md")))
                    .sorted(Comparator.naturalOrder())
                    .map(id -> summary(id, load(id)))
                    .toList();
        } catch (IOException error) {
            return List.of();
        }
    }

    public String content(String skillId) throws IOException {
        return Files.readString(resolveSkill(skillId), StandardCharsets.UTF_8);
    }

    public Map<String, Object> upsert(String skillId, String content) throws IOException {
        String id = normalizeId(skillId);
        if (id.isBlank()) throw new IllegalArgumentException("Skill id is required");
        Path file = skillsRoot.resolve(id).resolve("SKILL.md").toAbsolutePath().normalize();
        if (!file.startsWith(skillsRoot)) throw new IllegalArgumentException("Skill path is outside skills root");
        rejectSymlinks(file);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content == null ? "" : content, StandardCharsets.UTF_8);
        return summary(id, content(id));
    }

    public void delete(String skillId) throws IOException {
        String id = normalizeId(skillId);
        if(id.isBlank())throw new IllegalArgumentException("Skill id is required");
        Path dir = skillsRoot.resolve(id).toAbsolutePath().normalize();
        rejectSymlinks(dir.resolve("SKILL.md"));
        if (!dir.startsWith(skillsRoot) || !Files.exists(dir)) return;
        Path file = dir.resolve("SKILL.md");
        if (Files.exists(file)) Files.delete(file);
        try (Stream<Path> stream = Files.list(dir)) {
            if (stream.findAny().isEmpty()) Files.delete(dir);
        }
    }

    private String load(String skillId) {
        for (String candidate : candidates(skillId)) {
            try {
                Path file = skillsRoot.resolve(candidate).resolve("SKILL.md").toAbsolutePath().normalize();
                rejectSymlinks(file);
                if (file.startsWith(skillsRoot) && Files.isRegularFile(file)) {
                    return Files.readString(file, StandardCharsets.UTF_8);
                }
                Resource resource = resourceLoader.getResource("classpath:com/rronin/financialagent/skills/" + candidate + "/SKILL.md");
                if (resource.exists()) return resource.getContentAsString(StandardCharsets.UTF_8);
            } catch (Exception ignored) {
                // Try next candidate.
            }
        }
        return "";
    }

    private Map<String, Object> summary(String id, String content) {
        Map<String, String> meta = frontMatter(content);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("name", title(meta.getOrDefault("name", id)));
        out.put("description", meta.getOrDefault("description", ""));
        out.put("tools", toolsFor(id));
        out.put("userInvocable", !"false".equalsIgnoreCase(meta.get("user-invocable")));
        out.put("modelInvocable", !"true".equalsIgnoreCase(meta.get("disable-model-invocation")));
        return out;
    }

    private Map<String, String> frontMatter(String content) {
        Map<String, String> meta = new LinkedHashMap<>();
        if (content == null || !content.startsWith("---")) return meta;
        String[] lines = content.split("\\R");
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].trim().equals("---")) break;
            int idx = lines[i].indexOf(':');
            if (idx > 0) meta.put(lines[i].substring(0, idx).trim(), lines[i].substring(idx + 1).trim());
        }
        return meta;
    }

    private List<String> toolsFor(String id) {
        return switch (id) {
            case "company-research" -> List.of("get_financials", "get_market_data", "get_macro_data", "web_search", "web_fetch", "x_search", "write_file");
            case "portfolio-news-radar" -> List.of("mcp__ibkr__get_account_positions", "web_search", "web_fetch", "get_market_data", "x_search", "write_file");
            case "x-research" -> List.of("x_search", "web_search", "web_fetch");
            default -> List.of();
        };
    }

    private Path resolveSkill(String skillId) {
        String id = normalizeId(skillId);
        Path file = skillsRoot.resolve(id).resolve("SKILL.md").toAbsolutePath().normalize();
        rejectSymlinks(file);
        if (!file.startsWith(skillsRoot) || !Files.isRegularFile(file)) throw new IllegalArgumentException("Skill not found");
        return file;
    }
    private void rejectSymlinks(Path file){
        if(!file.startsWith(skillsRoot))throw new IllegalArgumentException("Skill path is outside skills root");
        Path current=skillsRoot;
        if(Files.isSymbolicLink(current))throw new IllegalArgumentException("Symlink skills root");
        for(Path part:skillsRoot.relativize(file)){current=current.resolve(part);if(Files.isSymbolicLink(current))throw new IllegalArgumentException("Symlink skill path");}
    }

    private String[] candidates(String skillId) {
        String camel = toCamelCase(skillId);
        return camel.equals(skillId) ? new String[]{skillId} : new String[]{skillId, camel};
    }

    private String normalizeSelected(String selectedSkillIds) {
        if (selectedSkillIds == null || selectedSkillIds.isBlank()) return "";
        return Stream.of(selectedSkillIds.split(","))
                .map(SkillRegistry::normalizeId)
                .filter(id -> !id.isBlank() && !"default".equals(id))
                .distinct()
                .reduce((a, b) -> a + "," + b)
                .orElse("");
    }

    private String inferFromPrompt(String prompt) {
        String normalizedPrompt = normalizeText(prompt);
        if (normalizedPrompt.isBlank()) return "";
        List<SkillMatch> matches = availableSkillIds().stream()
                .map(id -> {
                    String content = load(id);
                    return new SkillMatch(id, scoreSkill(id, content, normalizedPrompt));
                })
                .filter(match -> match.score() >= 8)
                .sorted(Comparator.comparingInt(SkillMatch::score).reversed())
                .toList();
        if (matches.isEmpty()) return "";
        if (matches.size() > 1 && matches.get(0).score() - matches.get(1).score() < 3) return "";
        return matches.get(0).id();
    }

    private List<String> availableSkillIds() {
        if (!Files.exists(skillsRoot)) return List.of();
        try (Stream<Path> stream = Files.list(skillsRoot)) {
            return stream.filter(Files::isDirectory)
                    .map(path -> path.getFileName().toString())
                    .filter(id -> Files.isRegularFile(skillsRoot.resolve(id).resolve("SKILL.md")))
                    .sorted(Comparator.naturalOrder())
                    .toList();
        } catch (IOException error) {
            return List.of();
        }
    }

    private int scoreSkill(String id, String content, String normalizedPrompt) {
        Map<String, String> meta = frontMatter(content);
        String name = meta.getOrDefault("name", id);
        String description = meta.getOrDefault("description", "");
        String heading = firstMarkdownHeading(content);
        int score = 0;

        for (String exact : List.of(id, id.replace('-', ' '), name, name.replace('-', ' '), heading)) {
            String phrase = normalizeText(exact);
            if (!phrase.isBlank() && normalizedPrompt.contains(phrase)) score += 20;
        }

        String searchable = normalizeText(id + " " + name + " " + description + " " + heading);
        List<String> skillTokens = importantTokens(searchable);
        Set<String> promptTokens = new HashSet<>(importantTokens(normalizedPrompt));
        for (String token : skillTokens) {
            if (promptTokens.contains(token)) score += 1;
        }

        for (String phrase : importantPhrases(searchable)) {
            if (normalizedPrompt.contains(phrase)) score += 5;
        }
        return score;
    }

    private String firstMarkdownHeading(String content) {
        if (content == null) return "";
        for (String line : content.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("# ")) return trimmed.substring(2).trim();
        }
        return "";
    }

    private List<String> importantPhrases(String text) {
        List<String> tokens = importantTokens(text);
        java.util.ArrayList<String> phrases = new java.util.ArrayList<>();
        for (int size = 2; size <= 4; size++) {
            for (int i = 0; i + size <= tokens.size(); i++) {
                phrases.add(String.join(" ", tokens.subList(i, i + size)));
            }
        }
        return phrases;
    }

    private List<String> importantTokens(String text) {
        Set<String> stop = Set.of(
                "the", "and", "for", "with", "this", "that", "when", "user", "asks", "skill", "use",
                "around", "about", "into", "from", "current", "public", "one", "a", "an", "to", "of",
                "in", "on", "or", "as", "by", "be", "is", "are", "should", "shoulder"
        );
        return Stream.of(normalizeText(text).split(" "))
                .map(String::trim)
                .filter(token -> token.length() >= 3)
                .filter(token -> !stop.contains(token))
                .distinct()
                .toList();
    }

    private static String normalizeText(String value) {
        if (value == null) return "";
        return value.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{IsAlphabetic}\\p{IsDigit}]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private record SkillMatch(String id, int score) {}

    private String toCamelCase(String value) {
        StringBuilder out = new StringBuilder();
        boolean upperNext = false;
        for (char c : value.toCharArray()) {
            if (c == '-' || c == '_' || c == ' ') { upperNext = true; continue; }
            out.append(upperNext ? Character.toUpperCase(c) : c);
            upperNext = false;
        }
        return out.toString();
    }

    private static String normalizeId(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9-]+", "-").replaceAll("(^-|-$)", "");
    }

    private static String title(String id) {
        String[] parts = id.replace('_', '-').split("-");
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            if (part.isBlank()) continue;
            if (!out.isEmpty()) out.append(' ');
            out.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return out.toString();
    }
}
