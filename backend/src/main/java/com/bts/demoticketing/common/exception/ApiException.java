package com.bts.demoticketing.common.exception;

import java.util.Objects;

import com.bts.demoticketing.common.response.ResponseCode;

public class ApiException extends RuntimeException {

	private final ResponseCode responseCode;

	public ApiException(ResponseCode responseCode) {
		super(Objects.requireNonNull(responseCode).getMessage());
		if (!responseCode.getStatus().isError()) {
			throw new IllegalArgumentException("API 예외에는 오류 응답 코드가 필요합니다.");
		}
		this.responseCode = responseCode;
	}

	public ResponseCode getResponseCode() {
		return responseCode;
	}
}
