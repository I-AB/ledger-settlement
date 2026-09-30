package com.ledger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Warmup;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5)
@Measurement(iterations = 10)
@Fork(3)

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