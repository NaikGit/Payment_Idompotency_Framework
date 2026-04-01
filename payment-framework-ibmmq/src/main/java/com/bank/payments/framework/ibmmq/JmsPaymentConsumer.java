package com.dev.payments.framework.ibmmq;

import com.dev.payments.framework.handler.PaymentMessageHandler;
import com.dev.payments.framework.model.OutboxPayload;
import com.dev.payments.framework.service.IdempotencyService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.jms.JMSException;
import jakarta.jms.Message;
import jakarta.jms.MessageListener;
import jakarta.jms.TextMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Abstract base class for idempotent IBM MQ payment consumers.
 *
 * Teams subclass this, implement the three abstract methods, and register the
 * subclass as a {@code @JmsListener} or as a {@code MessageListener} on a
 * {@code DefaultMessageListenerContainer}. The base class handles all
 * infrastructure concerns:
 * <ul>
 *   <li>JMS {@link TextMessage} unwrapping</li>
 *   <li>JSON deserialization to type {@code T}</li>
 *   <li>Idempotency pre-check before business logic runs</li>
 *   <li>Payload validation via {@link PaymentMessageHandler#validate}</li>
 *   <li>Atomic {@link IdempotencyService#processMessage} (ProcessedEvent + OutboxEntry)</li>
 *   <li>Micrometer metrics</li>
 *   <li>Callback hooks: onDuplicateDetected, onPermanentFailure, onDeliverySuccess</li>
 * </ul>
 *
 * IBM MQ's client-acknowledge mode (or transacted sessions managed by the listener container)
 * controls message acknowledgment externally — this class does not call {@code message.acknowledge()}.
 * The listener container will only acknowledge / commit after this method returns normally.
 * If an unchecked exception propagates out of {@link #onMessage}, the container rolls back
 * and MQ redelivers the message.
 *
 * <p>This class deliberately lets deserialization errors and validation rejections return
 * <em>without</em> throwing so the container considers them handled (poison-message avoidance).
 * Processing failures from {@link #processPayment} also return normally after calling
 * {@link #onPermanentFailure}. Only truly unexpected runtime failures should propagate.
 *
 * Example usage:
 * <pre>{@code
 * @Component
 * public class PaymentEventMqConsumer extends JmsPaymentConsumer<PaymentEvent> {
 *
 *     public PaymentEventMqConsumer(IdempotencyService idempotencyService,
 *                                   ObjectMapper objectMapper,
 *                                   MeterRegistry meterRegistry) {
 *         super(idempotencyService, objectMapper, meterRegistry);
 *     }
 *
 *     @JmsListener(destination = "PAYMENT.INBOUND.QUEUE",
 *                  containerFactory = "jmsListenerContainerFactory")
 *     @Override
 *     public void onMessage(Message message) {
 *         super.onMessage(message);
 *     }
 *
 *     @Override
 *     protected String extractIdempotencyKey(Message jmsMessage, PaymentEvent payload) throws JMSException {
 *         // Prefer a business key on the payload
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
 * @param <T> the deserialized JMS message payload type
 */
public abstract class JmsPaymentConsumer<T> implements PaymentMessageHandler<T>, MessageListener {

    private static final Logger log = LoggerFactory.getLogger(JmsPaymentConsumer.class);

    private final IdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;

    private final Counter messagesReceivedCounter;
    private final Counter duplicatesSkippedCounter;
    private final Counter processingFailuresCounter;
    private final Counter validationRejectionsCounter;
    private final Counter nonTextMessageCounter;
    private final Timer consumeTimer;

    protected JmsPaymentConsumer(IdempotencyService idempotencyService,
                                 ObjectMapper objectMapper,
                                 MeterRegistry meterRegistry) {
        this.idempotencyService = idempotencyService;
        this.objectMapper = objectMapper;

        this.messagesReceivedCounter = Counter.builder("payment.ibmmq.messages.received")
                .description("Total IBM MQ messages received")
                .register(meterRegistry);
        this.duplicatesSkippedCounter = Counter.builder("payment.ibmmq.duplicates.skipped")
                .description("IBM MQ messages skipped because idempotency key was already seen")
                .register(meterRegistry);
        this.processingFailuresCounter = Counter.builder("payment.ibmmq.processing.failures")
                .description("IBM MQ messages that failed processing")
                .register(meterRegistry);
        this.validationRejectionsCounter = Counter.builder("payment.ibmmq.validation.rejections")
                .description("IBM MQ messages rejected by payload validation")
                .register(meterRegistry);
        this.nonTextMessageCounter = Counter.builder("payment.ibmmq.non.text.messages")
                .description("IBM MQ messages that were not TextMessage (unexpected type)")
                .register(meterRegistry);
        this.consumeTimer = Timer.builder("payment.ibmmq.consume.time")
                .description("Time to process an IBM MQ message end-to-end")
                .register(meterRegistry);
    }

    /**
     * JMS {@link MessageListener} entry point. Annotate your override with {@code @JmsListener}.
     *
     * <p>This method handles only {@link TextMessage}. Non-text messages are counted and
     * returned without processing — the container considers them consumed, preventing
     * poison-message loops on unexpected message types.
     */
    @Override
    public void onMessage(Message message) {
        consumeTimer.record(() -> doProcess(message));
    }

    private void doProcess(Message message) {
        messagesReceivedCounter.increment();

        if (!(message instanceof TextMessage textMessage)) {
            nonTextMessageCounter.increment();
            log.warn("Received non-TextMessage from IBM MQ: type={}. Message will be consumed without processing.",
                    message.getClass().getSimpleName());
            return;
        }

        String body;
        String jmsMessageId;
        try {
            body = textMessage.getText();
            jmsMessageId = textMessage.getJMSMessageID();
        } catch (JMSException e) {
            log.error("Failed to read TextMessage body from IBM MQ", e);
            processingFailuresCounter.increment();
            return;
        }

        T payload;
        try {
            payload = objectMapper.readValue(body, getPayloadType());
        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize IBM MQ message: jmsMessageId={}", jmsMessageId, e);
            processingFailuresCounter.increment();
            return;
        }

        String idempotencyKey;
        try {
            idempotencyKey = extractIdempotencyKey(message, payload);
        } catch (JMSException e) {
            log.error("Failed to extract idempotency key from IBM MQ message: jmsMessageId={}", jmsMessageId, e);
            processingFailuresCounter.increment();
            return;
        }

        // Fast pre-check: avoids running business logic for already-seen messages.
        // The race condition (two identical messages arriving simultaneously) is handled
        // by the definitive check inside idempotencyService.processMessage().
        if (idempotencyService.isDuplicate(idempotencyKey)) {
            log.info("Duplicate IBM MQ message skipped: key={} jmsMessageId={}", idempotencyKey, jmsMessageId);
            duplicatesSkippedCounter.increment();
            onDuplicateDetected(payload, idempotencyKey);
            return;
        }

        if (!validate(payload)) {
            log.warn("IBM MQ message rejected by validation: key={} jmsMessageId={}", idempotencyKey, jmsMessageId);
            validationRejectionsCounter.increment();
            return;
        }

        OutboxPayload outboxPayload;
        try {
            outboxPayload = processPayment(payload);
        } catch (Exception e) {
            // Broad catch is intentional: processPayment() is team-implemented code
            // and may throw any unchecked exception. Return normally so the container
            // considers the message consumed (poison-message avoidance).
            log.error("Handler processPayment threw exception: key={} jmsMessageId={}", idempotencyKey, jmsMessageId, e);
            processingFailuresCounter.increment();
            onPermanentFailure(payload, idempotencyKey, e.getMessage());
            return;
        }

        String source = resolveSource(message, jmsMessageId);
        IdempotencyService.ProcessingResult result =
                idempotencyService.processMessage(idempotencyKey, source, getDestination(), outboxPayload);

        if (result.isDuplicate()) {
            // Lost the race — another thread processed the same key between pre-check and processMessage()
            log.info("Late-arriving duplicate resolved by processMessage: key={}", idempotencyKey);
            duplicatesSkippedCounter.increment();
            onDuplicateDetected(payload, idempotencyKey);
        } else {
            log.info("IBM MQ message processed: key={} outboxEntry={}", idempotencyKey,
                    result.getOutboxEntry().getId());
            onDeliverySuccess(payload, idempotencyKey);
        }
    }

    private String resolveSource(Message message, String fallback) {
        try {
            return message.getJMSDestination() != null
                    ? message.getJMSDestination().toString()
                    : fallback;
        } catch (JMSException e) {
            return fallback;
        }
    }

    // -------------------------------------------------------------------------
    // Abstract methods — subclasses must implement these
    // -------------------------------------------------------------------------

    /**
     * Extract the idempotency key from the JMS message and its deserialized payload.
     *
     * <p>Prefer extracting from the payload (e.g. {@code payload.getTransactionId()}) for
     * portability. JMS properties ({@code message.getStringProperty(...)}) are also available
     * if the upstream system sets them.
     *
     * @param message the raw JMS message (use for header/property access if needed)
     * @param payload the deserialized payload
     * @return the idempotency key — must be globally unique per business event
     * @throws JMSException if reading JMS properties fails
     */
    protected abstract String extractIdempotencyKey(Message message, T payload) throws JMSException;

    /**
     * The destination system identifier for outbox routing and metrics.
     * Example: {@code "CENTRAL_LEDGER"}, {@code "CORE_BANKING"}.
     */
    protected abstract String getDestination();

    /**
     * Return the {@link Class} of the payload type {@code T}.
     * Used by Jackson to deserialize the JMS {@link TextMessage} body.
     */
    @Override
    public abstract Class<T> getPayloadType();
}
