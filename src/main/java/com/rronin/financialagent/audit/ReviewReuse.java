package com.rronin.financialagent.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rronin.financialagent.model.ModelGateway;
import java.util.*;

/** Run-local review snapshots. Stage records retain evidence changes and claim dependencies. */
final class ReviewReuse {
    record Snapshot(String contract, JsonNode evidence, List<CandidateReviewService.AuditTargetDocument> documents,
                    Map<ModelGateway.Role,JsonNode> results) { }
    static Snapshot snapshot(ObjectMapper mapper, CandidateReviewService.Input input,
                             List<CandidateReviewService.AuditTargetDocument> documents) {
        var sources=mapper.createObjectNode();
        input.evidence().forEach((key,value)->{
            JsonNode node=mapper.valueToTree(value);
            // A write receipt is not factual evidence; changed file content is checked in documents.
            if(!Set.of("write_file","edit_file","Write","Edit").contains(node.path("tool").asText()))sources.set(key,node);
        });
        return new Snapshot(input.sessionId()+"\n"+input.userRequest()+"\n"+input.systemPrompt(), sources,
                List.copyOf(documents), new java.util.concurrent.ConcurrentHashMap<>());
    }
    static boolean compatible(Snapshot before, Snapshot after) {
        return before!=null && before.contract().equals(after.contract())
                && before.documents().stream().map(d->d.locator()+"|"+d.mediaType()).toList()
                .equals(after.documents().stream().map(d->d.locator()+"|"+d.mediaType()).toList());
    }
    static Set<String> changedEvidence(Snapshot before, Snapshot after) {
        Set<String> keys=new LinkedHashSet<>();before.evidence().fieldNames().forEachRemaining(keys::add);after.evidence().fieldNames().forEachRemaining(keys::add);
        keys.removeIf(key->Objects.equals(before.evidence().get(key),after.evidence().get(key)));return keys;
    }
    static boolean canReuse(Snapshot before, Snapshot after, ModelGateway.Role role) {
        if(!before.documents().equals(after.documents()))return false;
        if(role==ModelGateway.Role.FINANCIAL_AUDIT)return changedEvidence(before,after).isEmpty();
        // General scope and extracted statements do not depend on unrelated financial tool outputs.
        return Objects.equals(before.evidence().get("retrieved-memory"),after.evidence().get("retrieved-memory"));
    }
    static String claimId(JsonNode claim) {
        return UUID.nameUUIDFromBytes((claim.path("locator").asText()+"\n"+claim.path("statement").asText())
                .getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
    }
    static com.fasterxml.jackson.databind.node.ArrayNode ledger(ObjectMapper mapper,Snapshot before,Snapshot after) {
        var rows=mapper.createArrayNode();var changed=changedEvidence(before,after);
        String currentText=after.documents().stream().map(CandidateReviewService.AuditTargetDocument::text).collect(java.util.stream.Collectors.joining("\n"));
        var financial=before.results().get(ModelGateway.Role.FINANCIAL_AUDIT);
        var claims=before.results().get(ModelGateway.Role.CLAIM_EXTRACTION);
        if(claims==null)return rows;
        for(var claim:claims.path("claims")) {
            var row=rows.addObject();row.put("claimId",claimId(claim));row.put("statement",claim.path("statement").asText());row.put("locator",claim.path("locator").asText());
            Set<String> dependencies=new LinkedHashSet<>();
            for(var ref:claim.path("evidence"))before.evidence().fields().forEachRemaining(e->{if(e.getValue().toString().contains(ref.asText())&&!ref.asText().isBlank())dependencies.add(e.getKey());});
            row.set("evidenceKeys",mapper.valueToTree(dependencies));
            boolean sameText=!claim.path("statement").asText().isBlank()&&currentText.contains(claim.path("statement").asText());
            boolean unknown=dependencies.isEmpty();
            // New sources may supersede an old snapshot even under a different key. Let the auditor resolve that dependency.
            boolean newSource=changed.stream().anyMatch(k->!before.evidence().has(k));
            boolean affected=!sameText||(!changed.isEmpty()&&(unknown||newSource||dependencies.stream().anyMatch(changed::contains)));
            row.put("status",affected?"RECHECK":"UNCHANGED");
            row.put("priorVerdict",financial==null?"NOT_REVIEWED":financial.path("verdict").asText("ERROR"));
            row.put("dependencyUncertain",unknown||newSource);
        }
        return rows;
    }
    static ObjectNode revision(ObjectMapper mapper, Snapshot before, Snapshot after, ModelGateway.Role role) {
        var result=mapper.createObjectNode();
        result.put("mode","incremental");
        result.set("previous_result",before.results().get(role));
        if(role==ModelGateway.Role.FINANCIAL_AUDIT)result.set("claim_ledger",ledger(mapper,before,after));
        var evidenceChanges=result.putArray("evidence_changes");
        for(String key:changedEvidence(before,after)){
            var entry=evidenceChanges.addObject().put("key",key);
            entry.put("change",!before.evidence().has(key)?"ADDED":!after.evidence().has(key)?"REMOVED":"UPDATED");
        }
        result.put("instructions","Reuse unchanged checks from previous_result, including unresolved failures. Review changed passages, resolve previous failures, and check all affected dependencies in the full current documents. Changes to numbers, periods, attribution, assumptions or conclusions can invalidate unchanged passages. Do not assume unchanged text is independent. If the impact cannot be bounded, perform a full review. Return the normal complete verdict schema for the entire current candidate, not merely a patch verdict. Never clear previous failures without checking their resolution. Evidence changes do not reset unrelated checks. Use claim_ledger and evidence_changes to identify affected claims and their dependent conclusions; resolve uncertain associations against current evidence. UNCHANGED is a text/dependency hint, not a PASS. Preserve all unresolved failures until specifically resolved. Do not repeat stylistic revisions of already accepted unchanged passages. Return feedback only for remaining material problems.");
        var changes=result.putArray("changes");
        for(int i=0;i<after.documents().size();i++) {
            var old=before.documents().get(i);var next=after.documents().get(i);
            if(old.text().equals(next.text()))continue;
            String[] a=old.text().split("\n",-1),b=next.text().split("\n",-1);
            int start=0;while(start<Math.min(a.length,b.length)&&a[start].equals(b[start]))start++;
            int endA=a.length,endB=b.length;
            while(endA>start&&endB>start&&a[endA-1].equals(b[endB-1])){endA--;endB--;}
            var change=changes.addObject().put("locator",next.locator()).put("startLine",start+1);
            change.put("previousText",String.join("\n",Arrays.copyOfRange(a,start,endA)));
            change.put("currentText",String.join("\n",Arrays.copyOfRange(b,start,endB)));
        }
        return result;
    }
}
