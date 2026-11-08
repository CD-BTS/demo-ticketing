package com.bts.demoticketing.concert;

import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Tag("mysql")
@SpringBootTest(properties={"demo.jobs.enabled=false","bts.enabled=false"})
@AutoConfigureMockMvc
class ConcertApiMySqlTests {
    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;

    @BeforeEach
    void enforceDemoDatabase() throws Exception {
        try (var connection = dataSource.getConnection()) {
            assertEquals("demo_ticketing_db", connection.getCatalog(), "Never test against the BTS database");
        }
    }

    @Test
    void databaseSeedAndJsonMatchFrontend() throws Exception {
        mvc.perform(get("/api/v1/concerts"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("COMMON_200"))
                .andExpect(jsonPath("$.data[*].id", hasItems("c1", "c2", "c3")));
        for (String id : List.of("c1", "c2", "c3")) {
            mvc.perform(get("/api/v1/concerts/" + id))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.priceFrom").value(99000));
            mvc.perform(get("/api/v1/concerts/" + id + "/seats"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data", hasSize(96)))
                    .andExpect(jsonPath("$.data[0].id").value("A-01"))
                    .andExpect(jsonPath("$.data[95].id").value("H-12"))
                    .andExpect(jsonPath("$.data[0].heldByMe").value(false));
            assertEquals(24L, countGrade(id, "VIP"));
            assertEquals(36L, countGrade(id, "R"));
            assertEquals(36L, countGrade(id, "S"));
        }
        Long mismatch = jdbc.queryForObject("""
                SELECT COUNT(*) FROM concerts c WHERE c.price_from <>
                (SELECT MIN(s.price) FROM concert_seats s WHERE s.concert_id=c.id)
                """, Long.class);
        assertEquals(0L, mismatch);
    }

    @Test
    void sqlSchemaHasRequiredIntegrityConstraints() {
        assertEquals(11, jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.TABLES
                WHERE TABLE_SCHEMA='demo_ticketing_db' AND TABLE_TYPE='BASE TABLE'
                  AND TABLE_NAME IN ('demo_users','demo_sessions','concerts','concert_seats',
                    'seat_holds','seat_hold_items','bookings','booking_seats','admission_tickets',
                    'outbox_events','reservation_user_concert_guards')
                """, Integer.class));
        for (String type : List.of("PRIMARY KEY", "FOREIGN KEY", "UNIQUE", "CHECK")) {
            assertTrue(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
                    WHERE TABLE_SCHEMA='demo_ticketing_db' AND CONSTRAINT_TYPE=?
                    """, Integer.class, type) > 0, type);
        }
    }

    @Test
    void unknownConcertDoesNotInventSeats() throws Exception {
        mvc.perform(get("/api/v1/concerts/nonexistent"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("CONCERT_NOT_FOUND"));
        mvc.perform(get("/api/v1/concerts/nonexistent/seats"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("CONCERT_NOT_FOUND"));
    }

    private long countGrade(String concert, String grade) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM concert_seats s JOIN concerts c ON c.id=s.concert_id
                WHERE c.public_id=? AND s.grade=?
                """, Long.class, concert, grade);
    }
}
