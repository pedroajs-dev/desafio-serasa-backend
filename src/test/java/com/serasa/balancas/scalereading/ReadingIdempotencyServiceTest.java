package com.serasa.balancas.scalereading;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ReadingIdempotencyServiceTest {

    @Test
    void firstOccurrenceIsNotDuplicateButSecondIs() {
        ReadingIdempotencyService service = new ReadingIdempotencyService();

        assertThat(service.registerAndCheckDuplicate("BAL-001", 1L)).isFalse();
        assertThat(service.registerAndCheckDuplicate("BAL-001", 1L)).isTrue();
    }

    @Test
    void nullSeqIsNeverADuplicate() {
        ReadingIdempotencyService service = new ReadingIdempotencyService();

        assertThat(service.registerAndCheckDuplicate("BAL-001", null)).isFalse();
        assertThat(service.registerAndCheckDuplicate("BAL-001", null)).isFalse();
    }

    @Test
    void sameSeqOnDifferentScalesAreIndependent() {
        ReadingIdempotencyService service = new ReadingIdempotencyService();

        assertThat(service.registerAndCheckDuplicate("BAL-001", 1L)).isFalse();
        assertThat(service.registerAndCheckDuplicate("BAL-002", 1L)).isFalse();
        assertThat(service.registerAndCheckDuplicate("BAL-002", 1L)).isTrue();
    }

    @Test
    void evictedKeyCanBeSeenAgain() {
        ReadingIdempotencyService service = new ReadingIdempotencyService();

        assertThat(service.registerAndCheckDuplicate("BAL-001", 1L)).isFalse();
        service.evict("BAL-001", 1L);
        assertThat(service.registerAndCheckDuplicate("BAL-001", 1L)).isFalse();
    }

    @Test
    void evictWithNullSeqIsANoOp() {
        ReadingIdempotencyService service = new ReadingIdempotencyService();

        service.evict("BAL-001", null);

        assertThat(service.registerAndCheckDuplicate("BAL-001", 1L)).isFalse();
    }
}
