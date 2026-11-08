-- MySQL 8.0.16+. Apply once to the Demo database; not safe to rerun.
USE demo_ticketing_db;

CREATE TABLE booking_attempts (
  attempt_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
  user_id BIGINT NOT NULL,
  concert_id BIGINT NOT NULL,
  service_id VARCHAR(50) NOT NULL,
  stage VARCHAR(30) NOT NULL DEFAULT 'STARTED',
  status VARCHAR(20) NOT NULL DEFAULT 'ENTERED',
  sequence_no BIGINT NOT NULL DEFAULT 0,
  hold_id BIGINT NULL,
  booking_id BIGINT NULL,
  started_at DATETIME(6) NOT NULL,
  CONSTRAINT fk_attempt_user FOREIGN KEY (user_id) REFERENCES demo_users(id),
  CONSTRAINT fk_attempt_concert FOREIGN KEY (concert_id) REFERENCES concerts(id),
  CONSTRAINT fk_attempt_hold FOREIGN KEY (hold_id) REFERENCES seat_holds(id),
  CONSTRAINT fk_attempt_booking FOREIGN KEY (booking_id) REFERENCES bookings(id),
  CONSTRAINT uq_attempt_hold UNIQUE (hold_id),
  CONSTRAINT uq_attempt_booking UNIQUE (booking_id),
  INDEX ix_attempt_owner (user_id,concert_id,started_at)
) ENGINE=InnoDB;

CREATE TABLE booking_attempt_events (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  attempt_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  transition_id VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  sequence_no BIGINT NOT NULL,
  request_fingerprint CHAR(64) NOT NULL,
  metadata JSON NOT NULL,
  occurred_at DATETIME NOT NULL,
  outbox_id BIGINT NULL,
  CONSTRAINT fk_attempt_event FOREIGN KEY (attempt_id) REFERENCES booking_attempts(attempt_id),
  CONSTRAINT fk_attempt_outbox FOREIGN KEY (outbox_id) REFERENCES outbox_events(id),
  CONSTRAINT uq_attempt_transition UNIQUE (attempt_id,transition_id),
  CONSTRAINT uq_attempt_sequence UNIQUE (attempt_id,sequence_no)
) ENGINE=InnoDB;

-- CHECK replacement acquires a metadata lock. DDL statements commit independently.
ALTER TABLE outbox_events DROP CHECK ck_outbox_type;
ALTER TABLE outbox_events ADD CONSTRAINT ck_outbox_type
  CHECK (event_type IN ('BOOKING','CANCEL','CHECK_IN','BOOKING_ATTEMPT'));
