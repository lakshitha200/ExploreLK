package com.explorelk.destination.common.exception;

import com.explorelk.destination.common.ApiError;
import com.explorelk.destination.common.ErrorCode;
import lombok.Getter;

import java.util.List;

/**
 * A validation failure the service layer found, rather than Bean Validation.
 *
 * <p>Carries field errors so the response looks identical whether the problem was
 * caught by an annotation on a DTO or by a lookup the annotation could not do —
 * "is {@code BEECH} a real category" needs the database, so no {@code @Pattern}
 * can answer it. Clients should not have to tell the two apart.
 */
@Getter
public class ValidationException extends AppException {

    private final List<ApiError.FieldError> fieldErrors;

    public ValidationException(String field, String message) {
        super(ErrorCode.VALIDATION_FAILED, field + " " + message);
        this.fieldErrors = List.of(new ApiError.FieldError(field, message));
    }
}
