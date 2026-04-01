-- ============================================================================
-- Payment Idempotency Framework - Oracle Database Schema
-- ============================================================================
-- Run this script to create the required tables for the framework.
-- 
-- Prerequisites:
-- - Oracle 19c or later
-- - Database user with CREATE TABLE, CREATE INDEX privileges
-- ============================================================================

-- ============================================================================
-- Table: processed_events
-- Purpose: Tracks all payment events for idempotency checking
-- ============================================================================
CREATE TABLE processed_events (
    -- Primary key: unique identifier for each payment event
    idempotency_key VARCHAR2(255) NOT NULL,
    
    -- Current state in the payment lifecycle
    -- Values: RECEIVED, PROCESSING, SENDING, COMPLETED, FAILED
    state VARCHAR2(50) NOT NULL,
    
    -- Source system/queue identifier
    source VARCHAR2(255),
    
    -- Target system identifier
    destination VARCHAR2(255),
    
    -- When the event was first received
    received_at TIMESTAMP NOT NULL,
    
    -- When processing completed (NULL if not yet completed)
    completed_at TIMESTAMP,
    
    -- Node/instance that processed this event
    processed_by VARCHAR2(255),
    
    -- Number of delivery attempts
    retry_count NUMBER DEFAULT 0,
    
    -- Last error message (if failed)
    last_error VARCHAR2(2000),
    
    -- Optimistic locking version
    version NUMBER DEFAULT 0,
    
    -- Primary key constraint
    CONSTRAINT pk_processed_events PRIMARY KEY (idempotency_key)
);

-- Indexes for common queries
CREATE INDEX idx_processed_events_state ON processed_events(state);
CREATE INDEX idx_processed_events_received_at ON processed_events(received_at);
CREATE INDEX idx_processed_events_destination ON processed_events(destination);

-- Comments
COMMENT ON TABLE processed_events IS 'Tracks payment events for idempotency - prevents duplicate processing';
COMMENT ON COLUMN processed_events.idempotency_key IS 'Unique identifier extracted from payment message (e.g., transaction_id)';
COMMENT ON COLUMN processed_events.state IS 'Current state: RECEIVED, PROCESSING, SENDING, COMPLETED, FAILED';


-- ============================================================================
-- Table: outbox_entries
-- Purpose: Stores pending deliveries for reliable async processing
-- ============================================================================
CREATE TABLE outbox_entries (
    -- Primary key: UUID
    id VARCHAR2(36) NOT NULL,
    
    -- Links to processed_events table
    idempotency_key VARCHAR2(255) NOT NULL,
    
    -- Current status of the outbox entry
    -- Values: PENDING, PROCESSING, DELIVERED, RETRY_PENDING, FAILED
    status VARCHAR2(50) NOT NULL,
    
    -- Target system identifier
    destination VARCHAR2(255) NOT NULL,
    
    -- HTTP method for REST delivery
    http_method VARCHAR2(10) DEFAULT 'POST',
    
    -- Endpoint URL
    endpoint VARCHAR2(1000),
    
    -- Payload to deliver (JSON)
    payload CLOB NOT NULL,
    
    -- HTTP headers (JSON)
    headers CLOB,
    
    -- When the entry was created
    created_at TIMESTAMP NOT NULL,
    
    -- When delivery was last attempted
    last_attempt_at TIMESTAMP,
    
    -- When successfully delivered
    delivered_at TIMESTAMP,
    
    -- When to retry next (for failed entries)
    next_retry_at TIMESTAMP,
    
    -- Number of delivery attempts
    retry_count NUMBER DEFAULT 0,
    
    -- Last error message
    last_error VARCHAR2(2000),
    
    -- HTTP status from last attempt
    last_http_status NUMBER,
    
    -- Processor instance that locked this entry
    locked_by VARCHAR2(255),
    
    -- When the lock expires
    lock_expires_at TIMESTAMP,
    
    -- Optimistic locking version
    version NUMBER DEFAULT 0,
    
    -- Primary key constraint
    CONSTRAINT pk_outbox_entries PRIMARY KEY (id),
    
    -- Foreign key to processed_events
    CONSTRAINT fk_outbox_processed_event 
        FOREIGN KEY (idempotency_key) 
        REFERENCES processed_events(idempotency_key)
);

-- Indexes for common queries
CREATE INDEX idx_outbox_status ON outbox_entries(status);
CREATE INDEX idx_outbox_next_retry ON outbox_entries(next_retry_at);
CREATE INDEX idx_outbox_idempotency_key ON outbox_entries(idempotency_key);
CREATE INDEX idx_outbox_lock_expires ON outbox_entries(lock_expires_at);
CREATE INDEX idx_outbox_destination ON outbox_entries(destination);
CREATE INDEX idx_outbox_created_at ON outbox_entries(created_at);

-- Composite index for polling query
CREATE INDEX idx_outbox_ready_for_processing 
    ON outbox_entries(status, next_retry_at, created_at);

-- Comments
COMMENT ON TABLE outbox_entries IS 'Outbox pattern table for reliable async delivery';
COMMENT ON COLUMN outbox_entries.locked_by IS 'Processor instance holding the lock - prevents concurrent processing';


-- ============================================================================
-- Grants (adjust role name as needed)
-- ============================================================================
-- GRANT SELECT, INSERT, UPDATE, DELETE ON processed_events TO payment_app_role;
-- GRANT SELECT, INSERT, UPDATE, DELETE ON outbox_entries TO payment_app_role;


-- ============================================================================
-- Cleanup Job (optional - run via scheduler)
-- ============================================================================
-- This procedure cleans up old completed entries
CREATE OR REPLACE PROCEDURE cleanup_old_entries(p_retention_days IN NUMBER DEFAULT 7) AS
    v_cutoff_date TIMESTAMP;
    v_deleted_events NUMBER;
    v_deleted_outbox NUMBER;
BEGIN
    v_cutoff_date := SYSTIMESTAMP - INTERVAL '1' DAY * p_retention_days;
    
    -- Delete old outbox entries first (due to FK)
    DELETE FROM outbox_entries 
    WHERE status = 'DELIVERED' 
    AND delivered_at < v_cutoff_date;
    v_deleted_outbox := SQL%ROWCOUNT;
    
    -- Delete old processed events
    DELETE FROM processed_events 
    WHERE state = 'COMPLETED' 
    AND completed_at < v_cutoff_date;
    v_deleted_events := SQL%ROWCOUNT;
    
    COMMIT;
    
    DBMS_OUTPUT.PUT_LINE('Deleted ' || v_deleted_events || ' events and ' || 
                         v_deleted_outbox || ' outbox entries');
END;
/
