package com.dev.payments.framework.kafka;

import com.dev.payments.framework.handler.PaymentMessageHandler;
import com.dev.payments.framework.model.OutboxPayload;
import com.dev.payments.framework.service.IdempotencyService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.support.Acknowledgment;

/**
 * Abstract base class for idempotent Kafka payment consumers.
 *
 * Teams subclass this, implement the three abstract methods, and annotate
 * their {@code consume()} override with {@code @KafkaListener}. The base class
 * handles all infrastructure concerns:
 * <ul>
 *   <li>Idempotency check — fast pre-check before business logic runs</li>
 *   <li>Payload deserialization</li>
 *   <li>Payload validation via {@link PaymentMessageHandler#validate}</li>
 *   <li>Atomic ProcessedEvent + OutboxEntry creation via {@link IdempotencyService}</li>
 *   <li>Message acknowledgment</li>
 *   <li>Micrometer metrics</li>
 *   <li>Callback hooks: {@link #onDuplicateDetected}, {@link #onPermanentFailure}, {@link #onDeliverySuccess}</li>
 * </ul>
 *
 * Example usage:
 * <pre>{@code
 * @Component
 * @IdempotentConsumer(
 *     idempotencyKey = "#{payload.transactionId}",
 *     destination = "CENTRAL_LEDGER"
 * )
 * public class PaymentEventConsumer extends KafkaPaymentConsumer<PaymentEvent> {
 *
 *     public PaymentEventConsumer(IdempotencyService idempotencyService,
 *                                 ObjectMapper objectMapper,
 *                                 MeterRegistry meterRegistry) {
 *         super(idempotencyService, objectMapper, meterRegistry);
 *     }
 *
 *     @Override
 *     @KafkaListener(topics = "payments.inbound", groupId = "payment-processor")
 *     public void consume(ConsumerRecord<String, String> record, Acknowledgment ack) {
 *         super.consume(record, ack);
 *     }
 *
 *     @Override
 *     protected String extractIdempotencyKey(ConsumerRecord<String, String> record, PaymentEvent payload) {
 *         return payload.getTransactionId();
 *     }
 *
 *     @Override
 *     protected String getDestination() {
 *         return "CENTRAL_LEDGER";
 *     }
 *
 *     @Override
 *     public OutboxPayload processPayment(PaymentEvent event) {
 *         return OutboxPayload.restPost("https://ledger.internal/api/v1/transactions", event);
 *     }
 *
 *     @Override
 *     public Class<PaymentEvent> getPayloadType() {
 *         return PaymentEvent.class;
 *     }
 * }
 * }</pre>
 *
 * @param <T> the deserialized Kafka message payload type
 */
public abstract class KafkaPaymentConsumer<T> implements PaymentMessageHandler<T> {

    private static final Logger log = LoggerFactory.getLogger(KafkaPaymentConsumer.class);

    private final IdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;

    private final Counter messagesReceivedCounter;
    private final Counter duplicatesSkippedCounter;
    private final Counter processingFailuresCounter;
    private final Counter validationRejectionsCounter;
    private final Timer consumeTimer;

    protected KafkaPaymentConsumer(IdempotencyService idempotencyService,
                                   ObjectMapper objectMapper,
                                   MeterRegistry meterRegistry) {
        this.idempotencyService = idempotencyService;
        this.objectMapper = objectMapper;

        this.messagesReceivedCounter = Counter.builder("payment.kafka.messages.received")
                .description("Total Kafka messages received")
                .register(meterRegistry);
        this.duplicatesSkippedCounter = Counter.builder("payment.kafka.duplicates.skipped")
                .description("Kafka messages skipped because idempotency key was already seen")
                .register(meterRegistry);
        this.processingFailuresCounter = Counter.builder("payment.kafka.processing.failures")
                .description("Kafka messages that failed processing")
                .register(meterRegistry);
        this.validationRejectionsCounter = Counter.builder("payment.kafka.validation.rejections")
                .description("Kafka messages rejected by payload validation")
                .register(meterRegistry);
        this.consumeTimer = Timer.builder("payment.kafka.consume.time")
                .description("Time to process a Kafka message end-to-end")
                .register(meterRegistry);
    }

