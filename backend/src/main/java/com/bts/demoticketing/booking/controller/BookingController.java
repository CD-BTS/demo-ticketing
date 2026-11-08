package com.bts.demoticketing.booking.controller;

import java.net.InetAddress;
import java.util.List;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.bts.demoticketing.booking.dto.BookingDtos.*;
import com.bts.demoticketing.booking.service.ReservationService;
import com.bts.demoticketing.common.exception.ApiException;
import com.bts.demoticketing.common.response.*;
import com.bts.demoticketing.user.service.DemoAuthService;

@RestController
@RequestMapping("/api/v1")
public class BookingController {
    private final DemoAuthService auth;
    private final ReservationService reservations;
    public BookingController(DemoAuthService auth,ReservationService reservations){this.auth=auth;this.reservations=reservations;}
    @PostMapping("/concerts/{concertId}/seat-holds")
    public ResponseEntity<ApiResponse<HoldResponse>> hold(@PathVariable @NotBlank @Size(max=50) String concertId,
            @Valid @RequestBody HoldRequest body,HttpServletRequest request){
        var user=auth.require(request);
        HoldResponse hold=reservations.hold(concertId,user,body.seatIds(),body.attemptId(),body.transitionId());
        return ResponseEntity.status(201).body(ApiResponse.success(ResponseCode.CREATED,hold));
    }
    @DeleteMapping("/seat-holds/{holdId}")
    public ApiResponse<Void> release(@PathVariable @NotBlank @Size(max=50) String holdId,HttpServletRequest request){
        reservations.release(holdId,auth.require(request));return ApiResponse.success(null);
    }
    @GetMapping("/seat-holds/{holdId}")
    public ApiResponse<HoldResponse> getHold(@PathVariable @NotBlank @Size(max=50) String holdId,HttpServletRequest request){
        return ApiResponse.success(reservations.getHold(holdId,auth.require(request)));
    }
    @PostMapping("/bookings")
    public ResponseEntity<ApiResponse<BookingResponse>> confirm(@Valid @RequestBody ConfirmRequest body,HttpServletRequest request){
        var user=auth.require(request);
        var result=reservations.confirm(body.holdId(),body.paymentIdentifier(),peerIp(request),user);
        var code=result.created()?ResponseCode.CREATED:ResponseCode.SUCCESS;
        return ResponseEntity.status(code.getStatus()).body(ApiResponse.success(code,result.booking()));
    }
    @PostMapping("/bookings/{bookingId}/cancel")
    public ApiResponse<BookingResponse> cancel(@PathVariable @NotBlank @Size(max=100) String bookingId,HttpServletRequest request){
        return ApiResponse.success(reservations.cancel(bookingId,auth.require(request)));
    }
    @GetMapping("/bookings/me")
    public ApiResponse<List<BookingResponse>> mine(HttpServletRequest request){return ApiResponse.success(reservations.mine(auth.require(request)));}

    private static String peerIp(HttpServletRequest request){
        // No trusted proxy configuration exists: ignore every client-supplied forwarding header.
        String peer=request.getRemoteAddr();
        if(peer==null||!peer.matches("[0-9a-fA-F:.]+"))throw new ApiException(ResponseCode.INVALID_REQUEST);
        try{return InetAddress.getByName(peer).getHostAddress();}
        catch(java.net.UnknownHostException e){throw new ApiException(ResponseCode.INVALID_REQUEST);}
    }
}
