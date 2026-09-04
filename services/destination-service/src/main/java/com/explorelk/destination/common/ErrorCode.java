package com.explorelk.destination.common;

import org.springframework.http.HttpStatus;

/**
 * Every error this service can return.
 *
 * <p>{@code code} is what clients branch on and is part of the API contract —
 * renaming one is a breaking change. {@code defaultMessage} is what a human sees.
 *
 * <p>Deliberately the same enum shape as auth-service's, so a client parses one
 * error format across the whole platform. The codes differ because the failures
 * differ: nothing here can be an invalid credential, and nothing in auth can be
 * an invalid status transition.
 */
public enum ErrorCode {

    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "The request contains invalid values"),
    MALFORMED_REQUEST(HttpStatus.BAD_REQUEST, "The request body could not be read"),

    NOT_FOUND(HttpStatus.NOT_FOUND, "Not found"),

    /** A generated slug collided with one already in use. */
    SLUG_ALREADY_EXISTS(HttpStatus.CONFLICT, "That slug is already taken"),

    /** e.g. PUBLISHED -> PUBLISHED, or ARCHIVED -> PUBLISHED without restoring first. */
    INVALID_STATUS_TRANSITION(HttpStatus.CONFLICT, "That status change is not allowed"),

    /** Publishing content that is still missing fields the traveler-facing UI needs. */
    INCOMPLETE_FOR_PUBLISH(HttpStatus.CONFLICT, "This content is not complete enough to publish"),

    /** Optimistic lock clash — two admins edited the same row. */
    CONFLICT(HttpStatus.CONFLICT, "This item was changed by someone else. Reload and try again"),

    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "Authentication is required"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "You do not have permission to do that"),

    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Something went wrong");

    private final HttpStatus status;
    private final String defaultMessage;

    ErrorCode(HttpStatus status, String defaultMessage) {
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus status() {
        return status;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
