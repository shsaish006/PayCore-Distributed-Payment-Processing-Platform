package com.paycore.payment.domain.model;

import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.Objects;

public class PaymentId implements Serializable {
    private String id;
    private OffsetDateTime createdAt;

    public PaymentId() {}

    public PaymentId(String id, OffsetDateTime createdAt) {
        this.id = id;
        this.createdAt = createdAt;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PaymentId paymentId = (PaymentId) o;
        return Objects.equals(id, paymentId.id) && Objects.equals(createdAt, paymentId.createdAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, createdAt);
    }
}
