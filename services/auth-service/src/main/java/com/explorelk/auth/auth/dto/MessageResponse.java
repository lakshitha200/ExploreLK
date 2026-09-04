package com.explorelk.auth.auth.dto;

/**
 * A deliberately uninformative acknowledgement.
 *
 * <p>Returned by every endpoint that must not reveal whether an account exists:
 * registration, resend-verification and forgot-password all answer with this, and the
 * wording is identical whatever actually happened.
 */
public record MessageResponse(String message) {

    public static MessageResponse of(String message) {
        return new MessageResponse(message);
    }
}
