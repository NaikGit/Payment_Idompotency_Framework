package com.dev.payments.framework.ibmmq;

import com.dev.payments.framework.model.OutboxEntry;
import com.dev.payments.framework.model.OutboxPayload;
import com.dev.payments.framework.model.ProcessedEvent;
import com.dev.payments.framework.repository.OutboxRepository;
import com.dev.payments.framework.repository.ProcessedEventRepository;
import com.dev.payments.framework.service.IdempotencyService;
import com.dev.payments.framework.state.PaymentState;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.jms.Destination;
import jakarta.jms.JMSException;
import jakarta.jms.Message;
import jakarta.jms.TextMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for JmsPaymentConsumer using Mockito (no embedded MQ needed).
 *
 * JMS {@link TextMessage} and {@link Message} are mocked. The tests verify:
 *  - first-time message routes through IdempotencyService.processMessage()
 *  - duplicate message skipped without calling processPayment()
 *  - non-TextMessage consumed gracefully without crashing
 *  - malformed JSON consumed gracefully
 *  - validation rejection skips processPayment()
 *  - processPayment() exception triggers onPermanentFailure callback
 *  - onDuplicateDetected callback fires for duplicate messages
 *  - JMS property extraction is used when subclass reads from headers
 */
@DisplayName("JmsPaymentConsumer")
class JmsPaymentConsumerTest {

    // -------------------------------------------------------------------------
    // Test payload and consumer
    // -------------------------------------------------------------------------

    record PaymentEvent(String transactionId, int amount) {}

    static class TestJmsConsumer extends JmsPaymentConsumer<PaymentEvent> {

        final AtomicInteger processPaymentCallCount = new AtomicInteger(0);
        final List<String> duplicateCallbacks = new ArrayList<>();
        final List<String> permanentFailureCallbacks = new ArrayList<>();
        boolean shouldValidate = true;

        TestJmsConsumer(IdempotencyService idempotencyService,
                        ObjectMapper objectMapper,
                        SimpleMeterRegistry meterRegistry) {
            super(idempotencyService, objectMapper, meterRegistry);
        }

        @Override
        protected String extractIdempotencyKey(Message message, PaymentEvent payload) {
            return payload.transactionId();
        }

        @Override
        protected String getDestination() {
            return "CENTRAL_LEDGER";
        }

        @Override
        public Class<PaymentEvent> getPayloadType() {
            return PaymentEvent.class;
        }

        @Override
        public OutboxPayload processPayment(PaymentEvent event) {
            processPaymentCallCount.incrementAndGet();
            return OutboxPayload.restPost("https://ledger.internal/api/v1/transactions",
                    Map.of("amount", event.amount()));
        }

        @Override
        public boolean validate(PaymentEvent payload) {
            return shouldValidate;
        }

        @Override
        public void onDuplicateDetected(PaymentEvent payload, String idempotencyKey) {
            duplicateCallbacks.add(idempotencyKey);
        }

        @Override
        public void onPermanentFailure(PaymentEvent payload, String idempotencyKey, String lastError) {
            permanentFailureCallbacks.add(idempotencyKey);
        }
    }

    // -------------------------------------------------------------------------
    // Setup
    // -------------------------------------------------------------------------

    private TestJmsConsumer consumer;
    private ProcessedEventRepository processedEventRepository;
    private OutboxRepository outboxRepository;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        processedEventRepository = mock(ProcessedEventRepository.class);
        outboxRepository = mock(OutboxRepository.class);

