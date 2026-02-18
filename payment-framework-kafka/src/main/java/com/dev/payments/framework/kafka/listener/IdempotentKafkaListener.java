package com.dev.payments.framework.kafka.listener;

import com.dev.payments.framework.annotation.IdempotentConsumer;
import com.dev.payments.framework.handler.PaymentMessageHandler;
import com.dev.payments.framework.model.OutboxPayload;
import com.dev.payments.framework.service.IdempotencyService;
import com.dev.payments.framework.service.IdempotencyService.ProcessingResult;
import com.dev.payments.framework.util.IdempotencyKeyExtractor;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.listener.AcknowledgingMessageListener;
import org.springframework.kafka.support.Acknowledgment;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Kafka message listener that wraps PaymentMessageHandler with idempotency.
 * 
 * This listener:
 * 1. Receives records from Kafka
 * 2. Extracts the idempotency key using SpEL
 * 3. Checks for duplicates via IdempotencyService
 * 4. Calls the handler if not duplicate
 * 5. Persists state and outbox atomically
 * 6. Commits offset AFTER database commit
 * 
 * Important: Configure Kafka consumer with:
 * - enable.auto.commit=false
 * - AckMode.MANUAL or AckMode.MANUAL_IMMEDIATE
 * 
 * @param <T> The message payload type
 */
public class IdempotentKafkaListener<T> implements AcknowledgingMessageListener<String, String> {
    
    private static final Logger log = LoggerFactory.getLogger(IdempotentKafkaListener.class);
    
    private final PaymentMessageHandler<T> handler;
    private final IdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;
    private final IdempotentConsumer config;
    private final Class<T> payloadType;
    private final String topicName;
    
    // Metrics
    private final Counter messagesReceivedCounter;
    private final Counter messagesProcessedCounter;
    private final Counter duplicatesSkippedCounter;
    private final Counter processingErrorsCounter;
    private final Timer processingTimer;
    
    @SuppressWarnings("unchecked")
    public IdempotentKafkaListener(
            PaymentMessageHandler<T> handler,
            IdempotencyService idempotencyService,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry,
            String topicName) {
        this.handler = handler;
        this.idempotencyService = idempotencyService;
        this.objectMapper = objectMapper;
        this.topicName = topicName;
        
        // Get annotation config
        this.config = handler.getClass().getAnnotation(IdempotentConsumer.class);
        if (config == null) {
            throw new IllegalArgumentException(
                    "Handler must be annotated with @IdempotentConsumer: " + handler.getClass().getName());
        }
        
        // Resolve payload type
        this.payloadType = resolvePayloadType(handler);
        
        // Initialize metrics
        String destination = config.destination();
        
        this.messagesReceivedCounter = Counter.builder("payment.kafka.messages.received")
                .tag("topic", topicName)
                .tag("destination", destination)
                .register(meterRegistry);
        
        this.messagesProcessedCounter = Counter.builder("payment.kafka.messages.processed")
                .tag("topic", topicName)
                .tag("destination", destination)
                .register(meterRegistry);
        
        this.duplicatesSkippedCounter = Counter.builder("payment.kafka.messages.duplicates")
                .tag("topic", topicName)
                .tag("destination", destination)
                .register(meterRegistry);
        
        this.processingErrorsCounter = Counter.builder("payment.kafka.messages.errors")
                .tag("topic", topicName)
                .tag("destination", destination)
                .register(meterRegistry);
        
        this.processingTimer = Timer.builder("payment.kafka.processing.time")
                .tag("topic", topicName)
                .tag("destination", destination)
                .register(meterRegistry);
        
        log.info("Initialized IdempotentKafkaListener for topic: {}, destination: {}, handler: {}",
                topicName, destination, handler.getClass().getSimpleName());
    }
    
