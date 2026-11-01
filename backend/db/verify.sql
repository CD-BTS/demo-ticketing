-- Read-only checks. Returned violations should all be zero.
USE demo_ticketing_db;
SELECT DATABASE() AS active_database, VERSION() AS mysql_version;
SELECT (SELECT COUNT(*) FROM concerts) AS concert_count,
       (SELECT COUNT(*) FROM concert_seats) AS total_seat_count;
SELECT c.public_id, COUNT(s.id) AS seat_count, c.price_from, MIN(s.price) AS minimum_seat_price,
       SUM(s.state='AVAILABLE') AS available_count
FROM concerts c LEFT JOIN concert_seats s ON s.concert_id=c.id
GROUP BY c.id ORDER BY c.public_id;
SELECT c.public_id,s.grade,COUNT(*) AS count_per_grade,MIN(s.price) AS min_price,MAX(s.price) AS max_price
FROM concerts c JOIN concert_seats s ON s.concert_id=c.id
GROUP BY c.public_id,s.grade ORDER BY c.public_id,s.grade;
SELECT COUNT(*) AS price_mismatch_count FROM concerts c
WHERE c.price_from <> (SELECT MIN(s.price) FROM concert_seats s WHERE s.concert_id=c.id);
SELECT COUNT(*) AS invalid_seat_codes FROM concert_seats
WHERE seat_code <> CONCAT(row_label,'-',LPAD(seat_number,2,'0'));
SELECT TABLE_NAME,CONSTRAINT_TYPE,COUNT(*) AS constraint_count
FROM information_schema.TABLE_CONSTRAINTS
WHERE TABLE_SCHEMA='demo_ticketing_db'
GROUP BY TABLE_NAME,CONSTRAINT_TYPE ORDER BY TABLE_NAME,CONSTRAINT_TYPE;
