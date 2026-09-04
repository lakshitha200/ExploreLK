package com.explorelk.auth.common;

import com.explorelk.auth.common.exception.AppException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;
import java.util.UUID;

/**
 * Turns every exception into the one {@link ApiError} shape.
 *
 * <p>Two rules hold throughout:
 * <ul>
 *   <li>An exception message never reaches the client. Client wording comes from
 *       {@link ErrorCode}; the exception goes to the log.</li>
 *   <li>Every response carries a {@code traceId} that also appears in the log line,
 *       so a report of "I got error a1b2c3d4" is enough to find the stack trace.</li>
 * </ul>
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    /** Anything raised deliberately: the code already knows its status and wording. */
    @ExceptionHandler(AppException.class)
    public ResponseEntity<ApiError> handleApp(AppException ex, HttpServletRequest request) {
        String traceId = newTraceId();
        ErrorCode code = ex.getErrorCode();

        // 4xx is expected traffic, not a fault — log it at INFO without a stack trace.
        log.info("[{}] {} at {}: {}", traceId, code, request.getRequestURI(), ex.getMessage());

        return ResponseEntity.status(code.status())
                .body(ApiError.of(code, request.getRequestURI(), traceId));
    }

    /** Bean Validation failures on a {@code @Valid} request body. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex,
                                                     HttpServletRequest request) {
        String traceId = newTraceId();

        List<ApiError.FieldError> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> new ApiError.FieldError(fe.getField(), fe.getDefaultMessage()))
                .toList();

        // Field names and messages only — never the rejected value, which may be a password.
        log.info("[{}] validation failed at {}: {}", traceId, request.getRequestURI(),
                fieldErrors.stream().map(ApiError.FieldError::field).toList());

        return ResponseEntity.status(ErrorCode.VALIDATION_FAILED.status())
                .body(ApiError.validation(request.getRequestURI(), traceId, fieldErrors));
    }

    /** Malformed JSON, or an unknown field while {@code fail-on-unknown-properties} is on. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadable(HttpMessageNotReadableException ex,
                                                     HttpServletRequest request) {
        String traceId = newTraceId();
        log.info("[{}] unreadable body at {}: {}", traceId, request.getRequestURI(), ex.getMessage());

        return ResponseEntity.status(ErrorCode.MALFORMED_REQUEST.status())
                .body(ApiError.of(ErrorCode.MALFORMED_REQUEST, request.getRequestURI(), traceId));
    }

    /**
     * A database constraint fired.
     *
     * <p>In practice this is the unique email index losing a race with a concurrent
     * registration — the service-level check passed for both requests and the database
     * settled it. Reported the same as the checked case.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> handleIntegrity(DataIntegrityViolationException ex,
                                                    HttpServletRequest request) {
        String traceId = newTraceId();
        String message = String.valueOf(ex.getMostSpecificCause().getMessage());

        if (message.contains("ux_users_email_lower")) {
            log.info("[{}] concurrent registration lost the unique-email race at {}",
                    traceId, request.getRequestURI());
            return ResponseEntity.status(ErrorCode.EMAIL_ALREADY_REGISTERED.status())
                    .body(ApiError.of(ErrorCode.EMAIL_ALREADY_REGISTERED, request.getRequestURI(), traceId));
        }

        log.error("[{}] unexpected constraint violation at {}", traceId, request.getRequestURI(), ex);
        return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.status())
                .body(ApiError.of(ErrorCode.INTERNAL_ERROR, request.getRequestURI(), traceId));
    }

    /** Last resort. Something is genuinely broken, so log the stack trace. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex, HttpServletRequest request) {
        String traceId = newTraceId();
        log.error("[{}] unhandled exception at {}", traceId, request.getRequestURI(), ex);

        return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.status())
                .body(ApiError.of(ErrorCode.INTERNAL_ERROR, request.getRequestURI(), traceId));
    }

    private static String newTraceId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