    /**
     * Main entry point. Annotate your override with {@code @KafkaListener} and call {@code super.consume()}.
     *
     * @param record the raw Kafka record
     * @param ack    acknowledgment handle — always acknowledged by this method (success or duplicate)
     */
    public void consume(ConsumerRecord<String, String> record, Acknowledgment ack) {
        consumeTimer.record(() -> doConsume(record, ack));
    }

    private void doConsume(ConsumerRecord<String, String> record, Acknowledgment ack) {
        messagesReceivedCounter.increment();
        String source = record.topic();

        T payload;
        try {
            payload = objectMapper.readValue(record.value(), getPayloadType());
        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize Kafka message from topic={} partition={} offset={}",
                    record.topic(), record.partition(), record.offset(), e);
            processingFailuresCounter.increment();
            ack.acknowledge();
            return;
        }

        String idempotencyKey = extractIdempotencyKey(record, payload);

        // Fast pre-check: avoids running business logic for already-seen messages.
        // The definitive duplicate check is inside idempotencyService.processMessage(),
        // which handles the race condition where two identical messages arrive simultaneously.
        if (idempotencyService.isDuplicate(idempotencyKey)) {
            log.info("Duplicate Kafka message skipped: key={} topic={}", idempotencyKey, source);
            duplicatesSkippedCounter.increment();
            onDuplicateDetected(payload, idempotencyKey);
            ack.acknowledge();
            return;
        }

        if (!validate(payload)) {
            log.warn("Kafka message rejected by validation: key={} topic={}", idempotencyKey, source);
            validationRejectionsCounter.increment();
            ack.acknowledge();
            return;
        }

        OutboxPayload outboxPayload;
        try {
            outboxPayload = processPayment(payload);
        } catch (Exception e) {
            // Broad catch is intentional: processPayment() is team-implemented code
            // and may throw any unchecked exception. We must always acknowledge to avoid
            // an infinite Kafka redelivery loop on non-transient failures.
            log.error("Handler processPayment threw exception: key={} topic={}", idempotencyKey, source, e);
            processingFailuresCounter.increment();
            onPermanentFailure(payload, idempotencyKey, e.getMessage());
            ack.acknowledge();
            return;
        }

        IdempotencyService.ProcessingResult result =
                idempotencyService.processMessage(idempotencyKey, source, getDestination(), outboxPayload);

        if (result.isDuplicate()) {
            // Lost the race — another thread processed the same key between our pre-check and processMessage()
            log.info("Late-arriving duplicate resolved by processMessage: key={}", idempotencyKey);
            duplicatesSkippedCounter.increment();
            onDuplicateDetected(payload, idempotencyKey);
        } else {
            log.info("Kafka message processed: key={} outboxEntry={}", idempotencyKey,
                    result.getOutboxEntry().getId());
            onDeliverySuccess(payload, idempotencyKey);
        }

        ack.acknowledge();
    }

    // -------------------------------------------------------------------------
    // Abstract methods — subclasses must implement these
    // -------------------------------------------------------------------------

    /**
     * Extract the idempotency key from the Kafka record and its deserialized payload.
     *
     * <p>Prefer extracting from the payload (e.g. {@code payload.getTransactionId()}) rather than
     * from the Kafka message key, which may not be globally unique across partitions.
     *
     * @param record  the raw Kafka record
     * @param payload the deserialized payload
     * @return the idempotency key — must be globally unique per business event
     */
    protected abstract String extractIdempotencyKey(ConsumerRecord<String, String> record, T payload);

    /**
     * The destination system identifier for outbox routing and metrics.
     * Example: {@code "CENTRAL_LEDGER"}, {@code "CORE_BANKING"}.
     */
    protected abstract String getDestination();

    /**
     * Return the {@link Class} of the payload type {@code T}.
     * Used by Jackson to deserialize the Kafka message value.
     *
     * <p>Must be overridden — the default in {@link PaymentMessageHandler} returns {@code null}.
     */
    @Override
    public abstract Class<T> getPayloadType();
}
