package com.explorelk.destination.common.exception;

import com.explorelk.destination.common.ErrorCode;
import lombok.Getter;

/**
 * Base for every error this service raises deliberately.
 *
 * <p>Carrying an {@link ErrorCode} means the handler never has to map exception
 * types to HTTP statuses — the code already knows its status and its user-facing
 * wording. The constructor message is for the log only.
 */
@Getter
public class AppException extends RuntimeException {

    private final ErrorCode errorCode;

    public AppException(ErrorCode errorCode) {
        super(errorCode.name());
        this.errorCode = errorCode;
    }

    public AppException(ErrorCode errorCode, String logMessage) {
        super(logMessage);
        this.errorCode = errorCode;
    }
}
