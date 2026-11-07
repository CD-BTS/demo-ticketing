package com.bts.demoticketing.integration.bts;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import com.bts.demoticketing.user.entity.DemoUser;
import com.bts.demoticketing.queue.QueueJoinPolicy;
import static org.junit.jupiter.api.Assertions.*;

class PolicyConfigurationTests {
    final PolicyConfiguration config=new PolicyConfiguration();
    final JsonMapper json=JsonMapper.builder().build();
    final DemoUser user=new DemoUser(1,"demo-user","demo","demo-user",null);
    @Test void mockIsExplicitAndUsesServerIdentityWithoutBtsUuid(){
        var client=config.policyDecisionClient("mock","{\"demo-user\":\"DELAY\"}",json);
        assertEquals(PolicyDecisionClient.Action.DELAY,client.decide(user));
        assertEquals(PolicyDecisionClient.Action.ALLOW,client.decide(new DemoUser(2,"other","demo","other",null)));
        assertEquals(PolicyDecisionClient.Action.ALLOW,config.policyDecisionClient("disabled","{}",json).decide(user));
    }
    @Test void unsupportedRealModeAndUnknownActionsFailAtConfigurationTime(){
        assertThrows(IllegalArgumentException.class,()->config.policyDecisionClient("http","{}",json));
        assertThrows(IllegalArgumentException.class,()->config.policyDecisionClient("mock","{\"demo-user\":\"UNKNOWN\"}",json));
    }
    @Test void actionMappingKeepsCaptchaAndPriorityIndependent(){
        assertEquals(new QueueJoinPolicy(false,false,false),QueueJoinPolicy.forAction(PolicyDecisionClient.Action.ALLOW));
        assertEquals(new QueueJoinPolicy(true,false,false),QueueJoinPolicy.forAction(PolicyDecisionClient.Action.REQUIRE_CAPTCHA));
        assertEquals(new QueueJoinPolicy(true,true,false),QueueJoinPolicy.forAction(PolicyDecisionClient.Action.DELAY));
        assertEquals(new QueueJoinPolicy(false,false,true),QueueJoinPolicy.forAction(PolicyDecisionClient.Action.RESTRICT));
    }
}
