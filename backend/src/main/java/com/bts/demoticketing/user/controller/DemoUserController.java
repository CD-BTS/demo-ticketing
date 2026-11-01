package com.bts.demoticketing.user.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import com.bts.demoticketing.common.response.ApiResponse;
import com.bts.demoticketing.user.service.DemoAuthService;

@RestController
@RequestMapping("/api/v1/users")
public class DemoUserController {
    private final DemoAuthService auth;
    public DemoUserController(DemoAuthService auth){this.auth=auth;}
    public record LoginRequest(@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{3,30}") String userId){}
    @PostMapping public ApiResponse<DemoAuthService.Login> login(@Valid @RequestBody LoginRequest body){
        return ApiResponse.success(auth.login(body.userId()));
    }
}
