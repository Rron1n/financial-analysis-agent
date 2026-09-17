package com.rronin.financialagent.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rronin.financialagent.config.AgentProperties;
import com.rronin.financialagent.model.AgentMessage;
import com.rronin.financialagent.model.EmbeddingGateway;
import com.rronin.financialagent.session.SessionStore;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Explicit cross-session recall. Chroma stores derived hints; excerpts always come from current session files. */
@Service
public class SessionRecallService {
    record Unit(String firstMessageId,String lastMessageId,String text) { }
    record Snapshot(String id,String title,String version,String memory,List<Unit> units,Instant createdAt,Instant updatedAt) { }
    public record Hit(String sessionId,String title,String version,String firstMessageId,String lastMessageId,
                      double keywordScore,double vectorSimilarity,String excerpt,boolean truncated,Integer keywordRank,Integer vectorRank) {
        public Hit(String sessionId,String title,String version,String firstMessageId,String lastMessageId,double keywordScore,double vectorSimilarity,String excerpt,boolean truncated){this(sessionId,title,version,firstMessageId,lastMessageId,keywordScore,vectorSimilarity,excerpt,truncated,null,null);}
    }
    private final SessionStore sessions;
    private final ChromaMemoryIndex chroma;
    private final MemoryLanguageService language;
    private final Set<String> indexing=ConcurrentHashMap.newKeySet();
    public SessionRecallService(SessionStore sessions,AgentProperties properties,WebClient.Builder web,ObjectMapper mapper,EmbeddingGateway embeddings,MemoryLanguageService language){
        this.sessions=sessions;this.language=language;this.chroma=new ChromaMemoryIndex(properties,web,mapper,embeddings,"financial-agent-session-memory");
    }
    public Mono<List<Hit>> search(String query,String currentSessionId){
        var keyword=Mono.fromCallable(()->keywordCandidates(query,currentSessionId)).subscribeOn(Schedulers.boundedElastic())
                .timeout(Duration.ofSeconds(3)).onErrorReturn(List.of());
        var vector=chroma.searchDetailed(query,40).timeout(Duration.ofSeconds(4)).onErrorReturn(ChromaMemoryIndex.SearchResponse.empty()).defaultIfEmpty(ChromaMemoryIndex.SearchResponse.empty());
        return Mono.zip(keyword,vector).flatMap(tuple->Mono.fromCallable(()->merge(query,currentSessionId,tuple.getT1(),tuple.getT2())).subscribeOn(Schedulers.boundedElastic()));
    }
    private List<Snapshot> keywordCandidates(String query,String current) throws Exception {
        Set<String> terms=TopicRetrievalService.terms(query);
        var candidates=sessions.list().stream().filter(meta->!current.equals(meta.path("sessionId").asText()))
                .sorted(Comparator.comparingDouble((com.fasterxml.jackson.databind.node.ObjectNode meta)->12*score(terms,meta.path("entities").toString())+6*score(terms,meta.path("title").asText())+3*score(terms,meta.path("description").asText())+score(terms,meta.path("createdAt").asText()+" "+meta.path("updatedAt").asText())).reversed()).limit(20).toList();
        List<Snapshot> result=new ArrayList<>();
        for(var meta:candidates){if(Thread.currentThread().isInterrupted())break;try{var snapshot=snapshot(meta.path("sessionId").asText());result.add(snapshot);refresh(snapshot);}catch(Exception ignored){}}
        return result;
    }
    private List<Hit> merge(String query,String current,List<Snapshot> keyword,ChromaMemoryIndex.SearchResponse vector) throws Exception {
        Set<String> terms=TopicRetrievalService.terms(query);
        Map<String,Snapshot> snapshots=new LinkedHashMap<>();keyword.forEach(snapshot->snapshots.put(snapshot.id(),snapshot));
        Map<String,Double> similarities=new HashMap<>();
        Map<String,Set<String>> matchingUnits=new HashMap<>();
        Map<String,List<Double>> bestVectors=new HashMap<>();
        for(var hit:vector.hits()){
            String id=Objects.toString(hit.metadata().get("sessionId"),"");double similarity=1-hit.distance();
            if(id.equals(current)||!Double.isFinite(similarity)||similarity<.35)continue;
            try{
                Snapshot source=snapshots.get(id);if(source==null){source=snapshot(id);snapshots.put(id,source);}
                if(!source.version().equals(Objects.toString(hit.metadata().get("version"),""))){refresh(source);continue;}
                if(similarity>similarities.getOrDefault(id,-1.0)&&!hit.embedding().isEmpty())bestVectors.put(id,hit.embedding());
                similarities.merge(id,similarity,Math::max);
                if("transcript".equals(hit.metadata().get("kind"))) matchingUnits.computeIfAbsent(id,key->new HashSet<>()).add(Objects.toString(hit.metadata().get("firstMessageId"),""));
            }catch(Exception ignored){/* Deleted, unavailable or malformed sessions cannot be recalled from stale vectors. */}
        }
        List<Hit> hits=new ArrayList<>();
        for(var source:snapshots.values()){
            double titleScore=score(terms,source.title()),memoryScore=score(terms,source.memory());
            List<Unit> units=source.units().stream().sorted(Comparator.comparingDouble((Unit unit)->score(terms,unit.text())+(matchingUnits.getOrDefault(source.id(),Set.of()).contains(unit.firstMessageId())?100:0)).reversed()).limit(3).toList();
            double best=units.stream().mapToDouble(unit->score(terms,unit.text())).max().orElse(0);
            boolean eligible=lexicalEligible(query,source.title(),source.memory()+source.units().stream().map(Unit::text).collect(java.util.stream.Collectors.joining("\n")),source.createdAt(),source.updatedAt());
            double lexical=eligible?Math.max(1,titleScore*6+memoryScore*3+best):0;
            if(!eligible&&!similarities.containsKey(source.id()))continue;
            for(var unit:units){
                String text=unit.text();boolean truncated=text.length()>14000;
                if(truncated)text=text.substring(0,14000)+"\n[Excerpt truncated; the full semantic unit spans the message IDs above.]";
                hits.add(new Hit(source.id(),source.title(),source.version(),unit.firstMessageId(),unit.lastMessageId(),lexical,similarities.getOrDefault(source.id(),0.0),text,truncated));
            }
        }
        Map<String,Double> ranks=new HashMap<>();
        var lexicalOrder=hits.stream().filter(hit->hit.keywordScore()>0).sorted(Comparator.comparingDouble(Hit::keywordScore).reversed()).map(Hit::sessionId).distinct().toList();
        for(int i=0;i<lexicalOrder.size();i++)ranks.merge(lexicalOrder.get(i),1.0/(61+i),Double::sum);
        var semanticOrder=similarities.entrySet().stream().sorted(Map.Entry.<String,Double>comparingByValue().reversed()).toList();
        for(int i=0;i<semanticOrder.size();i++)ranks.merge(semanticOrder.get(i).getKey(),1.0/(61+i),Double::sum);
        double highest=ranks.values().stream().mapToDouble(Double::doubleValue).max().orElse(1);
        Map<String,Double> scores=new HashMap<>();ranks.forEach((id,rank)->scores.put(id,rank/highest*temporalScore(snapshots.get(id).updatedAt(),Instant.now())));
        var candidates=scores.keySet().stream().sorted(Comparator.comparingDouble((String id)->scores.get(id)).reversed()).limit(8).toList();
        var chosen=diverse(candidates,scores,bestVectors,3);
        List<Hit> result=new ArrayList<>();for(String id:chosen)hits.stream().filter(hit->hit.sessionId().equals(id)).limit(3).forEach(result::add);
        return withinBudget(result,24000).stream().map(hit->new Hit(hit.sessionId(),hit.title(),hit.version(),hit.firstMessageId(),hit.lastMessageId(),hit.keywordScore(),hit.vectorSimilarity(),hit.excerpt(),hit.truncated(),
                lexicalOrder.contains(hit.sessionId())?lexicalOrder.indexOf(hit.sessionId())+1:null,
                similarities.containsKey(hit.sessionId())?java.util.stream.IntStream.range(0,semanticOrder.size()).filter(i->semanticOrder.get(i).getKey().equals(hit.sessionId())).findFirst().orElseThrow()+1:null)).toList();
    }
    static boolean lexicalEligible(String query,String title,String text,Instant created,Instant updated){
        String normalized=query.trim().toLowerCase(Locale.ROOT),all=(title+"\n"+text).toLowerCase(Locale.ROOT);
        Set<String> terms=TopicRetrievalService.terms(query);
        if(title.equalsIgnoreCase(query)||score(terms,all)>=2)return true;
        var ticker=java.util.regex.Pattern.compile("\\b[A-Z]{1,6}\\b").matcher(query);
        while(ticker.find())if(TopicRetrievalService.terms(all).contains(ticker.group().toLowerCase(Locale.ROOT)))return true;
        if(normalized.contains(" ")&&normalized.length()>=4&&all.contains(normalized))return true;
        var dates=java.util.regex.Pattern.compile("\\b\\d{4}-\\d{2}-\\d{2}\\b").matcher(query);
        while(dates.find())try{var date=LocalDate.parse(dates.group());if(!date.isBefore(created.atZone(ZoneOffset.UTC).toLocalDate())&&!date.isAfter(updated.atZone(ZoneOffset.UTC).toLocalDate()))return true;}catch(java.time.DateTimeException ignored){}
        return false;
    }
    static double temporalScore(Instant updatedAt,Instant now){return .35+.65*Math.pow(.5,Math.max(0,Duration.between(updatedAt,now).toDays())/180.0);}
    static List<String> diverse(List<String> candidates,Map<String,Double> scores,Map<String,List<Double>> vectors,int limit){
        List<String> remaining=new ArrayList<>(candidates),selected=new ArrayList<>();
        while(!remaining.isEmpty()&&selected.size()<limit){
            String best=null;double bestScore=Double.NEGATIVE_INFINITY;
            for(String id:remaining){double similarity=selected.stream().mapToDouble(other->cosine(vectors.get(id),vectors.get(other))).max().orElse(0);
                double value=.7*scores.getOrDefault(id,0.0)-.3*similarity;
                if(value>bestScore){best=id;bestScore=value;}}
            selected.add(best);remaining.remove(best);
        }
        return List.copyOf(selected);
    }
    private static double cosine(List<Double> a,List<Double> b){
        if(a==null||b==null||a.isEmpty()||a.size()!=b.size())return 0;
        double dot=0,aa=0,bb=0;for(int i=0;i<a.size();i++){dot+=a.get(i)*b.get(i);aa+=a.get(i)*a.get(i);bb+=b.get(i)*b.get(i);}
        double value=dot/Math.sqrt(aa*bb);return Double.isFinite(value)?Math.max(0,Math.min(1,value)):0;
    }
    /** Preserve each session's primary excerpt before admitting secondary excerpts. */
    static List<Hit> withinBudget(List<Hit> hits,int maxCharacters){
        Map<String,List<Hit>> groups=new LinkedHashMap<>();
        for(var hit:hits)groups.computeIfAbsent(hit.sessionId(),key->new ArrayList<>()).add(hit);
        List<Hit> selected=new ArrayList<>();int used=0;
        for(int index=0;index<3;index++)for(var group:groups.values())if(index<group.size()){
            var hit=group.get(index);if(used+hit.excerpt().length()<=maxCharacters){selected.add(hit);used+=hit.excerpt().length();}
        }
        return List.copyOf(selected);
    }
    private Snapshot snapshot(String id) throws Exception {
        synchronized(sessions.lock(id)){
            var meta=sessions.metadata(id);String memory=Files.readString(sessions.directory(id).resolve("session-memory.md"));
            List<AgentMessage> messages=SessionMemoryService.completePrefix(sessions.messages(id));
            List<Unit> units=units(messages);
            String source=memory+units.stream().map(unit->unit.firstMessageId()+unit.lastMessageId()+unit.text()).collect(java.util.stream.Collectors.joining());
            String version=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8)));
            return new Snapshot(id,meta.path("title").asText(),version,memory,units,Instant.parse(meta.path("createdAt").asText()),Instant.parse(meta.path("updatedAt").asText()));
        }
    }
    static List<Unit> units(List<AgentMessage> messages){
        List<Unit> result=new ArrayList<>();List<AgentMessage> exchange=new ArrayList<>();
        for(var message:messages){if(message.role()==AgentMessage.Role.USER&&!message.meta()&&!exchange.isEmpty()){result.add(unit(exchange));exchange.clear();}exchange.add(message);}
        if(!exchange.isEmpty())result.add(unit(exchange));return List.copyOf(result);
    }
    private static Unit unit(List<AgentMessage> messages){
        StringBuilder text=new StringBuilder();
        for(var message:messages){text.append("\n").append(message.role()).append(" [").append(message.id()).append("]\n");
            for(var block:message.content()){text.append(block.type()).append(": ");if(block.toolCallId()!=null)text.append("call=").append(block.toolCallId()).append(' ');if(block.name()!=null)text.append("tool=").append(block.name()).append(' ');if(block.error())text.append("[error] ");if(block.input()!=null)text.append(block.input());if(block.text()!=null)text.append(block.text());text.append('\n');}}
        return new Unit(messages.getFirst().id(),messages.getLast().id(),text.toString());
    }
    private static double score(Set<String> terms,String text){Set<String> words=TopicRetrievalService.terms(text);return terms.stream().filter(words::contains).count();}
    public void refresh(String sessionId){Mono.fromCallable(()->snapshot(sessionId)).subscribeOn(Schedulers.boundedElastic()).subscribe(this::refresh,error->{});}
    private void refresh(Snapshot source){
        if(!chroma.enabled()||source.version().equals(chroma.indexedVersion(source.id()))||!indexing.add(source.id()))return;
        language.chunks(source.memory()).flatMapMany(chunks->Flux.range(0,chunks.size()).concatMap(i->chroma.index(source.id()+":"+source.version()+":memory:"+i,chunks.get(i),metadata(source,"memory",Integer.toString(i),""))))
                .thenMany(Flux.range(0,source.units().size()).concatMap(i->{var unit=source.units().get(i);return chroma.index(source.id()+":"+source.version()+":transcript:"+i,unit.text(),metadata(source,"transcript",unit.firstMessageId(),unit.lastMessageId()));}))
                .doOnComplete(()->chroma.markIndexed(source.id(),source.version())).doFinally(signal->indexing.remove(source.id())).subscribe(value->{},error->{});
    }
    private Map<String,Object> metadata(Snapshot source,String kind,String start,String end){return Map.of("sessionId",source.id(),"title",source.title(),"version",source.version(),"kind",kind,"firstMessageId",start,"lastMessageId",end);}
}
