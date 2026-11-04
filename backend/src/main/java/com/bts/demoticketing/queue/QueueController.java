package com.bts.demoticketing.queue;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.bts.demoticketing.common.response.*;
import com.bts.demoticketing.user.service.DemoAuthService;

@RestController
@RequestMapping("/api/v1/concerts/{concertId}/queue")
public class QueueController {
    private final DemoAuthService auth;
    private final QueueService queue;
    public QueueController(DemoAuthService auth,QueueService queue){this.auth=auth;this.queue=queue;}
    @PostMapping
    public ResponseEntity<ApiResponse<QueueStore.Snapshot>> join(
            @PathVariable @NotBlank @Size(max=50) String concertId,HttpServletRequest request){
        var result=queue.execute(concertId,auth.require(request).id(),"join");
        var code=result.created()?ResponseCode.CREATED:ResponseCode.SUCCESS;
        return ResponseEntity.status(code.getStatus()).body(ApiResponse.success(code,result.snapshot()));
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
