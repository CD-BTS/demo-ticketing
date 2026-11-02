package com.bts.demoticketing.user.repository;

import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import com.bts.demoticketing.user.entity.DemoUser;

@Repository
public class DemoUserRepository {
    private final JdbcTemplate jdbc;
    public DemoUserRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public DemoUser createOrGet(String publicId, String serviceId, LocalDateTime now) {
        jdbc.update("""
                INSERT INTO demo_users(public_user_id,bts_service_id,bts_external_user_id,created_at)
                VALUES(?,?,?,?) ON DUPLICATE KEY UPDATE id=id
                """, publicId, serviceId, publicId, now);
        return jdbc.query("SELECT * FROM demo_users WHERE public_user_id=?", (r,n) ->
                new DemoUser(r.getLong("id"),r.getString("public_user_id"),r.getString("bts_service_id"),
                        r.getString("bts_external_user_id"),r.getString("bts_user_id")), publicId).get(0);
    }

    public Optional<DemoUser> find(long id) {
        return jdbc.query("SELECT * FROM demo_users WHERE id=?", (r,n) ->
                new DemoUser(r.getLong("id"),r.getString("public_user_id"),r.getString("bts_service_id"),
                        r.getString("bts_external_user_id"),r.getString("bts_user_id")), id).stream().findFirst();
    }
    public void createSession(long userId, String hash, LocalDateTime expiry) {
        jdbc.update("INSERT INTO demo_sessions(user_id,token_hash,expires_at) VALUES(?,?,?)",userId,hash,expiry);
    }
    public Optional<DemoUser> authenticate(String hash, LocalDateTime now) {
        return jdbc.query("""
                SELECT u.* FROM demo_sessions s JOIN demo_users u ON u.id=s.user_id
                WHERE s.token_hash=? AND s.expires_at>?
                """, (r,n) -> new DemoUser(r.getLong("id"),r.getString("public_user_id"),r.getString("bts_service_id"),
                        r.getString("bts_external_user_id"),r.getString("bts_user_id")),hash,now).stream().findFirst();
    }
    public void mapBtsUser(long id, String btsId) {
        jdbc.update("UPDATE demo_users SET bts_user_id=? WHERE id=? AND bts_user_id IS NULL",btsId,id);
        if (!find(id).orElseThrow().btsUserId().equals(btsId)) {
            throw new IllegalStateException("BTS user mapping conflict");
        }
    }
}
