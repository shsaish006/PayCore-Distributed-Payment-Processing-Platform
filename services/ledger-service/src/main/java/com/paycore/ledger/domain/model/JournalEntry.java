package com.paycore.ledger.domain.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Entity
@Table(name = "journal_entries")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JournalEntry {

    @Id
    private String id;

    @Column(name = "ledger_entry_id", nullable = false)
    private String ledgerEntryId;

    @Column(name = "account_id", nullable = false)
    private String accountId;

    @Column(name = "entry_type", nullable = false, length = 6)
    private String entryType; // DEBIT, CREDIT

    @Column(name = "amount", nullable = false, precision = 18, scale = 4)
    private BigDecimal amount;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = OffsetDateTime.now();
    }
}
