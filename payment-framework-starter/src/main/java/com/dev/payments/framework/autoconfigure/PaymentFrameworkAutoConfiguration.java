package com.dev.payments.framework.autoconfigure;

import com.dev.payments.framework.config.OutboxProcessorConfig;
import com.dev.payments.framework.processor.OutboxProcessor;
import com.dev.payments.framework.repository.OutboxRepository;
import com.dev.payments.framework.repository.ProcessedEventRepository;
import com.dev.payments.framework.service.DeliveryService;
import com.dev.payments.framework.service.IdempotencyService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;

/**
 * Auto-configuration for the Payment Idempotency Framework.
 * 
 * This configuration:
 * 1. Sets up core services (IdempotencyService, DeliveryService)
 * 2. Configures the OutboxProcessor (if enabled)
 * 3. Integrates with Micrometer for metrics
 * 
 * To use:
 * 1. Add payment-framework-starter to your dependencies
 * 2. Add a database adapter (payment-framework-jpa or payment-framework-mongodb)
 * 3. Add a messaging adapter (payment-framework-ibmmq or payment-framework-kafka)
 * 4. Configure via application.yml
 */
@AutoConfiguration
@EnableScheduling
@EnableConfigurationProperties(OutboxProcessorConfig.class)
@ConditionalOnProperty(name = "payment.framework.enabled", havingValue = "true", matchIfMissing = true)
public class PaymentFrameworkAutoConfiguration {
    
    /**
     * Core idempotency service.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({ProcessedEventRepository.class, OutboxRepository.class})
    public IdempotencyService idempotencyService(
            ProcessedEventRepository processedEventRepository,
            OutboxRepository outboxRepository,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry) {
        return new IdempotencyService(
                processedEventRepository,
                outboxRepository,
                objectMapper,
                meterRegistry);
    }
    
    /**
     * Delivery service for outbox processing.
     */
    @Bean
    @ConditionalOnMissingBean
    public DeliveryService deliveryService(
            WebClient.Builder webClientBuilder,
            MeterRegistry meterRegistry,
            OutboxProcessorConfig config) {
        return new DeliveryService(
                webClientBuilder,
                meterRegistry,
                Duration.ofSeconds(config.getDeliveryTimeoutSeconds()));
    }
    
    /**
     * Outbox processor (embedded by default).
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(name = "payment.framework.outbox.enabled", havingValue = "true", matchIfMissing = true)
    @ConditionalOnBean({OutboxRepository.class, ProcessedEventRepository.class})
    public OutboxProcessor outboxProcessor(
            OutboxRepository outboxRepository,
            ProcessedEventRepository processedEventRepository,
            DeliveryService deliveryService,
            OutboxProcessorConfig config,
            MeterRegistry meterRegistry) {
        return new OutboxProcessor(
                outboxRepository,
                processedEventRepository,
                deliveryService,
                config,
                meterRegistry);
    }
    
    /**
     * ObjectMapper configuration (if not already provided).
     */
    @Bean
    @ConditionalOnMissingBean
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.findAndRegisterModules(); // Registers JSR-310 (java.time) module
        return mapper;
    }
    
    /**
     * WebClient builder (if not already provided).
     */
    @Bean
    @ConditionalOnMissingBean
    public WebClient.Builder webClientBuilder() {
        return WebClient.builder();
    }
}
