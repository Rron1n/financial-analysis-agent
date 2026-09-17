package com.rronin.financialagent.audit;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.*;
import java.util.*;
import com.rronin.financialagent.model.ModelGateway;
import static org.assertj.core.api.Assertions.assertThat;
class ReviewDeltaTest {
 final ObjectMapper mapper=new ObjectMapper();
 ReviewReuse.Snapshot snapshot(String text){return ReviewReuse.snapshot(mapper,new CandidateReviewService.Input("s","r","q",text,List.of(),Map.of(),""),List.of(new CandidateReviewService.AuditTargetDocument("candidate_answer","text/markdown",text)));}
 @Test void omittedBodyCannotLeakIntoGeneralPacket() throws Exception {
  var before=snapshot("Unchanged private paragraph\nOld line\nUnchanged ending");var after=snapshot("Unchanged private paragraph\nNew line\nUnchanged ending");
  before.results().put(ModelGateway.Role.GENERAL_VALIDATION,mapper.readTree("{\"status\":\"PASS\"}"));
  var payload=mapper.createObjectNode();payload.set("audit_documents",mapper.valueToTree(after.documents()));
  var packet=ReviewDelta.build(mapper,payload,before,after,ModelGateway.Role.GENERAL_VALIDATION);
  assertThat(packet.payload().toString()).contains("Old line","New line").doesNotContain("Unchanged private paragraph","Unchanged ending");
 }
 @Test void extractionRetainsAnchoredUnchangedClaimsAndMergesNewOnes() throws Exception {
  var before=snapshot("Stable fact.\nOld fact.");var after=snapshot("Stable fact.\nNew fact.");
  before.results().put(ModelGateway.Role.CLAIM_EXTRACTION,mapper.readTree("{\"claims\":[{\"statement\":\"Stable fact.\",\"locator\":\"candidate_answer\"}]}"));
  var packet=ReviewDelta.build(mapper,mapper.createObjectNode(),before,after,ModelGateway.Role.CLAIM_EXTRACTION);
  assertThat(packet.retainedClaims()).hasSize(1);
  assertThat(packet.payload().toString()).doesNotContain("Stable fact.");
  var merged=ReviewDelta.mergeClaims(mapper,mapper.readTree("{\"claims\":[{\"statement\":\"New fact.\"}]}"),packet.retainedClaims());
  assertThat(merged.path("claims")).hasSize(2);
 }
 @Test void financialDeltaKeepsCalculationsButOmitsUnchangedIndependentClaims() throws Exception {
  var before=snapshot("Stable narrative\nValue 10");var after=snapshot("Stable narrative\nValue 11");
  var claims=mapper.readTree("{\"claims\":[{\"statement\":\"Stable narrative\",\"kind\":\"FACT\",\"calculation\":\"\",\"assumptions\":[]},{\"statement\":\"Derived ratio\",\"kind\":\"FACT\",\"calculation\":\"10/20\",\"assumptions\":[]}]}");
  before.results().put(ModelGateway.Role.CLAIM_EXTRACTION,claims);
  before.results().put(ModelGateway.Role.FINANCIAL_AUDIT,mapper.readTree("{\"verdict\":\"PASS\",\"feedback\":[]}"));
  var payload=mapper.createObjectNode();payload.set("claims",claims.deepCopy());payload.putObject("original_material").set("audit_documents",mapper.valueToTree(after.documents()));
  var packet=ReviewDelta.build(mapper,payload,before,after,ModelGateway.Role.FINANCIAL_AUDIT);
  assertThat(packet.payload().path("claims").path("claims")).hasSize(1);
  assertThat(packet.payload().toString()).contains("Derived ratio").doesNotContain("Stable narrative");
 }
}
