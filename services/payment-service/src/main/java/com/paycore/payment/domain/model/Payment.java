package com.paycore.payment.domain.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Entity
@Table(name = "payments")
@IdClass(PaymentId.class)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Payment {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Id
    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "merchant_id", nullable = false)
    private String merchantId;

    @Column(name = "amount", nullable = false)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "status", nullable = false, length = 32)
    private String status; // CREATED, AUTHORIZED, CAPTURED, REFUNDED, CANCELLED, FAILED

    @Column(name = "payment_method", nullable = false, length = 32)
    private String paymentMethod;

    @Column(name = "idempotency_key", nullable = false, length = 128)
    private String idempotencyKey;

    @Column(name = "card_token", length = 128)
    private String cardToken;

    @Column(name = "gateway_reference", length = 128)
    private String gatewayReference;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = OffsetDateTime.now();
        }
        this.updatedAt = OffsetDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = OffsetDateTime.now();
    }

    public boolean canBeCaptured() {
        return "AUTHORIZED".equals(status);
    }

    public boolean canBeRefunded() {
        return "CAPTURED".equals(status) || "PARTIALLY_REFUNDED".equals(status);
    }

    public boolean canBeCancelled() {
        return "CREATED".equals(status) || "AUTHORIZED".equals(status);
    }
}
