package com.paycore.payment.domain.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

@Component
@Slf4j
@RequiredArgsConstructor
public class OutboxPublisher {

    private final OutboxRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Scheduled(fixedDelay = 500) // Polls every 500ms
    @Transactional
    public void publishPendingEvents() {
        List<OutboxEvent> pendingEvents = outboxRepository.findByStatusOrderByCreatedAtAsc("PENDING");
        if (pendingEvents.isEmpty()) {
            return;
        }

        log.debug("Found {} pending outbox events to publish.", pendingEvents.size());

        for (OutboxEvent event : pendingEvents) {
            try {
                String kafkaTopic = mapEventToTopic(event.getEventType());
                
                // Publish to Kafka using aggregate_id as the message key to preserve message ordering
                kafkaTemplate.send(kafkaTopic, event.getAggregateId(), event.getPayload())
                        .whenComplete((result, ex) -> {
                            if (ex != null) {
                                log.error("Failed to publish event {} to Kafka topic {}", event.getId(), kafkaTopic, ex);
                                updateEventStatus(event.getId(), "FAILED");
                            } else {
                                log.info("Successfully published outbox event {} to topic {}", event.getId(), kafkaTopic);
                                updateEventStatus(event.getId(), "PROCESSED");
                            }
                        });
            } catch (Exception e) {
                log.error("Error preparing outbox event {} for publication", event.getId(), e);
                event.setStatus("FAILED");
                outboxRepository.save(event);
            }
        }
    }

    private void updateEventStatus(String eventId, String status) {
        // Run update in separate thread or simple repository call
        outboxRepository.findById(eventId).ifPresent(event -> {
            event.setStatus(status);
            event.setProcessedAt(OffsetDateTime.now());
            outboxRepository.save(event);
        });
    }

    private String mapEventToTopic(String eventType) {
        return switch (eventType) {
            case "PAYMENT_INITIATED" -> "payment.initiated";
            case "PAYMENT_AUTHORIZED" -> "payment.authorized";
            case "PAYMENT_CAPTURED" -> "payment.captured";
            case "PAYMENT_REFUNDED" -> "payment.refunded";
            case "PAYMENT_FAILED" -> "payment.failed";
            default -> "payment.general";
        };
    }
}
