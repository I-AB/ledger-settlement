package com.ledger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

public class CollectionBenchmark {

    @State(Scope.Benchmark)
    public static class PaymentListState {
        List<PaymentEntity> payments;

        @Setup
        public void setUp() {
            payments = new ArrayList<>();
            for (int i = 0; i < 500; i++) {
                payments.add(new PaymentEntity(
                        "PAY-" + i, "MR-4471", 1000L + i, "USD", Instant.now()));
            }
        }
    }

    @Benchmark
    public void sumAmounts(PaymentListState state, Blackhole blackhole) {
        blackhole.consume(SettlementService.sumAmounts(state.payments));
    }
}