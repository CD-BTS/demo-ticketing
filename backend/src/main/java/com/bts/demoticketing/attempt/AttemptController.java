package com.bts.demoticketing.attempt;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import com.bts.demoticketing.common.response.ApiResponse;
import com.bts.demoticketing.user.service.DemoAuthService;

@RestController
@RequestMapping("/api/v1")
public class AttemptController {
    private final AttemptService service;private final DemoAuthService auth;
    public AttemptController(AttemptService service,DemoAuthService auth){this.service=service;this.auth=auth;}
    public record Start(@Size(max=40) String clientOccurredAt){}
    public record Transition(@NotBlank String transitionId,@NotBlank String stage,@NotBlank String status){}
    public record Capabilities(boolean enabled){}
    @GetMapping("/booking-attempts/capabilities")
    public ApiResponse<Capabilities> capabilities(){return ApiResponse.success(new Capabilities(service.enabled()));}
    @PutMapping("/concerts/{concertId}/booking-attempts/{attemptId}")
    public ApiResponse<AttemptService.Snapshot> start(@PathVariable @Size(max=50) String concertId,@PathVariable String attemptId,
            @Valid @RequestBody Start body,HttpServletRequest request){
        return ApiResponse.success(service.start(concertId,attemptId,auth.require(request),body.clientOccurredAt()));
    }
    @GetMapping("/booking-attempts/{attemptId}")
    public ApiResponse<AttemptService.Snapshot> get(@PathVariable String attemptId,HttpServletRequest request){
        return ApiResponse.success(service.get(attemptId,auth.require(request)));
    }
    @PostMapping("/booking-attempts/{attemptId}/transitions")
    public ApiResponse<AttemptService.Snapshot> transition(@PathVariable String attemptId,@Valid @RequestBody Transition body,HttpServletRequest request){
        return ApiResponse.success(service.client(attemptId,auth.require(request),body.transitionId(),body.stage(),body.status()));
    }
}
