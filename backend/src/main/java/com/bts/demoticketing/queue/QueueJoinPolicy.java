package com.bts.demoticketing.queue;

import com.bts.demoticketing.integration.bts.PolicyDecisionClient.Action;

// Keep the temporary action mapping independent of the Redis storage algorithm.
public record QueueJoinPolicy(boolean captchaRequired,boolean delayed,boolean restricted) {
    public static QueueJoinPolicy forAction(Action action){
        return switch(action){
            case ALLOW -> new QueueJoinPolicy(false,false,false);
            case REQUIRE_CAPTCHA -> new QueueJoinPolicy(true,false,false);
            case DELAY -> new QueueJoinPolicy(true,true,false);
            case RESTRICT -> new QueueJoinPolicy(false,false,true);
        };
    }
}
