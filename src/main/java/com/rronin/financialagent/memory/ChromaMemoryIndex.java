package com.rronin.financialagent.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rronin.financialagent.config.AgentProperties;
import com.rronin.financialagent.model.EmbeddingGateway;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class ChromaMemoryIndex {
    private static final String TENANT = "default_tenant";
    private static final String DATABASE = "default_database";
    private final String collectionName;

    private final AgentProperties properties;
    private final WebClient client;
    private final ObjectMapper mapper;
    private final EmbeddingGateway embeddings;
    private volatile String collectionId;
    private String manifestCollectionId="";
    private final java.nio.file.Path manifest;
    private final Map<String,String> indexedVersions=new java.util.concurrent.ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public ChromaMemoryIndex(AgentProperties properties, WebClient.Builder web, ObjectMapper mapper, EmbeddingGateway embeddings) {
        this(properties,web,mapper,embeddings,"financial-agent-topic-memory");
    }
    ChromaMemoryIndex(AgentProperties properties, WebClient.Builder web, ObjectMapper mapper, EmbeddingGateway embeddings, String collectionName) {
        if(!List.of("financial-agent-topic-memory","financial-agent-session-memory").contains(collectionName))throw new IllegalArgumentException("Invalid memory collection");
        this.collectionName=collectionName+"-embedding-v4";
        this.properties = properties;
        this.client = web.clone().baseUrl(properties.chroma().baseUrl()).build();
        this.mapper = mapper;
        this.embeddings = embeddings;
        this.manifest=properties.memory()==null||properties.memory().root()==null?null:properties.memory().root().resolveSibling("chroma").resolve("manifests").resolve(this.collectionName+".json");
        if(manifest!=null&&java.nio.file.Files.isRegularFile(manifest))try {
            var saved=mapper.readTree(java.nio.file.Files.readString(manifest));
            if(properties.chroma().baseUrl().equals(saved.path("baseUrl").asText())) {
                manifestCollectionId=saved.path("collectionId").asText();
                saved.path("versions").fields().forEachRemaining(entry->indexedVersions.put(entry.getKey(),entry.getValue().asText()));
            }
        }catch(Exception ignored){/* Derived index can be rebuilt from authoritative Markdown and transcript. */}
    }

    public String indexedVersion(String source) { return indexedVersions.get(source); }
    public synchronized void markIndexed(String source,String version) {
        indexedVersions.put(source,version);
        if(manifest==null)return;
        try {
            new com.rronin.financialagent.persistence.AtomicFileWriter().write(manifest,mapper.writeValueAsString(Map.of(
                    "schemaVersion",1,"baseUrl",properties.chroma().baseUrl(),"collection",collectionName,"collectionId",collectionId==null?manifestCollectionId:collectionId,
                    "embeddingModel","text-embedding-v4","updatedAt",java.time.Instant.now().toString(),"versions",indexedVersions)),false);
        }catch(Exception error){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Index manifest persistence failed; may re-index after restart ({})",error.getClass().getSimpleName());}
    }

    public Mono<Void> index(String id, String document, Map<String, Object> metadata) {
        if (!properties.chroma().enabled() || document == null || document.isBlank()) return Mono.empty();
        String safeId = id == null || id.isBlank() ? UUID.randomUUID().toString() : id;
        String safeDocument = document.length() <= 6_000 ? document : document.substring(0, 6_000);
        return embeddings.embed(safeDocument)
                .filter(vector -> !vector.isEmpty()).switchIfEmpty(Mono.error(new IllegalStateException("Embedding model returned no vector")))
                .flatMap(vector -> ensureCollectionId().flatMap(cid -> client.post()
                        .uri("/api/v2/tenants/{tenant}/databases/{database}/collections/{collectionId}/upsert", TENANT, DATABASE, cid)
                        .bodyValue(Map.of(
                                "ids", List.of(safeId),
                                "embeddings", List.of(vector),
                                "documents", List.of(safeDocument),
                                "metadatas", List.of(metadata == null ? Map.of() : metadata)
                        ))
                        .retrieve()
                        .bodyToMono(JsonNode.class)
                        .then()))
                .then();
    }

    public Mono<List<String>> search(String query, int limit) {
        return searchDetailed(query, limit).map(response -> response.hits().stream().map(ChromaHit::document).toList());
    }
    public boolean enabled() { return properties.chroma().enabled(); }

    public Mono<List<String>> search(List<Double> embedding, int limit) {
        return searchDetailed(embedding, limit).map(response -> response.hits().stream().map(ChromaHit::document).toList());
    }

    public Mono<SearchResponse> searchDetailed(String query, int limit) {
        if (!properties.chroma().enabled() || query == null || query.isBlank()) return Mono.just(SearchResponse.empty());
        return embeddings.embed(query)
                .filter(vector -> !vector.isEmpty())
                .flatMap(vector -> searchDetailed(vector, limit))
                .defaultIfEmpty(SearchResponse.empty())
                .onErrorReturn(SearchResponse.empty());
    }

    public Mono<SearchResponse> searchDetailed(List<Double> queryEmbedding, int limit) {
        if (!properties.chroma().enabled() || queryEmbedding == null || queryEmbedding.isEmpty()) return Mono.just(SearchResponse.empty());
        return ensureCollectionId().flatMap(cid -> client.post()
                        .uri("/api/v2/tenants/{tenant}/databases/{database}/collections/{collectionId}/query", TENANT, DATABASE, cid)
                        .bodyValue(Map.of(
                                "query_embeddings", List.of(queryEmbedding),
                                "n_results", Math.max(1, limit),
                                "include", List.of("documents", "metadatas", "distances", "embeddings")
                        ))
                        .retrieve().bodyToMono(JsonNode.class))
                .map(json -> parseSearchResponse(queryEmbedding, json))
                .onErrorResume(error -> searchDetailedWithoutEmbeddings(queryEmbedding, limit));
    }

    private Mono<SearchResponse> searchDetailedWithoutEmbeddings(List<Double> queryEmbedding, int limit) {
        return ensureCollectionId().flatMap(cid -> client.post()
                        .uri("/api/v2/tenants/{tenant}/databases/{database}/collections/{collectionId}/query", TENANT, DATABASE, cid)
                        .bodyValue(Map.of(
                                "query_embeddings", List.of(queryEmbedding),
                                "n_results", Math.max(1, limit),
                                "include", List.of("documents", "metadatas", "distances")
                        ))
                        .retrieve().bodyToMono(JsonNode.class))
                .map(json -> parseSearchResponse(queryEmbedding, json))
                .onErrorReturn(SearchResponse.empty());
    }

    private SearchResponse parseSearchResponse(List<Double> queryEmbedding, JsonNode json) {
        List<String> ids = mapper.convertValue(json.path("ids").path(0),
                mapper.getTypeFactory().constructCollectionType(List.class, String.class));
        List<String> documents = mapper.convertValue(json.path("documents").path(0),
                mapper.getTypeFactory().constructCollectionType(List.class, String.class));
        List<Double> distances = mapper.convertValue(json.path("distances").path(0),
                mapper.getTypeFactory().constructCollectionType(List.class, Double.class));
        JsonNode metadataNodes = json.path("metadatas").path(0);
        JsonNode embeddingNodes = json.path("embeddings").path(0);
        List<ChromaHit> hits = new ArrayList<>();
        int size = documents == null ? 0 : documents.size();
        for (int i = 0; i < size; i++) {
            Map<String, Object> metadata = metadataNodes.isArray() && metadataNodes.size() > i
                    ? mapper.convertValue(metadataNodes.get(i), mapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class))
                    : Map.of();
            List<Double> embedding = embeddingNodes.isArray() && embeddingNodes.size() > i
                    ? mapper.convertValue(embeddingNodes.get(i), mapper.getTypeFactory().constructCollectionType(List.class, Double.class))
                    : List.of();
            double distance = distances == null || distances.size() <= i || distances.get(i) == null ? 1.0 : distances.get(i);
            String id = ids == null || ids.size() <= i ? "chroma:" + i : ids.get(i);
            hits.add(new ChromaHit(id, documents.get(i), metadata == null ? Map.of() : metadata, distance, embedding == null ? List.of() : embedding));
        }
        return new SearchResponse(queryEmbedding, hits);
    }

    public Mono<Void> delete(String id) {
        if (!properties.chroma().enabled() || id == null || id.isBlank()) return Mono.empty();
        return ensureCollectionId().flatMap(cid -> client.post()
                        .uri("/api/v2/tenants/{tenant}/databases/{database}/collections/{collectionId}/delete", TENANT, DATABASE, cid)
                        .bodyValue(Map.of("ids", List.of(id)))
                        .retrieve()
                        .bodyToMono(JsonNode.class)
                        .then())
                .then();
    }

    private Mono<String> ensureCollectionId() {
        if (collectionId != null && !collectionId.isBlank()) return Mono.just(collectionId);
        ObjectNode body = mapper.createObjectNode();
        body.put("name", collectionName);
        body.put("get_or_create", true);
        body.set("configuration", mapper.valueToTree(Map.of("hnsw", Map.of("space", "cosine"))));
        ObjectNode metadata = mapper.createObjectNode();
        metadata.put("purpose", "financial-agent-memory");
        body.set("metadata", metadata);
        return client.post().uri("/api/v2/tenants/{tenant}/databases/{database}/collections", TENANT, DATABASE)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .map(json -> {
                    String space = json.path("configuration_json").path("hnsw").path("space").asText("cosine");
                    if (!"cosine".equals(space)) throw new IllegalStateException("Memory collection requires a cosine index rebuild");
                    collectionId = json.path("id").asText();
                    if (collectionId.isBlank()) throw new IllegalStateException("Chroma collection id missing");
                    synchronized(this){if(!manifestCollectionId.isBlank()&&!manifestCollectionId.equals(collectionId))indexedVersions.clear();manifestCollectionId=collectionId;}
                    return collectionId;
                });
    }

    public record SearchResponse(List<Double> queryEmbedding, List<ChromaHit> hits) {
        static SearchResponse empty() { return new SearchResponse(List.of(), List.of()); }
    }

    public record ChromaHit(String id, String document, Map<String, Object> metadata, double distance, List<Double> embedding) {}
}
