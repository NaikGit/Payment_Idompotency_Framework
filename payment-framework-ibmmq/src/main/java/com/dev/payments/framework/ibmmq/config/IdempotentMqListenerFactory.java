package com.dev.payments.framework.ibmmq.config;

import com.dev.payments.framework.handler.PaymentMessageHandler;
import com.dev.payments.framework.ibmmq.listener.IdempotentMqMessageListener;
import com.dev.payments.framework.service.IdempotencyService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.jms.ConnectionFactory;
import jakarta.jms.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jms.listener.DefaultMessageListenerContainer;
import org.springframework.stereotype.Component;

/**
 * Factory for creating idempotent MQ message listener containers.
 * 
 * Usage:
 * <pre>
 * {@code
 * @Bean
 * public DefaultMessageListenerContainer paymentListener(
 *         IdempotentMqListenerFactory factory,
 *         PaymentEventHandler handler) {
 *     return factory.createListener("PAYMENT.INPUT.QUEUE", handler);
 * }
 * }
 * </pre>
 */
@Component
public class IdempotentMqListenerFactory {
    
    private static final Logger log = LoggerFactory.getLogger(IdempotentMqListenerFactory.class);
    
    private final ConnectionFactory connectionFactory;
    private final IdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    
    public IdempotentMqListenerFactory(
            ConnectionFactory connectionFactory,
            IdempotencyService idempotencyService,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry) {
        this.connectionFactory = connectionFactory;
        this.idempotencyService = idempotencyService;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
    }
    
    /**
     * Create an idempotent message listener container for a queue.
     * 
     * @param queueName The IBM MQ queue name
     * @param handler The payment message handler
     * @param <T> The message payload type
     * @return Configured listener container
     */
    public <T> DefaultMessageListenerContainer createListener(
            String queueName,
            PaymentMessageHandler<T> handler) {
        return createListener(queueName, handler, 1);
    }
    
    /**
     * Create an idempotent message listener container with concurrency.
     * 
     * @param queueName The IBM MQ queue name
     * @param handler The payment message handler
     * @param concurrency Number of concurrent consumers
     * @param <T> The message payload type
     * @return Configured listener container
     */
    public <T> DefaultMessageListenerContainer createListener(
            String queueName,
            PaymentMessageHandler<T> handler,
            int concurrency) {
        
        log.info("Creating idempotent MQ listener for queue: {} with concurrency: {}",
                queueName, concurrency);
        
        DefaultMessageListenerContainer container = new DefaultMessageListenerContainer();
        
        // Connection settings
        container.setConnectionFactory(connectionFactory);
        container.setDestinationName(queueName);
        
        // Session settings - CLIENT_ACKNOWLEDGE for manual acknowledgment
        container.setSessionAcknowledgeMode(Session.CLIENT_ACKNOWLEDGE);
        container.setSessionTransacted(false);
        
        // Concurrency
        container.setConcurrentConsumers(concurrency);
        container.setMaxConcurrentConsumers(concurrency);
        
        // Create the idempotent listener
        IdempotentMqMessageListener<T> listener = new IdempotentMqMessageListener<>(
                handler,
                idempotencyService,
                objectMapper,
                meterRegistry,
                queueName);
        
        container.setMessageListener(listener);
        
        // Error handling
        container.setErrorHandler(t -> {
            log.error("Error in MQ listener for queue: {}", queueName, t);
        });
        
        // Recovery settings
        container.setRecoveryInterval(5000); // 5 seconds between recovery attempts
        
        // Auto-start
        container.setAutoStartup(true);
        
        return container;
    }
    
    /**
     * Create a listener with custom configuration.
     */
    public <T> DefaultMessageListenerContainer createListener(
            String queueName,
            PaymentMessageHandler<T> handler,
            MqListenerConfig config) {
        
        DefaultMessageListenerContainer container = createListener(
                queueName, handler, config.getConcurrency());
        
        // Apply custom config
        if (config.getReceiveTimeout() > 0) {
            container.setReceiveTimeout(config.getReceiveTimeout());
        }
        
        if (config.getRecoveryInterval() > 0) {
            container.setRecoveryInterval(config.getRecoveryInterval());
        }
        
        container.setAutoStartup(config.isAutoStartup());
        
        return container;
    }
    
    /**
     * Configuration options for MQ listener.
     */
    public static class MqListenerConfig {
        private int concurrency = 1;
        private long receiveTimeout = 1000;
        private long recoveryInterval = 5000;
        private boolean autoStartup = true;
        
        public static MqListenerConfig defaults() {
            return new MqListenerConfig();
        }
        
        public MqListenerConfig concurrency(int concurrency) {
            this.concurrency = concurrency;
            return this;
        }
        
        public MqListenerConfig receiveTimeout(long receiveTimeout) {
            this.receiveTimeout = receiveTimeout;
            return this;
        }
        
        public MqListenerConfig recoveryInterval(long recoveryInterval) {
            this.recoveryInterval = recoveryInterval;
            return this;
        }
        
        public MqListenerConfig autoStartup(boolean autoStartup) {
            this.autoStartup = autoStartup;
            return this;
        }
        
        public int getConcurrency() {
            return concurrency;
        }
        
        public long getReceiveTimeout() {
            return receiveTimeout;
        }
        
        public long getRecoveryInterval() {
            return recoveryInterval;
        }
        
        public boolean isAutoStartup() {
            return autoStartup;
        }
    }
}
