-- Additive demo-only schema for existing volumes; no ALTER/DROP or BTS DB access.
USE demo_ticketing_db;
CREATE TABLE IF NOT EXISTS demo_sessions (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  token_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  expires_at DATETIME(6) NOT NULL,
  CONSTRAINT uq_session_token UNIQUE (token_hash),
  CONSTRAINT fk_session_user FOREIGN KEY (user_id) REFERENCES demo_users(id),
  INDEX ix_session_expiry (expires_at)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS outbox_events (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  service_id VARCHAR(50) NOT NULL,
  external_event_id VARCHAR(100) NOT NULL,
  business_transition_id VARCHAR(100) NOT NULL,
  aggregate_id VARCHAR(100) NOT NULL,
  event_type VARCHAR(30) NOT NULL,
  occurred_at_utc DATETIME NOT NULL,
  metadata JSON NOT NULL,
  payload LONGTEXT NULL COMMENT 'Frozen JSON bytes; set once before first delivery',
  delivery_status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
  lease_token CHAR(36) NULL,
  locked_until DATETIME(6) NULL,
  retries INT NOT NULL DEFAULT 0,
  next_attempt_at DATETIME(6) NOT NULL,
  bts_event_id BIGINT NULL,
  last_error_code VARCHAR(80) NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  sent_at DATETIME(6) NULL,
  CONSTRAINT uq_outbox_external UNIQUE (service_id,external_event_id),
  CONSTRAINT uq_outbox_transition UNIQUE (business_transition_id,event_type),
  CONSTRAINT fk_outbox_user FOREIGN KEY (user_id) REFERENCES demo_users(id),
  CONSTRAINT ck_outbox_type CHECK (event_type IN ('BOOKING','CANCEL','CHECK_IN')),
  CONSTRAINT ck_outbox_status CHECK (delivery_status IN ('PENDING','IN_FLIGHT','RETRY','SENT','FAILED')),
  CONSTRAINT ck_outbox_payload CHECK (payload IS NULL OR JSON_VALID(payload)),
  INDEX ix_outbox_due (delivery_status,next_attempt_at,locked_until),
  INDEX ix_outbox_aggregate (aggregate_id,id,delivery_status)
) ENGINE=InnoDB;
