-- MySQL 8.0.16+ DDL. Compose creates demo_ticketing_db before first-volume initialization.
-- For manual setup outside Compose, run 00-create-database.sql first.
-- IF NOT EXISTS preserves existing tables; this is not a schema migration engine.
USE demo_ticketing_db;
SET time_zone = '+00:00';

CREATE TABLE IF NOT EXISTS demo_users (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  public_user_id VARCHAR(100) NOT NULL,
  bts_service_id VARCHAR(50) NOT NULL,
  bts_external_user_id VARCHAR(100) NOT NULL,
  bts_user_id VARCHAR(50) NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT uq_demo_user_public UNIQUE (public_user_id),
  CONSTRAINT uq_demo_user_external UNIQUE (bts_service_id, bts_external_user_id),
  CONSTRAINT uq_demo_user_bts UNIQUE (bts_user_id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS concerts (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  public_id VARCHAR(50) NOT NULL,
  title VARCHAR(255) NOT NULL,
  artist VARCHAR(255) NOT NULL,
  venue VARCHAR(255) NOT NULL,
  start_at DATETIME(6) NOT NULL COMMENT 'UTC',
  end_at DATETIME(6) NOT NULL COMMENT 'UTC; demo schedule, not an observed attendance fact',
  price_from BIGINT NOT NULL,
  tagline VARCHAR(255) NOT NULL,
  hue INT NOT NULL,
  CONSTRAINT uq_concert_public UNIQUE (public_id),
  CONSTRAINT ck_concert_times CHECK (end_at > start_at),
  CONSTRAINT ck_concert_price CHECK (price_from >= 0),
  CONSTRAINT ck_concert_hue CHECK (hue >= 0 AND hue < 360),
  INDEX ix_concert_start (start_at, public_id)
) ENGINE=InnoDB;

-- 동일 사용자/공연의 선점 생성·변경만 직렬화한다. 좌석 점유는 좌석 행으로 보호한다.
CREATE TABLE IF NOT EXISTS reservation_user_concert_guards (
  user_id BIGINT NOT NULL,
  concert_id BIGINT NOT NULL,
  PRIMARY KEY (user_id, concert_id),
  CONSTRAINT fk_reservation_guard_user FOREIGN KEY (user_id) REFERENCES demo_users(id) ON DELETE CASCADE,
  CONSTRAINT fk_reservation_guard_concert FOREIGN KEY (concert_id) REFERENCES concerts(id) ON DELETE CASCADE
) ENGINE=InnoDB;

-- Reservation tables are reused by the transactional booking implementation.
CREATE TABLE IF NOT EXISTS seat_holds (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  public_id VARCHAR(50) NOT NULL,
  user_id BIGINT NOT NULL,
  concert_id BIGINT NOT NULL,
  status VARCHAR(20) NOT NULL,
  expires_at DATETIME(6) NOT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT uq_hold_public UNIQUE (public_id),
  CONSTRAINT fk_hold_user FOREIGN KEY (user_id) REFERENCES demo_users(id),
  CONSTRAINT fk_hold_concert FOREIGN KEY (concert_id) REFERENCES concerts(id),
  CONSTRAINT ck_hold_status CHECK (status IN ('ACTIVE','RELEASED','EXPIRED','CONVERTED')),
  CONSTRAINT ck_hold_expiry CHECK (expires_at > created_at),
  INDEX ix_hold_expiry (status, expires_at),
  INDEX ix_hold_owner (user_id, concert_id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS bookings (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  public_id VARCHAR(100) NOT NULL,
  user_id BIGINT NOT NULL,
  concert_id BIGINT NOT NULL,
  hold_id BIGINT NOT NULL,
  status VARCHAR(20) NOT NULL,
  total_price BIGINT NOT NULL,
  concert_title_snapshot VARCHAR(255) NOT NULL,
  venue_snapshot VARCHAR(255) NOT NULL,
  start_at_snapshot DATETIME(6) NOT NULL,
  end_at_snapshot DATETIME(6) NOT NULL,
  booking_started_at DATETIME(6) NOT NULL,
  confirmed_at DATETIME(6) NOT NULL,
  booking_duration_seconds DECIMAL(18,6) NOT NULL,
  payment_identifier VARCHAR(100) NOT NULL COMMENT 'Test identifiers only',
  ip_address VARCHAR(45) NOT NULL COMMENT 'Server-observed IP',
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  cancelled_at DATETIME(6) NULL,
  CONSTRAINT uq_booking_public UNIQUE (public_id),
  CONSTRAINT uq_booking_hold UNIQUE (hold_id),
  CONSTRAINT fk_booking_user FOREIGN KEY (user_id) REFERENCES demo_users(id),
  CONSTRAINT fk_booking_concert FOREIGN KEY (concert_id) REFERENCES concerts(id),
  CONSTRAINT fk_booking_hold FOREIGN KEY (hold_id) REFERENCES seat_holds(id),
  CONSTRAINT ck_booking_status CHECK (status IN ('CONFIRMED','CANCELLED')),
  CONSTRAINT ck_booking_price CHECK (total_price >= 0),
  CONSTRAINT ck_booking_times CHECK (confirmed_at >= booking_started_at AND end_at_snapshot > start_at_snapshot),
  CONSTRAINT ck_booking_duration CHECK (booking_duration_seconds >= 0),
  CONSTRAINT ck_booking_cancel CHECK (
    (status = 'CONFIRMED' AND cancelled_at IS NULL) OR
    (status = 'CANCELLED' AND cancelled_at IS NOT NULL AND cancelled_at >= confirmed_at)),
  INDEX ix_booking_owner (user_id, created_at)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS concert_seats (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  concert_id BIGINT NOT NULL,
  seat_code VARCHAR(20) NOT NULL,
  row_label VARCHAR(10) NOT NULL,
  seat_number INT NOT NULL,
  grade VARCHAR(10) NOT NULL,
  price BIGINT NOT NULL,
  state VARCHAR(20) NOT NULL DEFAULT 'AVAILABLE',
  active_hold_id BIGINT NULL,
  active_booking_id BIGINT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  CONSTRAINT uq_concert_seat UNIQUE (concert_id, seat_code),
  CONSTRAINT uq_concert_position UNIQUE (concert_id, row_label, seat_number),
  CONSTRAINT fk_seat_concert FOREIGN KEY (concert_id) REFERENCES concerts(id),
  CONSTRAINT fk_seat_active_hold FOREIGN KEY (active_hold_id) REFERENCES seat_holds(id),
  CONSTRAINT fk_seat_active_booking FOREIGN KEY (active_booking_id) REFERENCES bookings(id),
  CONSTRAINT ck_seat_number CHECK (seat_number > 0),
  CONSTRAINT ck_seat_grade CHECK (grade IN ('VIP','R','S')),
  CONSTRAINT ck_seat_price CHECK (price >= 0),
  CONSTRAINT ck_seat_state CHECK (state IN ('AVAILABLE','HELD','BOOKED')),
  CONSTRAINT ck_seat_occupancy CHECK (
    (state = 'AVAILABLE' AND active_hold_id IS NULL AND active_booking_id IS NULL) OR
    (state = 'HELD' AND active_hold_id IS NOT NULL AND active_booking_id IS NULL) OR
    (state = 'BOOKED' AND active_hold_id IS NULL AND active_booking_id IS NOT NULL)),
  INDEX ix_seat_state (concert_id, state)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS seat_hold_items (
  hold_id BIGINT NOT NULL,
  concert_seat_id BIGINT NOT NULL,
  price_snapshot BIGINT NOT NULL,
  PRIMARY KEY (hold_id, concert_seat_id),
  CONSTRAINT fk_hold_item_hold FOREIGN KEY (hold_id) REFERENCES seat_holds(id),
  CONSTRAINT fk_hold_item_seat FOREIGN KEY (concert_seat_id) REFERENCES concert_seats(id),
  CONSTRAINT ck_hold_item_price CHECK (price_snapshot >= 0)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS booking_seats (
  booking_id BIGINT NOT NULL,
  concert_seat_id BIGINT NOT NULL,
  seat_code_snapshot VARCHAR(20) NOT NULL,
  grade_snapshot VARCHAR(10) NOT NULL,
  price_snapshot BIGINT NOT NULL,
  PRIMARY KEY (booking_id, concert_seat_id),
  CONSTRAINT fk_booking_seat_booking FOREIGN KEY (booking_id) REFERENCES bookings(id),
  CONSTRAINT fk_booking_seat_seat FOREIGN KEY (concert_seat_id) REFERENCES concert_seats(id),
  CONSTRAINT ck_booking_seat_grade CHECK (grade_snapshot IN ('VIP','R','S')),
  CONSTRAINT ck_booking_seat_price CHECK (price_snapshot >= 0)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS admission_tickets (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  public_id VARCHAR(100) NOT NULL,
  booking_id BIGINT NOT NULL,
  concert_seat_id BIGINT NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'ISSUED',
  checked_in_at DATETIME(6) NULL,
  CONSTRAINT uq_ticket_public UNIQUE (public_id),
  CONSTRAINT uq_ticket_booking_seat UNIQUE (booking_id, concert_seat_id),
  CONSTRAINT fk_ticket_booking_seat FOREIGN KEY (booking_id, concert_seat_id)
    REFERENCES booking_seats(booking_id, concert_seat_id),
  CONSTRAINT ck_ticket_status CHECK (status IN ('ISSUED','CHECKED_IN','CANCELLED')),
  CONSTRAINT ck_ticket_checkin CHECK (
    (status = 'CHECKED_IN' AND checked_in_at IS NOT NULL) OR
    (status IN ('ISSUED','CANCELLED') AND checked_in_at IS NULL))
) ENGINE=InnoDB;
