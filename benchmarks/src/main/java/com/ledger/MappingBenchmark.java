package com.ledger;

import java.time.Instant;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.infra.Blackhole;

public class MappingBenchmark {

    @Benchmark
    public void mapToResponse_broken() {
        PaymentEntity entity = new PaymentEntity(
                "PAY-benchmark", "MR-4471", 10000L, "USD", Instant.now());
        PaymentController.toResponse(entity);
    }

    @Benchmark
    public void mapToResponse_fixed(Blackhole blackhole) {
        PaymentEntity entity = new PaymentEntity(
                "PAY-benchmark", "MR-4471", 10000L, "USD", Instant.now());
        blackhole.consume(PaymentController.toResponse(entity));
    }
}