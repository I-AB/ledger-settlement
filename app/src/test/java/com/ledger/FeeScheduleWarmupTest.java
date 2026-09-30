package com.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class FeeScheduleWarmupTest {

    @Test
    @DisplayName("FeeScheduleLookup is already initialized once the application context is up")
    void feeScheduleIsWarmedUpBeforeAnyRequestCanArrive() {
        assertThat(FeeScheduleLookup.TABLE_VERSION).isEqualTo(1);
    }
}