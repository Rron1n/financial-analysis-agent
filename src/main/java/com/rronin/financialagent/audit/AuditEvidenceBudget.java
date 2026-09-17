package com.rronin.financialagent.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import java.util.regex.Pattern;

/** Deterministic excerpts, never summaries. Omitted evidence is explicit and cannot support a PASS. */
final class AuditEvidenceBudget {
    private static final Pattern URL=Pattern.compile("https?://[^\\s<>\"\\)\\]]+");
    static ObjectNode pack(ObjectMapper mapper,Map<String,Object> evidence,String candidate,int budget){
        Set<String> urls=new LinkedHashSet<>();var matcher=URL.matcher(candidate);while(matcher.find())urls.add(matcher.group());
        Map<String,String> raw=new LinkedHashMap<>();evidence.forEach((id,value)->raw.put(id,mapper.valueToTree(value).toString()));
        var newestFirst=new ArrayList<>(raw.keySet());
        Collections.reverse(newestFirst);
        var order=newestFirst.stream().sorted(Comparator.comparingLong((String id)->(raw.get(id).contains("mcp__ibkr__") ? 1000L : 0L) + urls.stream().filter(raw.get(id)::contains).count()).reversed()).toList();
        var result=mapper.createObjectNode();var entries=result.putObject("entries");
        result.put("notice","Excerpts are exact source slices. Entries prioritize recently received evidence within each relevance group. Receipt order is not the source date: compare embedded dates and account/period identity before choosing between snapshots. Old snapshots do not disprove a newer snapshot. Truncation or omission is not evidence of absence; unsupported material claims require REVISE, never guessed support.");
        for(String id:order){
            String original=raw.get(id);
            if(budget-result.toString().length()<400)break;
            var entry=entries.putObject(id);entry.put("originalCharacters",original.length());
            int available=Math.max(0,budget-result.toString().length()-200);
            // Uploaded primary documents need their financial tables, often near the end.
            int cap=original.contains("\"fileType\":\"pdf\"") ? 40_000 : 6_000;
            String excerpt=excerpt(original,urls,Math.min(cap,available/2));
            entry.put("excerpt",excerpt);entry.put("truncated",!excerpt.equals(original));
            if(result.toString().length()>budget){entries.remove(id);break;}
        }
        result.put("omittedEntries",evidence.size()-entries.size());
        return result;
    }
    static String excerpt(String original,Set<String> urls,int limit){
        if(original.length()<=limit)return original;
        if(limit<100)return "";
        List<int[]> windows=new ArrayList<>();windows.add(new int[]{0,Math.min(500,limit)});
        for(String url:urls){int at=original.indexOf(url);if(at>=0)windows.add(new int[]{Math.max(0,at-600),Math.min(original.length(),at+url.length()+1200)});}
        if(windows.size()==1)return original.substring(0,Math.min(limit,original.length()));
        windows.sort(Comparator.comparingInt(a->a[0]));StringBuilder text=new StringBuilder();int end=0;
        for(var window:windows){int from=Math.max(end,window[0]),to=window[1];if(from>=to)continue;
            String separator=from>end?"\n[... source omitted ...]\n":"";
            int allowed=limit-text.length()-separator.length();if(allowed<=0)break;
            int stop=Math.min(to,from+allowed);if(stop<original.length()&&stop>from&&Character.isHighSurrogate(original.charAt(stop-1)))stop--;
            text.append(separator).append(original,from,stop);end=stop;
        }
        return text.toString();
    }
}
