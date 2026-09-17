package com.rronin.financialagent.schedulers;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.time.Duration;
import java.util.function.BooleanSupplier;

/** Human approval waiting is not agent execution time. One-second accounting resolution. */
final class ActiveRuntimeBudget {
    static Mono<Long> elapsed(Duration budget,BooleanSupplier waitingForApproval){
        long seconds=Math.max(1,(budget.toMillis()+999)/1000);
        return Flux.interval(Duration.ofSeconds(1))
                .scan(0L,(used,tick)->used+(waitingForApproval.getAsBoolean()?0:1))
                .filter(used->used>=seconds).next();
    }
}
