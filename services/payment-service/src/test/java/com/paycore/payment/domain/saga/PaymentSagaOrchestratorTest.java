package com.paycore.payment.domain.saga;

import com.paycore.payment.domain.model.Payment;
import com.paycore.payment.domain.repository.PaymentRepository;
import com.paycore.payment.domain.outbox.OutboxRepository;
import com.paycore.fraud.grpc.FraudDetectionServiceGrpc;
import com.paycore.fraud.grpc.AnalyzeTransactionResponse;
import com.paycore.payment.grpc.PaymentServiceGrpc;
import com.paycore.payment.grpc.PaymentResponse;
import com.paycore.ledger.grpc.LedgerServiceGrpc;
import com.paycore.ledger.grpc.RecordTransactionResponse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class PaymentSagaOrchestratorTest {

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private OutboxRepository outboxRepository;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @InjectMocks
    private PaymentSagaOrchestrator sagaOrchestrator;

    private FraudDetectionServiceGrpc.FraudDetectionServiceBlockingStub fraudStub;
    private PaymentServiceGrpc.PaymentServiceBlockingStub authorizationStub;
    private LedgerServiceGrpc.LedgerServiceBlockingStub ledgerStub;

    @BeforeEach
    public void setup() {
        // Setup Redis value operations mock
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        
        // Mock gRPC stubs inside orchestrator via reflection/field setting or mockito injection
        fraudStub = mock(FraudDetectionServiceGrpc.FraudDetectionServiceBlockingStub.class);
        authorizationStub = mock(PaymentServiceGrpc.PaymentServiceBlockingStub.class);
        ledgerStub = mock(LedgerServiceGrpc.LedgerServiceBlockingStub.class);
        
        // Inject mocks using reflection or setters if needed, but Mockito's InjectMocks handles standard fields
        // Let's explicitly bind gRPC fields in test to guarantee no null pointers
        org.springframework.test.util.ReflectionTestUtils.setField(sagaOrchestrator, "fraudStub", fraudStub);
        org.springframework.test.util.ReflectionTestUtils.setField(sagaOrchestrator, "authorizationStub", authorizationStub);
        org.springframework.test.util.ReflectionTestUtils.setField(sagaOrchestrator, "ledgerStub", ledgerStub);
    }

    @Test
    public void testExecuteSaga_Success() {
        // Arrange
        String merchantId = "merchant_123";
        BigDecimal amount = BigDecimal.valueOf(150.00);
        String currency = "USD";
        String paymentMethod = "card";
        String idempotencyKey = "idem_key_111";
        String cardToken = "tok_success";

        // Idempotency: no existing payment
        when(paymentRepository.findByMerchantIdAndIdempotencyKey(merchantId, idempotencyKey)).thenReturn(Optional.empty());

        // Step 1: Fraud Engine returns APPROVE
        AnalyzeTransactionResponse fraudResp = AnalyzeTransactionResponse.newBuilder()
                .setIsFlagged(false)
                .setRecommendation("APPROVE")
                .build();
        when(fraudStub.analyzeTransaction(any())).thenReturn(fraudResp);

        // Step 2: Gateway returns AUTHORIZED
        PaymentResponse authResp = PaymentResponse.newBuilder()
                .setStatus("AUTHORIZED")
                .setGatewayReference("ch_gateway_999")
                .build();
        when(authorizationStub.authorizePayment(any())).thenReturn(authResp);

        // Step 3: Ledger records successfully
        RecordTransactionResponse ledgerResp = RecordTransactionResponse.newBuilder()
                .setSuccess(true)
                .build();
        when(ledgerStub.recordTransaction(any())).thenReturn(ledgerResp);

        // Act
        Payment result = sagaOrchestrator.executeSaga(
                merchantId, amount, currency, paymentMethod, idempotencyKey, cardToken, "Product Purchase"
        );

        // Assert
        assertNotNull(result);
        assertEquals("AUTHORIZED", result.getStatus());
        assertEquals("ch_gateway_999", result.getGatewayReference());
        verify(paymentRepository, atLeastOnce()).save(any(Payment.class));
        verify(outboxRepository, times(3)).save(any()); // INITIATED, AUTHORIZED
    }

    @Test
    public void testExecuteSaga_FraudDecline() {
        // Arrange
        String merchantId = "merchant_123";
        BigDecimal amount = BigDecimal.valueOf(150.00);
        String currency = "USD";
        String paymentMethod = "card";
        String idempotencyKey = "idem_key_222";
        String cardToken = "tok_fraud";

        when(paymentRepository.findByMerchantIdAndIdempotencyKey(merchantId, idempotencyKey)).thenReturn(Optional.empty());

        // Step 1: Fraud Engine returns REJECT
        AnalyzeTransactionResponse fraudResp = AnalyzeTransactionResponse.newBuilder()
                .setIsFlagged(true)
                .setRecommendation("REJECT")
                .addReasons("VELOCITY_EXCEEDED")
                .build();
        when(fraudStub.analyzeTransaction(any())).thenReturn(fraudResp);

        // Act
        Payment result = sagaOrchestrator.executeSaga(
                merchantId, amount, currency, paymentMethod, idempotencyKey, cardToken, "Product Purchase"
        );

        // Assert
        assertNotNull(result);
        assertEquals("FAILED", result.getStatus());
        assertTrue(result.getErrorMessage().contains("fraud"));
        verify(authorizationStub, never()).authorizePayment(any());
    }

    @Test
    public void testExecuteSaga_CompensationWorkflow() {
        // Arrange
        String merchantId = "merchant_123";
        BigDecimal amount = BigDecimal.valueOf(150.00);
        String currency = "USD";
        String paymentMethod = "card";
        String idempotencyKey = "idem_key_333";
        String cardToken = "tok_ledger_fails";

        when(paymentRepository.findByMerchantIdAndIdempotencyKey(merchantId, idempotencyKey)).thenReturn(Optional.empty());

        // Step 1: Fraud Engine returns APPROVE
        when(fraudStub.analyzeTransaction(any())).thenReturn(
                AnalyzeTransactionResponse.newBuilder().setRecommendation("APPROVE").build()
        );

        // Step 2: Gateway returns AUTHORIZED
        when(authorizationStub.authorizePayment(any())).thenReturn(
                PaymentResponse.newBuilder().setStatus("AUTHORIZED").setGatewayReference("ch_gateway_333").build()
        );

        // Step 3: Ledger fails
        when(ledgerStub.recordTransaction(any())).thenReturn(
                RecordTransactionResponse.newBuilder().setSuccess(false).setErrorMessage("DB connection failed").build()
        );

        // Act
        Payment result = sagaOrchestrator.executeSaga(
                merchantId, amount, currency, paymentMethod, idempotencyKey, cardToken, "Product Purchase"
        );

        // Assert
        assertNotNull(result);
        assertEquals("FAILED", result.getStatus());
        assertTrue(result.getErrorMessage().contains("Ledger system rejected entry"));
        
        // Verify compensating transaction was invoked at the Gateway
        verify(authorizationStub, times(1)).cancelPayment(any());
    }
}
