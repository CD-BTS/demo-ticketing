package com.bts.demoticketing.queue;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.bts.demoticketing.common.response.*;
import com.bts.demoticketing.user.service.DemoAuthService;
import com.bts.demoticketing.captcha.CaptchaService;
import jakarta.validation.Valid;
import com.bts.demoticketing.attempt.AttemptService;

@RestController
@RequestMapping("/api/v1/concerts/{concertId}/queue")
public class QueueController {
    private final DemoAuthService auth;
    private final QueueService queue;
    private final CaptchaService captcha;
    private final AttemptService attempts;
    public QueueController(DemoAuthService auth,QueueService queue,CaptchaService captcha,AttemptService attempts){this.auth=auth;this.queue=queue;this.captcha=captcha;this.attempts=attempts;}
    private void record(String attempt,String concert,com.bts.demoticketing.user.entity.DemoUser user,QueueStore.Snapshot snapshot){
        if(snapshot.status().equals("WAITING")||snapshot.status().equals("ADMITTED")){
            String generation=snapshot.expiresAt()==null?"none":Long.toString(snapshot.expiresAt().toEpochMilli());
            attempts.observe(attempt,user,concert,"queue:"+snapshot.status()+":"+generation,"QUEUE",
                snapshot.status().equals("ADMITTED")?"SUCCEEDED":"ENTERED");
        }
    }
    @PostMapping
    public ResponseEntity<ApiResponse<QueueStore.Snapshot>> join(
            @PathVariable @NotBlank @Size(max=50) String concertId,
            @RequestHeader(value="X-Captcha-Token",required=false) @Size(max=256) String captchaToken,
            @RequestHeader(value="X-Booking-Attempt",required=false) String attempt,HttpServletRequest request){
        var user=auth.require(request);if(attempt!=null)attempts.check(attempt,user,concertId,false);
        var result=queue.join(concertId,user,captchaToken);record(attempt,concertId,user,result.snapshot());
        var code=result.created()?ResponseCode.CREATED:ResponseCode.SUCCESS;
        return ResponseEntity.status(code.getStatus()).body(ApiResponse.success(code,result.snapshot()));
    }
    @PostMapping("/captcha-challenges")
    public ApiResponse<CaptchaService.Challenge> challenge(@PathVariable @NotBlank @Size(max=50) String concertId,
            @RequestHeader(value="X-Booking-Attempt",required=false) String attempt,HttpServletRequest request){
        var user=auth.require(request);
        if(attempt!=null)attempts.check(attempt,user,concertId,false);
        var challenge=captcha.challenge(concertId,user.id(),queue.captchaAction(concertId,user));
        attempts.observe(attempt,user,concertId,"captcha:challenge:"+challenge.challengeId(),"CAPTCHA","ENTERED");
        return ApiResponse.success(challenge);
    }
    @PostMapping("/captcha-verifications")
    public ApiResponse<CaptchaService.Grant> verifyCaptcha(@PathVariable @NotBlank @Size(max=50) String concertId,
            @Valid @RequestBody CaptchaService.Verification body,
            @RequestHeader(value="X-Booking-Attempt",required=false) String attempt,
            @RequestHeader(value="X-Attempt-Transition",required=false) String transition,HttpServletRequest request){
        var user=auth.require(request);
        if(attempt!=null)attempts.check(attempt,user,concertId,false);
        if(transition!=null)AttemptService.uuid(transition);
        queue.captchaAction(concertId,user);
        try{
            var grant=captcha.verify(concertId,user.id(),body,transition);
            attempts.observe(attempt,user,concertId,"captcha:verified:"+body.challengeId(),"CAPTCHA","SUCCEEDED");
            return ApiResponse.success(grant);
        }catch(com.bts.demoticketing.common.exception.ApiException e){
            if(transition!=null&&e.getResponseCode()==ResponseCode.CAPTCHA_INVALID)
                attempts.observe(attempt,user,concertId,"captcha:rejected:"+transition,"CAPTCHA","REJECTED","CAPTCHA_INVALID");
            throw e;
        }
    }
    @GetMapping("/me")
    public ApiResponse<QueueStore.Snapshot> status(
            @PathVariable @NotBlank @Size(max=50) String concertId,
            @RequestHeader(value="X-Booking-Attempt",required=false) String attempt,HttpServletRequest request){
        var user=auth.require(request);if(attempt!=null)attempts.check(attempt,user,concertId,false);
        var snapshot=queue.execute(concertId,user.id(),"status").snapshot();record(attempt,concertId,user,snapshot);
        return ApiResponse.success(snapshot);
    }
    @DeleteMapping("/me")
    public ApiResponse<Void> leave(
            @PathVariable @NotBlank @Size(max=50) String concertId,HttpServletRequest request){
        queue.execute(concertId,auth.require(request).id(),"leave");
        return ApiResponse.success(null);
    }
}
