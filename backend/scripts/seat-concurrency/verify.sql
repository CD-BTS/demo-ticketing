-- __CONCERT__는 실행기가 생성하고 검증한 전용 공연 ID로만 치환한다.
SET @concert = (SELECT id FROM concerts WHERE public_id='__CONCERT__');
SELECT JSON_OBJECT(
 'held', (SELECT COUNT(*) FROM concert_seats WHERE concert_id=@concert AND state='HELD'),
 'booked', (SELECT COUNT(*) FROM concert_seats WHERE concert_id=@concert AND state='BOOKED'),
 'holds', (SELECT COUNT(*) FROM seat_holds WHERE concert_id=@concert),
 'activeHolds', (SELECT COUNT(*) FROM seat_holds WHERE concert_id=@concert AND status='ACTIVE'),
 'bookings', (SELECT COUNT(*) FROM bookings WHERE concert_id=@concert),
 'tickets', (SELECT COUNT(*) FROM admission_tickets t JOIN bookings b ON b.id=t.booking_id WHERE b.concert_id=@concert),
 'outbox', (SELECT COUNT(*) FROM outbox_events e JOIN bookings b ON b.public_id=e.aggregate_id WHERE b.concert_id=@concert),
 'violations',
 (SELECT COUNT(*) FROM concert_seats s LEFT JOIN seat_holds h ON h.id=s.active_hold_id LEFT JOIN bookings b ON b.id=s.active_booking_id
  WHERE s.concert_id=@concert AND (
   (s.state='AVAILABLE' AND (s.active_hold_id IS NOT NULL OR s.active_booking_id IS NOT NULL)) OR
   (s.state='HELD' AND (h.id IS NULL OR h.status<>'ACTIVE' OR s.active_booking_id IS NOT NULL OR NOT EXISTS
      (SELECT 1 FROM seat_hold_items i WHERE i.hold_id=h.id AND i.concert_seat_id=s.id))) OR
   (s.state='BOOKED' AND (b.id IS NULL OR b.status<>'CONFIRMED' OR s.active_hold_id IS NOT NULL OR NOT EXISTS
      (SELECT 1 FROM booking_seats i WHERE i.booking_id=b.id AND i.concert_seat_id=s.id)))))
 + (SELECT COUNT(*) FROM seat_hold_items i JOIN seat_holds h ON h.id=i.hold_id JOIN concert_seats s ON s.id=i.concert_seat_id
    WHERE h.concert_id=@concert AND h.status='ACTIVE' AND (s.state<>'HELD' OR NOT(s.active_hold_id <=> h.id)))
 + (SELECT COUNT(*) FROM (SELECT user_id FROM seat_holds WHERE concert_id=@concert AND status='ACTIVE' GROUP BY user_id HAVING COUNT(*)>1) x)
 + (SELECT COUNT(*) FROM (SELECT i.concert_seat_id FROM booking_seats i JOIN bookings b ON b.id=i.booking_id
    WHERE b.concert_id=@concert AND b.status='CONFIRMED' GROUP BY i.concert_seat_id HAVING COUNT(*)>1) x)
 + (SELECT COUNT(*) FROM booking_seats i JOIN bookings b ON b.id=i.booking_id JOIN concert_seats s ON s.id=i.concert_seat_id
    WHERE b.concert_id=@concert AND (b.status<>'CONFIRMED' OR s.state<>'BOOKED' OR NOT(s.active_booking_id <=> b.id) OR
      (SELECT COUNT(*) FROM admission_tickets t WHERE t.booking_id=b.id AND t.concert_seat_id=s.id AND t.status='ISSUED')<>1))
 + (SELECT COUNT(*) FROM bookings b JOIN seat_holds h ON h.id=b.hold_id WHERE b.concert_id=@concert AND
    (h.status<>'CONVERTED' OR (SELECT COUNT(*) FROM outbox_events e WHERE e.aggregate_id=b.public_id AND e.event_type='BOOKING')<>1)),
 'seats', (SELECT JSON_ARRAYAGG(JSON_OBJECT('id',id,'seat',seat_code,'state',state,'hold',active_hold_id,'booking',active_booking_id))
           FROM concert_seats WHERE concert_id=@concert AND state<>'AVAILABLE')
) AS result;
