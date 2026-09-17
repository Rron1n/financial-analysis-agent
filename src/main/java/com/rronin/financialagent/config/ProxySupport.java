package com.rronin.financialagent.config;

import reactor.netty.http.client.HttpClient;
import reactor.netty.transport.ProxyProvider;
import java.net.*;
import java.util.*;

/** Honor the process's existing outbound proxy without logging proxy credentials or changing TLS validation. */
public final class ProxySupport {
    private ProxySupport() { }
    private static URI proxy() {
        for (String key : List.of("https_proxy", "HTTPS_PROXY", "http_proxy", "HTTP_PROXY")) {
            String value = System.getenv(key);
            if (value != null && !value.isBlank()) {
                URI uri = URI.create(value);
                if (!"http".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) throw new IllegalArgumentException("Only HTTP CONNECT proxy URLs are supported");
                return uri;
            }
        }
        return null;
    }
    public static HttpClient apply(HttpClient client) {
        URI proxy = proxy();
        if (proxy == null) return client.proxyWithSystemProperties();
        return client.proxy(spec -> {
            var builder = spec.type(ProxyProvider.Proxy.HTTP).host(proxy.getHost()).port(proxy.getPort() < 0 ? 80 : proxy.getPort())
                    .nonProxyHosts(nonProxyHosts());
            if (proxy.getUserInfo() != null) {
                String[] auth = proxy.getUserInfo().split(":", 2);
                builder.username(auth[0]).password(ignored -> auth.length > 1 ? auth[1] : "");
            }
        });
    }
    public static java.net.http.HttpClient.Builder apply(java.net.http.HttpClient.Builder builder) {
        URI proxy = proxy();
        if (proxy == null) return builder;
        var address = new InetSocketAddress(proxy.getHost(), proxy.getPort() < 0 ? 80 : proxy.getPort());
        return builder.proxy(new ProxySelector() {
            public List<Proxy> select(URI target) { return List.of(target.getHost().matches(nonProxyHosts()) ? Proxy.NO_PROXY : new Proxy(Proxy.Type.HTTP, address)); }
            public void connectFailed(URI uri, SocketAddress socket, java.io.IOException error) { }
        });
    }
    private static String nonProxyHosts() {
        List<String> hosts = new ArrayList<>(List.of("localhost", "127\\..*", "\\[?::1\\]?"));
        String excluded = System.getenv().getOrDefault("no_proxy", System.getenv().getOrDefault("NO_PROXY", ""));
        for (String raw : excluded.split(",")) {
            String host = raw.trim(); if (host.isBlank()) continue;
            if (host.equals("*")) return ".*";
            if (host.startsWith(".")) hosts.add("(?:.*\\.)?" + java.util.regex.Pattern.quote(host.substring(1)));
            else hosts.add(java.util.regex.Pattern.quote(host));
        }
        return String.join("|", hosts);
    }
}