    @Override
    public void onMessage(ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
        messagesReceivedCounter.increment();
        
        Timer.Sample timerSample = Timer.start();
        String recordKey = record.key();
        long offset = record.offset();
        int partition = record.partition();
        
        try {
            log.debug("Received record: topic={}, partition={}, offset={}, key={}",
                    topicName, partition, offset, recordKey);
            
            // Step 1: Deserialize payload
            T payload = deserializePayload(record.value());
            
            // Step 2: Validate payload
            if (!handler.validate(payload)) {
                log.warn("Payload validation failed for record: partition={}, offset={}",
                        partition, offset);
                // Acknowledge to prevent reprocessing of invalid messages
                acknowledgment.acknowledge();
                return;
            }
            
            // Step 3: Extract idempotency key
            Map<String, Object> headers = extractHeaders(record);
            String idempotencyKey = IdempotencyKeyExtractor.extractKey(
                    config.idempotencyKey(), payload, headers);
            
            log.debug("Extracted idempotency key: {} for record: partition={}, offset={}",
                    idempotencyKey, partition, offset);
            
            // Step 4: Process with idempotency check
            OutboxPayload outboxPayload = handler.processPayment(payload);
            
            ProcessingResult result = idempotencyService.processMessage(
                    idempotencyKey,
                    config.source().isEmpty() ? topicName : config.source(),
                    config.destination(),
                    outboxPayload);
            
            if (result.isDuplicate()) {
                // Duplicate detected
                duplicatesSkippedCounter.increment();
                handler.onDuplicateDetected(payload, idempotencyKey);
                log.info("Duplicate record skipped: partition={}, offset={}, key={}",
                        partition, offset, idempotencyKey);
            } else {
                // Successfully processed
                messagesProcessedCounter.increment();
                log.info("Record processed successfully: partition={}, offset={}, key={}",
                        partition, offset, idempotencyKey);
            }
            
            // Step 5: Acknowledge AFTER database commit
            acknowledgment.acknowledge();
            
        } catch (IdempotencyKeyExtractor.IdempotencyKeyExtractionException e) {
            processingErrorsCounter.increment();
            log.error("Failed to extract idempotency key from record: partition={}, offset={}",
                    partition, offset, e);
            // Don't acknowledge - will be reprocessed
            throw new RuntimeException("Idempotency key extraction failed", e);
            
        } catch (Exception e) {
            processingErrorsCounter.increment();
            log.error("Error processing record: partition={}, offset={}", partition, offset, e);
            // Don't acknowledge - will be reprocessed
            throw new RuntimeException("Processing failed", e);
            
        } finally {
            timerSample.stop(processingTimer);
        }
    }
    
    /**
     * Deserialize the payload from JSON.
     */
    private T deserializePayload(String value) {
        try {
            return objectMapper.readValue(value, payloadType);
        } catch (Exception e) {
            throw new RuntimeException("Failed to deserialize payload: " + e.getMessage(), e);
        }
    }
    
    /**
     * Extract headers from Kafka record.
     */
    private Map<String, Object> extractHeaders(ConsumerRecord<String, String> record) {
        Map<String, Object> headers = new HashMap<>();
        
        // Kafka record metadata
        headers.put("kafka.topic", record.topic());
        headers.put("kafka.partition", record.partition());
        headers.put("kafka.offset", record.offset());
        headers.put("kafka.timestamp", record.timestamp());
        headers.put("kafka.key", record.key());
        
        // Kafka headers
        if (record.headers() != null) {
            record.headers().forEach(header -> {
                String value = header.value() != null ?
                        new String(header.value(), StandardCharsets.UTF_8) : null;
                headers.put(header.key(), value);
            });
        }
        
        return headers;
    }
    
    /**
     * Resolve the payload type from the handler's generic parameter.
     */
    @SuppressWarnings("unchecked")
    private Class<T> resolvePayloadType(PaymentMessageHandler<T> handler) {
        // Try to get from handler method
        Class<T> fromHandler = handler.getPayloadType();
        if (fromHandler != null) {
            return fromHandler;
        }
        
        // Fall back to reflection
        Type[] interfaces = handler.getClass().getGenericInterfaces();
        for (Type iface : interfaces) {
            if (iface instanceof ParameterizedType) {
                ParameterizedType pt = (ParameterizedType) iface;
                if (pt.getRawType().equals(PaymentMessageHandler.class)) {
                    Type arg = pt.getActualTypeArguments()[0];
                    if (arg instanceof Class) {
                        return (Class<T>) arg;
                    }
                }
            }
        }
        
        throw new IllegalArgumentException(
                "Cannot resolve payload type for handler: " + handler.getClass().getName());
    }
}
