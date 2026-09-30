package com.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class PaymentControllerIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Value("${local.server.port}")
    private int port;

    @Test
    @DisplayName("posting 128450 minor units for MR-4471 yields a settlement of 124469")
    void postedPaymentSettlesAfterFee() {
        RestClient client = RestClient.create("http://localhost:" + port);

        ResponseEntity<PaymentController.PaymentResponse> created = client.post()
                .uri("/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new PaymentController.RecordPaymentRequest("MR-4471", 128450L, "GBP"))
                .retrieve()
                .toEntity(PaymentController.PaymentResponse.class);

        assertThat(created.getStatusCode().value()).isEqualTo(201);
        assertThat(created.getHeaders().getLocation()).isNotNull();

        PaymentController.SettlementResponse settlement = client.get()
                .uri("/payments/settlement?merchantId=MR-4471")
                .retrieve()
                .body(PaymentController.SettlementResponse.class);

        assertThat(settlement).isNotNull();
        assertThat(settlement.owedMinor()).isEqualTo(124469L);
    }
}