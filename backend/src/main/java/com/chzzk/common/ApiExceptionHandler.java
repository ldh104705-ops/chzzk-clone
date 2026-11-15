package com.chzzk.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/** Maps exceptions to a uniform JSON error body: {"status":400,"error":"Bad Request","message":"..."}. */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    public record ErrorBody(int status, String error, String message) {
    }

    @ExceptionHandler(InvalidRequestException.class)
    ResponseEntity<ErrorBody> invalid(InvalidRequestException e) {
        return body(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ErrorBody> tooLarge(MaxUploadSizeExceededException e) {
        return body(HttpStatus.CONTENT_TOO_LARGE, "업로드 가능한 최대 크기를 초과했습니다.");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ErrorBody> badParameter(MethodArgumentTypeMismatchException e) {
        return body(HttpStatus.BAD_REQUEST, "잘못된 요청 파라미터입니다: " + e.getName());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ErrorBody> unreadable(HttpMessageNotReadableException e) {
        return body(HttpStatus.BAD_REQUEST, "요청 본문(JSON)을 읽을 수 없습니다.");
    }

    @ExceptionHandler(ForbiddenException.class)
    ResponseEntity<ErrorBody> forbidden(ForbiddenException e) {
        return body(HttpStatus.FORBIDDEN, e.getMessage());
    }

    @ExceptionHandler(UnauthorizedException.class)
    ResponseEntity<ErrorBody> unauthorized(UnauthorizedException e) {
        return body(HttpStatus.UNAUTHORIZED, e.getMessage());
    }

    @ExceptionHandler(ConflictException.class)
    ResponseEntity<ErrorBody> conflict(ConflictException e) {
        return body(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(NotFoundException.class)
    ResponseEntity<ErrorBody> notFound(NotFoundException e) {
        return body(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(ServiceUnavailableException.class)
    ResponseEntity<ErrorBody> unavailable(ServiceUnavailableException e) {
        return body(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
    }

    @ExceptionHandler(BadGatewayException.class)
    ResponseEntity<ErrorBody> badGateway(BadGatewayException e) {
        return body(HttpStatus.BAD_GATEWAY, e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorBody> unexpected(Exception e) throws Exception {
        // Spring MVC's own exceptions (missing param, 405, type mismatch ...) keep their default handling.
        if (e instanceof ErrorResponse) {
            throw e;
        }
        log.error("Unhandled exception", e);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "서버 오류가 발생했습니다.");
    }

    private static ResponseEntity<ErrorBody> body(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(new ErrorBody(status.value(), status.getReasonPhrase(), message));
    }
}
