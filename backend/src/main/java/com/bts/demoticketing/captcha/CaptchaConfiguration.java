package com.bts.demoticketing.captcha;

import org.springframework.context.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@Configuration
public class CaptchaConfiguration {
    @Bean
    @ConditionalOnProperty(name="demo.captcha.mode",havingValue="mock")
    CaptchaVerifier mockCaptchaVerifier(){return new MockCaptchaVerifier();}
}
