package com.explorelk.auth.common.exception;

import com.explorelk.auth.common.ErrorCode;

/**
 * Raised when a registration targets an email that already exists.
 *
 * <p><b>Step 7 will change this.</b> Returning 409 tells an attacker which email
 * addresses hold accounts. Once the Notification Service can send mail, registration
 * returns 202 for both cases and emails the existing owner instead — see the
 * enumeration-resistance table in {@code docs/auth-service.md} §9. 409 is kept for
 * now because without email there is no other way to tell the two apart while
 * developing.
 */
public class EmailAlreadyRegisteredException extends AppException {

    public EmailAlreadyRegisteredException(String maskedEmail) {
        super(ErrorCode.EMAIL_ALREADY_REGISTERED, "Registration attempted for existing email " + maskedEmail);
    }
}
