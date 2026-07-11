package com.paycore.ledger.infrastructure.grpc;

import com.paycore.ledger.domain.service.LedgerService;
import com.paycore.ledger.grpc.LedgerServiceGrpc;
import com.paycore.ledger.grpc.RecordTransactionRequest;
import com.paycore.ledger.grpc.RecordTransactionResponse;
import com.paycore.ledger.grpc.GetAccountBalanceRequest;
import com.paycore.ledger.grpc.GetAccountBalanceResponse;

import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.server.service.GrpcService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;

@GrpcService
@Slf4j
@RequiredArgsConstructor
public class LedgerGrpcService extends LedgerServiceGrpc.LedgerServiceImplBase {

    private final LedgerService ledgerService;

    @Override
    public void recordTransaction(RecordTransactionRequest request, StreamObserver<RecordTransactionResponse> responseObserver) {
        log.info("gRPC Ledger: RecordTransaction transactionId={}, type={}", request.getTransactionId(), request.getType());
        try {
            boolean success = ledgerService.recordTransaction(
                    request.getMerchantId(),
                    request.getTransactionId(),
                    BigDecimal.valueOf(request.getAmount()),
                    request.getCurrency(),
                    request.getType(),
                    request.getDescription()
            );

            responseObserver.onNext(RecordTransactionResponse.newBuilder()
                    .setTransactionId(request.getTransactionId())
                    .setSuccess(success)
                    .build());
            responseObserver.onCompleted();
        } catch (Exception ex) {
            log.error("Failed to record ledger transaction", ex);
            responseObserver.onNext(RecordTransactionResponse.newBuilder()
                    .setTransactionId(request.getTransactionId())
                    .setSuccess(false)
                    .setErrorMessage(ex.getMessage())
                    .build());
            responseObserver.onCompleted();
        }
    }

    @Override
    public void getAccountBalance(GetAccountBalanceRequest request, StreamObserver<GetAccountBalanceResponse> responseObserver) {
        log.info("gRPC Ledger: GetAccountBalance merchantId={}, accountType={}", request.getMerchantId(), request.getAccountType());
        try {
            BigDecimal balance = ledgerService.getBalance(
                    request.getMerchantId(),
                    request.getAccountType(),
                    request.getCurrency()
            );

            responseObserver.onNext(GetAccountBalanceResponse.newBuilder()
                    .setMerchantId(request.getMerchantId())
                    .setAccountType(request.getAccountType())
                    .setBalance(balance.doubleValue())
                    .setCurrency(request.getCurrency())
                    .build());
            responseObserver.onCompleted();
        } catch (Exception ex) {
            log.error("Failed to get account balance", ex);
            responseObserver.onError(ex);
        }
    }
}
