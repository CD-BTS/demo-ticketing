package com.bts.demoticketing.integration.bts;

import java.util.HashMap;
import java.util.Map;
import tools.jackson.core.type.TypeReference;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import tools.jackson.databind.json.JsonMapper;

@Configuration
public class PolicyConfiguration {
    @Bean PolicyDecisionClient configuredPolicyDecisionClient(
            @Value("${demo.policy.mode:disabled}") String mode,
            @Value("${demo.policy.mock-actions:{}}") String actions,JsonMapper json,
            org.springframework.beans.factory.ObjectProvider<BtsClient> clients,
            org.springframework.beans.factory.ObjectProvider<com.bts.demoticketing.user.repository.DemoUserRepository> repositories){
        if(!mode.equals("bts"))return policyDecisionClient(mode,actions,json);
        return user->{
            var users=repositories.getObject();var bts=clients.getObject();
            String uuid=users.find(user.id()).orElseThrow().btsUserId();
            if(uuid==null){uuid=bts.register(user);users.mapBtsUser(user.id(),uuid);}
            return bts.policy(uuid);
        };
    }
    PolicyDecisionClient policyDecisionClient(String mode,String actions,JsonMapper json){
        if(mode.equals("disabled"))return user->PolicyDecisionClient.Action.ALLOW;
        if(!mode.equals("mock"))throw new IllegalArgumentException("Policy mode must be disabled or mock; real BTS policy integration is not implemented");
        var configured=json.readValue(actions,new TypeReference<Map<String,Object>>() {});
        var result=new HashMap<String,PolicyDecisionClient.Action>();
        configured.forEach((key,value)->result.put(key,PolicyDecisionClient.Action.valueOf((String)value)));
        return new MockPolicyDecisionClient(result);
    }
}
