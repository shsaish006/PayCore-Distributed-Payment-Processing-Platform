package com.paycore.ledger.domain.repository;

import com.paycore.ledger.domain.model.LedgerEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, String> {
}
