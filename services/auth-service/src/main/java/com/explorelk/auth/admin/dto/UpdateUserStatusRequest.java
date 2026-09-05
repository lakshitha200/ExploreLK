package com.explorelk.auth.admin.dto;

import com.explorelk.auth.user.UserStatus;
import jakarta.validation.constraints.NotNull;

/**
 * @param status the target status. Validated as an enum so an unknown value is a
 *               400 naming the field, rather than a 500 from a failed valueOf
 *               deep inside the service.
 * @param reason free text kept for the audit trail and for the event payload —
 *               "why was I suspended" is the first question the user asks
 */
public record UpdateUserStatusRequest(
        @NotNull(message = "is required")
        UserStatus status,

        String reason) {
}
