package com.rronin.financialagent.model;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class RunOutputBudgetTest {
    @Test void concurrentReservationsCannotOversubscribeRunCap() {
        var budget = new RunOutputBudget(100_000);
        var first = budget.reserve(64_000);
        var second = budget.reserve(64_000);
        assertThat(first.limit()).isEqualTo(64_000);
        assertThat(second.limit()).isEqualTo(36_000);
        assertThatThrownBy(() -> budget.reserve(1)).isInstanceOf(IllegalStateException.class);
        first.settle(1000);
        first.settle(5000);
        assertThat(budget.used()).isEqualTo(1000);
        assertThat(budget.reserve(64_000).limit()).isEqualTo(63_000);
    }
    @Test void failedCallReleasesReservationAndReportedOverrunStillCounts() {
        var budget = new RunOutputBudget(100);
        budget.reserve(100).settle(0);
        budget.reserve(100).settle(110);
        assertThat(budget.used()).isEqualTo(110);
        assertThatThrownBy(() -> budget.reserve(1)).isInstanceOf(IllegalStateException.class);
    }
}
