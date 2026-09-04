package com.explorelk.auth.common;

import org.springframework.http.HttpStatus;

/**
 * Every error this service can return.
 *
 * <p>{@code code} is what clients branch on and is part of the API contract —
 * renaming one is a breaking change. {@code defaultMessage} is what a human sees,
 * and is deliberately vague where being specific would leak information.
 */
public enum ErrorCode {

    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "The request contains invalid values"),
    MALFORMED_REQUEST(HttpStatus.BAD_REQUEST, "The request body could not be read"),

    // Deliberately identical wording for "no such email" and "wrong password".
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "Invalid email or password"),

    EMAIL_NOT_VERIFIED(HttpStatus.FORBIDDEN, "Email address has not been verified"),
    ACCOUNT_SUSPENDED(HttpStatus.FORBIDDEN, "This account is suspended"),
    ACCOUNT_DISABLED(HttpStatus.FORBIDDEN, "This account is disabled"),
    ACCOUNT_LOCKED(HttpStatus.FORBIDDEN, "Too many failed attempts. Try again later"),

    TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "Token has expired"),
    TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "Token is invalid"),
    TOKEN_REUSED(HttpStatus.UNAUTHORIZED, "Token has already been used"),

    EMAIL_ALREADY_REGISTERED(HttpStatus.CONFLICT, "That email address is already registered"),

    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "Too many requests. Slow down"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "You do not have permission to do that"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "Not found"),
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
