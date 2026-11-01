package com.bts.demoticketing.booking.repository;

import java.math.BigDecimal;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.support.*;
import org.springframework.stereotype.Repository;
import com.bts.demoticketing.booking.entity.ReservationRecords.*;

@Repository
public class ReservationRepository {
    private final JdbcTemplate jdbc;
    public ReservationRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
    // DATETIME stores a UTC wall-clock value. Timestamp.toLocalDateTime() would apply the JVM zone again.
    private static LocalDateTime date(ResultSet r,String name)throws SQLException{return r.getObject(name,LocalDateTime.class);}
    private final RowMapper<Concert> concert=(r,n)->new Concert(r.getLong("id"),r.getString("public_id"),r.getString("title"),r.getString("venue"),date(r,"start_at"),date(r,"end_at"));
    private final RowMapper<Hold> hold=(r,n)->new Hold(r.getLong("id"),r.getString("public_id"),r.getLong("user_id"),r.getLong("concert_id"),r.getString("status"),date(r,"expires_at"),date(r,"created_at"));
    private final RowMapper<Seat> seat=(r,n)->new Seat(r.getLong("id"),r.getString("seat_code"),r.getString("grade"),r.getLong("price"),r.getString("state"),r.getObject("active_hold_id",Long.class),r.getObject("active_booking_id",Long.class));
    private final RowMapper<Booking> booking=(r,n)->new Booking(r.getLong("id"),r.getString("public_id"),r.getLong("user_id"),r.getLong("concert_id"),r.getLong("hold_id"),r.getString("status"),r.getLong("total_price"),r.getString("concert_title_snapshot"),r.getString("venue_snapshot"),date(r,"start_at_snapshot"),date(r,"end_at_snapshot"),date(r,"created_at"),date(r,"confirmed_at"),r.getBigDecimal("booking_duration_seconds"),r.getString("payment_identifier"),r.getString("ip_address"));

