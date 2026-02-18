package com.dev.payments.framework.kafka.config;

import com.dev.payments.framework.handler.PaymentMessageHandler;
import com.dev.payments.framework.kafka.listener.IdempotentKafkaListener;
import com.dev.payments.framework.service.IdempotencyService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Factory for creating idempotent Kafka message listener containers.
 * 
 * Usage:
 * <pre>
 * {@code
 * @Bean
 * public ConcurrentMessageListenerContainer<String, String> paymentListener(
 *         IdempotentKafkaListenerFactory factory,
 *         PaymentEventHandler handler) {
 *     return factory.createListener("payment-events", handler);
 * }
 * }
 * </pre>
 */
@Component
public class IdempotentKafkaListenerFactory {
    
    private static final Logger log = LoggerFactory.getLogger(IdempotentKafkaListenerFactory.class);
    
    private final IdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final KafkaProperties kafkaProperties;
    
    public IdempotentKafkaListenerFactory(
            IdempotencyService idempotencyService,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry,
            KafkaProperties kafkaProperties) {
        this.idempotencyService = idempotencyService;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
        this.kafkaProperties = kafkaProperties;
    }
    
    /**
     * Create an idempotent Kafka listener for a topic.
     * 
     * @param topic The Kafka topic name
     * @param handler The payment message handler
     * @param <T> The message payload type
     * @return Configured listener container
     */
    public <T> ConcurrentMessageListenerContainer<String, String> createListener(
            String topic,
            PaymentMessageHandler<T> handler) {
        return createListener(topic, handler, KafkaListenerConfig.defaults());
    }
    
    /**
     * Create an idempotent Kafka listener with custom configuration.
     */
    public <T> ConcurrentMessageListenerContainer<String, String> createListener(
            String topic,
            PaymentMessageHandler<T> handler,
            KafkaListenerConfig config) {
        
        log.info("Creating idempotent Kafka listener for topic: {} with concurrency: {}",
                topic, config.getConcurrency());
        
        // Create consumer factory
        ConsumerFactory<String, String> consumerFactory = createConsumerFactory(config);
        
        // Create container factory
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setConcurrency(config.getConcurrency());
        
        // Configure for manual acknowledgment
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        
        // Create the idempotent listener
        IdempotentKafkaListener<T> listener = new IdempotentKafkaListener<>(
                handler,
                idempotencyService,
                objectMapper,
                meterRegistry,
                topic);
        
        // Create container
        ConcurrentMessageListenerContainer<String, String> container =
                factory.createContainer(topic);
        container.setupMessageListener(listener);
        container.setAutoStartup(config.isAutoStartup());
        
        // Set group ID
        container.getContainerProperties().setGroupId(config.getGroupId());
        
        return container;
    }
    
    /**
     * Create a Kafka consumer factory with proper configuration.
     */
    private ConsumerFactory<String, String> createConsumerFactory(KafkaListenerConfig config) {
        Map<String, Object> props = new HashMap<>();
        
        // Bootstrap servers
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaProperties.getBootstrapServers());
        
        // Deserializers
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        
        // Group ID
        props.put(ConsumerConfig.GROUP_ID_CONFIG, config.getGroupId());
        
        // CRITICAL: Disable auto-commit - we commit after processing
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        
        // Start from earliest if no committed offset
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        
        // Fetch settings
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, config.getMaxPollRecords());
        props.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, config.getMaxPollIntervalMs());
        
        // Session timeout
        props.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, config.getSessionTimeoutMs());
        
        return new DefaultKafkaConsumerFactory<>(props);
    }
    
    /**
     * Configuration options for Kafka listener.
     */
    public static class KafkaListenerConfig {
        private String groupId = "payment-processor";
        private int concurrency = 1;
        private int maxPollRecords = 500;
        private int maxPollIntervalMs = 300000;
        private int sessionTimeoutMs = 10000;
        private boolean autoStartup = true;
        
        public static KafkaListenerConfig defaults() {
            return new KafkaListenerConfig();
        }
        
        public KafkaListenerConfig groupId(String groupId) {
            this.groupId = groupId;
            return this;
        }
        
        public KafkaListenerConfig concurrency(int concurrency) {
            this.concurrency = concurrency;
            return this;
        }
        
        public KafkaListenerConfig maxPollRecords(int maxPollRecords) {
            this.maxPollRecords = maxPollRecords;
            return this;
        }
        
        public KafkaListenerConfig maxPollIntervalMs(int maxPollIntervalMs) {
            this.maxPollIntervalMs = maxPollIntervalMs;
            return this;
        }
        
        public KafkaListenerConfig sessionTimeoutMs(int sessionTimeoutMs) {
            this.sessionTimeoutMs = sessionTimeoutMs;
            return this;
        }
        
        public KafkaListenerConfig autoStartup(boolean autoStartup) {
            this.autoStartup = autoStartup;
            return this;
        }
        
        public String getGroupId() {
            return groupId;
        }
        
        public int getConcurrency() {
            return concurrency;
        }
        
        public int getMaxPollRecords() {
            return maxPollRecords;
        }
        
        public int getMaxPollIntervalMs() {
            return maxPollIntervalMs;
        }
        
        public int getSessionTimeoutMs() {
            return sessionTimeoutMs;
        }
        
        public boolean isAutoStartup() {
            return autoStartup;
        }
    }
    
    /**
     * Kafka connection properties.
     */
    public static class KafkaProperties {
        private String bootstrapServers = "localhost:9092";
        
        public String getBootstrapServers() {
            return bootstrapServers;
        }
        
        public void setBootstrapServers(String bootstrapServers) {
            this.bootstrapServers = bootstrapServers;
        }
    }
}
