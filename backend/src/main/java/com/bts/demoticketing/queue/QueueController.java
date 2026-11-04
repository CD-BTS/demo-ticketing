package com.bts.demoticketing.queue;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.bts.demoticketing.common.response.*;
import com.bts.demoticketing.user.service.DemoAuthService;
import com.bts.demoticketing.captcha.CaptchaService;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/concerts/{concertId}/queue")
public class QueueController {
    private final DemoAuthService auth;
    private final QueueService queue;
    private final CaptchaService captcha;
    public QueueController(DemoAuthService auth,QueueService queue,CaptchaService captcha){this.auth=auth;this.queue=queue;this.captcha=captcha;}
    @PostMapping
    public ResponseEntity<ApiResponse<QueueStore.Snapshot>> join(
            @PathVariable @NotBlank @Size(max=50) String concertId,
            @RequestHeader(value="X-Captcha-Token",required=false) @Size(max=256) String captchaToken,HttpServletRequest request){
        var result=queue.join(concertId,auth.require(request),captchaToken);
        var code=result.created()?ResponseCode.CREATED:ResponseCode.SUCCESS;
        return ResponseEntity.status(code.getStatus()).body(ApiResponse.success(code,result.snapshot()));
    }
    @PostMapping("/captcha-challenges")
    public ApiResponse<CaptchaService.Challenge> challenge(@PathVariable @NotBlank @Size(max=50) String concertId,HttpServletRequest request){
        var user=auth.require(request);
        return ApiResponse.success(captcha.challenge(concertId,user.id(),queue.captchaAction(concertId,user)));
    }
    @PostMapping("/captcha-verifications")
    public ApiResponse<CaptchaService.Grant> verifyCaptcha(@PathVariable @NotBlank @Size(max=50) String concertId,
            @Valid @RequestBody CaptchaService.Verification body,HttpServletRequest request){
        var user=auth.require(request);
        queue.captchaAction(concertId,user);
        return ApiResponse.success(captcha.verify(concertId,user.id(),body));
    }
    @GetMapping("/me")
    public ApiResponse<QueueStore.Snapshot> status(
            @PathVariable @NotBlank @Size(max=50) String concertId,HttpServletRequest request){
        return ApiResponse.success(queue.execute(concertId,auth.require(request).id(),"status").snapshot());
    }
    @DeleteMapping("/me")
    public ApiResponse<Void> leave(
            @PathVariable @NotBlank @Size(max=50) String concertId,HttpServletRequest request){
        queue.execute(concertId,auth.require(request).id(),"leave");
        return ApiResponse.success(null);
    }
}
