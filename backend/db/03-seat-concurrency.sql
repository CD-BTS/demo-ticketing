-- 기존 DB에도 독립적으로 적용할 수 있는 추가 전용 DDL.
USE demo_ticketing_db;
CREATE TABLE IF NOT EXISTS reservation_user_concert_guards (
  user_id BIGINT NOT NULL,
  concert_id BIGINT NOT NULL,
  PRIMARY KEY (user_id, concert_id),
  CONSTRAINT fk_reservation_guard_user FOREIGN KEY (user_id) REFERENCES demo_users(id) ON DELETE CASCADE,
  CONSTRAINT fk_reservation_guard_concert FOREIGN KEY (concert_id) REFERENCES concerts(id) ON DELETE CASCADE
) ENGINE=InnoDB;
