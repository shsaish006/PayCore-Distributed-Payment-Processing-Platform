package com.paycore.authorization.infrastructure.grpc;

import com.paycore.payment.grpc.PaymentServiceGrpc;
import com.paycore.payment.grpc.AuthorizePaymentRequest;
import com.paycore.payment.grpc.CancelPaymentRequest;
import com.paycore.payment.grpc.PaymentResponse;

import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.server.service.GrpcService;
import lombok.extern.slf4j.Slf4j;

import java.time.OffsetDateTime;
import java.util.UUID;

@GrpcService
@Slf4j
public class AuthorizationGrpcService extends PaymentServiceGrpc.PaymentServiceImplBase {

    @Override
    public void authorizePayment(AuthorizePaymentRequest request, StreamObserver<PaymentResponse> responseObserver) {
        log.info("gRPC Gateway Auth: Authorizing paymentId={}, amount={}", request.getPaymentId(), request.getAmount());
        
        // Simple mock rules:
        // 1. If card token starts with "tok_decline" -> DECLINED
        // 2. If amount is greater than 10,000 -> DECLINED (Limit Exceeded)
        // 3. Otherwise -> APPROVED (AUTHORIZED)
        boolean approved = true;
        String errorMessage = "";

        if (request.getCardToken().startsWith("tok_decline")) {
            approved = false;
            errorMessage = "Card declined: Insufficient funds (Mock Gateway)";
        } else if (request.getAmount() >= 10000.0) {
            approved = false;
            errorMessage = "Card declined: Transaction limit exceeded (Mock Gateway)";
        }

        PaymentResponse.Builder responseBuilder = PaymentResponse.newBuilder()
                .setId(request.getPaymentId())
                .setMerchantId(request.getMerchantId())
                .setAmount(request.getAmount())
                .setCreatedAt(OffsetDateTime.now().toString())
                .setUpdatedAt(OffsetDateTime.now().toString());

        if (approved) {
            log.info("gRPC Gateway Auth: Payment approved for paymentId={}", request.getPaymentId());
            responseBuilder
                    .setStatus("AUTHORIZED")
                    .setGatewayReference("ch_gateway_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        } else {
            log.warn("gRPC Gateway Auth: Payment declined for paymentId={}, reason={}", request.getPaymentId(), errorMessage);
            responseBuilder
                    .setStatus("FAILED")
                    .setErrorMessage(errorMessage);
        }

        responseObserver.onNext(responseBuilder.build());
        responseObserver.onCompleted();
    }

    @Override
    public void cancelPayment(CancelPaymentRequest request, StreamObserver<PaymentResponse> responseObserver) {
        log.info("gRPC Gateway Auth: Voiding/Cancelling transaction paymentId={}, reason={}", request.getPaymentId(), request.getReason());
        
        PaymentResponse response = PaymentResponse.newBuilder()
                .setId(request.getPaymentId())
                .setStatus("CANCELLED")
                .setGatewayReference("ch_void_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16))
                .setUpdatedAt(OffsetDateTime.now().toString())
                .build();

        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }
}
