package com.bts.demoticketing.attempt;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.*;
import org.springframework.transaction.*;
import org.springframework.transaction.support.SimpleTransactionStatus;
import tools.jackson.databind.json.JsonMapper;
import com.bts.demoticketing.user.entity.DemoUser;
import com.bts.demoticketing.outbox.OutboxRepository;
import com.bts.demoticketing.common.exception.ApiException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AttemptServiceTests {
    final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    final OutboxRepository outbox=mock(OutboxRepository.class);
    final PlatformTransactionManager manager=mock(PlatformTransactionManager.class);
    final JsonMapper json=JsonMapper.builder().build();
    final String id=UUID.randomUUID().toString();
    final DemoUser user=new DemoUser(1,"alice","service","alice",null);
    AttemptService service(boolean enabled){
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        return new AttemptService(jdbc,json,Clock.fixed(Instant.parse("2026-11-08T00:00:00Z"),ZoneOffset.UTC),outbox,manager,enabled);
    }
    void owner(long owner){
        when(jdbc.queryForList(contains("SELECT a.*"),eq(id))).thenReturn(List.of(Map.of("user_id",owner,"service_id","service","concert","c1")));
    }
    @Test void disabledModeDoesNotTouchLegacyDatabase(){
        var s=service(false);assertNull(s.forHold(1));assertNull(s.forBooking(1));s.materialize();
        assertThrows(ApiException.class,()->s.start("c1",id,user,null));verifyNoInteractions(jdbc,outbox);
    }
    @Test void ownershipAndConcertCannotBeRebound(){
        var s=service(true);owner(2);
        assertEquals("FORBIDDEN",assertThrows(ApiException.class,()->s.check(id,user,"c1",false)).getResponseCode().getCode());
        owner(1);assertEquals("DATA_CONFLICT",assertThrows(ApiException.class,()->s.check(id,user,"c2",false)).getResponseCode().getCode());
        verifyNoInteractions(outbox);
    }
    @Test void retriedTransitionReturnsExistingStateWithoutCreatingAnEvent(){
        var s=service(true);owner(1);
        String transition=UUID.randomUUID().toString();
        String hash=AttemptService.fingerprint("PAYMENT|ENTERED|CLIENT_REPORT|{}");
        when(jdbc.queryForList(contains("SELECT request_fingerprint"),eq(id),eq(transition))).thenReturn(List.of(Map.of("request_fingerprint",hash)));
        var snapshot=new AttemptService.Snapshot(id,"c1","COMPLETED","SUCCEEDED",8,"h1","b1");
        when(jdbc.query(contains("LEFT JOIN seat_holds"),org.mockito.ArgumentMatchers.<RowMapper<AttemptService.Snapshot>>any(),eq(id))).thenReturn(List.of(snapshot));
        assertEquals(snapshot,s.client(id,user,transition,"PAYMENT","ENTERED"));
        verify(jdbc,never()).update(anyString(),org.mockito.ArgumentMatchers.<Object[]>any());
        verifyNoInteractions(outbox);
    }
    @Test void changedPayloadWithSameTransitionConflicts(){
        var s=service(true);owner(1);String transition=UUID.randomUUID().toString();
        when(jdbc.queryForList(contains("SELECT request_fingerprint"),eq(id),eq(transition))).thenReturn(List.of(Map.of("request_fingerprint","different")));
        assertEquals("DATA_CONFLICT",assertThrows(ApiException.class,()->s.client(id,user,transition,"PAYMENT","ENTERED")).getResponseCode().getCode());
    }
    @Test void queueObservationFailureDoesNotFailQueueOperation(){
        var s=service(true);when(jdbc.queryForList(contains("SELECT a.*"),eq(id))).thenThrow(new IllegalStateException());
        assertDoesNotThrow(()->s.observe(id,user,"c1","queue:registered","QUEUE","ENTERED"));
    }
}
