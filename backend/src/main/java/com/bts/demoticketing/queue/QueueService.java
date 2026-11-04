package com.bts.demoticketing.queue;

import org.springframework.stereotype.Service;
import com.bts.demoticketing.concert.service.ConcertService;
import com.bts.demoticketing.common.exception.ApiException;
import com.bts.demoticketing.common.response.ResponseCode;

@Service
public class QueueService {
    private final ConcertService concerts;
    private final QueueStore store;
    public QueueService(ConcertService concerts, QueueStore store){this.concerts=concerts;this.store=store;}
    public QueueStore.Result execute(String concert, long user, String operation){
        concerts.get(concert);
        return store.execute(concert,user,operation);
    }
    public void requireAdmitted(String concert, long user){
        // The existing Lua status operation expires stale admissions and promotes FIFO waiters
        // using Redis TIME. Never authorize from a cached status or a separate ZSET lookup.
        if(!"ADMITTED".equals(execute(concert,user,"status").snapshot().status()))
            throw new ApiException(ResponseCode.QUEUE_NOT_ADMITTED);
    }
}
