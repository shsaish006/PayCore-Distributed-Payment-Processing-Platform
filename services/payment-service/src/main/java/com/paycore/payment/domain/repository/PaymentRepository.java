package com.paycore.payment.domain.repository;

import com.paycore.payment.domain.model.Payment;
import com.paycore.payment.domain.model.PaymentId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface PaymentRepository extends JpaRepository<Payment, PaymentId> {

    @Query("SELECT p FROM Payment p WHERE p.merchantId = :merchantId AND p.idempotencyKey = :idempotencyKey")
    Optional<Payment> findByMerchantIdAndIdempotencyKey(@Param("merchantId") String merchantId, @Param("idempotencyKey") String idempotencyKey);

    @Query("SELECT p FROM Payment p WHERE p.id = :id")
    Optional<Payment> findFirstById(@Param("id") String id);
}
