package com.paycore.ledger.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

@Entity
@Table(name = "inbox_messages")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InboxMessage {

    @Id
    private String id; // unique event UUID

    @Column(name = "consumer_group", nullable = false)
    private String consumerGroup;

    @Column(name = "processed_at", nullable = false)
    private OffsetDateTime processedAt;
}
