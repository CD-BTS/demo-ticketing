package com.bts.demoticketing.common.response;

import org.springframework.http.HttpStatusCode;

public record ApiResponse<T>(boolean success, String code, T data, String message) {

	public static <T> ApiResponse<T> success(T data) {
		return success(ResponseCode.SUCCESS, data);
	}

	public static <T> ApiResponse<T> success(ResponseCode responseCode, T data) {
		if (!responseCode.getStatus().is2xxSuccessful()) {
			throw new IllegalArgumentException("성공 응답에는 2xx 상태 코드가 필요합니다.");
		}
		return new ApiResponse<>(true, responseCode.getCode(), data, responseCode.getMessage());
	}

	public static <T> ApiResponse<T> failure(ResponseCode responseCode, T data) {
		if (!responseCode.getStatus().isError()) {
			throw new IllegalArgumentException("오류 응답에는 4xx 또는 5xx 상태 코드가 필요합니다.");
		}
		return new ApiResponse<>(false, responseCode.getCode(), data, responseCode.getMessage());
	}

	public static <T> ApiResponse<T> failure(HttpStatusCode status, T data) {
		if (!status.isError()) {
			throw new IllegalArgumentException("오류 응답에는 4xx 또는 5xx 상태 코드가 필요합니다.");
		}
		return failure(ResponseCode.forHttpStatus(status), data);
	}
}
