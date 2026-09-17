package com.rronin.financialagent.events;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rronin.financialagent.config.AgentProperties;
import com.rronin.financialagent.integrations.ExternalApiClient;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class UpcomingEventService {
    private static final ZoneId ET = ZoneId.of("America/New_York");
    private final EventStore store;
    private final ExternalApiClient api;
    private final ObjectMapper mapper;
    private final AgentProperties properties;

    public UpcomingEventService(EventStore store, ExternalApiClient api, ObjectMapper mapper, AgentProperties properties) {
        this.store = store;
        this.api = api;
        this.mapper = mapper;
        this.properties = properties;
    }

    public List<UpcomingEvent> list(int months) {
        Instant now = Instant.now();
        store.expire(now);
        Instant until = ZonedDateTime.now(ET).plusMonths(months <= 0 ? monthsAhead() : months).toInstant();
        return store.load().stream()
                .filter(e -> e.startsAt() == null || (!e.startsAt().isBefore(now) && e.startsAt().isBefore(until)))
                .sorted(Comparator.comparing(UpcomingEvent::startsAt, Comparator.nullsLast(Comparator.naturalOrder())).thenComparing(UpcomingEvent::importance, Comparator.reverseOrder()))
                .toList();
    }

    public UpcomingEvent create(UpsertRequest request) {
        Instant now = Instant.now();
        UpcomingEvent item = normalize(new UpcomingEvent(
                UUID.randomUUID().toString(), required(request.title(), "title"), type(request.type()), safe(request.ticker()).toUpperCase(Locale.ROOT), safe(request.companyName()),
                parseInstant(required(request.startsAt(), "startsAt"), safe(request.timezone()).isBlank() ? "America/New_York" : request.timezone()),
                safe(request.timezone()).isBlank() ? "America/New_York" : request.timezone(), nonBlank(request.source(), "manual"), safe(request.sourceUrl()),
                importance(request.importance()), safe(request.notes()), now, now));
        List<UpcomingEvent> items = new ArrayList<>(store.load());
        items.add(item);
        store.save(dedupe(items));
        return item;
    }

    public UpcomingEvent update(String id, UpsertRequest request) {
        List<UpcomingEvent> items = new ArrayList<>(store.load());
        for (int i = 0; i < items.size(); i++) {
            UpcomingEvent old = items.get(i);
            if (!old.id().equals(id)) continue;
            String zone = safe(request.timezone()).isBlank() ? old.timezone() : request.timezone();
            UpcomingEvent updated = normalize(new UpcomingEvent(
                    old.id(), nonBlank(request.title(), old.title()), safe(request.type()).isBlank() ? old.type() : type(request.type()),
                    safe(request.ticker()).isBlank() ? old.ticker() : request.ticker().toUpperCase(Locale.ROOT),
                    safe(request.companyName()).isBlank() ? old.companyName() : request.companyName(),
                    safe(request.startsAt()).isBlank() ? old.startsAt() : parseInstant(request.startsAt(), zone),
                    zone, safe(request.source()).isBlank() ? old.source() : request.source(),
                    safe(request.sourceUrl()).isBlank() ? old.sourceUrl() : request.sourceUrl(),
                    request.importance() == null ? old.importance() : importance(request.importance()),
                    request.notes() == null ? old.notes() : request.notes(), old.createdAt(), Instant.now()));
            items.set(i, updated);
            store.save(dedupe(items));
            return updated;
        }
        throw new IllegalArgumentException("Upcoming event not found");
    }

    public void delete(String id) {
        List<UpcomingEvent> items = new ArrayList<>(store.load());
        items.removeIf(e -> e.id().equals(id));
        store.save(items);
    }

    public Mono<Map<String, Object>> refresh() {
        LocalDate from = LocalDate.now(ET).minusDays(1);
        LocalDate to = from.plusMonths(monthsAhead());
        List<UpcomingEvent> seed = new ArrayList<>();
        seed.addAll(politicalEvents(from, to));
        Mono<List<String>> tickers = portfolioTickers().map(list -> {
            Set<String> all = new LinkedHashSet<>(List.of("AAPL","MSFT","GOOGL","AMZN","NVDA","META","TSLA"));
            list.forEach(t -> all.add(t.toUpperCase(Locale.ROOT)));
            return List.copyOf(all);
        }).cache();
        Mono<List<UpcomingEvent>> earnings = tickers.flatMapMany(list -> Flux.fromIterable(list)
                .flatMap(ticker -> finnhubEarnings(ticker, from, to)
                        .switchIfEmpty(financialDatasetsEarnings(ticker, from, to))
                        .switchIfEmpty(Mono.just(generated(ticker + " earnings", "EARNINGS", ticker, "", null, "unconfirmed", "", 4, "尚未确定：数据源尚未返回未来发布日期"))), 3)).collectList();
        Map<String,String> sourceStatus = new java.util.concurrent.ConcurrentHashMap<>();
        Mono<List<UpcomingEvent>> macro = Mono.just(List.of());
        Mono<List<UpcomingEvent>> officialMacro=Mono.zip(
                calendarSource("fed", api.fomcCalendar().map(html->parseFomcCalendar(html,from,to)),
                        finnhubEconomicEvents(from,to).filter(e -> e.title().toLowerCase(Locale.ROOT).matches(".*(fomc|fed chair|interest rate decision).*"))
                                .collectList(), sourceStatus),
                calendarSource("bls", api.blsReleaseCalendar().map(ics->parseBlsCalendar(ics,from,to)),
                        fredReleaseEvents(from,to).collectList(), sourceStatus))
                .map(tuple->{List<UpcomingEvent> values=new ArrayList<>(tuple.getT1());values.addAll(tuple.getT2());return values;});
        return Mono.zip(earnings.onErrorReturn(List.of()), macro.onErrorReturn(List.of()),officialMacro)
                .map(tuple -> {
                    List<UpcomingEvent> existingManual = store.load().stream().filter(e -> "manual".equalsIgnoreCase(e.source())).toList();
                    List<UpcomingEvent> merged = new ArrayList<>();
                    merged.addAll(existingManual);
                    merged.addAll(seed);
                    merged.addAll(tuple.getT1());
                    merged.addAll(tuple.getT2());
                    merged.addAll(tuple.getT3());
                    List<UpcomingEvent> deduped = dedupe(merged);
                    store.save(deduped);
                    return Map.of("count", deduped.size(), "events", list(monthsAhead()), "sourceStatus", sourceStatus);
                });
    }

    Mono<List<UpcomingEvent>> calendarSource(String source, Mono<List<UpcomingEvent>> primary,
            Mono<List<UpcomingEvent>> fallback, Map<String,String> status) {
        return primary.filter(values -> !values.isEmpty()).switchIfEmpty(Mono.error(new IllegalStateException("Empty calendar")))
                .doOnNext(values -> status.put(source,"primary"))
                .onErrorResume(error -> fallback.filter(values -> !values.isEmpty())
                        .switchIfEmpty(Mono.error(new IllegalStateException("Empty fallback calendar")))
                        .doOnNext(values -> status.put(source,"fallback")))
                .onErrorResume(error -> {
                    status.put(source,"unavailable; retained previously fetched events");
                    return Mono.just(store.load().stream().filter(e -> source.equals(e.source())
                            || (source.equals("bls") && "fred".equals(e.source()))
                            || (source.equals("fed") && "finnhub".equals(e.source()) && e.title().toLowerCase(Locale.ROOT).matches(".*(fomc|fed chair|interest rate decision).*"))).toList());
                });
    }

    List<UpcomingEvent> parseBlsCalendar(String ics,LocalDate from,LocalDate to){
        if(ics==null||!ics.contains("BEGIN:VEVENT"))throw new IllegalArgumentException("Invalid BLS calendar response");
        List<UpcomingEvent> out=new ArrayList<>();
        for(String block:ics.replace("\r\n ","").split("BEGIN:VEVENT")){
            String summary=field(block,"SUMMARY"),start=fieldPrefix(block,"DTSTART");
            String lower=summary.toLowerCase(Locale.ROOT);String type;
            if(lower.contains("consumer price index"))type="CPI";else if(lower.contains("producer price index"))type="PPI";else continue;
            try{
                String digits=start.replaceAll("[^0-9]","");if(digits.length()<8)continue;
                LocalDate date=LocalDate.parse(digits.substring(0,8),DateTimeFormatter.BASIC_ISO_DATE);
                if(date.isBefore(from)||date.isAfter(to))continue;
                out.add(generated(type+" release","MACRO","","",date.atTime(8,30).atZone(ET).toInstant(),"bls",
                        "https://www.bls.gov/schedule/news_release/",5,"Official BLS release calendar"));
            }catch(Exception ignored){}
        }
        return out;
    }

    List<UpcomingEvent> parseFomcCalendar(String html,LocalDate from,LocalDate to){
        if(html==null||!html.contains("FOMC Meetings"))throw new IllegalArgumentException("Invalid FOMC calendar response");
        List<UpcomingEvent> out=new ArrayList<>();
        for(int year=from.getYear();year<=to.getYear();year++){
            String marker=year+" FOMC Meetings";int start=html.indexOf(marker);if(start<0)continue;
            int end=html.indexOf(" FOMC Meetings",start+marker.length());
            String section=html.substring(start,end<0?html.length():end);
            var matcher=java.util.regex.Pattern.compile("(?s)fomc-meeting__month[^>]*><strong>([A-Za-z]+)</strong>.*?fomc-meeting__date[^>]*>([0-9]+)(?:-([0-9]+))?\\*?</div>").matcher(section);
            while(matcher.find())try{
                Month month=Month.valueOf(matcher.group(1).toUpperCase(Locale.ROOT));int day=Integer.parseInt(matcher.group(3)==null?matcher.group(2):matcher.group(3));
                LocalDate date=LocalDate.of(year,month,day);if(date.isBefore(from)||date.isAfter(to))continue;
                out.add(generated("FOMC Statement / Interest Rate Decision","MACRO","","",date.atTime(14,0).atZone(ET).toInstant(),"fed","https://www.federalreserve.gov/monetarypolicy/fomccalendars.htm",5,"Official scheduled meeting ending date; statement time is expected, subject to Fed confirmation."));
                out.add(generated("Fed Chair Press Conference","MACRO","","",date.atTime(14,30).atZone(ET).toInstant(),"fed","https://www.federalreserve.gov/monetarypolicy/fomccalendars.htm",5,"Expected press conference following the scheduled meeting; time subject to Fed confirmation."));
            }catch(Exception ignored){}
        }
        if(out.isEmpty())throw new IllegalArgumentException("No FOMC dates parsed");return out;
    }

    private static String field(String block,String key){var m=java.util.regex.Pattern.compile("(?m)^"+key+":(.*)$").matcher(block);return m.find()?m.group(1).trim():"";}
    private static String fieldPrefix(String block,String key){var m=java.util.regex.Pattern.compile("(?m)^"+key+"[^:]*:(.*)$").matcher(block);return m.find()?m.group(1).trim():"";}

    /** Stable calendars are refreshed once per day; startup leaves five minutes for dependencies to settle. */
    @Scheduled(fixedDelay=86_400_000, initialDelay=300_000)
    public void refreshDaily() { refresh().subscribe(ignored -> { }, ignored -> { }); }

    private Mono<List<String>> portfolioTickers() {
        ObjectNode params = mapper.createObjectNode();
        params.put("name", "get_account_positions");
        params.set("arguments", mapper.createObjectNode());
        return api.ibkrCall("tools/call", params)
                .map(this::extractTickers)
                .onErrorReturn(List.of());
    }

    private List<String> extractTickers(JsonNode response) {
        JsonNode positions = findPositions(response);
        if (!positions.isArray()) return List.of();
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (JsonNode p : positions) {
            String ticker = tickerFromPosition(p);
            if (!ticker.isBlank()) out.add(ticker.toUpperCase(Locale.ROOT));
        }
        return List.copyOf(out);
    }

    private JsonNode findPositions(JsonNode response) {
        for (JsonNode candidate : List.of(
                response.path("result").path("structuredContent").path("positions"),
                response.path("result").path("positions"),
                response.path("result").path("content").path("positions"),
                response.path("positions"))) {
            if (candidate.isArray()) return candidate;
        }
        JsonNode content = response.path("result").path("content");
        if (content.isArray()) {
            for (JsonNode item : content) {
                String text = item.path("text").asText("");
                if (text.isBlank()) continue;
                try {
                    JsonNode parsed = mapper.readTree(text);
                    JsonNode positions = findPositions(parsed);
                    if (positions.isArray()) return positions;
                    if (parsed.isArray()) return parsed;
                } catch (Exception ignored) {}
            }
        }
        return mapper.createArrayNode();
    }

    private String tickerFromPosition(JsonNode position) {
        for (String key : List.of("symbol", "ticker", "contract_ticker", "contractTicker", "local_symbol", "localSymbol")) {
            String value = position.path(key).asText("").trim();
            if (!value.isBlank()) return cleanTicker(value);
        }
        String description = position.path("description").asText(position.path("contract_description").asText(""));
        if (!description.isBlank()) return cleanTicker(description.trim().split("\\s+")[0]);
        return "";
    }

    private String cleanTicker(String value) {
        return value == null ? "" : value.replaceAll("[^A-Za-z0-9.\\-]", "").trim();
    }


    private Flux<UpcomingEvent> finnhubPortfolioEarnings(List<String> tickers, LocalDate from, LocalDate to) {
        Set<String> wanted = new HashSet<>(tickers);
        if (wanted.isEmpty()) return Flux.empty();
        return api.finnhubEarningsCalendar(from.toString(), to.toString(), "")
                .flatMapMany(node -> {
                    JsonNode earnings = node.path("earningsCalendar");
                    if (!earnings.isArray()) return Flux.empty();
                    return Flux.fromIterable(earnings);
                })
                .filter(node -> wanted.contains(node.path("symbol").asText("").toUpperCase(Locale.ROOT)))
                .map(node -> finnhubEarningsEvent(node, node.path("symbol").asText("")))
                .onErrorResume(error -> Flux.empty());
    }

    private UpcomingEvent finnhubEarningsEvent(JsonNode node, String fallbackTicker) {
        String symbol = nonBlank(node.path("symbol").asText(""), fallbackTicker).toUpperCase(Locale.ROOT);
        LocalDate date = LocalDate.parse(node.path("date").asText(LocalDate.now(ET).toString()));
        String hour = node.path("hour").asText("").toLowerCase(Locale.ROOT);
        LocalTime time = hour.contains("bmo") || hour.contains("before") ? LocalTime.of(8, 0) : LocalTime.of(16, 0);
        String suffix = hour.isBlank() ? "" : " (" + hour.toUpperCase(Locale.ROOT) + ")";
        return generated(symbol + " earnings", "EARNINGS", symbol, "", date.atTime(time).atZone(ET).toInstant(), "finnhub", "", 4, "Provider calendar estimate; exact release time unconfirmed. " + suffix);
    }

    private Flux<UpcomingEvent> finnhubEarnings(String ticker, LocalDate from, LocalDate to) {
        return api.finnhubEarningsCalendar(from.toString(), to.toString(), ticker)
                .flatMapMany(node -> {
                    JsonNode earnings = node.path("earningsCalendar");
                    if (!earnings.isArray()) return Flux.empty();
                    return Flux.fromIterable(earnings);
                })
                .filter(node -> ticker.equalsIgnoreCase(node.path("symbol").asText(ticker)))
                .filter(node -> { LocalDate date=earningsDate(node);return date!=null && !date.isBefore(from) && !date.isAfter(to); })
                .map(node -> finnhubEarningsEvent(node, ticker))
                .onErrorResume(error -> Flux.empty());
    }


    private Flux<UpcomingEvent> financialDatasetsEarnings(String ticker, LocalDate from, LocalDate to) {
        return api.financialDatasets("/earnings", Map.of("ticker", ticker, "period", "quarterly", "limit", 12))
                .flatMapMany(node -> Flux.fromIterable(findObjects(node)))
                .map(node -> earningsDate(node))
                .filter(Objects::nonNull)
                .filter(date -> !date.isBefore(from) && !date.isAfter(to))
                .distinct()
                .map(date -> generated(ticker.toUpperCase(Locale.ROOT) + " earnings", "EARNINGS", ticker.toUpperCase(Locale.ROOT), "", date.atTime(16, 0).atZone(ET).toInstant(), "financial-datasets", "", 4, "Provider calendar estimate; exact release time unconfirmed."))
                .onErrorResume(error -> Flux.empty());
    }

    private List<JsonNode> findObjects(JsonNode node) {
        List<JsonNode> out = new ArrayList<>();
        collectObjects(node, out);
        return out;
    }

    private void collectObjects(JsonNode node, List<JsonNode> out) {
        if (node == null || node.isMissingNode() || node.isNull()) return;
        if (node.isObject()) {
            if (earningsDate(node) != null) out.add(node);
            node.forEach(child -> collectObjects(child, out));
        } else if (node.isArray()) {
            node.forEach(child -> collectObjects(child, out));
        }
    }

    private LocalDate earningsDate(JsonNode node) {
        for (String key : List.of("date", "report_date", "reportDate", "earnings_date", "earningsDate", "announcement_date", "announcementDate")) {
            String value = node.path(key).asText("").trim();
            if (value.isBlank()) continue;
            try { return LocalDate.parse(value.length() > 10 ? value.substring(0, 10) : value); } catch (Exception ignored) {}
        }
        return null;
    }

    private Flux<UpcomingEvent> finnhubEconomicEvents(LocalDate from, LocalDate to) {
        return api.finnhubEconomicCalendar(from.toString(), to.toString())
                .flatMapMany(node -> {
                    JsonNode events = node.path("economicCalendar");
                    if (!events.isArray()) events = node.path("economic");
                    if (!events.isArray()) return Flux.empty();
                    return Flux.fromIterable(events);
                })
                .filter(this::isImportantUsMacro)
                .map(node -> {
                    String event = nonBlank(node.path("event").asText(""), node.path("name").asText("Macro event"));
                    Instant at = parseEconomicInstant(node, from);
                    return generated(event, "MACRO", "", "", at, "finnhub", "", 4, nonBlank(node.path("country").asText(""), "US economic calendar"));
                }).filter(event -> event.startsAt()!=null && !event.startsAt().atZone(ET).toLocalDate().isBefore(from) && !event.startsAt().atZone(ET).toLocalDate().isAfter(to));
    }

    private Flux<UpcomingEvent> fredReleaseEvents(LocalDate from,LocalDate to){
        return api.fredReleaseDates(from.toString(),to.toString()).flatMapMany(node->{
            JsonNode dates=node.path("release_dates");return dates.isArray()?Flux.fromIterable(dates):Flux.empty();
        }).filter(node->{String name=node.path("release_name").asText("").toLowerCase(Locale.ROOT);
            return name.equals("consumer price index")||name.equals("producer price index");
        }).flatMap(node->{
            try{
                LocalDate date=LocalDate.parse(node.path("date").asText());String name=node.path("release_name").asText();
                return Mono.just(generated(name,"MACRO","","",date.atTime(8,30).atZone(ET).toInstant(),"fred",
                        "https://fred.stlouisfed.org/releases",5,"FRED release dates fallback"));
            }catch(Exception ignored){return Mono.empty();}
        }).onErrorResume(error->Flux.empty());
    }

    private boolean isImportantUsMacro(JsonNode node) {
        String country = (node.path("country").asText("") + " " + node.path("countryCode").asText("")).toLowerCase(Locale.ROOT);
        String event = (node.path("event").asText("") + " " + node.path("name").asText("")).toLowerCase(Locale.ROOT);
        boolean us = country.isBlank() || country.contains("us") || country.contains("united states");
        if (!us) return false;
        return event.contains("cpi") || event.contains("consumer price") || event.contains("pce") ||
                event.contains("nonfarm") || event.contains("non-farm") || event.contains("payroll") ||
                event.contains("unemployment") || event.contains("gdp") || event.contains("gross domestic product") ||
                event.contains("fomc") || event.contains("fed chair") || event.contains("powell") || event.contains("warsh");
    }

    private Instant parseEconomicInstant(JsonNode node, LocalDate fallback) {
        for (String key : List.of("time", "datetime", "date")) {
            String value = node.path(key).asText("");
            if (value.isBlank()) continue;
            try { return Instant.parse(value); } catch (Exception ignored) {}
            try { return OffsetDateTime.parse(value).toInstant(); } catch (Exception ignored) {}
            try { return LocalDateTime.parse(value).atZone(ET).toInstant(); } catch (Exception ignored) {}
            try { return LocalDate.parse(value).atTime(8, 30).atZone(ET).toInstant(); } catch (Exception ignored) {}
        }
        return null;
    }

    List<UpcomingEvent> politicalEvents(LocalDate from, LocalDate to) {
        List<UpcomingEvent> out = new ArrayList<>();
        for(int year=from.getYear();year<=to.getYear();year++) {
            if(year%2!=0)continue;
            LocalDate date=LocalDate.of(year,11,1).with(java.time.temporal.TemporalAdjusters.firstInMonth(DayOfWeek.MONDAY)).plusDays(1);
            if(date.isBefore(from)||date.isAfter(to))continue;
            out.add(generated(year%4==0?"U.S. presidential election":"U.S. midterm elections", "POLITICAL", "", "", date.atStartOfDay(ET).toInstant(),
                    "built-in", "https://uscode.house.gov/view.xhtml?req=granuleid:USC-prelim-title2-section7", 4, "Election Day; all-day event"));
        }
        return out;
    }

    private UpcomingEvent generated(String title, String type, String ticker, String company, Instant at, String source, String url, int importance, String notes) {
        Instant now = Instant.now();
        return normalize(new UpcomingEvent(UUID.nameUUIDFromBytes((type + "|" + title + "|" + ticker + "|" + at).getBytes()).toString(), title, type, ticker, company, at, "America/New_York", source, url, importance, notes, now, now));
    }

    private List<UpcomingEvent> dedupe(List<UpcomingEvent> events) {
        Map<String, UpcomingEvent> byKey = new LinkedHashMap<>();
        for (UpcomingEvent e : events) {
            UpcomingEvent n = normalize(e);
            byKey.put(key(n), n);
        }
        return byKey.values().stream().sorted(Comparator.comparing(UpcomingEvent::startsAt, Comparator.nullsLast(Comparator.naturalOrder()))).toList();
    }

    private String key(UpcomingEvent e) { return (e.type() + "|" + safe(e.ticker()) + "|" + e.title() + "|" + e.startsAt()).toLowerCase(Locale.ROOT); }
    private UpcomingEvent normalize(UpcomingEvent e) {
        return new UpcomingEvent(safe(e.id()).isBlank() ? UUID.randomUUID().toString() : e.id(), safe(e.title()), type(e.type()), safe(e.ticker()).toUpperCase(Locale.ROOT), safe(e.companyName()), e.startsAt(), safe(e.timezone()).isBlank() ? "America/New_York" : e.timezone(), safe(e.source()).isBlank() ? "manual" : e.source(), safe(e.sourceUrl()), importance(e.importance()), safe(e.notes()), e.createdAt() == null ? Instant.now() : e.createdAt(), e.updatedAt() == null ? Instant.now() : e.updatedAt());
    }
    private int monthsAhead() { return properties.events() == null || properties.events().monthsAhead() <= 0 ? 6 : properties.events().monthsAhead(); }
    private static Instant parseInstant(String value, String zone) {
        try { return Instant.parse(value); } catch (Exception ignored) {}
        try { return LocalDateTime.parse(value).atZone(ZoneId.of(zone)).toInstant(); } catch (Exception ignored) {}
        return LocalDate.parse(value).atStartOfDay(ZoneId.of(zone)).toInstant();
    }
    private static String type(String value) {
        String v = safe(value).toUpperCase(Locale.ROOT);
        return switch (v) {
            case "", "CUSTOM" -> "CUSTOM";
            case "MACRO", "EARNINGS", "IPO", "POLITICAL", "REGULATORY" -> v;
            default -> "CUSTOM";
        };
    }
    private static int importance(Integer value) { return value == null ? 3 : Math.max(1, Math.min(5, value)); }
    private static int importance(int value) { return Math.max(1, Math.min(5, value)); }
    private static String required(String value, String name) { if (safe(value).isBlank()) throw new IllegalArgumentException(name + " is required"); return value; }
    private static String nonBlank(String value, String fallback) { return safe(value).isBlank() ? safe(fallback) : value; }
    private static String safe(String value) { return value == null ? "" : value.trim(); }

    public record UpsertRequest(String title, String type, String ticker, String companyName, String startsAt, String timezone, String source, String sourceUrl, Integer importance, String notes) {}
}
