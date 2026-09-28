package com.ledger;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

// PaymentEntity is a mutable class and not a record because JPA creates an empty instance
// through the no-argument constructor and then fills its fields in, which a record forbids.
@Entity
@Table(name = "payments")
public class PaymentEntity {

    @Id
    private String id;

    @Column(name = "merchant_id", nullable = false)
    private String merchantId;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @Column(name = "currency", nullable = false)
    private String currency;

    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;

    public PaymentEntity() {
    }

    public PaymentEntity(String id, String merchantId, long amountMinor, String currency, Instant recordedAt) {
        this.id = id;
        this.merchantId = merchantId;
        this.amountMinor = amountMinor;
        this.currency = currency;
        this.recordedAt = recordedAt;
    }

    public String getId() {
        return id;
    }

    public String getMerchantId() {
        return merchantId;
    }

    public long getAmountMinor() {
        return amountMinor;
    }

    public String getCurrency() {
        return currency;
    }

    public Instant getRecordedAt() {
        return recordedAt;
    }
}