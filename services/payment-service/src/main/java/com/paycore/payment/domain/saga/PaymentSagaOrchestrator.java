package com.paycore.payment.domain.saga;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paycore.payment.domain.model.Payment;
import com.paycore.payment.domain.repository.PaymentRepository;
import com.paycore.payment.domain.outbox.OutboxEvent;
import com.paycore.payment.domain.outbox.OutboxRepository;

import com.paycore.fraud.grpc.FraudDetectionServiceGrpc;
import com.paycore.fraud.grpc.AnalyzeTransactionRequest;
import com.paycore.fraud.grpc.AnalyzeTransactionResponse;

import com.paycore.payment.grpc.PaymentServiceGrpc;
import com.paycore.payment.grpc.AuthorizePaymentRequest;
import com.paycore.payment.grpc.CancelPaymentRequest;
import com.paycore.payment.grpc.PaymentResponse;

import com.paycore.ledger.grpc.LedgerServiceGrpc;
import com.paycore.ledger.grpc.RecordTransactionRequest;
import com.paycore.ledger.grpc.RecordTransactionResponse;

import net.devh.boot.grpc.client.inject.GrpcClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class PaymentSagaOrchestrator {

    private final PaymentRepository paymentRepository;
    private final OutboxRepository outboxRepository;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @GrpcClient("fraud-service")
    private FraudDetectionServiceGrpc.FraudDetectionServiceBlockingStub fraudStub;

    @GrpcClient("authorization-service")
    private PaymentServiceGrpc.PaymentServiceBlockingStub authorizationStub;

    @GrpcClient("ledger-service")
    private LedgerServiceGrpc.LedgerServiceBlockingStub ledgerStub;

    @Transactional
    public Payment executeSaga(
            String merchantId,
            BigDecimal amount,
            String currency,
            String paymentMethod,
            String idempotencyKey,
            String cardToken,
            String description
    ) {
        String lockKey = "lock:idempotency:" + merchantId + ":" + idempotencyKey;
        // Acquire Redis Distributed Lock with a 10s lease time
        Boolean lockAcquired = redisTemplate.opsForValue().setIfAbsent(lockKey, "LOCKED", Duration.ofSeconds(10));
        
        if (Boolean.FALSE.equals(lockAcquired)) {
            throw new IllegalStateException("Duplicate request in progress for idempotency key");
        }

        try {
            // 1. Idempotency Check in Database
            var existingPayment = paymentRepository.findByMerchantIdAndIdempotencyKey(merchantId, idempotencyKey);
            if (existingPayment.isPresent()) {
                log.info("Duplicate payment detected in DB for idempotency key: {}", idempotencyKey);
                return existingPayment.get();
            }

            // 2. Initialize Payment
            String paymentId = "pay_" + UUID.randomUUID().toString().replace("-", "");
            Payment payment = Payment.builder()
                    .id(paymentId)
                    .merchantId(merchantId)
                    .amount(amount)
                    .currency(currency)
                    .status("CREATED")
                    .paymentMethod(paymentMethod)
                    .idempotencyKey(idempotencyKey)
                    .cardToken(cardToken)
                    .createdAt(OffsetDateTime.now())
                    .updatedAt(OffsetDateTime.now())
                    .build();

            paymentRepository.save(payment);
            saveOutboxEvent(paymentId, "PAYMENT_INITIATED", payment);

            // 3. Step 1: Fraud Check via gRPC
            log.info("SAGA [{}]: Step 1 - Initiating Fraud check", paymentId);
            try {
                AnalyzeTransactionResponse fraudResponse = fraudStub.analyzeTransaction(
                        AnalyzeTransactionRequest.newBuilder()
                                .setTransactionId(paymentId)
                                .setMerchantId(merchantId)
                                .setAmount(amount.doubleValue())
                                .setCurrency(currency)
                                .setCardFingerprint(cardToken) // Using cardToken as fingerprint mockup
                                .setDeviceFingerprint("dev_fp_12345")
                                .setIpAddress("127.0.0.1")
                                .setBillingCountry("US")
                                .build()
                );

                if (fraudResponse.getIsFlagged() || "REJECT".equals(fraudResponse.getRecommendation())) {
                    log.warn("SAGA [{}]: Fraud check failed. Reason: {}", paymentId, fraudResponse.getReasonsList());
                    failPayment(payment, "Transaction flagged as fraud: " + String.join(", ", fraudResponse.getReasonsList()));
                    return payment;
                }
            } catch (Exception ex) {
                // If Fraud service fails, we fail-open or fail-closed. In high-security systems, fail-closed.
                log.error("SAGA [{}]: Fraud service unavailable. Failing transaction for safety.", paymentId, ex);
                failPayment(payment, "Fraud verification service unavailable");
                return payment;
            }

            // 4. Step 2: Card Authorization via gRPC (acquiring network call)
            log.info("SAGA [{}]: Step 2 - Dispatching Card Authorization", paymentId);
            PaymentResponse authResponse;
            try {
                authResponse = authorizationStub.authorizePayment(
                        AuthorizePaymentRequest.newBuilder()
                                .setPaymentId(paymentId)
                                .setMerchantId(merchantId)
                                .setAmount(amount.doubleValue())
                                .setCardToken(cardToken)
                                .build()
                );
            } catch (Exception ex) {
                log.error("SAGA [{}]: Card Authorization network error", paymentId, ex);
                failPayment(payment, "Authorization Gateway timeout / Network error");
                return payment;
            }

            if (!"AUTHORIZED".equals(authResponse.getStatus())) {
                log.warn("SAGA [{}]: Card declined by issuer. Message: {}", paymentId, authResponse.getErrorMessage());
                failPayment(payment, "Card declined: " + authResponse.getErrorMessage());
                return payment;
            }

            // 5. Update state to Authorized
            payment.setStatus("AUTHORIZED");
            payment.setGatewayReference(authResponse.getGatewayReference());
            paymentRepository.save(payment);
            saveOutboxEvent(paymentId, "PAYMENT_AUTHORIZED", payment);

            // 6. Step 3: Journal Recording in Ledger Service
            log.info("SAGA [{}]: Step 3 - Recording double-entry ledger items", paymentId);
            try {
                RecordTransactionResponse ledgerResponse = ledgerStub.recordTransaction(
                        RecordTransactionRequest.newBuilder()
                                .setMerchantId(merchantId)
                                .setTransactionId(paymentId)
                                .setAmount(amount.doubleValue())
                                .setCurrency(currency)
                                .setDescription("Capture payment: " + description)
                                .setType("PAYMENT")
                                .build()
                );

                if (!ledgerResponse.getSuccess()) {
                    log.error("SAGA [{}]: Ledger rejected record. Executing compensating rollback.", paymentId);
                    compensateSaga(payment, "Ledger system rejected entry");
                    return payment;
                }
            } catch (Exception ex) {
                log.error("SAGA [{}]: Ledger Service connection failure. Initiating compensation.", paymentId, ex);
                compensateSaga(payment, "Ledger registration timeout");
                return payment;
            }

            // 7. Saga Completed Successfully
            log.info("SAGA [{}]: Complete. Payment fully processed.", paymentId);
            return payment;

        } finally {
            // Release Distributed Lock
            redisTemplate.delete(lockKey);
        }
    }

    private void failPayment(Payment payment, String reason) {
        payment.setStatus("FAILED");
        payment.setErrorMessage(reason);
        paymentRepository.save(payment);
        saveOutboxEvent(payment.getId(), "PAYMENT_FAILED", payment);
    }

    private void compensateSaga(Payment payment, String rollbackReason) {
        log.warn("SAGA COMPENSATION [{}]: Voiding authorization due to: {}", payment.getId(), rollbackReason);
        
        try {
            // Compensate Step: Cancel Authorization at Gateway
            authorizationStub.cancelPayment(
                    CancelPaymentRequest.newBuilder()
                            .setPaymentId(payment.getId())
                            .setReason("Compensating Saga: " + rollbackReason)
                            .build()
            );
        } catch (Exception ex) {
            // In a real system, failed compensations are retried or queued for operations alerts
            log.error("CRITICAL ERROR: SAGA COMPENSATION FAILED for payment {}. Manual reconciliation required.", payment.getId(), ex);
        }

        payment.setStatus("FAILED");
        payment.setErrorMessage("Transaction cancelled: " + rollbackReason);
        paymentRepository.save(payment);
        saveOutboxEvent(payment.getId(), "PAYMENT_FAILED", payment);
    }

    private void saveOutboxEvent(String aggregateId, String eventType, Object domainObj) {
        try {
            String payload = objectMapper.writeValueAsString(domainObj);
            OutboxEvent outbox = OutboxEvent.builder()
                    .id(UUID.randomUUID().toString())
                    .aggregateType("PAYMENT")
                    .aggregateId(aggregateId)
                    .eventType(eventType)
                    .payload(payload)
                    .createdAt(OffsetDateTime.now())
                    .status("PENDING")
                    .build();
            outboxRepository.save(outbox);
        } catch (Exception ex) {
            log.error("Failed to write to transaction outbox for payment {}", aggregateId, ex);
            throw new RuntimeException("Outbox transaction write failed", ex);
        }
    }
}
