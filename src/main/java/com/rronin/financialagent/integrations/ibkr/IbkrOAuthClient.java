package com.rronin.financialagent.integrations.ibkr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rronin.financialagent.config.AgentProperties;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class IbkrOAuthClient {
    public record AuthSession(String state, String codeVerifier, String clientId, String clientSecret, String tokenEndpoint) {}

    private final WebClient.Builder web;
    private final ObjectMapper mapper;
    private final AgentProperties properties;
    private final IbkrTokenStore tokenStore;
    private final Map<String, AuthSession> sessions = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    public IbkrOAuthClient(WebClient.Builder web, ObjectMapper mapper, AgentProperties properties, IbkrTokenStore tokenStore) {
        this.web = web;
        this.mapper = mapper;
        this.properties = properties;
        this.tokenStore = tokenStore;
    }

    public Mono<String> authorizationUrl() {
        String state = randomUrlSafe(24);
        String verifier = randomUrlSafe(64);
        String challenge = sha256Base64Url(verifier);
        return discoverMetadata().flatMap(metadata -> registerIfNeeded(metadata).map(client -> {
            String authorizationEndpoint = metadata.path("authorization_endpoint").asText();
            String tokenEndpoint = metadata.path("token_endpoint").asText();
            if (authorizationEndpoint.isBlank() || tokenEndpoint.isBlank()) {
                throw new IllegalStateException("IBKR OAuth metadata is missing authorization_endpoint or token_endpoint");
            }
            sessions.put(state, new AuthSession(state, verifier, client.clientId(), client.clientSecret(), tokenEndpoint));
            return authorizationEndpoint + "?" + query(Map.of(
                    "response_type", "code",
                    "client_id", client.clientId(),
                    "redirect_uri", properties.ibkr().redirectUri(),
                    "scope", "mcp.read",
                    "state", state,
                    "code_challenge", challenge,
                    "code_challenge_method", "S256"
            ));
        }));
    }

    public Mono<JsonNode> exchange(String code, String state) {
        AuthSession session = sessions.remove(state);
        if (session == null) return Mono.error(new IllegalArgumentException("Unknown or expired OAuth state"));
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("code", code);
        form.add("redirect_uri", properties.ibkr().redirectUri());
        form.add("client_id", session.clientId());
        form.add("code_verifier", session.codeVerifier());
        if (session.clientSecret() != null && !session.clientSecret().isBlank()) form.add("client_secret", session.clientSecret());
        return web.build().post().uri(session.tokenEndpoint())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData(form))
                .retrieve().bodyToMono(JsonNode.class)
                .map(token -> attachClientInfo(token, session.clientId(), session.clientSecret(), session.tokenEndpoint()));
    }

    public Mono<String> validAccessToken() {
        if (properties.ibkr().accessToken() != null && !properties.ibkr().accessToken().isBlank()) {
            return Mono.just(properties.ibkr().accessToken());
        }
        return Mono.defer(() -> {
            var maybeToken = tokenStore.tokenJson();
            if (maybeToken.isEmpty()) return Mono.empty();
            JsonNode token = maybeToken.get();
            String accessToken = token.path("access_token").asText("");
            if (accessToken.isBlank()) return Mono.empty();
            if (!isExpired(token)) return Mono.just(accessToken);
            String refreshToken = token.path("refresh_token").asText("");
            String clientId = token.path("client_id").asText(properties.ibkr().clientId());
            String clientSecret = token.path("client_secret").asText(properties.ibkr().clientSecret());
            String tokenEndpoint = token.path("token_endpoint").asText("");
            if (refreshToken.isBlank() || clientId == null || clientId.isBlank() || tokenEndpoint.isBlank()) {
                return Mono.error(new IllegalStateException("IBKR MCP token expired and cannot be refreshed. Reconnect IBKR."));
            }
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("grant_type", "refresh_token");
            form.add("refresh_token", refreshToken);
            form.add("client_id", clientId);
            if (clientSecret != null && !clientSecret.isBlank()) form.add("client_secret", clientSecret);
            return web.build().post().uri(tokenEndpoint)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(BodyInserters.fromFormData(form))
                    .retrieve().bodyToMono(JsonNode.class)
                    .map(refreshed -> attachClientInfo(refreshed, clientId, clientSecret, tokenEndpoint))
                    .flatMap(refreshed -> Mono.fromCallable(() -> {
                        tokenStore.save(refreshed);
                        return refreshed.path("access_token").asText();
                    }));
        });
    }

    public Mono<JsonNode> discoverMetadata() {
        return discoverProtectedResource().flatMap(resource -> {
            String authServer = firstText(resource.path("authorization_servers"));
            if (authServer == null) authServer = resource.path("authorization_server").asText(null);
            if (authServer == null || authServer.isBlank()) {
                // Some servers expose authorization metadata directly beside the MCP URL.
                return getJson(wellKnownFor(properties.ibkr().mcpUrl(), "oauth-authorization-server"));
            }
            return getJson(trimSlash(authServer) + "/.well-known/oauth-authorization-server");
        }).onErrorResume(error -> getJson(wellKnownFor(properties.ibkr().mcpUrl(), "oauth-authorization-server")));
    }

    private Mono<JsonNode> discoverProtectedResource() {
        return getJson(protectedResourceMetadataUrl(properties.ibkr().mcpUrl()));
    }

    private Mono<ClientInfo> registerIfNeeded(JsonNode metadata) {
        if (properties.ibkr().clientId() != null && !properties.ibkr().clientId().isBlank()) {
            return Mono.just(new ClientInfo(properties.ibkr().clientId(), properties.ibkr().clientSecret()));
        }
        String registrationEndpoint = metadata.path("registration_endpoint").asText();
        if (registrationEndpoint.isBlank()) {
            return Mono.error(new IllegalStateException("IBKR OAuth metadata has no registration_endpoint. Configure IBKR_MCP_CLIENT_ID and IBKR_MCP_CLIENT_SECRET if IBKR requires a pre-registered client."));
        }
        ObjectNode body = mapper.createObjectNode();
        body.put("client_name", "Financial Analysis Agent");
        body.put("application_type", "web");
        body.set("redirect_uris", mapper.valueToTree(List.of(properties.ibkr().redirectUri())));
        body.set("grant_types", mapper.valueToTree(List.of("authorization_code", "refresh_token")));
        body.set("response_types", mapper.valueToTree(List.of("code")));
        body.put("token_endpoint_auth_method", "none");
        return web.build().post().uri(registrationEndpoint)
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body)
                .retrieve().bodyToMono(JsonNode.class)
                .map(json -> new ClientInfo(json.path("client_id").asText(), json.path("client_secret").asText("")));
    }


    private JsonNode attachClientInfo(JsonNode token, String clientId, String clientSecret, String tokenEndpoint) {
        ObjectNode copy = token.deepCopy();
        copy.put("client_id", clientId);
        if (clientSecret != null && !clientSecret.isBlank()) copy.put("client_secret", clientSecret);
        copy.put("token_endpoint", tokenEndpoint);
        return copy;
    }

    private boolean isExpired(JsonNode token) {
        try {
            String savedAt = token.path("saved_at").asText("");
            int expiresIn = token.path("expires_in").asInt(0);
            if (savedAt.isBlank() || expiresIn <= 0) return false;
            var saved = java.time.Instant.parse(savedAt);
            return java.time.Instant.now().isAfter(saved.plusSeconds(Math.max(0, expiresIn - 60)));
        } catch (Exception error) {
            return false;
        }
    }

    private Mono<JsonNode> getJson(String url) {
        return web.build().get().uri(url).accept(MediaType.APPLICATION_JSON).retrieve().bodyToMono(JsonNode.class);
    }

    private String protectedResourceMetadataUrl(String mcpUrl) {
        URI uri = URI.create(mcpUrl);
        String origin = uri.getScheme() + "://" + uri.getAuthority();
        String path = uri.getPath();
        int marker = path.indexOf("/mcp");
        String basePath = marker >= 0 ? path.substring(0, marker) : "";
        return origin + basePath + "/.well-known/oauth-protected-resource";
    }

    private String wellKnownFor(String baseUrl, String name) {
        URI uri = URI.create(baseUrl);
        String origin = uri.getScheme() + "://" + uri.getAuthority();
        return origin + "/.well-known/" + name;
    }

    private String firstText(JsonNode node) {
        if (node != null && node.isArray() && !node.isEmpty()) return node.get(0).asText();
        return null;
    }

    private String query(Map<String, String> values) {
        StringBuilder out = new StringBuilder();
        values.forEach((key, value) -> {
            if (!out.isEmpty()) out.append('&');
            out.append(enc(key)).append('=').append(enc(value));
        });
        return out.toString();
    }

    private String enc(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private String trimSlash(String value) { return value.endsWith("/") ? value.substring(0, value.length() - 1) : value; }

    private String randomUrlSafe(int bytes) {
        byte[] buf = new byte[bytes];
        random.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    private String sha256Base64Url(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    private record ClientInfo(String clientId, String clientSecret) {}
}
