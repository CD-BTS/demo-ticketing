package com.bts.demoticketing.integration.bts;

import java.util.Map;
import com.bts.demoticketing.user.entity.DemoUser;

// Server configuration only. Public IDs are read from authenticated sessions.
public final class MockPolicyDecisionClient implements PolicyDecisionClient {
    private final Map<String,Action> actions;
    public MockPolicyDecisionClient(Map<String,Action> actions){this.actions=Map.copyOf(actions);}
    @Override public Action decide(DemoUser user){return actions.getOrDefault(user.publicId(),Action.ALLOW);}
}