    public Optional<Concert> lockConcert(String publicId){return jdbc.query("SELECT * FROM concerts WHERE public_id=? FOR UPDATE",concert,publicId).stream().findFirst();}
    public Concert lockConcert(long id){return jdbc.query("SELECT * FROM concerts WHERE id=? FOR UPDATE",concert,id).get(0);}
    public Concert concert(long id){return jdbc.query("SELECT * FROM concerts WHERE id=?",concert,id).get(0);}
    public List<com.bts.demoticketing.seat.dto.SeatResponse> visibleSeats(String concertId,Long userId,LocalDateTime now){
        return jdbc.query("""
                SELECT s.*, h.user_id AS hold_owner, h.expires_at AS hold_expiry
                FROM concert_seats s JOIN concerts c ON c.id=s.concert_id
                LEFT JOIN seat_holds h ON h.id=s.active_hold_id
                WHERE c.public_id=? ORDER BY s.row_label,s.seat_number
                """,(r,n)->{
                    String state=r.getString("state");
                    LocalDateTime expiry=r.getObject("hold_expiry",LocalDateTime.class);
                    boolean expired=state.equals("HELD")&&expiry!=null&&!expiry.isAfter(now);
                    boolean mine=!expired&&state.equals("HELD")&&userId!=null&&r.getLong("hold_owner")==userId;
                    return new com.bts.demoticketing.seat.dto.SeatResponse(r.getString("seat_code"),r.getString("row_label"),
                            r.getInt("seat_number"),com.bts.demoticketing.seat.entity.SeatGrade.valueOf(r.getString("grade")),
                            r.getLong("price"),com.bts.demoticketing.seat.entity.SeatStatus.valueOf(expired?"AVAILABLE":state),mine);
                },concertId);
    }
    public Optional<Hold> hold(String id){return jdbc.query("SELECT * FROM seat_holds WHERE public_id=?",hold,id).stream().findFirst();}
    public Optional<Hold> activeHold(long user,long concert){return jdbc.query("SELECT * FROM seat_holds WHERE user_id=? AND concert_id=? AND status='ACTIVE' ORDER BY id FOR UPDATE",hold,user,concert).stream().findFirst();}
    public List<Hold> expired(long concert,LocalDateTime now){return jdbc.query("SELECT * FROM seat_holds WHERE concert_id=? AND status='ACTIVE' AND expires_at<=? ORDER BY id FOR UPDATE",hold,concert,now);}
    public List<Long> expiredConcerts(LocalDateTime now){return jdbc.queryForList("SELECT DISTINCT concert_id FROM seat_holds WHERE status='ACTIVE' AND expires_at<=?",Long.class,now);}
    public List<Seat> seats(long concert,List<String> codes){
        String placeholders=String.join(",",Collections.nCopies(codes.size(),"?"));
        List<Object> values=new ArrayList<>(); values.add(concert); values.addAll(codes);
        return jdbc.query("SELECT * FROM concert_seats WHERE concert_id=? AND seat_code IN ("+placeholders+") ORDER BY id FOR UPDATE",seat,values.toArray());
    }
    public List<Seat> heldSeats(long holdId){return jdbc.query("""
            SELECT s.* FROM concert_seats s JOIN seat_hold_items i ON i.concert_seat_id=s.id
            WHERE i.hold_id=? ORDER BY s.id FOR UPDATE
            """,seat,holdId);}
    public List<String> holdCodes(long holdId){return jdbc.queryForList("""
            SELECT s.seat_code FROM seat_hold_items i JOIN concert_seats s ON s.id=i.concert_seat_id
            WHERE i.hold_id=? ORDER BY s.seat_code
            """,String.class,holdId);}
    public void endHold(long holdId,String state){
        jdbc.update("UPDATE concert_seats SET state='AVAILABLE',active_hold_id=NULL,version=version+1 WHERE active_hold_id=?",holdId);
        jdbc.update("UPDATE seat_holds SET status=? WHERE id=? AND status='ACTIVE'",state,holdId);
    }
    public Hold createHold(String publicId,long user,long concert,LocalDateTime now,LocalDateTime expiry,List<Seat> seats){
        long id=insert("INSERT INTO seat_holds(public_id,user_id,concert_id,status,created_at,expires_at) VALUES(?,?,?,'ACTIVE',?,?)",publicId,user,concert,now,expiry);
        for(Seat seat:seats){
            jdbc.update("INSERT INTO seat_hold_items(hold_id,concert_seat_id,price_snapshot) VALUES(?,?,?)",id,seat.id(),seat.price());
            int changed=jdbc.update("UPDATE concert_seats SET state='HELD',active_hold_id=?,version=version+1 WHERE id=? AND state='AVAILABLE'",id,seat.id());
            if(changed!=1)throw new IllegalStateException("Seat lock invariant violated");
        }
        return new Hold(id,publicId,user,concert,"ACTIVE",expiry,now);
    }
    public Optional<Booking> byHold(long holdId){return jdbc.query("SELECT * FROM bookings WHERE hold_id=?",booking,holdId).stream().findFirst();}
    public Optional<Booking> booking(String publicId){return jdbc.query("SELECT * FROM bookings WHERE public_id=?",booking,publicId).stream().findFirst();}
    public List<Booking> bookings(long user){return jdbc.query("SELECT * FROM bookings WHERE user_id=? ORDER BY created_at DESC,id DESC",booking,user);}
    public List<String> bookingCodes(long id){return jdbc.queryForList("SELECT seat_code_snapshot FROM booking_seats WHERE booking_id=? ORDER BY seat_code_snapshot",String.class,id);}
    public long holdTotal(long hold){return jdbc.queryForObject("SELECT SUM(price_snapshot) FROM seat_hold_items WHERE hold_id=?",Long.class,hold);}
    public Booking createBooking(String publicId,Hold h,Concert c,LocalDateTime now,BigDecimal duration,String payment,String ip,List<Seat> seats){
        long total=holdTotal(h.id());
        long id=insert("""
                INSERT INTO bookings(public_id,user_id,concert_id,hold_id,status,total_price,concert_title_snapshot,
                venue_snapshot,start_at_snapshot,end_at_snapshot,booking_started_at,confirmed_at,booking_duration_seconds,
                payment_identifier,ip_address,created_at) VALUES(?,?,?,?,'CONFIRMED',?,?,?,?,?,?,?,?,?,?,?)
                """,publicId,h.userId(),c.id(),h.id(),total,c.title(),c.venue(),c.startAt(),c.endAt(),h.createdAt(),now,duration,payment,ip,now);
        jdbc.update("""
                INSERT INTO booking_seats(booking_id,concert_seat_id,seat_code_snapshot,grade_snapshot,price_snapshot)
                SELECT ?,s.id,s.seat_code,s.grade,i.price_snapshot FROM seat_hold_items i
                JOIN concert_seats s ON s.id=i.concert_seat_id WHERE i.hold_id=?
                """,id,h.id());
        for(Seat s:seats){
            jdbc.update("INSERT INTO admission_tickets(public_id,booking_id,concert_seat_id,status) VALUES(?,?,?,'ISSUED')",UUID.randomUUID().toString(),id,s.id());
        }
        int changed=jdbc.update("UPDATE concert_seats SET state='BOOKED',active_hold_id=NULL,active_booking_id=?,version=version+1 WHERE active_hold_id=? AND state='HELD'",id,h.id());
        if(changed!=seats.size())throw new IllegalStateException("Hold occupancy invariant violated");
        jdbc.update("UPDATE seat_holds SET status='CONVERTED' WHERE id=?",h.id());
        return booking(publicId).orElseThrow();
    }
    public List<String> ticketIds(long booking){return jdbc.queryForList("SELECT public_id FROM admission_tickets WHERE booking_id=? ORDER BY concert_seat_id",String.class,booking);}
    public boolean checkedIn(long booking){return jdbc.queryForObject("SELECT COUNT(*) FROM admission_tickets WHERE booking_id=? AND status='CHECKED_IN'",Long.class,booking)>0;}
    public void cancel(Booking b,LocalDateTime now){
        jdbc.update("UPDATE bookings SET status='CANCELLED',cancelled_at=? WHERE id=? AND status='CONFIRMED'",now,b.id());
        jdbc.update("UPDATE admission_tickets SET status='CANCELLED' WHERE booking_id=? AND status='ISSUED'",b.id());
        jdbc.update("UPDATE concert_seats SET state='AVAILABLE',active_booking_id=NULL,version=version+1 WHERE active_booking_id=?",b.id());
    }
    private long insert(String sql,Object... args){
        KeyHolder keys=new GeneratedKeyHolder();
        jdbc.update(connection->{PreparedStatement s=connection.prepareStatement(sql,Statement.RETURN_GENERATED_KEYS); for(int i=0;i<args.length;i++)s.setObject(i+1,args[i]); return s;},keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }
}
