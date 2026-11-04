package com.bts.demoticketing.queue;

import org.springframework.stereotype.Service;
import com.bts.demoticketing.concert.service.ConcertService;

@Service
public class QueueService {
    private final ConcertService concerts;
    private final QueueStore store;
    public QueueService(ConcertService concerts, QueueStore store){this.concerts=concerts;this.store=store;}
    public QueueStore.Result execute(String concert, long user, String operation){
        concerts.get(concert);
        return store.execute(concert,user,operation);
    }
}
