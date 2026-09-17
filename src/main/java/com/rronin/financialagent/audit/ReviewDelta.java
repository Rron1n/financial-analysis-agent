package com.rronin.financialagent.audit;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.rronin.financialagent.model.ModelGateway;
import java.util.*;

/** Runtime-enforced delta packets. Original source evidence stays available, not candidate prose. */
final class ReviewDelta {
    record Packet(ObjectNode payload, List<JsonNode> retainedClaims) { }
    static Packet build(ObjectMapper mapper,ObjectNode payload,ReviewReuse.Snapshot before,
                        ReviewReuse.Snapshot after,ModelGateway.Role role) {
        var revision=ReviewReuse.revision(mapper,before,after,role);
        revision.remove("claim_ledger"); // Avoid reintroducing all previous claims into the delta request.
        revision.put("mode","delta_only");
        revision.put("instructions","Review ONLY supplied changed passages and dependency claims, plus unresolved failures from previous_result. Unchanged accepted prose is deliberately omitted, not missing from the answer. Do not demand its repetition or create new stylistic requirements. Check removals as well as additions. Preserve unresolved failures unless this packet demonstrates their resolution. If a necessary dependency is unavailable return REVISE identifying that dependency; never assume PASS. Return the complete verdict for these changes and unresolved failures; the runtime retains unchanged accepted content.");
        var allowed=new LinkedHashMap<String,String>();
        for(var change:revision.path("changes"))allowed.put(change.path("locator").asText(),change.path("currentText").asText());
        List<JsonNode> retained=new ArrayList<>();
        var previousClaims=before.results().get(ModelGateway.Role.CLAIM_EXTRACTION);
        if(role==ModelGateway.Role.CLAIM_EXTRACTION && previousClaims!=null){
            // Only carry claims whose exact source statement remains in an unchanged document paragraph.
            for(var claim:previousClaims.path("claims")){
                String statement=claim.path("statement").asText();
                boolean anchored=false;
                for(var doc:after.documents()){
                    if(statement.isBlank()||!doc.text().contains(statement))continue;
                    if(!allowed.getOrDefault(doc.locator(),"").contains(statement)){anchored=true;break;}
                }
                if(anchored)retained.add(claim.deepCopy());
                else {
                    // Paraphrased claims cannot be safely mapped: re-extract that document, not guess coverage.
                    for(var doc:after.documents())if(claim.path("locator").asText().startsWith(doc.locator()))allowed.put(doc.locator(),doc.text());
                }
            }
            // If a document was expanded, none of its claims may also be carried forward.
            retained.removeIf(c->allowed.values().stream().anyMatch(t->t.contains(c.path("statement").asText())));
            revision.remove("previous_result");
            revision.put("instructions","Extract claims ONLY from supplied passages. Omitted unchanged claims are retained by runtime. Return the normal claims schema for the supplied passages, not the whole answer. Do not reconstruct omitted text.");
        }
        if(role==ModelGateway.Role.FINANCIAL_AUDIT){
            var selected=mapper.createArrayNode();
            var old=before.results().get(ModelGateway.Role.CLAIM_EXTRACTION);
            boolean evidenceChanged=!ReviewReuse.changedEvidence(before,after).isEmpty();
            for(var claim:payload.path("claims").path("claims")){
                boolean same=false;if(old!=null)for(var prior:old.path("claims"))if(prior.equals(claim)){same=true;break;}
                boolean dependent=claim.path("kind").asText().equals("CONCLUSION")||!claim.path("calculation").asText().isBlank()||!claim.path("assumptions").isEmpty();
                boolean unresolved=!before.results().get(ModelGateway.Role.FINANCIAL_AUDIT).path("verdict").asText().equals("PASS");
                if(!same||evidenceChanged||dependent||unresolved)selected.add(claim);
            }
            payload.set("claims",mapper.createObjectNode().set("claims",selected));
            // Original material is already a compact evidence packet. Replace only the candidate documents.
            var material=(ObjectNode)payload.path("original_material");
            material.set("audit_documents",documents(mapper,after,allowed));
        } else payload.set("audit_documents",documents(mapper,after,allowed));
        payload.set("revision_context",revision);
        return new Packet(payload,retained);
    }
    private static ArrayNode documents(ObjectMapper mapper,ReviewReuse.Snapshot after,Map<String,String> allowed){
        var docs=mapper.createArrayNode();
        for(var doc:after.documents())if(allowed.containsKey(doc.locator()))docs.add(mapper.valueToTree(new CandidateReviewService.AuditTargetDocument(doc.locator(),doc.mediaType(),allowed.get(doc.locator()))));
        return docs;
    }
    static JsonNode mergeClaims(ObjectMapper mapper,JsonNode result,List<JsonNode> retained){
        var merged=(ObjectNode)result.deepCopy();var list=merged.putArray("claims");Set<String> seen=new HashSet<>();
        for(var claim:retained)if(seen.add(claim.toString()))list.add(claim);
        for(var claim:result.path("claims"))if(seen.add(claim.toString()))list.add(claim);
        return merged;
    }
}
