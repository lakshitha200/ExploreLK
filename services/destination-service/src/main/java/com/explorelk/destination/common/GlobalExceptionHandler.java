package com.explorelk.destination.common;

import com.explorelk.destination.common.exception.AppException;
import com.explorelk.destination.common.exception.ValidationException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.List;
import java.util.UUID;

/**
 * Turns every exception into the one {@link ApiError} shape.
 *
 * <p>Two rules hold throughout, the same as auth-service:
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

    /**
     * A validation failure the service layer found — something no annotation could
     * check, such as whether a category code actually exists.
     *
     * <p>Declared separately from {@link AppException} so the field errors survive;
     * Spring dispatches to the most specific handler, so this wins for its type.
     */
    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<ApiError> handleServiceValidation(ValidationException ex,
                                                            HttpServletRequest request) {
        String traceId = newTraceId();
        log.info("[{}] validation failed at {}: {}", traceId, request.getRequestURI(), ex.getMessage());

        return ResponseEntity.status(ErrorCode.VALIDATION_FAILED.status())
                .body(ApiError.validation(request.getRequestURI(), traceId, ex.getFieldErrors()));
    }

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

        log.info("[{}] validation failed at {}: {}", traceId, request.getRequestURI(),
                fieldErrors.stream().map(ApiError.FieldError::field).toList());

        return ResponseEntity.status(ErrorCode.VALIDATION_FAILED.status())
                .body(ApiError.validation(request.getRequestURI(), traceId, fieldErrors));
    }

    /**
     * Bean Validation on request parameters — {@code @Validated} on the controller
     * plus constraints on {@code @RequestParam}. A different exception type from
     * the body case above, and easy to forget until a {@code @Min} on a query
     * parameter starts returning 500 instead of 400.
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiError> handleParamValidation(ConstraintViolationException ex,
                                                          HttpServletRequest request) {
        String traceId = newTraceId();

        List<ApiError.FieldError> fieldErrors = ex.getConstraintViolations().stream()
                .map(v -> new ApiError.FieldError(lastNode(v.getPropertyPath().toString()), v.getMessage()))
                .toList();

        log.info("[{}] parameter validation failed at {}: {}", traceId, request.getRequestURI(),
                fieldErrors.stream().map(ApiError.FieldError::field).toList());

        return ResponseEntity.status(ErrorCode.VALIDATION_FAILED.status())
                .body(ApiError.validation(request.getRequestURI(), traceId, fieldErrors));
    }

    /** {@code ?page=abc} — the parameter is present but the wrong type. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleTypeMismatch(MethodArgumentTypeMismatchException ex,
                                                       HttpServletRequest request) {
        String traceId = newTraceId();
        log.info("[{}] bad parameter type '{}' at {}", traceId, ex.getName(), request.getRequestURI());

        List<ApiError.FieldError> fieldErrors =
                List.of(new ApiError.FieldError(ex.getName(), "has the wrong type"));

        return ResponseEntity.status(ErrorCode.VALIDATION_FAILED.status())
                .body(ApiError.validation(request.getRequestURI(), traceId, fieldErrors));
    }

    /** A required query parameter was not supplied. */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiError> handleMissingParam(MissingServletRequestParameterException ex,
                                                       HttpServletRequest request) {
        String traceId = newTraceId();
        log.info("[{}] missing parameter '{}' at {}", traceId, ex.getParameterName(), request.getRequestURI());

        List<ApiError.FieldError> fieldErrors =
                List.of(new ApiError.FieldError(ex.getParameterName(), "is required"));

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

    /** Last resort. Something is genuinely broken, so log the stack trace. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex, HttpServletRequest request) {
        String traceId = newTraceId();
        log.error("[{}] unhandled exception at {}", traceId, request.getRequestURI(), ex);

        return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.status())
                .body(ApiError.of(ErrorCode.INTERNAL_ERROR, request.getRequestURI(), traceId));
    }

    /** {@code list.size} -> {@code size}: the client knows the parameter, not the method. */
    private static String lastNode(String propertyPath) {
        int dot = propertyPath.lastIndexOf('.');
        return dot < 0 ? propertyPath : propertyPath.substring(dot + 1);
    }

    private static String newTraceId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
