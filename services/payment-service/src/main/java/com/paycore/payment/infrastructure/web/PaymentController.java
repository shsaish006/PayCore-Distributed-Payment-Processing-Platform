package com.paycore.payment.infrastructure.web;

import com.paycore.payment.domain.model.Payment;
import com.paycore.payment.domain.saga.PaymentSagaOrchestrator;
import com.paycore.payment.domain.repository.PaymentRepository;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;

@RestController
@RequestMapping("/v1/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentSagaOrchestrator sagaOrchestrator;
    private final PaymentRepository paymentRepository;

    @PostMapping
    public ResponseEntity<Payment> createPayment(
            @RequestHeader("idempotency-key") String idempotencyKey,
            @RequestBody CreatePaymentRequest request
    ) {
        Payment payment = sagaOrchestrator.executeSaga(
                request.getMerchantId(),
                request.getAmount(),
                request.getCurrency(),
                request.getPaymentMethod(),
                idempotencyKey,
                request.getCardToken(),
                request.getDescription()
        );

        HttpStatus status = "FAILED".equals(payment.getStatus()) ? HttpStatus.PAYMENT_REQUIRED : HttpStatus.CREATED;
        return new ResponseEntity<>(payment, status);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Payment> getPayment(@PathVariable("id") String paymentId) {
        return paymentRepository.findFirstById(paymentId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @Data
    public static class CreatePaymentRequest {
        private String merchantId;
        private BigDecimal amount;
        private String currency;
        private String paymentMethod;
        private String cardToken;
        private String description;
    }
}
