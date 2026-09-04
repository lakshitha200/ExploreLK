package com.explorelk.destination.common;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * The single error shape this service returns. Never varies by endpoint, and is
 * identical to auth-service's so a client parses one format platform-wide.
 *
 * <pre>
 * {
 *   "code": "NOT_FOUND",
 *   "message": "Not found",
 *   "timestamp": "2026-08-31T10:15:30Z",
 *   "path": "/api/v1/destinations/atlantis",
 *   "traceId": "a1b2c3d4"
 * }
 * </pre>
 *
 * <p>{@code traceId} is echoed into the server log, so a user can quote it and the
 * stack trace can be found without exposing the exception. Exception messages
 * never reach {@code message} — that text comes from {@link ErrorCode} only.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record ApiError(
        String code,
        String message,
        Instant timestamp,
        String path,
        String traceId,
        List<FieldError> fieldErrors
) {

    public record FieldError(String field, String message) {
    }

    public static ApiError of(ErrorCode code, String path, String traceId) {
        return new ApiError(code.name(), code.defaultMessage(), Instant.now(), path, traceId, List.of());
    }

    public static ApiError of(ErrorCode code, String message, String path, String traceId) {
        return new ApiError(code.name(), message, Instant.now(), path, traceId, List.of());
    }

    public static ApiError validation(String path, String traceId, List<FieldError> fieldErrors) {
        return new ApiError(
                ErrorCode.VALIDATION_FAILED.name(),
                ErrorCode.VALIDATION_FAILED.defaultMessage(),
                Instant.now(),
                path,
                traceId,
                fieldErrors);
    }
}
