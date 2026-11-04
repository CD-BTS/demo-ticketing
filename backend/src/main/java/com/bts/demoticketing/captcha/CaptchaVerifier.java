package com.bts.demoticketing.captcha;

public interface CaptchaVerifier {
    record Problem(String question,String answer) {}
    Problem create();
    boolean verify(String expectedDigest,String answer);
}
