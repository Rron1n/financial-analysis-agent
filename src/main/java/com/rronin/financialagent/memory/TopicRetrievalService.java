package com.rronin.financialagent.memory;

import com.rronin.financialagent.agent.TokenEstimator;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import java.time.*;
import java.util.*;

/** Hybrid recall. Vectors are derived hints; returned evidence is reread from authoritative files. */
@Service
public class TopicRetrievalService {
    private final TopicFileStore files;
    private final ChromaMemoryIndex chroma;
    private final MemoryLanguageService language;
    private final Set<String> indexing = java.util.concurrent.ConcurrentHashMap.newKeySet();
    public TopicRetrievalService(TopicFileStore files, ChromaMemoryIndex chroma, MemoryLanguageService language) { this.files = files; this.chroma = chroma; this.language=language; }
    public Mono<String> retrieve(String query, List<com.rronin.financialagent.model.AgentMessage> recent) {
        return language.query(query,recent).flatMap(this::retrieve);
    }
    public Mono<String> retrieve(String query) {
        return Mono.zip(Mono.fromCallable(files::list).subscribeOn(Schedulers.boundedElastic()),
                chroma.searchDetailed(query, 20).timeout(Duration.ofSeconds(20)).onErrorReturn(ChromaMemoryIndex.SearchResponse.empty()))
                .flatMap(tuple -> Mono.fromCallable(() -> rank(query, tuple.getT1(), tuple.getT2())).subscribeOn(Schedulers.boundedElastic()))
                .onErrorReturn("");
    }
    private String rank(String query, List<TopicFileStore.Topic> topics, ChromaMemoryIndex.SearchResponse response) throws Exception {
        Set<String> terms = terms(query);
        Map<String, TopicFileStore.Topic> byPath = new HashMap<>(); topics.forEach(t -> byPath.put(t.path(), t));
        List<TopicFileStore.Topic> coarse = topics.stream()
                .filter(t -> lexicalScore(terms,t,false)>0)
                .sorted(Comparator.comparingDouble((TopicFileStore.Topic t)->lexicalScore(terms,t,false)).reversed()).limit(12).toList();
        List<TopicFileStore.Topic> lexical = coarse.stream().filter(t -> lexicalEligible(query,terms,t))
                .sorted(Comparator.comparingDouble((TopicFileStore.Topic t)->lexicalScore(terms,t,true)).reversed()).limit(10).toList();
        Map<String, Double> scores = new HashMap<>();
        Map<String, List<Double>> vectors = new HashMap<>();
        Map<String,Integer> keywordRanks=new HashMap<>(),vectorRanks=new HashMap<>();
        Map<String,Double> vectorSimilarities=new HashMap<>();
        for(int i=0;i<lexical.size();i++)keywordRanks.put(lexical.get(i).path(),i+1);
        for (int i = 0; i < lexical.size(); i++) scores.put(lexical.get(i).path(), 1.0 / (61 + i));
        Set<String> ranked = new HashSet<>();
        int rank = 0;
        for (var hit : response.hits()) {
            if (!Double.isFinite(hit.distance()) || 1.0-hit.distance()<.35) continue;
            String path = Objects.toString(hit.metadata().get("path"), "");
            var topic = byPath.get(path);
            if (topic == null) continue;
            if (!topic.version().equals(Objects.toString(hit.metadata().get("version"), ""))) { refresh(topic); continue; }
            if (ranked.add(path)) { scores.merge(path, 1.0 / (61 + rank++), Double::sum); vectors.put(path, hit.embedding()); vectorRanks.put(path,rank);vectorSimilarities.put(path,1-hit.distance()); }
        }
        for (var topic : topics) if (!topic.version().equals(chroma.indexedVersion(topic.path()))) refresh(topic);
        double maxRrf=scores.values().stream().mapToDouble(Double::doubleValue).max().orElse(1);
        scores.replaceAll((path, score) -> {
            var t = byPath.get(path);
            return score / maxRrf * temporalScore(t.type(), t.updatedAt(), Instant.now());
        });
        List<String> selected = new ArrayList<>();
        StringBuilder content = new StringBuilder();
        int budget = com.rronin.financialagent.config.TokenBudgetPolicy.TOPIC_RECALL_TOKENS;
        while (!scores.isEmpty() && selected.size() < 5) {
            String best = scores.keySet().stream().max(Comparator.comparingDouble(path -> .72 * scores.get(path) - .28 * selected.stream()
                    .mapToDouble(other -> similarity(path, other, vectors, byPath)).max().orElse(0))).orElseThrow();
            scores.remove(best);
            var current = files.parse(best, files.read(best));
            String text = current.text();
            if (TokenEstimator.text(text) > budget) text = matchedSections(text, terms, budget);
            if (text.isBlank()) continue;
            content.append("\n<retrieved-topic path=\"").append(best).append("\" version=\"").append(current.version()).append("\">\n")
                    .append("Retrieval metadata: keywordRank=").append(keywordRanks.get(best)).append(", keywordScore=").append(lexicalScore(terms,current,true))
                    .append(", vectorRank=").append(vectorRanks.get(best)).append(", vectorSimilarity=").append(vectorSimilarities.get(best)).append("\n")
                    .append(text).append("\n</retrieved-topic>\n");
            budget -= TokenEstimator.text(text);
            selected.add(best);
            if (budget <= 0) break;
        }
        return content.toString();
    }
    static double lexicalScore(Set<String> query, TopicFileStore.Topic topic, boolean body) {
        return 12*overlap(query,terms(String.join(" ",topic.entities())))
                + 8*overlap(query,terms(String.join(" ",topic.tags())))
                + 5*overlap(query,terms(topic.title()+" "+topic.path()))
                + 3*overlap(query,terms(topic.description()))
                + overlap(query,terms(topic.type()))
                + (body?overlap(query,terms(topic.text())):0);
    }
    static boolean lexicalEligible(String query, Set<String> terms, TopicFileStore.Topic topic) {
        if(overlap(terms,terms(String.join(" ",topic.entities())+" "+String.join(" ",topic.tags())))>0)return true;
        var ticker=java.util.regex.Pattern.compile("\\b[A-Z]{1,6}\\b").matcher(query);
        while(ticker.find())if(terms(topic.title()+" "+topic.path()+" "+topic.text()).contains(ticker.group().toLowerCase(Locale.ROOT)))return true;
        String phrase=query.trim().toLowerCase(Locale.ROOT);
        if(phrase.length()>=4 && topic.text().toLowerCase(Locale.ROOT).contains(phrase))return true;
        return overlap(terms,terms(topic.text()))>=2;
    }
    static double temporalScore(String type, Instant updatedAt, Instant now) {
        if (type.equals("user") || type.equals("investment")) return 1.0;
        double halfLife = type.equals("research") ? 60.0 : 365.0;
        double age = Math.max(0, Duration.between(updatedAt, now).toSeconds() / 86400.0);
        return .35 + .65 * Math.pow(.5, age / halfLife);
    }
    public void refresh(TopicFileStore.Topic topic) {
        if (!chroma.enabled() || topic.version().equals(chroma.indexedVersion(topic.path())) || !indexing.add(topic.path())) return;
        language.chunks(topic.text()).flatMapMany(chunks -> Flux.range(0, chunks.size()).concatMap(i -> chroma.index(topic.path() + ":" + topic.version() + ":" + i, chunks.get(i),
                Map.of("path", topic.path(), "memoryId", topic.path(), "title", topic.title(), "version", topic.version(), "type", topic.type(), "chunk", i))))
                .doOnComplete(() -> chroma.markIndexed(topic.path(), topic.version()))
                .doFinally(signal -> indexing.remove(topic.path())).subscribe(ignored -> { }, ignored -> { });
    }
    static List<String> chunks(String text) {
        List<String> chunks = new ArrayList<>();
        for (String section : text.split("(?m)(?=^#{1,6} )")) {
            for (int start = 0; start < section.length(); start += 6000) chunks.add(section.substring(start, Math.min(section.length(), start + 6000)));
        }
        return chunks;
    }
    private String matchedSections(String text, Set<String> terms, int budget) {
        StringBuilder result = new StringBuilder();
        for (String section : chunks(text)) if (overlap(terms, terms(section)) > 0 && TokenEstimator.text(result + section) <= budget) result.append(section);
        return result.toString();
    }
    static Set<String> terms(String text) {
        Set<String> result = new HashSet<>();
        for (String word : text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}_]+")) {
            if (word.isBlank() || Set.of("the","a","an","and","or","of","for","in","to","is","it","this","that","please","我","的","了","帮我","分析","如何").contains(word)) continue;
            result.add(word);
            if (word.codePoints().anyMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN))
                for (int i = 0; i + 1 < word.length(); i++) result.add(word.substring(i, i + 2));
        }
        return result;
    }
    private static double overlap(Set<String> a, Set<String> b) { return a.stream().filter(b::contains).count(); }
    private double similarity(String a, String b, Map<String, List<Double>> vectors, Map<String, TopicFileStore.Topic> topics) {
        var x = vectors.getOrDefault(a, List.of()); var y = vectors.getOrDefault(b, List.of());
        if (!x.isEmpty() && x.size() == y.size()) {
            double dot = 0, xx = 0, yy = 0;
            for (int i = 0; i < x.size(); i++) { dot += x.get(i) * y.get(i); xx += x.get(i) * x.get(i); yy += y.get(i) * y.get(i); }
            return xx == 0 || yy == 0 ? 0 : dot / Math.sqrt(xx * yy);
        }
        var ta = terms(topics.get(a).description()); var tb = terms(topics.get(b).description());
        double common = overlap(ta, tb); return common / Math.max(1, ta.size() + tb.size() - common);
    }
}
