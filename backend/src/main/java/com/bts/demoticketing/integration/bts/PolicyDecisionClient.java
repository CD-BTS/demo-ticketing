package com.bts.demoticketing.integration.bts;

import com.bts.demoticketing.user.entity.DemoUser;

public interface PolicyDecisionClient {
    enum Action { ALLOW, REQUIRE_CAPTCHA, DELAY, RESTRICT }
    Action decide(DemoUser user);
}
