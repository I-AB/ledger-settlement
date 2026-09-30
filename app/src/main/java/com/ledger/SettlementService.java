package com.ledger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SettlementService {

    private final PaymentRepository repository;
    private final SettlementProperties properties;

    public SettlementService(PaymentRepository repository, SettlementProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    @Transactional
    public PaymentEntity recordPayment(String merchantId, long amountMinor, String currency) {
        PaymentEntity entity = new PaymentEntity(
                "PAY-" + UUID.randomUUID(), merchantId, amountMinor, currency, Instant.now());
        return repository.save(entity);
    }

    @Transactional(readOnly = true)
    public PaymentEntity getPayment(String id) {
        return repository.findById(id).orElseThrow(() -> new PaymentNotFoundException(id));
    }

    @Transactional(readOnly = true)
    public long owedMinor(String merchantId) {
        int tableVersion = FeeScheduleLookup.TABLE_VERSION;

        List<PaymentEntity> payments = repository.findByMerchantId(merchantId);
        if (payments.isEmpty()) {
            throw new MerchantNotFoundException(merchantId);
        }
        long total = payments.stream().mapToLong(PaymentEntity::getAmountMinor).sum();
        BigDecimal fee = BigDecimal.valueOf(total)
                .multiply(properties.feeRate())
                .setScale(0, RoundingMode.DOWN);
        return total - fee.longValueExact();
    }
}