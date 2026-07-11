package com.paycore.payment.infrastructure.grpc;

import com.paycore.payment.domain.model.Payment;
import com.paycore.payment.domain.repository.PaymentRepository;
import com.paycore.payment.domain.saga.PaymentSagaOrchestrator;
import com.paycore.payment.domain.outbox.OutboxEvent;
import com.paycore.payment.domain.outbox.OutboxRepository;

import com.paycore.payment.grpc.PaymentServiceGrpc;
import com.paycore.payment.grpc.CreatePaymentRequest;
import com.paycore.payment.grpc.GetPaymentRequest;
import com.paycore.payment.grpc.CapturePaymentRequest;
import com.paycore.payment.grpc.RefundPaymentRequest;
import com.paycore.payment.grpc.CancelPaymentRequest;
import com.paycore.payment.grpc.PaymentResponse;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.server.service.GrpcService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@GrpcService
@Slf4j
@RequiredArgsConstructor
public class PaymentGrpcService extends PaymentServiceGrpc.PaymentServiceImplBase {

    private final PaymentSagaOrchestrator sagaOrchestrator;
    private final PaymentRepository paymentRepository;
    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void createPayment(CreatePaymentRequest request, StreamObserver<PaymentResponse> responseObserver) {
        log.info("gRPC Request: CreatePayment amount={}", request.getAmount());
        try {
            Payment payment = sagaOrchestrator.executeSaga(
                    request.getMerchantId(),
                    BigDecimal.valueOf(request.getAmount()),
                    request.getCurrency(),
                    request.getPaymentMethod(),
                    request.getIdempotencyKey(),
                    request.getCardToken(),
                    request.getDescription()
            );
            responseObserver.onNext(mapToResponse(payment));
            responseObserver.onCompleted();
        } catch (Exception ex) {
            log.error("Failed to execute Payment Saga via gRPC", ex);
            responseObserver.onNext(PaymentResponse.newBuilder()
                    .setStatus("FAILED")
                    .setErrorMessage(ex.getMessage())
                    .build());
            responseObserver.onCompleted();
        }
    }

    @Override
    public void getPayment(GetPaymentRequest request, StreamObserver<PaymentResponse> responseObserver) {
        paymentRepository.findFirstById(request.getPaymentId())
                .map(this::mapToResponse)
                .ifPresentOrElse(
                        response -> {
                            responseObserver.onNext(response);
                            responseObserver.onCompleted();
                        },
                        () -> {
                            responseObserver.onNext(PaymentResponse.newBuilder().setStatus("NOT_FOUND").build());
                            responseObserver.onCompleted();
                        }
                );
    }

    @Override
    @Transactional
    public void capturePayment(CapturePaymentRequest request, StreamObserver<PaymentResponse> responseObserver) {
        log.info("gRPC Request: CapturePayment paymentId={}", request.getPaymentId());
        var paymentOpt = paymentRepository.findFirstById(request.getPaymentId());
        
        if (paymentOpt.isEmpty()) {
            responseObserver.onNext(PaymentResponse.newBuilder().setStatus("NOT_FOUND").build());
            responseObserver.onCompleted();
            return;
        }

        Payment payment = paymentOpt.get();
        if (!payment.canBeCaptured()) {
            responseObserver.onNext(PaymentResponse.newBuilder()
                    .setStatus(payment.getStatus())
                    .setErrorMessage("Payment cannot be captured in its current state")
                    .build());
            responseObserver.onCompleted();
            return;
        }

        BigDecimal captureAmount = BigDecimal.valueOf(request.getAmount());
        if (captureAmount.compareTo(BigDecimal.ZERO) <= 0 || captureAmount.compareTo(payment.getAmount()) > 0) {
            // Default to full capture
            captureAmount = payment.getAmount();
        }

        payment.setStatus("CAPTURED");
        paymentRepository.save(payment);
        
        // Write event to outbox
        saveOutboxEvent(payment.getId(), "PAYMENT_CAPTURED", payment);

        responseObserver.onNext(mapToResponse(payment));
        responseObserver.onCompleted();
    }

    @Override
    @Transactional
    public void refundPayment(RefundPaymentRequest request, StreamObserver<PaymentResponse> responseObserver) {
        log.info("gRPC Request: RefundPayment paymentId={}", request.getPaymentId());
        var paymentOpt = paymentRepository.findFirstById(request.getPaymentId());

        if (paymentOpt.isEmpty()) {
            responseObserver.onNext(PaymentResponse.newBuilder().setStatus("NOT_FOUND").build());
            responseObserver.onCompleted();
            return;
        }

        Payment payment = paymentOpt.get();
        if (!payment.canBeRefunded()) {
            responseObserver.onNext(PaymentResponse.newBuilder()
                    .setStatus(payment.getStatus())
                    .setErrorMessage("Payment cannot be refunded in its current state")
                    .build());
            responseObserver.onCompleted();
            return;
        }

        BigDecimal refundAmount = BigDecimal.valueOf(request.getAmount());
        if (refundAmount.compareTo(BigDecimal.ZERO) <= 0) {
            refundAmount = payment.getAmount(); // Default to full refund
        }

        payment.setStatus("REFUNDED");
        paymentRepository.save(payment);

        saveOutboxEvent(payment.getId(), "PAYMENT_REFUNDED", payment);

        responseObserver.onNext(mapToResponse(payment));
        responseObserver.onCompleted();
    }

    @Override
    @Transactional
    public void cancelPayment(CancelPaymentRequest request, StreamObserver<PaymentResponse> responseObserver) {
        log.info("gRPC Request: CancelPayment paymentId={}", request.getPaymentId());
        var paymentOpt = paymentRepository.findFirstById(request.getPaymentId());

        if (paymentOpt.isEmpty()) {
            responseObserver.onNext(PaymentResponse.newBuilder().setStatus("NOT_FOUND").build());
            responseObserver.onCompleted();
            return;
        }

        Payment payment = paymentOpt.get();
        if (!payment.canBeCancelled()) {
            responseObserver.onNext(PaymentResponse.newBuilder()
                    .setStatus(payment.getStatus())
                    .setErrorMessage("Payment cannot be cancelled in its current state")
                    .build());
            responseObserver.onCompleted();
            return;
        }

        payment.setStatus("CANCELLED");
        payment.setErrorMessage(request.getReason());
        paymentRepository.save(payment);

        saveOutboxEvent(payment.getId(), "PAYMENT_FAILED", payment);

        responseObserver.onNext(mapToResponse(payment));
        responseObserver.onCompleted();
    }

    private PaymentResponse mapToResponse(Payment p) {
        return PaymentResponse.newBuilder()
                .setId(p.getId())
                .setMerchantId(p.getMerchantId())
                .setAmount(p.getAmount().doubleValue())
                .setCurrency(p.getCurrency())
                .setStatus(p.getStatus())
                .setIdempotencyKey(p.getIdempotencyKey())
                .setCreatedAt(p.getCreatedAt().toString())
                .setUpdatedAt(p.getUpdatedAt().toString())
                .setGatewayReference(p.getGatewayReference() != null ? p.getGatewayReference() : "")
                .setErrorMessage(p.getErrorMessage() != null ? p.getErrorMessage() : "")
                .build();
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
        }
    }
}
