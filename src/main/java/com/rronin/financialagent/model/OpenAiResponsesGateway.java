package com.rronin.financialagent.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rronin.financialagent.config.ModelSettings;
import io.netty.channel.ChannelOption;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import reactor.util.retry.Retry;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

@Component
public class OpenAiResponsesGateway implements ModelGateway {
    private final ObjectMapper mapper;
    private final ModelSettings settings;
    private final ResponsesCodec codec;
    private com.rronin.financialagent.config.QwenSettings qwen;
    @org.springframework.beans.factory.annotation.Autowired
    public void setQwen(com.rronin.financialagent.config.QwenSettings settings){qwen=settings;}
    private final java.util.concurrent.ConcurrentHashMap<String,String> selectedModels=new java.util.concurrent.ConcurrentHashMap<>();
    @Override public void selectPrimary(String runId,String model){
        if(!java.util.Set.of("gpt-5.6-sol","qwen3.8-max").contains(model))throw new IllegalArgumentException("Unsupported primary model");
        selectedModels.put(runId,model);
    }
    private String modelFor(Request request){return request.role()==Role.PRIMARY?selectedModels.getOrDefault(request.runId(),settings.primary()):settings.model(request.role());}
    private String keyFor(String model){return model.startsWith("qwen") && qwen!=null?qwen.apiKey():settings.apiKey();}
    private String urlFor(String model){return model.startsWith("qwen") && qwen!=null?qwen.baseUrl():settings.baseUrl();}
    private final java.util.concurrent.ConcurrentHashMap<String,java.util.concurrent.atomic.AtomicLong> inputUsage=new java.util.concurrent.ConcurrentHashMap<>();
    private final WebClient client;
    private final java.util.concurrent.ConcurrentHashMap<String, RunOutputBudget> outputUsage = new java.util.concurrent.ConcurrentHashMap<>();
    public OpenAiResponsesGateway(ObjectMapper mapper, ModelSettings settings) {
        this.mapper = mapper;
        this.settings = settings;
        this.codec = new ResponsesCodec(mapper);
        var http = com.rronin.financialagent.config.ProxySupport.apply(HttpClient.create()).option(ChannelOption.CONNECT_TIMEOUT_MILLIS, Math.toIntExact(settings.connectTimeout().toMillis()));
        this.client = WebClient.builder().baseUrl(settings.baseUrl())
                .clientConnector(new ReactorClientHttpConnector(http))
                .codecs(c -> c.defaultCodecs().maxInMemorySize(16 * 1024 * 1024)).build();
    }
    @Override public Mono<Reply> call(Request original, Consumer<StreamEvent> listener) {
        Consumer<StreamEvent> events = listener == null ? ignored -> { } : listener;
        return Mono.defer(() -> {
            if (keyFor(modelFor(original)) == null || keyFor(modelFor(original)).isBlank()) return Mono.error(new ModelFailure(ModelFailure.Kind.AUTHENTICATION, 0));
            var reservation = outputUsage.computeIfAbsent(original.runId(), ignored -> new RunOutputBudget(com.rronin.financialagent.config.TokenBudgetPolicy.RUN_OUTPUT))
                    .reserve(Math.min(original.maxOutputTokens(), settings.maxOutputTokens()));
            if (original.role()!=Role.PRIMARY && reservation.limit()<Math.min(original.maxOutputTokens(),settings.maxOutputTokens())) {
                reservation.settle(0);
                return Mono.error(new IllegalStateException("Run output token budget cannot fund a complete auxiliary call; stopped before spending the remaining tokens"));
            }
            Request request = new Request(original.runId(), original.role(), original.instructions(), original.messages(), original.tools(),
                    reservation.limit(), original.outputSchema());
            // Each retry constructs a fresh stream and discards any partial assistant message.
            return stream(request, events).onErrorResume(this::isStreamFailure, first -> {
                events.accept(new StreamEvent("message_reset", Map.of("reason", "stream_retry")));
                return stream(request, events);
            }).onErrorResume(this::isStreamFailure, second -> {
                events.accept(new StreamEvent("message_reset", Map.of("reason", "nonstream_fallback")));
                return nonStream(request);
            }).onErrorResume(error -> request.role() == Role.PRIMARY && shouldFallback(error), error -> {
                events.accept(new StreamEvent("message_reset", Map.of("reason", "fallback_model")));
                return nonStream(new Request(request.runId(), Role.FALLBACK, request.instructions(), request.messages(), request.tools(), request.maxOutputTokens(), request.outputSchema()));
            }).retryWhen(Retry.backoff(settings.transientRetries(), Duration.ofSeconds(1)).maxBackoff(Duration.ofSeconds(15))
                    .filter(error -> error instanceof ModelFailure f && f.retryable())
                    .doBeforeRetry(retry -> events.accept(new StreamEvent("model_retry", Map.of("attempt", retry.totalRetries() + 1)))))
                    .onErrorMap(reactor.core.Exceptions::isRetryExhausted, Throwable::getCause)
                    .doOnNext(reply -> { org.slf4j.LoggerFactory.getLogger(OpenAiResponsesGateway.class).info("Model usage run={} role={} model={} input={} output={} stop={}",original.runId(),original.role(),reply.model(),reply.inputTokens(),reply.outputTokens(),reply.stopReason()); reservation.settle(reply.outputTokens()); inputUsage.computeIfAbsent(original.runId(), ignored -> new java.util.concurrent.atomic.AtomicLong()).addAndGet(reply.inputTokens()); })
                    .doFinally(signal -> reservation.settle(0));
        });
    }
    @Override public long recordedInputTokens(String runId) { var count=inputUsage.get(runId);return count==null?0:count.get(); }
    @Override public long recordedOutputTokens(String runId) {
        var count = outputUsage.get(runId);
        return count == null ? 0 : count.used();
    }
    @Override public void releaseRun(String runId) { outputUsage.remove(runId); inputUsage.remove(runId); selectedModels.remove(runId); }
    @Override public void restoreOutputUsage(String runId, long tokens) {
        outputUsage.computeIfAbsent(runId, ignored -> new RunOutputBudget(com.rronin.financialagent.config.TokenBudgetPolicy.RUN_OUTPUT)).restore(tokens);
    }
    private Mono<Reply> stream(Request request, Consumer<StreamEvent> events) {
        return Mono.defer(() -> {
            AtomicReference<JsonNode> completed = new AtomicReference<>();
            ObjectNode body = codec.encode(request, modelFor(request), settings.reasoning(request.role()), settings.maxOutputTokens(), true);
            return post(body).accept(MediaType.TEXT_EVENT_STREAM).exchangeToFlux(response -> {
                if (response.statusCode().isError()) return response.bodyToMono(JsonNode.class)
                        .defaultIfEmpty(mapper.createObjectNode()).flatMapMany(node -> Flux.error(ModelFailure.http(response.statusCode().value(), node.path("error"))));
                return response.bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {});
            }).timeout(settings.idleTimeout()).doOnNext(frame -> {
                // Comment-only heartbeats also reset timeout because timeout is before parsing/filtering.
                if (frame.data() == null || frame.data().isBlank() || "[DONE]".equals(frame.data())) return;
                JsonNode node;
                try { node = mapper.readTree(frame.data()); }
                catch (Exception error) { throw new ModelFailure(ModelFailure.Kind.INCOMPLETE_STREAM, 0); }
                String type = node.path("type").asText();
                switch (type) {
                    case "response.created" -> events.accept(new StreamEvent("message_start", Map.of()));
                    case "response.output_text.delta" -> events.accept(new StreamEvent("text_delta", Map.of("text", node.path("delta").asText())));
                    case "response.function_call_arguments.delta" -> events.accept(new StreamEvent("tool_input_delta", Map.of("itemId", node.path("item_id").asText(), "delta", node.path("delta").asText())));
                    case "response.output_item.added" -> events.accept(new StreamEvent("content_block_start", node.path("item")));
                    case "response.output_item.done" -> events.accept(new StreamEvent("content_block_stop", node.path("item")));
                    case "response.completed", "response.incomplete" -> completed.set(node.path("response"));
                    case "response.failed", "error" -> throw ModelFailure.http(0, node.has("response")?node.path("response").path("error"):node.has("error")?node.path("error"):node);
                    default -> { }
                }
            }).then(Mono.defer(() -> {
                if (completed.get() == null) return Mono.error(new ModelFailure(ModelFailure.Kind.INCOMPLETE_STREAM, 0));
                Reply reply = decode(request, completed.get());
                events.accept(new StreamEvent("message_stop", Map.of("inputTokens", reply.inputTokens(), "outputTokens", reply.outputTokens(), "visibleCharacters",reply.message().text().length(), "reasoningTokens",completed.get().path("usage").path("output_tokens_details").path("reasoning_tokens").asLong())));
                return Mono.just(reply);
            })).onErrorMap(TimeoutException.class, error -> new ModelFailure(ModelFailure.Kind.TIMEOUT, 0))
                    .onErrorMap(org.springframework.web.reactive.function.client.WebClientRequestException.class,
                            error -> new ModelFailure(ModelFailure.Kind.TRANSPORT, 0));
        });
    }
    private Mono<Reply> nonStream(Request request) {
        ObjectNode body = codec.encode(request, modelFor(request), settings.reasoning(request.role()), settings.maxOutputTokens(), false);
        return post(body).exchangeToMono(response -> response.bodyToMono(JsonNode.class).defaultIfEmpty(mapper.createObjectNode()).flatMap(node -> {
            if (response.statusCode().isError()) return Mono.error(ModelFailure.http(response.statusCode().value(), node.path("error")));
            return Mono.just(decode(request, node));
        })).timeout(settings.nonStreamTimeout()).onErrorMap(TimeoutException.class, error -> new ModelFailure(ModelFailure.Kind.TIMEOUT, 0))
                .onErrorMap(org.springframework.web.reactive.function.client.WebClientRequestException.class, error -> new ModelFailure(ModelFailure.Kind.TRANSPORT, 0));
    }
    private Reply decode(Request request, JsonNode response) {
        Reply reply=codec.decode(request.runId(),response);
        if(request.outputSchema()==null || !"completed".equals(reply.stopReason()))return reply;
        String text=reply.message().text().trim();
        if(text.startsWith("```"))text=text.replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "");
        return new Reply(AgentMessage.text(request.runId(),AgentMessage.Role.ASSISTANT,text,false),reply.inputTokens(),reply.outputTokens(),reply.stopReason(),reply.model());
    }
    private WebClient.RequestHeadersSpec<?> post(ObjectNode body) {
        return client.post().uri(urlFor(body.path("model").asText()) + "/responses").header("Authorization", "Bearer " + keyFor(body.path("model").asText()))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body);
    }
    private boolean isStreamFailure(Throwable error) {
        return error instanceof ModelFailure f && (f.kind() == ModelFailure.Kind.TIMEOUT || f.kind() == ModelFailure.Kind.INCOMPLETE_STREAM || f.kind() == ModelFailure.Kind.TRANSPORT);
    }
    private boolean shouldFallback(Throwable error) {
        return error instanceof ModelFailure f && (f.retryable() || f.kind() == ModelFailure.Kind.MODEL_UNAVAILABLE);
    }
}
