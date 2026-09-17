package com.rronin.financialagent.events;

import com.fasterxml.jackson.databind.JsonNode;
import com.rronin.financialagent.integrations.ExternalApiClient;
import com.rronin.financialagent.schedulers.SchedulerNotificationService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Publishes a quick official BLS observation after CPI/PPI release events become due. */
@Service
public class EventOutcomeService {
    private final EventStore events;private final EventReminderStore state;private final ExternalApiClient api;
    private final SchedulerNotificationService notifications;private final Set<String> inFlight=ConcurrentHashMap.newKeySet();
    private final java.util.Map<String,Instant> lastAttempt=new ConcurrentHashMap<>();
    public EventOutcomeService(EventStore events,EventReminderStore state,ExternalApiClient api,SchedulerNotificationService notifications){
        this.events=events;this.state=state;this.api=api;this.notifications=notifications;
    }
    @Scheduled(fixedDelay=300_000,initialDelay=120_000) public void scan(){
        Instant now=Instant.now();
        for(UpcomingEvent event:events.forOutcomeChecks()){
            if(event.startsAt()==null||now.isBefore(event.startsAt())||event.startsAt().isBefore(now.minus(Duration.ofDays(7))))continue;
            String key=event.id()+"|"+event.startsAt()+"|OUTCOME";
            if(state.load().contains(key)||lastAttempt.getOrDefault(key,Instant.EPOCH).isAfter(now.minus(Duration.ofMinutes(15)))||!inFlight.add(key))continue;
            lastAttempt.put(key,now);
            reactor.core.publisher.Mono<String> result=outcome(event);if(result==null){inFlight.remove(key);continue;}
            result.filter(text->!text.isBlank()).subscribe(text->{if(state.markOnce(key))notifications.create("event:"+event.id(),event.title(),event.title()+" result",text);inFlight.remove(key);},error->inFlight.remove(key),()->inFlight.remove(key));
        }
    }
    private reactor.core.publisher.Mono<String> outcome(UpcomingEvent event){
        String kind=event.title().toUpperCase();
        if("bls".equalsIgnoreCase(event.source()) || "fred".equalsIgnoreCase(event.source())){
            String series=(kind.contains("PPI") || kind.contains("PRODUCER PRICE"))?"WPUFD4":(kind.contains("CPI") || kind.contains("CONSUMER PRICE"))?"CUUR0000SA0":"";if(series.isBlank())return null;
            LocalDate date=event.startsAt().atZone(ZoneId.of("America/New_York")).toLocalDate();
            String release=series.equals("WPUFD4")?"ppi":"cpi";
            String url="https://www.bls.gov/news.release/archives/"+release+"_"+date.format(java.time.format.DateTimeFormatter.ofPattern("MMddyyyy"))+".htm";
            return api.officialText(url).map(html->releaseSummary(html,release.toUpperCase(),date.toString()))
                    .filter(x->!x.isBlank()).map(x->x+"\n官方发布："+url);

        }
        if("fed".equalsIgnoreCase(event.source())&&kind.contains("STATEMENT")){
            LocalDate date=ZonedDateTime.ofInstant(event.startsAt(),ZoneId.of("America/New_York")).toLocalDate();
            String url="https://www.federalreserve.gov/newsevents/pressreleases/monetary"+date.format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE)+"a.htm";
            return api.officialText(url).map(this::fomcDecision).filter(x->!x.isBlank()).map(x->x+" · "+url);
        }
        if("EARNINGS".equalsIgnoreCase(event.type())&&event.ticker()!=null&&!event.ticker().isBlank()){
            LocalDate date=ZonedDateTime.ofInstant(event.startsAt(),ZoneId.of("America/New_York")).toLocalDate();
            return api.finnhubEarningsCalendar(date.minusDays(1).toString(),date.plusDays(1).toString(),event.ticker())
                    .map(node->earningsResult(node,event.ticker())).filter(x->!x.isBlank()).map(x->x+"\n来源：https://finnhub.io/calendar");
        }
        return null;
    }
    String releaseSummary(String html,String kind,String date){
        if(html==null)return "";
        String text=html.replaceAll("(?is)<script.*?</script>|<style.*?</style>"," ").replaceAll("<[^>]+>"," ")
                .replace("&nbsp;"," ").replace("&amp;","&").replaceAll("\\s+"," ");
        var matcher=java.util.regex.Pattern.compile("(?i)([^.!?]{0,180}(?:consumer price index|producer price index|all items less food|final demand|all items index)(?:[^.!?]|\\.(?=[0-9])){0,450}\\.(?=\\s|$))").matcher(text);
        var lines=new java.util.LinkedHashSet<String>();
        while(matcher.find()&&lines.size()<4){String sentence=matcher.group().trim();if(sentence.matches(".*[0-9].*"))lines.add(sentence);}
        return lines.isEmpty()?"":kind+" 官方发布（"+date+"）\n"+String.join("\n",lines);
    }
    String observation(JsonNode response){
        if(!"REQUEST_SUCCEEDED".equals(response.path("status").asText()))return "";
        JsonNode series=response.path("Results").path("series");if(!series.isArray()||series.isEmpty())return "";
        JsonNode data=series.get(0).path("data");if(!data.isArray()||data.isEmpty())return "";JsonNode latest=data.get(0);
        String value=latest.path("value").asText(),year=latest.path("year").asText(),period=latest.path("periodName").asText(latest.path("period").asText());
        return value.isBlank()?"":"Latest official observation: "+value+" ("+period+" "+year+")";
    }
    String fomcDecision(String html){
        if(html==null)return "";String text=html.replaceAll("(?s)<script.*?</script>|<style.*?</style>"," ").replaceAll("<[^>]+>"," ")
                .replace("&nbsp;"," ").replace("&ndash;","-").replace("&amp;","&").replaceAll("\\s+"," ");
        var matcher=java.util.regex.Pattern.compile("(?i)(decided to (?:maintain|raise|lower)[^.]{0,300}\\.)").matcher(text);
        return matcher.find()?"Federal Reserve decision: "+matcher.group(1).trim():"FOMC statement is now available";
    }
    String earningsResult(JsonNode response,String ticker){
        JsonNode values=response.path("earningsCalendar");if(!values.isArray())return "";
        for(JsonNode value:values)if(ticker.equalsIgnoreCase(value.path("symbol").asText())){
            String actual=value.path("epsActual").asText(""),estimate=value.path("epsEstimate").asText("");
            if(actual.isBlank())return "";
            String revenue=value.path("revenueActual").asText(""), revenueEstimate=value.path("revenueEstimate").asText("");
            return ticker+" 财报已发布（"+value.path("date").asText()+"）\nEPS："+actual+(estimate.isBlank()?"":"；预期："+estimate)
                    +(revenue.isBlank()?"":"\n营收："+revenue+(revenueEstimate.isBlank()?"":"；预期："+revenueEstimate))
                    +"\n口径与币种以原始财报为准；预期值来自数据供应商。";
        }return "";
    }
}
