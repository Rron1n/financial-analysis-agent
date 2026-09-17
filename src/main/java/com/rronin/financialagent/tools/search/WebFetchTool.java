package com.rronin.financialagent.tools.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rronin.financialagent.model.AgentModels.ToolResult;
import com.rronin.financialagent.tools.AgentTool;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class WebFetchTool implements AgentTool {
    private static final int DEFAULT_MAX_CHARS = 8_000;
    private static final int HARD_MAX_CHARS = 20_000;
    private static final Pattern TITLE = Pattern.compile("(?is)<title[^>]*>(.*?)</title>");

    private final WebClient.Builder web;
    private final ObjectMapper mapper;

    public WebFetchTool(WebClient.Builder web, ObjectMapper mapper) {
        this.web = web;
        this.mapper = mapper;
    }

    public String name() { return "web_fetch"; }

    public String description() {
        return "Fetch a single public web page and return a cleaned text preview with strict character limits for source verification.";
    }

public Mode mode() { return Mode.READ_ONLY; }
    public boolean isReadOnly(JsonNode input) { return true; }
    public boolean isConcurrencySafe(JsonNode input) { return true; }
    public boolean isOpenWorld(JsonNode input) { return true; }
    public InterruptBehavior interruptBehavior() { return InterruptBehavior.CANCEL; }
    public java.util.Optional<com.rronin.financialagent.agent.ApprovalBehaviour> evaluateApproval(JsonNode input, ApprovalContext context) {
        return java.util.Optional.of(com.rronin.financialagent.tools.ToolSafety.publicReadAdvice(input));
    }

    public JsonNode inputSchema() {
        return mapper.valueToTree(Map.of(
                "type", "object",
                "required", new String[]{"url"},
                "properties", Map.of(
                        "url", Map.of("type", "string", "description", "Public http(s) URL to fetch."),
                        "maxChars", Map.of("type", "integer", "description", "Maximum cleaned text characters to return. Default 8000, hard capped at 20000.")
                ),
                "additionalProperties", false
        ));
    }

    public Mono<ToolResult> execute(JsonNode args) {
        String url = args.path("url").asText("").trim();
        int maxChars = clamp(args.path("maxChars").asInt(DEFAULT_MAX_CHARS));
        URI uri;
        try {
            uri = validate(url);
        } catch (IllegalArgumentException error) {
            return Mono.just(ToolResult.error(name(), "INVALID_URL", error.getMessage()));
        }
        return web.build().get().uri(uri)
                .header(HttpHeaders.USER_AGENT, "Mozilla/5.0 FinancialAnalysisAgent/0.1")
                .exchangeToMono(response -> {
                    if (response.statusCode().is2xxSuccessful()) return response.bodyToMono(String.class);
                    return response.releaseBody().then(Mono.error(new IllegalStateException("HTTP " + response.statusCode().value() + " while fetching " + uri.getHost())));
                })
                .timeout(Duration.ofSeconds(12))
                .map(html -> toResult(uri.toString(), html, maxChars))
                .onErrorResume(error -> Mono.just(ToolResult.ok(name(), Map.of(
                        "url", uri.toString(),
                        "title", "",
                        "text", "",
                        "chars", 0,
                        "originalChars", 0,
                        "truncated", false,
                        "fetched", false,
                        "note", "Unable to fetch this page directly. Use web_search snippet results or choose another source URL.",
                        "error", safeMessage(error)
                ))));
    }

    private ToolResult toResult(String url, String html, int maxChars) {
        String title = extractTitle(html);
        String text = clean(html);
        int originalChars = text.length();
        boolean truncated = originalChars > maxChars;
        if (truncated) text = text.substring(0, maxChars).trim();
        return ToolResult.ok(name(), Map.of(
                "url", url,
                "title", title,
                "text", text,
                "chars", text.length(),
                "originalChars", originalChars,
                "truncated", truncated,
                "fetched", true,
                "maxChars", maxChars
        ));
    }

    private static URI validate(String url) {
        if (url.isBlank()) throw new IllegalArgumentException("url is required");
        URI uri = URI.create(url);
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        if (!scheme.equals("http") && !scheme.equals("https")) throw new IllegalArgumentException("Only http and https URLs are supported");
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase();
        if (host.isBlank()) throw new IllegalArgumentException("URL host is required");
        if (host.equals("localhost") || host.equals("127.0.0.1") || host.equals("0.0.0.0") || host.endsWith(".local")) {
            throw new IllegalArgumentException("Local/private hosts are not supported");
        }
        return uri;
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) message = error.getClass().getSimpleName();
        message = message.replaceAll("\\s+", " ").trim();
        return message.length() > 500 ? message.substring(0, 500) : message;
    }

    private static int clamp(int requested) {
        if (requested <= 0) return DEFAULT_MAX_CHARS;
        return Math.min(Math.max(1_000, requested), HARD_MAX_CHARS);
    }

    private static String extractTitle(String html) {
        Matcher matcher = TITLE.matcher(html == null ? "" : html);
        if (!matcher.find()) return "";
        return decode(matcher.group(1).replaceAll("\s+", " ").trim());
    }

    private static String clean(String html) {
        if (html == null || html.isBlank()) return "";
        String text = html
                .replaceAll("(?is)<script[^>]*>.*?</script>", " ")
                .replaceAll("(?is)<style[^>]*>.*?</style>", " ")
                .replaceAll("(?is)<noscript[^>]*>.*?</noscript>", " ")
                .replaceAll("(?is)<!--.*?-->", " ")
                .replaceAll("(?i)</(p|div|section|article|header|footer|li|br|h[1-6])>", "\n")
                .replaceAll("(?is)<[^>]+>", " ")
                .replaceAll("[ \t\u000B\f\r]+", " ")
                .replaceAll("\n[ \t]+", "\n")
                .replaceAll("\n{3,}", "\n\n")
                .trim();
        return decode(text);
    }

    private static String decode(String value) {
        return value.replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&apos;", "'");
    }
}
