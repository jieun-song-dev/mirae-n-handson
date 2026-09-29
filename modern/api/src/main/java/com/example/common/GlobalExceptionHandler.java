package com.example.common;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 컨트롤러 밖으로 나온 예외를 한 곳에서 HTTP 응답으로 바꾼다.
 * 컨트롤러 · 서비스는 try/catch 로 예외를 삼키지 않고 그대로 던진다.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(NotFoundException ex, HttpServletRequest request) {
        log.info("not found: {} ({})", ex.getMessage(), request.getRequestURI());
        return build(HttpStatus.NOT_FOUND, ex.getMessage(), request);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResource(NoResourceFoundException ex, HttpServletRequest request) {
        log.info("no handler for {}", request.getRequestURI());
        return build(HttpStatus.NOT_FOUND, "요청한 경로가 없습니다", request);
    }

    @ExceptionHandler({
        MethodArgumentTypeMismatchException.class,
        MethodArgumentNotValidException.class,
        ConstraintViolationException.class,
        IllegalArgumentException.class
    })
    public ResponseEntity<ErrorResponse> handleBadRequest(Exception ex, HttpServletRequest request) {
        log.info("bad request: {} ({})", ex.getMessage(), request.getRequestURI());
        return build(HttpStatus.BAD_REQUEST, "요청 값이 올바르지 않습니다", request);
    }

    /**
     * 레거시(Apache)의 414 와 같게 응답한다. 레거시 414 응답에는 안내 문구가 없으므로 {@code message} 를 비운다
     * (동작 보존 테스트의 정규화 결과 {@code message: null} 과 맞춤).
     */
    @ExceptionHandler(RequestLineTooLongException.class)
    public ResponseEntity<ErrorResponse> handleRequestLineTooLong(
            RequestLineTooLongException ex, HttpServletRequest request) {
        log.info("request line too long: {} ({})", ex.getMessage(), request.getRequestURI());
        return build(HttpStatus.URI_TOO_LONG, null, request);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ErrorResponse> handleConflict(IllegalStateException ex, HttpServletRequest request) {
        log.warn("state conflict: {} ({})", ex.getMessage(), request.getRequestURI());
        return build(HttpStatus.CONFLICT, ex.getMessage(), request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("unhandled exception on {}", request.getRequestURI(), ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 오류", request);
    }

    private static ResponseEntity<ErrorResponse> build(HttpStatus status, String message, HttpServletRequest request) {
        return ResponseEntity.status(status)
            .body(ErrorResponse.of(status.value(), status.getReasonPhrase(), message, request.getRequestURI()));
    }
}
