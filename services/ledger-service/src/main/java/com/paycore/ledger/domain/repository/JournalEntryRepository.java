package com.paycore.ledger.domain.repository;

import com.paycore.ledger.domain.model.JournalEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface JournalEntryRepository extends JpaRepository<JournalEntry, String> {
    List<JournalEntry> findByLedgerEntryId(String ledgerEntryId);
}
