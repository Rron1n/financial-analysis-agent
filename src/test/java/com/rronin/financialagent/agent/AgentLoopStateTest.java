package com.rronin.financialagent.agent;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class AgentLoopStateTest {
    @Test void runBudgetIsACapNotAMinimum() {
        var state = new AgentLoopState("session", "run", 30);
        assertThat(state.requestOutputLimit(128_000)).isEqualTo(6_000);
        state.outputTokens = com.rronin.financialagent.config.TokenBudgetPolicy.RUN_OUTPUT-1_000;
        state.maxOutputTokens = 128_000;
        assertThat(state.requestOutputLimit(128_000)).isZero();
        state.outputTokens=com.rronin.financialagent.config.TokenBudgetPolicy.RUN_OUTPUT-12_000;
        assertThat(state.requestOutputLimit(128_000)).isZero();
        state.outputTokens = com.rronin.financialagent.config.TokenBudgetPolicy.RUN_OUTPUT+1;
        assertThat(state.requestOutputLimit(128_000)).isZero();
        state.outputTokens = 100;
        state.status = AgentLoopState.RunStatus.COMPLETED;
        assertThat(state.terminal()).isTrue();
    }
    @Test void primaryLeavesEnoughForAllThreeReviewStages() {
        var state=new AgentLoopState("s","r",30);
        state.outputTokens=com.rronin.financialagent.config.TokenBudgetPolicy.RUN_OUTPUT-28_000;
        assertThat(state.requestOutputLimit(128_000)).isEqualTo(3_000);
        state.outputTokens+=3_000;
        assertThat(state.requestOutputLimit(128_000)).isZero();
    }
    @Test void chineseIsNotCountedAsFourCharactersPerToken() {
        assertThat(TokenEstimator.text("中文测试")).isEqualTo(4);
        assertThat(TokenEstimator.text("test中文")).isEqualTo(3);
    }
    @org.junit.jupiter.api.Test void providerUsageCalibratesNewAndRemovedContext(){
        assertThat(TokenEstimator.calibrated(20000,15000,18000)).isEqualTo(23000);
        assertThat(TokenEstimator.calibrated(10000,15000,18000)).isEqualTo(13000);
        assertThat(TokenEstimator.calibrated(10000,0,0)).isEqualTo(10000);
    }
}
