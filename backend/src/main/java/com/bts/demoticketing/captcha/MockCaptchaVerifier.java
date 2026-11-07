package com.bts.demoticketing.captcha;

import java.security.SecureRandom;

// Demonstration only: arithmetic challenges do not provide real bot protection.
public final class MockCaptchaVerifier implements CaptchaVerifier {
    private final SecureRandom random=new SecureRandom();
    public Problem create(){int a=random.nextInt(90)+10,b=random.nextInt(90)+10;return new Problem(a+" + "+b+" = ?",Integer.toString(a+b));}
    public boolean verify(String expectedDigest,String answer){return java.security.MessageDigest.isEqual(
            expectedDigest.getBytes(java.nio.charset.StandardCharsets.UTF_8),
            CaptchaService.digest(answer).getBytes(java.nio.charset.StandardCharsets.UTF_8));}
}
