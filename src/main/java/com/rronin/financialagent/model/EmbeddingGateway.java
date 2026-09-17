package com.rronin.financialagent.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rronin.financialagent.config.ModelSettings;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.ResponseErrorHandler;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Spring AI owns embedding invocation; the agent consumes only provider-independent vectors. */
@Component
public class EmbeddingGateway {
    private final ModelSettings settings;
    private com.rronin.financialagent.config.QwenSettings qwen;
    @org.springframework.beans.factory.annotation.Autowired public void setQwen(com.rronin.financialagent.config.QwenSettings value){qwen=value;}
    private final ObjectMapper mapper;
    public EmbeddingGateway(ModelSettings settings, ObjectMapper mapper) { this.settings = settings; this.mapper = mapper; }
    public Mono<List<Double>> embed(String text) {
        return Mono.fromCallable(() -> {
            if ((qwen == null ? settings.apiKey() : qwen.apiKey()) == null || (qwen == null ? settings.apiKey() : qwen.apiKey()).isBlank()) throw new ModelFailure(ModelFailure.Kind.AUTHENTICATION, 0);
            var factory = new JdkClientHttpRequestFactory(com.rronin.financialagent.config.ProxySupport.apply(java.net.http.HttpClient.newBuilder()).connectTimeout(settings.connectTimeout()).build());
            factory.setReadTimeout(settings.nonStreamTimeout());
            var api = OpenAiApi.builder().baseUrl((qwen == null ? settings.baseUrl() : qwen.baseUrl()).replaceAll("/v1/?$", ""))
                    .apiKey((qwen == null ? settings.apiKey() : qwen.apiKey())).restClientBuilder(RestClient.builder().requestFactory(factory))
                    .responseErrorHandler(new ResponseErrorHandler() {
                        @Override public boolean hasError(org.springframework.http.client.ClientHttpResponse response) throws IOException { return response.getStatusCode().isError(); }
                        @Override public void handleError(java.net.URI url, org.springframework.http.HttpMethod method,
                                                         org.springframework.http.client.ClientHttpResponse response) throws IOException {
                            String code = mapper.readTree(response.getBody()).path("error").path("code").asText();
                            throw ModelFailure.http(response.getStatusCode().value(), code);
                        }
                    }).build();
            var model = new OpenAiEmbeddingModel(api, MetadataMode.NONE,
                    OpenAiEmbeddingOptions.builder().model(settings.embedding()).build(),
                    org.springframework.retry.support.RetryTemplate.builder().maxAttempts(settings.transientRetries() + 1)
                            .fixedBackoff(1000).retryOn(error -> error instanceof ModelFailure f && f.retryable()).build());
            float[] vector = model.embed(text);
            List<Double> values = new ArrayList<>(vector.length);
            for (float value : vector) values.add((double) value);
            return values;
        }).subscribeOn(Schedulers.boundedElastic());
    }
}
