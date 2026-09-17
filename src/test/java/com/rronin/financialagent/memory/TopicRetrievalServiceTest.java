package com.rronin.financialagent.memory;
import org.junit.jupiter.api.Test;
import java.time.*;
import static org.assertj.core.api.Assertions.*;
class TopicRetrievalServiceTest {
 @Test void stablePreferencesNeverDecayAndResearchUsesSixtyDayHalfLife(){
  Instant now=Instant.parse("2026-09-08T00:00:00Z"),old=now.minus(Duration.ofDays(3650));
  assertThat(TopicRetrievalService.temporalScore("user",old,now)).isEqualTo(1);
  assertThat(TopicRetrievalService.temporalScore("investment",old,now)).isEqualTo(1);
  assertThat(TopicRetrievalService.temporalScore("research",now.minus(Duration.ofDays(60)),now)).isEqualTo(.675);
  assertThat(TopicRetrievalService.temporalScore("workflow",now.minus(Duration.ofDays(365)),now)).isEqualTo(.675);
 }
 @Test void keywordGateRejectsGenericSingleMatchAndWeightsEntitiesAboveBody(){
  var topic=new TopicFileStore.Topic("topics/research/nebius.md","Nebius","research","Cloud capacity research","v",Instant.now(),"Cloud capacity research about NBIS",java.util.List.of("NBIS"),java.util.List.of("AI"));
  assertThat(TopicRetrievalService.lexicalEligible("research elsewhere",TopicRetrievalService.terms("research elsewhere"),topic)).isFalse();
  assertThat(TopicRetrievalService.lexicalEligible("NBIS",TopicRetrievalService.terms("NBIS"),topic)).isTrue();
  assertThat(TopicRetrievalService.lexicalEligible("cloud capacity",TopicRetrievalService.terms("cloud capacity"),topic)).isTrue();
  assertThat(TopicRetrievalService.lexicalScore(java.util.Set.of("nbis"),topic,false)).isGreaterThan(TopicRetrievalService.lexicalScore(java.util.Set.of("capacity"),topic,true));
 }
}
