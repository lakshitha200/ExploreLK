package com.explorelk.auth.common.exception;

import com.explorelk.auth.common.ErrorCode;
import lombok.Getter;

/**
 * Base for every error this service raises deliberately.
 *
 * <p>Carrying an {@link ErrorCode} means the handler never has to map exception
 * types to HTTP statuses — the code already knows its status and its user-facing
 * wording.
 */
@Getter
public class AppException extends RuntimeException {

    private final ErrorCode errorCode;

    public AppException(ErrorCode errorCode) {
        // The ErrorCode name is the exception message; it goes to logs, never to the client.
        super(errorCode.name());
        this.errorCode = errorCode;
    }

    public AppException(ErrorCode errorCode, String logMessage) {
        super(logMessage);
        this.errorCode = errorCode;
    }
}
