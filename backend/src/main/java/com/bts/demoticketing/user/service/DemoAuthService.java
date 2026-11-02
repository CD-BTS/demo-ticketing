package com.bts.demoticketing.user.service;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.bts.demoticketing.common.exception.ApiException;
import com.bts.demoticketing.common.response.ResponseCode;
import com.bts.demoticketing.user.entity.DemoUser;
import com.bts.demoticketing.user.repository.DemoUserRepository;

@Service
public class DemoAuthService {
    private final DemoUserRepository users;
    private final Clock clock;
    private final String serviceId;
    public DemoAuthService(DemoUserRepository users, Clock clock,
            @Value("${bts.service-id}") String serviceId) {
        this.users=users; this.clock=clock; this.serviceId=serviceId;
    }
    public record Login(String userId, String token, OffsetDateTime expiresAt) {}

    // Shared demonstration identities, NOT proof of real account ownership.
    @Transactional
    public Login login(String publicId) {
        LocalDateTime now=LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        DemoUser user=users.createOrGet(publicId,serviceId,now);
        if (!user.serviceId().equals(serviceId)) throw new ApiException(ResponseCode.DATA_CONFLICT);
        byte[] bytes=new byte[32]; new SecureRandom().nextBytes(bytes);
        String token=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        LocalDateTime expiry=now.plusHours(24);
        users.createSession(user.id(),digest(token),expiry);
        return new Login(user.publicId(),token,expiry.atOffset(ZoneOffset.UTC));
    }

    @Transactional(readOnly=true)
    public DemoUser require(HttpServletRequest request) {
        DemoUser user=optional(request);
        if(user==null) throw new ApiException(ResponseCode.UNAUTHORIZED);
        return user;
    }

    @Transactional(readOnly=true)
    public DemoUser optional(HttpServletRequest request) {
        List<String> headers=Collections.list(request.getHeaders("Authorization"));
        if(headers.isEmpty()) return null;
        if(headers.size()!=1 || !headers.get(0).matches("Bearer [A-Za-z0-9_-]{43}"))
            throw new ApiException(ResponseCode.UNAUTHORIZED);
        return users.authenticate(digest(headers.get(0).substring(7)),
                LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC))
                .orElseThrow(()->new ApiException(ResponseCode.UNAUTHORIZED));
    }

    private static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch(NoSuchAlgorithmException e){ throw new IllegalStateException(e); }
    }
}
