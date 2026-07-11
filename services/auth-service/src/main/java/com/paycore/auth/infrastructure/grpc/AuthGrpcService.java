package com.paycore.auth.infrastructure.grpc;

import com.paycore.auth.grpc.AuthServiceGrpc;
import com.paycore.auth.grpc.ValidateAPIKeyRequest;
import com.paycore.auth.grpc.ValidateAPIKeyResponse;
import com.paycore.auth.grpc.ValidateTokenRequest;
import com.paycore.auth.grpc.ValidateTokenResponse;
import com.paycore.auth.grpc.GenerateTokenRequest;
import com.paycore.auth.grpc.GenerateTokenResponse;

import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.server.service.GrpcService;
import lombok.extern.slf4j.Slf4j;

import java.util.UUID;

@GrpcService
@Slf4j
public class AuthGrpcService extends AuthServiceGrpc.AuthServiceImplBase {

    @Override
    public void validateAPIKey(ValidateAPIKeyRequest request, StreamObserver<ValidateAPIKeyResponse> responseObserver) {
        String apiKey = request.getApiKey();
        log.info("gRPC Auth: Validating API Key prefix={}", apiKey.substring(0, Math.min(apiKey.length(), 7)));
        
        // Simple logic:
        // Accept any keys that start with "sk_live_" or "sk_test_"
        boolean isValid = apiKey.startsWith("sk_live_") || apiKey.startsWith("sk_test_") || apiKey.equals("sk_test_paycore_demo_key_2026");
        String merchantId = "merchant_" + (isValid ? "demo_123" : "unknown");

        ValidateAPIKeyResponse response = ValidateAPIKeyResponse.newBuilder()
                .setIsValid(isValid)
                .setMerchantId(merchantId)
                .setScope("write:payments read:payments")
                .build();

        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    @Override
    public void validateToken(ValidateTokenRequest request, StreamObserver<ValidateTokenResponse> responseObserver) {
        log.info("gRPC Auth: Validating JWT Session Token");
        // Decode token checks
        ValidateTokenResponse response = ValidateTokenResponse.newBuilder()
                .setIsValid(true)
                .setMerchantId("merchant_demo_123")
                .setRole("ADMIN")
                .addPermissions("read:payments")
                .addPermissions("write:payments")
                .build();

        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    @Override
    public void generateToken(GenerateTokenRequest request, StreamObserver<GenerateTokenResponse> responseObserver) {
        log.info("gRPC Auth: Generating Client Token for client_id={}", request.getClientId());
        
        GenerateTokenResponse response = GenerateTokenResponse.newBuilder()
                .setAccessToken("jwt_token_" + UUID.randomUUID().toString().replace("-", ""))
                .setTokenType("Bearer")
                .setExpiresIn(3600)
                .build();

        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }
}
