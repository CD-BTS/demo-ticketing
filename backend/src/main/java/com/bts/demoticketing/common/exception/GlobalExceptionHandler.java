package com.bts.demoticketing.common.exception;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import com.bts.demoticketing.common.response.ApiResponse;
import com.bts.demoticketing.common.response.ResponseCode;

@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	@Override
	protected ResponseEntity<Object> handleMethodArgumentNotValid(
			MethodArgumentNotValidException exception, HttpHeaders headers,
			HttpStatusCode status, WebRequest request) {
		List<ValidationError> errors = exception.getBindingResult().getAllErrors().stream()
				.map(error -> new ValidationError(
						error instanceof FieldError fieldError ? fieldError.getField() : error.getObjectName(),
						error.getDefaultMessage()))
				.toList();
		return handleExceptionInternal(exception, Map.of("errors", errors), headers, status, request);
	}

	@Override
	protected ResponseEntity<Object> handleHandlerMethodValidationException(
			HandlerMethodValidationException exception, HttpHeaders headers,
			HttpStatusCode status, WebRequest request) {
		if (exception.isForReturnValue()) {
			return handleExceptionInternal(exception, null, headers, status, request);
		}
		List<ValidationError> errors = exception.getParameterValidationResults().stream()
				.flatMap(result -> result.getResolvableErrors().stream().map(error -> {
					String name = result.getMethodParameter().getParameterName();
					return new ValidationError(name != null ? name
							: "arg" + result.getMethodParameter().getParameterIndex(), error.getDefaultMessage());
				}))
				.toList();
		return handleExceptionInternal(exception, Map.of("errors", errors), headers, status, request);
	}

	@Override
	protected ResponseEntity<Object> handleExceptionInternal(
			Exception exception, Object body, HttpHeaders headers,
			HttpStatusCode status, WebRequest request) {
		if (status.is5xxServerError()) {
			log.error("Spring MVC 요청 처리 중 서버 오류", exception);
		}
		// Spring MVC의 상태와 Allow 등의 응답 헤더를 보존하고 본문만 공통 형식으로 변환한다.
		Object data = body instanceof Map<?, ?> ? body : null;
		return super.handleExceptionInternal(exception,
				ApiResponse.failure(status, data), headers, status, request);
	}

	@ExceptionHandler(ApiException.class)
	public ResponseEntity<ApiResponse<Void>> handleApiException(ApiException exception) {
		ResponseCode responseCode = exception.getResponseCode();
		if (responseCode.getStatus().is5xxServerError()) {
			log.error("API 요청 처리 중 서버 오류", exception);
		}
		return ResponseEntity.status(responseCode.getStatus()).body(ApiResponse.failure(responseCode, null));
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<ApiResponse<Void>> handleUnexpectedException(Exception exception) {
		log.error("예상하지 못한 서버 오류", exception);
		return ResponseEntity.internalServerError()
				.body(ApiResponse.failure(ResponseCode.INTERNAL_SERVER_ERROR, null));
	}

	public record ValidationError(String field, String message) {
	}
}
