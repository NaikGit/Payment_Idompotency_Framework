package com.dev.payments.framework.ibmmq.listener;

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
import jakarta.jms.JMSException;
import jakarta.jms.Message;
import jakarta.jms.Session;
import jakarta.jms.TextMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jms.listener.SessionAwareMessageListener;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;

/**
 * IBM MQ message listener that wraps PaymentMessageHandler with idempotency.
 * 
 * This listener:
 * 1. Receives messages from IBM MQ
 * 2. Extracts the idempotency key using SpEL
 * 3. Checks for duplicates via IdempotencyService
 * 4. Calls the handler if not duplicate
 * 5. Persists state and outbox atomically
 * 6. Acknowledges the message AFTER database commit
 * 
 * Thread Safety: Thread-safe; designed for concurrent message processing.
 * 
 * @param <T> The message payload type
 */
public class IdempotentMqMessageListener<T> implements SessionAwareMessageListener<Message> {
    
    private static final Logger log = LoggerFactory.getLogger(IdempotentMqMessageListener.class);
    
    private final PaymentMessageHandler<T> handler;
    private final IdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;
    private final IdempotentConsumer config;
    private final Class<T> payloadType;
    private final String queueName;
    
    // Metrics
    private final Counter messagesReceivedCounter;
    private final Counter messagesProcessedCounter;
    private final Counter duplicatesSkippedCounter;
    private final Counter processingErrorsCounter;
    private final Timer processingTimer;
    
    @SuppressWarnings("unchecked")
    public IdempotentMqMessageListener(
            PaymentMessageHandler<T> handler,
            IdempotencyService idempotencyService,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry,
            String queueName) {
        this.handler = handler;
        this.idempotencyService = idempotencyService;
        this.objectMapper = objectMapper;
        this.queueName = queueName;
        
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
        
        this.messagesReceivedCounter = Counter.builder("payment.mq.messages.received")
                .tag("queue", queueName)
                .tag("destination", destination)
                .register(meterRegistry);
        
        this.messagesProcessedCounter = Counter.builder("payment.mq.messages.processed")
                .tag("queue", queueName)
                .tag("destination", destination)
                .register(meterRegistry);
        
        this.duplicatesSkippedCounter = Counter.builder("payment.mq.messages.duplicates")
                .tag("queue", queueName)
                .tag("destination", destination)
                .register(meterRegistry);
        
        this.processingErrorsCounter = Counter.builder("payment.mq.messages.errors")
                .tag("queue", queueName)
                .tag("destination", destination)
                .register(meterRegistry);
        
        this.processingTimer = Timer.builder("payment.mq.processing.time")
                .tag("queue", queueName)
                .tag("destination", destination)
                .register(meterRegistry);
        
        log.info("Initialized IdempotentMqMessageListener for queue: {}, destination: {}, handler: {}",
                queueName, destination, handler.getClass().getSimpleName());
    }
    
    @Override
    public void onMessage(Message message, Session session) throws JMSException {
        messagesReceivedCounter.increment();
        
        Timer.Sample timerSample = Timer.start();
        String messageId = message.getJMSMessageID();
        
        try {
            log.debug("Received message: {} from queue: {}", messageId, queueName);
            
            // Step 1: Extract payload
            String payloadText = extractPayload(message);
            T payload = deserializePayload(payloadText);
            
            // Step 2: Validate payload
            if (!handler.validate(payload)) {
                log.warn("Payload validation failed for message: {}", messageId);
                // Acknowledge to prevent redelivery of invalid messages
                message.acknowledge();
                return;
            }
            
            // Step 3: Extract idempotency key
            Map<String, Object> headers = extractHeaders(message);
            String idempotencyKey = IdempotencyKeyExtractor.extractKey(
                    config.idempotencyKey(), payload, headers);
            
            log.debug("Extracted idempotency key: {} for message: {}", idempotencyKey, messageId);
            
            // Step 4: Process with idempotency check
            OutboxPayload outboxPayload = handler.processPayment(payload);
            
            ProcessingResult result = idempotencyService.processMessage(
                    idempotencyKey,
                    config.source().isEmpty() ? queueName : config.source(),
                    config.destination(),
                    outboxPayload);
            
            if (result.isDuplicate()) {
                // Duplicate detected
                duplicatesSkippedCounter.increment();
                handler.onDuplicateDetected(payload, idempotencyKey);
                log.info("Duplicate message skipped: {} (key: {})", messageId, idempotencyKey);
            } else {
                // Successfully processed
                messagesProcessedCounter.increment();
                log.info("Message processed successfully: {} (key: {})", messageId, idempotencyKey);
            }
            
            // Step 5: Acknowledge AFTER database commit
            // This is critical - if we crash here, message will be redelivered
            // but our idempotency check will catch it
            message.acknowledge();
            
        } catch (IdempotencyKeyExtractor.IdempotencyKeyExtractionException e) {
            processingErrorsCounter.increment();
            log.error("Failed to extract idempotency key from message: {}", messageId, e);
            // Don't acknowledge - let it be redelivered after fix
            throw new JMSException("Idempotency key extraction failed: " + e.getMessage());
            
        } catch (Exception e) {
            processingErrorsCounter.increment();
            log.error("Error processing message: {}", messageId, e);
            // Don't acknowledge - will be redelivered
            throw new JMSException("Processing failed: " + e.getMessage());
            
        } finally {
            timerSample.stop(processingTimer);
        }
    }
    
    /**
     * Extract the text payload from a JMS message.
     */
    private String extractPayload(Message message) throws JMSException {
        if (message instanceof TextMessage) {
            return ((TextMessage) message).getText();
        } else {
            throw new JMSException("Unsupported message type: " + message.getClass().getName() +
                    ". Only TextMessage is supported.");
        }
    }
    
    /**
     * Deserialize the payload from JSON.
     */
    private T deserializePayload(String payloadText) {
        try {
            return objectMapper.readValue(payloadText, payloadType);
        } catch (Exception e) {
            throw new RuntimeException("Failed to deserialize payload: " + e.getMessage(), e);
        }
    }
    
    /**
     * Extract headers from JMS message.
     */
    private Map<String, Object> extractHeaders(Message message) throws JMSException {
        Map<String, Object> headers = new HashMap<>();
        
        // Standard JMS headers
        headers.put("JMSMessageID", message.getJMSMessageID());
        headers.put("JMSCorrelationID", message.getJMSCorrelationID());
        headers.put("JMSTimestamp", message.getJMSTimestamp());
        headers.put("JMSType", message.getJMSType());
        
        // Custom properties
        var propertyNames = message.getPropertyNames();
        while (propertyNames.hasMoreElements()) {
            String name = (String) propertyNames.nextElement();
            headers.put(name, message.getObjectProperty(name));
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
                "Cannot resolve payload type for handler: " + handler.getClass().getName() +
                ". Override getPayloadType() or use a concrete type parameter.");
    }
}
