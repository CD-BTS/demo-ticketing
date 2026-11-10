-- Repair concert text that was stored with a wrong client character set at first initialization.
-- Updates only the four text columns of c1/c2/c3. Seats, bookings and sessions are untouched.
USE demo_ticketing_db;
SET NAMES utf8mb4;
UPDATE concerts SET title='MIDNIGHT SIGNAL — Seoul Encore', artist='Nova Lane',
  venue='올림픽공원 KSPO DOME', tagline='밤 사이로 번지는 신호, 앙코르 서울' WHERE public_id='c1';
UPDATE concerts SET title='Paper Lanterns Tour 2026', artist='The Hollow Birds',
  venue='인스파이어 아레나', tagline='종이 등불 아래서 듣는 어쿠스틱 세트' WHERE public_id='c2';
UPDATE concerts SET title='Neon Harbor Live', artist='Kite & Ember',
  venue='고척스카이돔', tagline='항구의 네온, 한 번뿐인 라이브' WHERE public_id='c3';
SELECT public_id, title, venue, tagline, HEX(LEFT(venue,3)) AS venue_first_bytes FROM concerts ORDER BY public_id;