        when(processedEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(outboxRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        IdempotencyService idempotencyService = new IdempotencyService(
                processedEventRepository, outboxRepository, objectMapper, new SimpleMeterRegistry());
        consumer = new TestJmsConsumer(idempotencyService, objectMapper, new SimpleMeterRegistry());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private TextMessage textMessage(String json) throws JMSException {
        TextMessage msg = mock(TextMessage.class);
        when(msg.getText()).thenReturn(json);
        when(msg.getJMSMessageID()).thenReturn("ID:test-msg-1");
        Destination dest = mock(Destination.class);
        when(dest.toString()).thenReturn("PAYMENT.INBOUND.QUEUE");
        when(msg.getJMSDestination()).thenReturn(dest);
        return msg;
    }

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("given a payment event received for the first time")
    class GivenFirstTimeMessage {

        @Test
        @DisplayName("when onMessage is called, then processPayment is invoked once")
        void whenOnMessage_thenProcessPaymentInvokedOnce() throws Exception {
            // Given
            when(processedEventRepository.existsByIdempotencyKey("txn-jms-001")).thenReturn(false);
            when(processedEventRepository.findByIdempotencyKey("txn-jms-001")).thenReturn(Optional.empty());

            String json = objectMapper.writeValueAsString(new PaymentEvent("txn-jms-001", 100));

            // When
            consumer.onMessage(textMessage(json));

            // Then
            assertThat(consumer.processPaymentCallCount.get())
                    .as("processPayment must be called once for a new message")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("when onMessage is called, then no duplicate callbacks fire")
        void whenOnMessage_thenNoDuplicateCallbacks() throws Exception {
            // Given
            when(processedEventRepository.existsByIdempotencyKey("txn-jms-002")).thenReturn(false);
            when(processedEventRepository.findByIdempotencyKey("txn-jms-002")).thenReturn(Optional.empty());

            String json = objectMapper.writeValueAsString(new PaymentEvent("txn-jms-002", 200));

            // When
            consumer.onMessage(textMessage(json));

            // Then
            assertThat(consumer.duplicateCallbacks).isEmpty();
        }

        @Test
        @DisplayName("when onMessage is called, then IdempotencyService.processMessage is called")
        void whenOnMessage_thenIdempotencyServiceProcessMessageCalled() throws Exception {
            // Given — verify via repository save being called (processMessage calls repo.save internally)
            when(processedEventRepository.existsByIdempotencyKey("txn-jms-003")).thenReturn(false);
            when(processedEventRepository.findByIdempotencyKey("txn-jms-003")).thenReturn(Optional.empty());

            String json = objectMapper.writeValueAsString(new PaymentEvent("txn-jms-003", 300));

            // When
            consumer.onMessage(textMessage(json));

            // Then
            verify(processedEventRepository).save(any(ProcessedEvent.class));
            verify(outboxRepository).save(any(OutboxEntry.class));
        }
    }

    @Nested
    @DisplayName("given a payment event received a second time (duplicate)")
    class GivenDuplicateMessage {

        @Test
        @DisplayName("when onMessage is called for the duplicate, then processPayment is NOT called")
        void whenDuplicate_thenProcessPaymentNotCalled() throws Exception {
            // Given
            when(processedEventRepository.existsByIdempotencyKey("txn-dup-jms-001")).thenReturn(true);

            String json = objectMapper.writeValueAsString(new PaymentEvent("txn-dup-jms-001", 100));

            // When
            consumer.onMessage(textMessage(json));

            // Then
            assertThat(consumer.processPaymentCallCount.get())
                    .as("processPayment must NOT be called for a duplicate message")
                    .isZero();
        }

        @Test
        @DisplayName("when onMessage is called for the duplicate, then onDuplicateDetected callback fires")
        void whenDuplicate_thenOnDuplicateDetectedFires() throws Exception {
            // Given
            when(processedEventRepository.existsByIdempotencyKey("txn-dup-jms-002")).thenReturn(true);

            String json = objectMapper.writeValueAsString(new PaymentEvent("txn-dup-jms-002", 100));

            // When
            consumer.onMessage(textMessage(json));

            // Then
            assertThat(consumer.duplicateCallbacks)
                    .as("onDuplicateDetected must fire for a duplicate")
                    .containsExactly("txn-dup-jms-002");
        }

        @Test
        @DisplayName("when onMessage is called for the duplicate, then no save to repository")
        void whenDuplicate_thenNoRepositorySave() throws Exception {
            // Given
            when(processedEventRepository.existsByIdempotencyKey("txn-dup-jms-003")).thenReturn(true);

            String json = objectMapper.writeValueAsString(new PaymentEvent("txn-dup-jms-003", 100));

            // When
            consumer.onMessage(textMessage(json));

            // Then — no new ProcessedEvent or OutboxEntry should be created
            verify(processedEventRepository, org.mockito.Mockito.never()).save(any());
            verify(outboxRepository, org.mockito.Mockito.never()).save(any());
        }
    }

    @Nested
    @DisplayName("given a non-TextMessage (e.g. BytesMessage)")
    class GivenNonTextMessage {

        @Test
        @DisplayName("when onMessage is called, then consumer does not crash and processPayment is not called")
        void whenNonTextMessage_thenConsumerDoesNotCrash() {
            // Given — a plain JMS Message (not TextMessage)
            Message msg = mock(Message.class);

            // When / Then — must not throw
            consumer.onMessage(msg);

            assertThat(consumer.processPaymentCallCount.get()).isZero();
        }
    }

    @Nested
    @DisplayName("given a TextMessage with malformed JSON body")
    class GivenMalformedJson {

        @Test
        @DisplayName("when onMessage is called, then consumer does not crash and processPayment is not called")
        void whenMalformedJson_thenConsumerDoesNotCrash() throws Exception {
            // Given
            TextMessage msg = mock(TextMessage.class);
            when(msg.getText()).thenReturn("NOT_VALID_JSON{{{");
            when(msg.getJMSMessageID()).thenReturn("ID:bad-json");
            when(msg.getJMSDestination()).thenReturn(null);

            // When / Then — must not throw
            consumer.onMessage(msg);

            assertThat(consumer.processPaymentCallCount.get()).isZero();
        }
    }

    @Nested
    @DisplayName("given a message that fails payload validation")
    class GivenInvalidPayload {

        @Test
        @DisplayName("when onMessage is called, then processPayment is NOT called")
        void whenInvalidPayload_thenProcessPaymentNotCalled() throws Exception {
            // Given
            consumer.shouldValidate = false;
            when(processedEventRepository.existsByIdempotencyKey("txn-inv-001")).thenReturn(false);

            String json = objectMapper.writeValueAsString(new PaymentEvent("txn-inv-001", -1));

            // When
            consumer.onMessage(textMessage(json));

            // Then
            assertThat(consumer.processPaymentCallCount.get())
                    .as("processPayment must not be called for an invalid payload")
                    .isZero();
        }
    }

    @Nested
    @DisplayName("given processPayment throws an exception")
    class GivenProcessPaymentThrows {

        @Test
        @DisplayName("when processPayment throws, then onPermanentFailure fires and consumer does not propagate exception")
        void whenProcessPaymentThrows_thenPermanentFailureCallbackFires() throws Exception {
            // Given — consumer whose processPayment throws
            List<String> permanentFailures = new ArrayList<>();
            IdempotencyService localService = new IdempotencyService(
                    processedEventRepository, outboxRepository, objectMapper, new SimpleMeterRegistry());

            JmsPaymentConsumer<PaymentEvent> throwingConsumer = new JmsPaymentConsumer<>(
                    localService, objectMapper, new SimpleMeterRegistry()) {

                @Override
                protected String extractIdempotencyKey(Message message, PaymentEvent payload) {
                    return payload.transactionId();
                }

                @Override
                protected String getDestination() { return "CENTRAL_LEDGER"; }

                @Override
                public Class<PaymentEvent> getPayloadType() { return PaymentEvent.class; }

                @Override
                public OutboxPayload processPayment(PaymentEvent event) throws Exception {
                    throw new RuntimeException("ledger unavailable");
                }

                @Override
                public void onPermanentFailure(PaymentEvent payload, String idempotencyKey, String lastError) {
                    permanentFailures.add(idempotencyKey);
                }
            };

            when(processedEventRepository.existsByIdempotencyKey("txn-throw-001")).thenReturn(false);
            String json = objectMapper.writeValueAsString(new PaymentEvent("txn-throw-001", 100));

            // When / Then — must not propagate
            throwingConsumer.onMessage(textMessage(json));

            assertThat(permanentFailures).containsExactly("txn-throw-001");
        }
    }

    @Nested
    @DisplayName("given JMSException reading the TextMessage body")
    class GivenJmsExceptionOnRead {

        @Test
        @DisplayName("when JMSException is thrown reading message body, then consumer does not crash")
        void whenJmsExceptionOnRead_thenConsumerDoesNotCrash() throws Exception {
            // Given
            TextMessage msg = mock(TextMessage.class);
            when(msg.getText()).thenThrow(new JMSException("connection dropped"));
            when(msg.getJMSMessageID()).thenReturn("ID:jms-err");

            // When / Then — must not propagate
            consumer.onMessage(msg);

            assertThat(consumer.processPaymentCallCount.get()).isZero();
        }
    }

    @Nested
    @DisplayName("given a subclass that reads the idempotency key from a JMS property")
    class GivenJmsPropertyKey {

        @Test
        @DisplayName("when the JMS property is present, then it is used as the idempotency key")
        void whenJmsPropertyPresent_thenUsedAsIdempotencyKey() throws Exception {
            // Given — consumer that reads key from JMS property
            String[] capturedKey = {null};
            IdempotencyService localService = new IdempotencyService(
                    processedEventRepository, outboxRepository, objectMapper, new SimpleMeterRegistry());

            JmsPaymentConsumer<PaymentEvent> propertyConsumer = new JmsPaymentConsumer<>(
                    localService, objectMapper, new SimpleMeterRegistry()) {

                @Override
                protected String extractIdempotencyKey(Message message, PaymentEvent payload) throws JMSException {
                    return message.getStringProperty("X-Transaction-Id");
                }

                @Override
                protected String getDestination() { return "CENTRAL_LEDGER"; }

                @Override
                public Class<PaymentEvent> getPayloadType() { return PaymentEvent.class; }

                @Override
                public OutboxPayload processPayment(PaymentEvent event) {
                    return OutboxPayload.restPost("https://ledger.internal/api/v1/transactions",
                            Map.of("amount", event.amount()));
                }
            };

            when(processedEventRepository.existsByIdempotencyKey("header-key-001")).thenReturn(false);
            when(processedEventRepository.findByIdempotencyKey("header-key-001")).thenReturn(Optional.empty());

            TextMessage msg = mock(TextMessage.class);
            when(msg.getText()).thenReturn(objectMapper.writeValueAsString(new PaymentEvent("ignored", 100)));
            when(msg.getJMSMessageID()).thenReturn("ID:prop-msg");
            when(msg.getStringProperty("X-Transaction-Id")).thenReturn("header-key-001");
            when(msg.getJMSDestination()).thenReturn(null);

            // When
            propertyConsumer.onMessage(msg);

            // Then — key from JMS property was used; processMessage saved a ProcessedEvent
            verify(processedEventRepository).save(
                    org.mockito.ArgumentMatchers.argThat(e ->
                            "header-key-001".equals(((ProcessedEvent) e).getIdempotencyKey())));
        }
    }
}
