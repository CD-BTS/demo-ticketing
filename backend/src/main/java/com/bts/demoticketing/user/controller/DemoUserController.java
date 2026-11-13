package com.bts.demoticketing.user.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import com.bts.demoticketing.common.response.ApiResponse;
import com.bts.demoticketing.integration.bts.AccountPolicyService;
import com.bts.demoticketing.user.service.DemoAuthService;

@RestController
@RequestMapping("/api/v1/users")
public class DemoUserController {
    private final DemoAuthService auth;
    private final AccountPolicyService policies;
    public DemoUserController(DemoAuthService auth,AccountPolicyService policies){this.auth=auth;this.policies=policies;}
    public record LoginRequest(@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{3,30}") String userId){}
    @PostMapping public ApiResponse<DemoAuthService.Login> login(@Valid @RequestBody LoginRequest body){
        return ApiResponse.success(auth.login(body.userId()));
    }
    /** 로그인 사용자에게 대기열 진입 시 적용되는 정책. 마이페이지 등급 표시가 이 값을 따른다. */
    @GetMapping("/me/policy") public ApiResponse<AccountPolicyService.View> policy(HttpServletRequest request){
        return ApiResponse.success(policies.view(auth.require(request)));
    }
}
