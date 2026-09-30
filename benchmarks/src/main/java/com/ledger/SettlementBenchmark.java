package com.ledger;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;
import org.mockito.Mockito;

public class SettlementBenchmark {

    @State(Scope.Benchmark)
    public static class SettlementState {
        SettlementService service;
        String merchantId = "MR-4471";

        @Setup
        public void setUp() {
            List<PaymentEntity> payments = new ArrayList<>();
            for (int i = 0; i < 500; i++) {
                payments.add(new PaymentEntity(
                        "PAY-" + i, merchantId, 1000L + i, "USD", Instant.now()));
            }
            PaymentRepository repository = Mockito.mock(PaymentRepository.class);
            Mockito.when(repository.findByMerchantId(merchantId)).thenReturn(payments);
            SettlementProperties properties = new SettlementProperties(BigDecimal.valueOf(0.031));
            service = new SettlementService(repository, properties);
        }
    }

    @Benchmark
    public void owedMinor(SettlementState state, Blackhole blackhole) {
        blackhole.consume(state.service.owedMinor(state.merchantId));
    }
}