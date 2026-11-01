package com.bts.demoticketing.common.response;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

public enum ResponseCode {

	SUCCESS(HttpStatus.OK, "COMMON_200", "요청이 성공했습니다."),
	CREATED(HttpStatus.CREATED, "COMMON_201", "요청이 성공했습니다."),
	INVALID_REQUEST(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "요청 데이터가 올바르지 않습니다."),
	UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "인증이 필요합니다."),
	FORBIDDEN(HttpStatus.FORBIDDEN, "FORBIDDEN", "접근 권한이 없습니다."),
	RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "요청한 리소스를 찾을 수 없습니다."),
	METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", "지원하지 않는 요청 메서드입니다."),
	NOT_ACCEPTABLE(HttpStatus.NOT_ACCEPTABLE, "NOT_ACCEPTABLE", "지원하지 않는 응답 형식입니다."),
	DATA_CONFLICT(HttpStatus.CONFLICT, "DATA_CONFLICT", "요청이 현재 리소스 상태와 충돌합니다."),
	UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE", "지원하지 않는 요청 형식입니다."),
	INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_SERVER_ERROR", "서버 내부 오류가 발생했습니다."),
	SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE", "현재 서비스를 이용할 수 없습니다."),

	CONCERT_NOT_FOUND(HttpStatus.NOT_FOUND, "CONCERT_NOT_FOUND", "공연을 찾을 수 없습니다."),
	INVALID_SEAT_SELECTION(HttpStatus.BAD_REQUEST, "INVALID_SEAT_SELECTION", "좌석은 중복 없이 1~4개 선택해야 합니다."),
	SEAT_NOT_FOUND(HttpStatus.NOT_FOUND, "SEAT_NOT_FOUND", "좌석을 찾을 수 없습니다."),
	SEAT_ALREADY_TAKEN(HttpStatus.CONFLICT, "SEAT_ALREADY_TAKEN", "이미 선택된 좌석입니다."),
	HOLD_EXPIRED(HttpStatus.GONE, "HOLD_EXPIRED", "좌석 선점 시간이 만료되었습니다. 다시 선택해 주세요."),
	BOOKING_CONFLICT(HttpStatus.CONFLICT, "BOOKING_CONFLICT", "좌석 상태가 변경되어 예매할 수 없습니다."),
	BOOKING_NOT_FOUND(HttpStatus.NOT_FOUND, "BOOKING_NOT_FOUND", "예매 내역을 찾을 수 없습니다."),
	BOOKING_NOT_CANCELLABLE(HttpStatus.CONFLICT, "BOOKING_NOT_CANCELLABLE", "취소할 수 없는 예매입니다."),
	REQUEST_FAILED(HttpStatus.BAD_REQUEST, "REQUEST_FAILED", "요청을 처리할 수 없습니다.");

	private final HttpStatus status;
	private final String code;
	private final String message;

	ResponseCode(HttpStatus status, String code, String message) {
		this.status = status;
		this.code = code;
		this.message = message;
	}

	public HttpStatus getStatus() {
		return status;
	}

	public String getCode() {
		return code;
	}

	public String getMessage() {
		return message;
	}

	// HTTP 상태만 있는 Spring MVC 오류에는 도메인 코드 대신 기본 코드를 사용한다.
	public static ResponseCode forHttpStatus(HttpStatusCode status) {
		return switch (status.value()) {
			case 400 -> INVALID_REQUEST;
			case 401 -> UNAUTHORIZED;
			case 403 -> FORBIDDEN;
			case 404 -> RESOURCE_NOT_FOUND;
			case 405 -> METHOD_NOT_ALLOWED;
			case 406 -> NOT_ACCEPTABLE;
			case 409 -> DATA_CONFLICT;
			case 415 -> UNSUPPORTED_MEDIA_TYPE;
			case 503 -> SERVICE_UNAVAILABLE;
			default -> status.is5xxServerError() ? INTERNAL_SERVER_ERROR : REQUEST_FAILED;
		};
	}
}
