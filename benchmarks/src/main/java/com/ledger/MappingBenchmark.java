package com.ledger;

import java.time.Instant;
import org.openjdk.jmh.annotations.Benchmark;
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