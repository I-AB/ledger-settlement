package com.ledger;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/payments")
public class PaymentController {

    private final SettlementService service;

    public PaymentController(SettlementService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<PaymentResponse> recordPayment(@Valid @RequestBody RecordPaymentRequest request) {
        PaymentEntity saved = service.recordPayment(
                request.merchantId(), request.amountMinor(), request.currency());
        return ResponseEntity.created(URI.create("/payments/" + saved.getId())).body(toResponse(saved));
    }

    @GetMapping("/{id}")
    public PaymentResponse getPayment(@PathVariable @NotBlank String id) {
        return toResponse(service.getPayment(id));
    }

    @GetMapping("/settlement")
    public SettlementResponse settlement(@RequestParam String merchantId) {
        return new SettlementResponse(merchantId, service.owedMinor(merchantId));
    }

    private static PaymentResponse toResponse(PaymentEntity entity) {
        return new PaymentResponse(
                entity.getId(), entity.getMerchantId(), entity.getAmountMinor(), entity.getCurrency());
    }

    public record RecordPaymentRequest(
            @NotBlank String merchantId,
            @Positive long amountMinor,
            @NotBlank String currency) {
    }

    public record PaymentResponse(String id, String merchantId, long amountMinor, String currency) {
    }

    public record SettlementResponse(String merchantId, long owedMinor) {
    }
}