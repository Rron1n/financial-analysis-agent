package com.rronin.financialagent.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rronin.financialagent.config.ModelSettings;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class OpenAiResponsesGatewayTest {
    private ModelSettings settings(int port) {
        return new ModelSettings("http://127.0.0.1:" + port + "/v1", "test-only", "primary", "auxiliary", "embedding",
                "high", "medium", Duration.ofSeconds(2), Duration.ofSeconds(2), Duration.ofSeconds(2),
                0, 1_050_000, 400_000, 128_000, Map.of());
    }
    private ModelGateway.Request request() {
        return new ModelGateway.Request("run", ModelGateway.Role.PRIMARY, "Test", List.of(
                AgentMessage.text("run", AgentMessage.Role.USER, "hello", false)), List.of(), 100, null);
    }
    @Test void collectsWholeAssistantResponseAndForwardsTextDeltas() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/responses", exchange -> {
            exchange.getRequestBody().readAllBytes();
            String frames = """
                    data: {"type":"response.created"}

                    data: {"type":"response.output_text.delta","delta":"hello"}

                    data: {"type":"response.completed","response":{"status":"completed","model":"primary","usage":{"input_tokens":5,"output_tokens":2},"output":[{"type":"message","content":[{"type":"output_text","text":"hello"}]}]}}

                    """;
            byte[] bytes = frames.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            var gateway = new OpenAiResponsesGateway(new ObjectMapper(), settings(server.getAddress().getPort()));
            List<ModelGateway.StreamEvent> events = Collections.synchronizedList(new ArrayList<>());
            var reply = gateway.call(request(), events::add).block(Duration.ofSeconds(10));
            assertThat(reply.message().text()).isEqualTo("hello");
            assertThat(reply.outputTokens()).isEqualTo(2);
            assertThat(events.stream().map(ModelGateway.StreamEvent::type)).contains("message_start", "text_delta", "message_stop");
        } finally { server.stop(0); }
    }
    @Test void authenticationFailureDoesNotRetryOrSwitchModel() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger attempts = new AtomicInteger();
        server.createContext("/v1/responses", exchange -> {
            attempts.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            byte[] bytes = "{\"error\":{\"code\":\"invalid_api_key\",\"message\":\"sensitive\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(401, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            var gateway = new OpenAiResponsesGateway(new ObjectMapper(), settings(server.getAddress().getPort()));
            assertThatThrownBy(() -> gateway.call(request(), null).block(Duration.ofSeconds(10)))
                    .isInstanceOf(ModelFailure.class).hasMessageNotContaining("sensitive");
            assertThat(attempts.get()).isEqualTo(1);
        } finally { server.stop(0); }
    }
    @Test void qwenAuxiliaryUsesItsOwnCredentialAndNormalizesStructuredJson() throws Exception {
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var received=new java.util.concurrent.atomic.AtomicReference<com.fasterxml.jackson.databind.JsonNode>();
        var auth=new java.util.concurrent.atomic.AtomicReference<String>();
        var mapper=new ObjectMapper();
        server.createContext("/qwen/responses",exchange->{
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            received.set(mapper.readTree(exchange.getRequestBody()));
            var body=mapper.createObjectNode().put("status","completed").put("model","qwen3.8-flash");
            body.putObject("usage").put("input_tokens",20).put("output_tokens",10);
            body.putArray("output").addObject().put("type","message").putArray("content").addObject().put("type","output_text").put("text","```json\n{\"ok\":true}\n```");
            byte[] bytes=mapper.writeValueAsBytes(body);exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });server.start();
        try {
            var base=settings(server.getAddress().getPort());
            var configured=new ModelSettings(base.baseUrl(),"openai-test","gpt-5.6-sol","qwen3.8-flash","text-embedding-v4","medium","low",base.connectTimeout(),base.idleTimeout(),base.nonStreamTimeout(),0,base.primaryContextWindow(),base.auxiliaryContextWindow(),base.maxOutputTokens(),Map.of());
            var gateway=new OpenAiResponsesGateway(mapper,configured);
            gateway.setQwen(new com.rronin.financialagent.config.QwenSettings("http://127.0.0.1:"+server.getAddress().getPort()+"/qwen","qwen-test"));
            gateway.selectPrimary("run","qwen3.8-max");
            var request=new ModelGateway.Request("run",ModelGateway.Role.CLAIM_EXTRACTION,"Extract",List.of(AgentMessage.text("run",AgentMessage.Role.USER,"data",false)),List.of(),7000,mapper.readTree("{\"type\":\"object\"}"));
            var reply=gateway.call(request,null).block(Duration.ofSeconds(10));
            assertThat(auth.get()).isEqualTo("Bearer qwen-test");
            assertThat(received.get().path("model").asText()).isEqualTo("qwen3.8-flash");
            assertThat(received.get().path("reasoning").path("effort").asText()).isEqualTo("low");
            assertThat(received.get().path("instructions").asText()).contains("JSON object");
            assertThat(reply.message().text()).isEqualTo("{\"ok\":true}");
            assertThat(gateway.recordedInputTokens("run")).isEqualTo(20);
            gateway.restoreOutputUsage("run",com.rronin.financialagent.config.TokenBudgetPolicy.RUN_OUTPUT-1_000);
            assertThatThrownBy(()->gateway.call(request,null).block(Duration.ofSeconds(10)))
                    .hasMessageContaining("cannot fund a complete auxiliary call");
            assertThat(gateway.recordedOutputTokens("run")).isEqualTo(com.rronin.financialagent.config.TokenBudgetPolicy.RUN_OUTPUT-1_000);
        }finally{server.stop(0);}
    }

}
