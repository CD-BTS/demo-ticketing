-- Add missing demo rows only. Never updates/deletes existing data or resets seat state.
USE demo_ticketing_db;
SET time_zone = '+00:00';
START TRANSACTION;

-- The original mock's c2/c3 priceFrom was inconsistent with its seat price map.
-- All concerts use the same VIP/R/S map, so price_from is 99000 for all three.
-- end_at = start_at + 3 hours is demo fixture data, not an agreed real duration.
INSERT INTO concerts (public_id,title,artist,venue,start_at,end_at,price_from,tagline,hue)
SELECT s.public_id,s.title,s.artist,s.venue,s.start_at,s.end_at,s.price_from,s.tagline,s.hue
FROM (
  SELECT 'c1' AS public_id, 'MIDNIGHT SIGNAL — Seoul Encore' AS title,
    'Nova Lane' AS artist, '올림픽공원 KSPO DOME' AS venue,
    CAST('2026-12-12 09:00:00' AS DATETIME) AS start_at,
    CAST('2026-12-12 12:00:00' AS DATETIME) AS end_at,
    99000 AS price_from, '밤 사이로 번지는 신호, 앙코르 서울' AS tagline, 265 AS hue
  UNION ALL SELECT 'c2','Paper Lanterns Tour 2026','The Hollow Birds','인스파이어 아레나',
    '2026-12-26 10:00:00','2026-12-26 13:00:00',99000,'종이 등불 아래서 듣는 어쿠스틱 세트',28
  UNION ALL SELECT 'c3','Neon Harbor Live','Kite & Ember','고척스카이돔',
    '2027-01-09 08:30:00','2027-01-09 11:30:00',99000,'항구의 네온, 한 번뿐인 라이브',190
) s
WHERE NOT EXISTS (SELECT 1 FROM concerts c WHERE c.public_id=s.public_id);

INSERT INTO concert_seats (concert_id,seat_code,row_label,seat_number,grade,price,state)
SELECT c.id,CONCAT(r.row_label,'-',LPAD(n.num,2,'0')),r.row_label,n.num,
  CASE WHEN r.row_label <= 'B' THEN 'VIP' WHEN r.row_label <= 'E' THEN 'R' ELSE 'S' END,
  CASE WHEN r.row_label <= 'B' THEN 154000 WHEN r.row_label <= 'E' THEN 132000 ELSE 99000 END,
  'AVAILABLE'
FROM concerts c
CROSS JOIN (SELECT 'A' row_label UNION ALL SELECT 'B' UNION ALL SELECT 'C' UNION ALL SELECT 'D'
  UNION ALL SELECT 'E' UNION ALL SELECT 'F' UNION ALL SELECT 'G' UNION ALL SELECT 'H') r
CROSS JOIN (SELECT 1 num UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
  UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8
  UNION ALL SELECT 9 UNION ALL SELECT 10 UNION ALL SELECT 11 UNION ALL SELECT 12) n
WHERE c.public_id IN ('c1','c2','c3')
AND NOT EXISTS (SELECT 1 FROM concert_seats cs
  WHERE cs.concert_id=c.id AND cs.seat_code=CONCAT(r.row_label,'-',LPAD(n.num,2,'0')));
COMMIT;
