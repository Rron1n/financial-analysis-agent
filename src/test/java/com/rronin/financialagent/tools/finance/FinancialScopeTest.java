package com.rronin.financialagent.tools.finance;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.assertThat;
class FinancialScopeTest {
 @Test void preservesActualPeriodAndDoesNotInventMissingCurrency() throws Exception {
  var data=new ObjectMapper().readTree("{\"income_statements\":[{\"report_period\":\"2026-06-30\",\"period\":\"quarterly\",\"filing_date\":\"2026-08-10\",\"revenue\":12}]}");
  var scope=GetFinancialsTool.scope("TE","income","ttm",data);
  assertThat(scope.get("requestedPeriod")).isEqualTo("ttm");
  assertThat(scope.get("records").toString()).contains("quarterly","2026-06-30","$.income_statements[0]").doesNotContain("currency=");
  assertThat(data.path("income_statements").get(0).path("revenue").asInt()).isEqualTo(12);
 }
}
