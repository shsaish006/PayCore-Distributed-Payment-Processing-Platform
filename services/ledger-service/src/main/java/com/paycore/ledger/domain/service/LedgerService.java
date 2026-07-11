package com.paycore.ledger.domain.service;

import com.paycore.ledger.domain.model.*;
import com.paycore.ledger.domain.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class LedgerService {

    private final AccountRepository accountRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final JournalEntryRepository journalEntryRepository;
    private final InboxRepository inboxRepository;

    @Transactional
    public boolean recordTransaction(
            String merchantId,
            String transactionId,
            BigDecimal amount,
            String currency,
            String type,
            String description
    ) {
        // 1. Inbox Deduplication Check
        String inboxKey = "ledger:" + transactionId + ":" + type;
        if (inboxRepository.existsById(inboxKey)) {
            log.info("Duplicate ledger write requested for transactionId={}, type={}. Skipping.", transactionId, type);
            return true;
        }

        log.info("Processing double-entry bookkeeping for transactionId={}, amount={}, type={}", transactionId, amount, type);

        // 2. Fetch or Initialize double-entry Accounts
        // For PayCore Cash Assets (Receivables from bank/processor)
        Account assetsAccount = getOrCreateAccount("SYSTEM_PAYCORE", "ASSET", currency);
        // For Merchant balance payable (Liabilities of PayCore to payout the merchant)
        Account liabilitiesAccount = getOrCreateAccount(merchantId, "LIABILITY", currency);

        // 3. Create parent Ledger Entry
        LedgerEntry ledgerEntry = LedgerEntry.builder()
                .id("ent_" + UUID.randomUUID().toString().replace("-", ""))
                .transactionId(transactionId)
                .type(type)
                .description(description)
                .build();
        ledgerEntryRepository.save(ledgerEntry);

        // 4. Determine Debit vs Credit lines
        JournalEntry debit;
        JournalEntry credit;

        if ("PAYMENT".equals(type) || "CAPTURE".equals(type)) {
            // Payment: Debit Assets (receivables up), Credit Liabilities (payable to merchant up)
            assetsAccount.setBalance(assetsAccount.getBalance().add(amount));
            liabilitiesAccount.setBalance(liabilitiesAccount.getBalance().add(amount));

            debit = JournalEntry.builder()
                    .id("je_" + UUID.randomUUID().toString().replace("-", ""))
                    .ledgerEntryId(ledgerEntry.getId())
                    .accountId(assetsAccount.getId())
                    .entryType("DEBIT")
                    .amount(amount)
                    .build();

            credit = JournalEntry.builder()
                    .id("je_" + UUID.randomUUID().toString().replace("-", ""))
                    .ledgerEntryId(ledgerEntry.getId())
                    .accountId(liabilitiesAccount.getId())
                    .entryType("CREDIT")
                    .amount(amount)
                    .build();
        } else if ("REFUND".equals(type)) {
            // Refund (reversal): Debit Liabilities (payable to merchant down), Credit Assets (receivables down)
            assetsAccount.setBalance(assetsAccount.getBalance().subtract(amount));
            liabilitiesAccount.setBalance(liabilitiesAccount.getBalance().subtract(amount));

            debit = JournalEntry.builder()
                    .id("je_" + UUID.randomUUID().toString().replace("-", ""))
                    .ledgerEntryId(ledgerEntry.getId())
                    .accountId(liabilitiesAccount.getId())
                    .entryType("DEBIT")
                    .amount(amount)
                    .build();

            credit = JournalEntry.builder()
                    .id("je_" + UUID.randomUUID().toString().replace("-", ""))
                    .ledgerEntryId(ledgerEntry.getId())
                    .accountId(assetsAccount.getId())
                    .entryType("CREDIT")
                    .amount(amount)
                    .build();
        } else {
            throw new IllegalArgumentException("Unsupported ledger transaction type: " + type);
        }

        // 5. Save updated accounts (Optimistic Locking will check version here)
        accountRepository.save(assetsAccount);
        accountRepository.save(liabilitiesAccount);

        // 6. Save Journal Entries
        journalEntryRepository.save(debit);
        journalEntryRepository.save(credit);

        // 7. Write to Inbox table to prevent reprocessing
        InboxMessage inbox = InboxMessage.builder()
                .id(inboxKey)
                .consumerGroup("ledger-service")
                .processedAt(OffsetDateTime.now())
                .build();
        inboxRepository.save(inbox);

        log.info("Ledger entry committed successfully. Entries: DEBIT Account={}, CREDIT Account={}", 
                debit.getAccountId(), credit.getAccountId());
        return true;
    }

    private Account getOrCreateAccount(String merchantId, String type, String currency) {
        return accountRepository.findByMerchantIdAndTypeAndCurrency(merchantId, type, currency)
                .orElseGet(() -> {
                    Account acc = Account.builder()
                            .id("acc_" + UUID.randomUUID().toString().replace("-", ""))
                            .merchantId(merchantId)
                            .type(type)
                            .balance(BigDecimal.ZERO)
                            .currency(currency)
                            .build();
                    try {
                        return accountRepository.saveAndFlush(acc);
                    } catch (Exception e) {
                        // Handle race condition where account was created in another thread
                        return accountRepository.findByMerchantIdAndTypeAndCurrency(merchantId, type, currency)
                                .orElseThrow(() -> new RuntimeException("Failed to initialize account: " + merchantId, e));
                    }
                });
    }

    public BigDecimal getBalance(String merchantId, String type, String currency) {
        return accountRepository.findByMerchantIdAndTypeAndCurrency(merchantId, type, currency)
                .map(Account::getBalance)
                .orElse(BigDecimal.ZERO);
    }
}
