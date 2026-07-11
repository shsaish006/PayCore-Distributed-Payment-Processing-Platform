package com.paycore.ledger.domain.repository;

import com.paycore.ledger.domain.model.InboxMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface InboxRepository extends JpaRepository<InboxMessage, String> {
}
