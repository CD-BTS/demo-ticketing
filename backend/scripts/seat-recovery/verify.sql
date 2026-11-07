-- 격리 DB의 Issue #25 테스트 공연만 검사하는 읽기 전용 SQL.
SELECT JSON_OBJECT(
 'concerts',(SELECT COUNT(*) FROM concerts WHERE public_id LIKE 'r25_%'),
 'holds',(SELECT COUNT(*) FROM seat_holds h JOIN concerts c ON c.id=h.concert_id WHERE c.public_id LIKE 'r25_%'),
 'expiredHolds',(SELECT COUNT(*) FROM seat_holds h JOIN concerts c ON c.id=h.concert_id WHERE c.public_id LIKE 'r25_%' AND h.status='EXPIRED'),
 'bookings',(SELECT COUNT(*) FROM bookings b JOIN concerts c ON c.id=b.concert_id WHERE c.public_id LIKE 'r25_%'),
 'cancelledBookings',(SELECT COUNT(*) FROM bookings b JOIN concerts c ON c.id=b.concert_id WHERE c.public_id LIKE 'r25_%' AND b.status='CANCELLED'),
 'tickets',(SELECT COUNT(*) FROM admission_tickets t JOIN bookings b ON b.id=t.booking_id JOIN concerts c ON c.id=b.concert_id WHERE c.public_id LIKE 'r25_%'),
 'outbox',(SELECT COUNT(*) FROM outbox_events e JOIN bookings b ON b.public_id=e.aggregate_id JOIN concerts c ON c.id=b.concert_id WHERE c.public_id LIKE 'r25_%'),
 'violations',
 (SELECT COUNT(*) FROM concert_seats s JOIN concerts c ON c.id=s.concert_id
 LEFT JOIN seat_holds h ON h.id=s.active_hold_id LEFT JOIN bookings b ON b.id=s.active_booking_id
 WHERE c.public_id LIKE 'r25_%' AND (
 (s.state='AVAILABLE' AND (s.active_hold_id IS NOT NULL OR s.active_booking_id IS NOT NULL)) OR
 (s.state='HELD' AND (h.id IS NULL OR h.status<>'ACTIVE' OR s.active_booking_id IS NOT NULL OR NOT EXISTS
 (SELECT 1 FROM seat_hold_items i WHERE i.hold_id=h.id AND i.concert_seat_id=s.id))) OR
 (s.state='BOOKED' AND (b.id IS NULL OR b.status<>'CONFIRMED' OR s.active_hold_id IS NOT NULL OR NOT EXISTS
 (SELECT 1 FROM booking_seats i WHERE i.booking_id=b.id AND i.concert_seat_id=s.id)))))
 + (SELECT COUNT(*) FROM seat_hold_items i JOIN seat_holds h ON h.id=i.hold_id JOIN concerts c ON c.id=h.concert_id JOIN concert_seats s ON s.id=i.concert_seat_id
 WHERE c.public_id LIKE 'r25_%' AND ((h.status='ACTIVE' AND (s.state<>'HELD' OR NOT(s.active_hold_id <=> h.id))) OR (h.status<>'ACTIVE' AND s.active_hold_id=h.id)))
 + (SELECT COUNT(*) FROM seat_holds h JOIN concerts c ON c.id=h.concert_id WHERE c.public_id LIKE 'r25_%' AND NOT EXISTS (SELECT 1 FROM seat_hold_items i WHERE i.hold_id=h.id))
 + (SELECT COUNT(*) FROM (SELECT h.user_id,h.concert_id FROM seat_holds h JOIN concerts c ON c.id=h.concert_id
 WHERE c.public_id LIKE 'r25_%' AND h.status='ACTIVE' GROUP BY h.user_id,h.concert_id HAVING COUNT(*)>1) x)
 + (SELECT COUNT(*) FROM (SELECT i.concert_seat_id FROM booking_seats i JOIN bookings b ON b.id=i.booking_id JOIN concerts c ON c.id=b.concert_id
 WHERE c.public_id LIKE 'r25_%' AND b.status='CONFIRMED' GROUP BY i.concert_seat_id HAVING COUNT(*)>1) x)
 + (SELECT COUNT(*) FROM booking_seats i JOIN bookings b ON b.id=i.booking_id JOIN concerts c ON c.id=b.concert_id JOIN concert_seats s ON s.id=i.concert_seat_id
 WHERE c.public_id LIKE 'r25_%' AND ((b.status='CONFIRMED' AND (s.state<>'BOOKED' OR NOT(s.active_booking_id <=> b.id))) OR
 (SELECT COUNT(*) FROM admission_tickets t WHERE t.booking_id=b.id AND t.concert_seat_id=s.id AND t.status=IF(b.status='CANCELLED','CANCELLED','ISSUED'))<>1))
 + (SELECT COUNT(*) FROM bookings b JOIN concerts c ON c.id=b.concert_id JOIN seat_holds h ON h.id=b.hold_id
 WHERE c.public_id LIKE 'r25_%' AND (h.status<>'CONVERTED' OR
 (SELECT COUNT(*) FROM booking_seats i WHERE i.booking_id=b.id)<>(SELECT COUNT(*) FROM seat_hold_items i WHERE i.hold_id=h.id) OR
 (SELECT COUNT(*) FROM outbox_events e WHERE e.aggregate_id=b.public_id AND e.event_type='BOOKING')<>1 OR
 (SELECT COUNT(*) FROM outbox_events e WHERE e.aggregate_id=b.public_id AND e.event_type='CANCEL')<>IF(b.status='CANCELLED',1,0)))
 + (SELECT COUNT(*) FROM outbox_events e JOIN demo_users u ON u.id=e.user_id WHERE u.public_user_id LIKE 'r25_%'
 AND NOT EXISTS (SELECT 1 FROM bookings b WHERE b.public_id=e.aggregate_id))
) AS result;
