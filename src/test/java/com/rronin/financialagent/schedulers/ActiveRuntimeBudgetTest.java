package com.rronin.financialagent.schedulers;

import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

class ActiveRuntimeBudgetTest {
    @Test void waitingForApprovalDoesNotConsumeExecutionBudget(){
        var waiting=new AtomicBoolean(true);
        StepVerifier.withVirtualTime(()->ActiveRuntimeBudget.elapsed(Duration.ofSeconds(3),waiting::get))
                .expectSubscription().expectNoEvent(Duration.ofHours(2))
                .then(()->waiting.set(false)).expectNoEvent(Duration.ofSeconds(2))
                .thenAwait(Duration.ofSeconds(1)).expectNext(3L).verifyComplete();
    }
    @Test void executionTimeAccumulatesAcrossMultipleApprovalWaits(){
        var waiting=new AtomicBoolean(false);
        StepVerifier.withVirtualTime(()->ActiveRuntimeBudget.elapsed(Duration.ofSeconds(3),waiting::get))
                .expectSubscription().thenAwait(Duration.ofSeconds(1))
                .then(()->waiting.set(true)).expectNoEvent(Duration.ofMinutes(10))
                .then(()->waiting.set(false)).thenAwait(Duration.ofSeconds(2))
                .expectNext(3L).verifyComplete();
    }
}
