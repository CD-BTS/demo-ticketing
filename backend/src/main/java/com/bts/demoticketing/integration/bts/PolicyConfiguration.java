package com.bts.demoticketing.integration.bts;

import java.util.HashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import tools.jackson.databind.json.JsonMapper;

@Configuration
public class PolicyConfiguration {
    @Bean PolicyDecisionClient policyDecisionClient(
            @Value("${demo.policy.mode:disabled}") String mode,
            @Value("${demo.policy.mock-actions:{}}") String actions,JsonMapper json){
        if(mode.equals("disabled"))return user->PolicyDecisionClient.Action.ALLOW;
        if(!mode.equals("mock"))throw new IllegalArgumentException("Policy mode must be disabled or mock; real BTS policy integration is not implemented");
        var configured=json.readValue(actions,java.util.Map.class);
        var result=new HashMap<String,PolicyDecisionClient.Action>();
        configured.forEach((key,value)->result.put((String)key,PolicyDecisionClient.Action.valueOf((String)value)));
        return new MockPolicyDecisionClient(result);
    }
}
