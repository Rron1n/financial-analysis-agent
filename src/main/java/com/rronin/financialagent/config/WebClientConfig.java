package com.rronin.financialagent.config;

import io.netty.channel.ChannelOption;
import io.netty.resolver.ResolvedAddressTypes;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;

@Configuration
public class WebClientConfig {
    @Bean
    WebClient.Builder webClientBuilder() {
        HttpClient client = ProxySupport.apply(HttpClient.create())
                .resolver(spec -> spec.resolvedAddressTypes(ResolvedAddressTypes.IPV4_ONLY))
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10_000)
                .responseTimeout(Duration.ofMinutes(10));
        return WebClient.builder().clientConnector(new ReactorClientHttpConnector(client));
    }
}
