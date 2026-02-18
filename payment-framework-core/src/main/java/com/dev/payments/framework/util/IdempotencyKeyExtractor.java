package com.dev.payments.framework.util;

import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Utility for evaluating SpEL expressions to extract idempotency keys.
 * 
 * Supports expressions like:
 * - #{payload.transactionId}
 * - #{payload.header.messageId}
 * - #{headers['correlationId']}
 * - #{payload.accountId + '-' + payload.transactionId}
 * 
 * Thread Safety: Thread-safe; caches parsed expressions.
 */
public class IdempotencyKeyExtractor {
    
    private static final ExpressionParser parser = new SpelExpressionParser();
    private static final Map<String, Expression> expressionCache = new ConcurrentHashMap<>();
    
    /**
     * Extract the idempotency key from a payload using a SpEL expression.
     * 
     * @param expression SpEL expression (e.g., "#{payload.transactionId}")
     * @param payload The message payload
     * @param headers Message headers (optional)
     * @return The extracted key as a string
     * @throws IdempotencyKeyExtractionException if extraction fails
     */
    public static String extractKey(String expression, Object payload, Map<String, Object> headers) {
        try {
            // Remove #{ } wrapper if present
            String cleanExpression = cleanExpression(expression);
            
            // Get or parse expression
            Expression expr = expressionCache.computeIfAbsent(cleanExpression, parser::parseExpression);
            
            // Create evaluation context
            EvaluationContext context = createContext(payload, headers);
            
            // Evaluate
            Object result = expr.getValue(context);
            
            if (result == null) {
                throw new IdempotencyKeyExtractionException(
                        "Expression evaluated to null: " + expression);
            }
            
            return result.toString();
            
        } catch (IdempotencyKeyExtractionException e) {
            throw e;
        } catch (Exception e) {
            throw new IdempotencyKeyExtractionException(
                    "Failed to extract idempotency key using expression: " + expression, e);
        }
    }
    
    /**
     * Validate that an expression can be parsed.
     * 
     * @param expression The SpEL expression to validate
     * @return true if valid
     */
    public static boolean isValidExpression(String expression) {
        try {
            String cleanExpression = cleanExpression(expression);
            parser.parseExpression(cleanExpression);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * Remove SpEL wrapper syntax (#{ }).
     */
    private static String cleanExpression(String expression) {
        if (expression == null) {
            throw new IdempotencyKeyExtractionException("Expression cannot be null");
        }
        
        String trimmed = expression.trim();
        
        // Remove #{ } wrapper
        if (trimmed.startsWith("#{") && trimmed.endsWith("}")) {
            trimmed = trimmed.substring(2, trimmed.length() - 1).trim();
        }
        
        return trimmed;
    }
    
    /**
     * Create SpEL evaluation context with payload and headers.
     */
    private static EvaluationContext createContext(Object payload, Map<String, Object> headers) {
        StandardEvaluationContext context = new StandardEvaluationContext();
        
        // Add payload as root object and as variable
        context.setRootObject(new RootObject(payload, headers));
        context.setVariable("payload", payload);
        context.setVariable("headers", headers != null ? headers : Map.of());
        
        return context;
    }
    
    /**
     * Root object for SpEL evaluation context.
     * Allows expressions like: payload.field or headers['key']
     */
    public static class RootObject {
        private final Object payload;
        private final Map<String, Object> headers;
        
        public RootObject(Object payload, Map<String, Object> headers) {
            this.payload = payload;
            this.headers = headers;
        }
        
        public Object getPayload() {
            return payload;
        }
        
        public Map<String, Object> getHeaders() {
            return headers;
        }
    }
    
    /**
     * Exception thrown when idempotency key extraction fails.
     */
    public static class IdempotencyKeyExtractionException extends RuntimeException {
        public IdempotencyKeyExtractionException(String message) {
            super(message);
        }
        
        public IdempotencyKeyExtractionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
