package com.bts.demoticketing.queue;

import org.springframework.stereotype.Service;
import com.bts.demoticketing.concert.service.ConcertService;
import com.bts.demoticketing.common.exception.ApiException;
import com.bts.demoticketing.common.response.ResponseCode;
import com.bts.demoticketing.integration.bts.PolicyDecisionClient;
import com.bts.demoticketing.user.entity.DemoUser;
import com.bts.demoticketing.user.repository.DemoUserRepository;

@Service
public class QueueService {
    private final ConcertService concerts;
    private final QueueStore store;
    private final PolicyDecisionClient policies;
    private final DemoUserRepository users;
    public QueueService(ConcertService concerts, QueueStore store,PolicyDecisionClient policies,DemoUserRepository users){
        this.concerts=concerts;this.store=store;this.policies=policies;this.users=users;
    }
    public QueueStore.Result execute(String concert, long user, String operation){
        if(operation.equals("join"))return join(concert,users.find(user).orElseThrow(()->new ApiException(ResponseCode.UNAUTHORIZED)),null);
        concerts.get(concert);
        return store.execute(concert,user,operation);
    }
    private static boolean active(QueueStore.Result result){return result.snapshot().status().equals("WAITING")||result.snapshot().status().equals("ADMITTED");}
    public PolicyDecisionClient.Action decision(DemoUser user){
        try{var action=policies.decide(user);if(action==null)throw new IllegalStateException("Missing action");return action;}
        catch(RuntimeException e){throw new ApiException(ResponseCode.SERVICE_UNAVAILABLE);}
    }
    public QueueStore.Result join(String concert,DemoUser user,String captchaToken){
        concerts.get(concert);
        var current=store.execute(concert,user.id(),"status");
        if(active(current))return current;
        PolicyDecisionClient.Action action;
        try{action=decision(user);}
        catch(ApiException failure){
            // Another concurrent request may already have admitted/registered this user.
            var raced=store.execute(concert,user.id(),"status");
            if(active(raced))return raced;
            throw failure;
        }
        return store.join(concert,user.id(),action.name(),captchaToken);
    }
    public PolicyDecisionClient.Action captchaAction(String concert,DemoUser user){
        concerts.get(concert);
        var action=decision(user);
        var policy=QueueJoinPolicy.forAction(action);
        if(policy.restricted())throw new ApiException(ResponseCode.QUEUE_RESTRICTED);
        if(!policy.captchaRequired())throw new ApiException(ResponseCode.INVALID_REQUEST);
        return action;
    }
    public void requireAdmitted(String concert, long user){
        // The existing Lua status operation expires stale admissions and promotes FIFO waiters
        // using Redis TIME. Never authorize from a cached status or a separate ZSET lookup.
        if(!"ADMITTED".equals(execute(concert,user,"status").snapshot().status()))
            throw new ApiException(ResponseCode.QUEUE_NOT_ADMITTED);
    }
}
