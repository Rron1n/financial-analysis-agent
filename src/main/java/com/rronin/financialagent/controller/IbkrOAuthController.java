package com.rronin.financialagent.controller;

import com.rronin.financialagent.integrations.ibkr.IbkrOAuthClient;
import com.rronin.financialagent.integrations.ibkr.IbkrTokenStore;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.Map;

@RestController
@RequestMapping("/api/ibkr/oauth")
public class IbkrOAuthController {
    private final IbkrOAuthClient oauth;
    private final IbkrTokenStore tokens;

    public IbkrOAuthController(IbkrOAuthClient oauth, IbkrTokenStore tokens) {
        this.oauth = oauth;
        this.tokens = tokens;
    }

    @GetMapping("/connect")
    public Mono<ResponseEntity<Void>> connect() {
        return oauth.authorizationUrl().map(url -> ResponseEntity.status(302).location(URI.create(url)).build());
    }

    @GetMapping("/callback")
    public Mono<ResponseEntity<String>> callback(@RequestParam String code, @RequestParam String state) {
        return oauth.exchange(code, state)
                .flatMap(token -> Mono.fromCallable(() -> {
                    tokens.save(token);
                    return ResponseEntity.ok("""
                            <!doctype html><html><head><meta charset=\"utf-8\"><meta http-equiv=\"refresh\" content=\"1;url=/\"><title>IBKR Connected</title></head>
                            <body style=\"font-family:system-ui;background:#202020;color:#f4f4f4;display:grid;place-items:center;height:100vh;margin:0\">
                              <div>IBKR MCP authorization saved. Returning to Financial Analysis Agent...</div>
                              <script>setTimeout(()=>location.href='/',800)</script>
                            </body></html>
                            """);
                }));
    }

    @GetMapping("/disconnect")
    public Mono<Map<String, Object>> disconnect() {
        return Mono.fromCallable(() -> {
            tokens.clear();
            return Map.of("status", "DISCONNECTED");
        });
    }

    @GetMapping("/token-status")
    public Map<String, Object> tokenStatus() {
        return Map.of(
                "hasToken", tokens.accessToken().isPresent(),
                "tokenFile", tokens.tokenPath().toString()
        );
    }
}
