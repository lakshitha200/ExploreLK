package com.explorelk.gateway.common;

import java.time.Instant;
import java.util.List;

/**
 * The same error shape auth-service and destination-service return.
 *
 * <p>Copied rather than shared. A gateway that depends on a library owned by the
 * services it fronts is a gateway that has to be redeployed with them, and the
 * whole point of the error contract is that it is stable enough to duplicate in
 * six lines.
 *
 * <p>What matters to a client is that a 401 from the edge and a 401 from a
 * service parse identically. Without that, every caller needs two error
 * handlers and has to know which one it is talking to — which is exactly the
 * knowledge a gateway exists to remove.
 *
 * @param code      machine-readable; the client switches on this
 * @param message   human-readable, safe to show
 * @param timestamp when it happened
 * @param path      the path that failed, as the client asked for it
 * @param traceId   quotable in a bug report and greppable in the logs
 */
public record ApiError(
        String code,
        String message,
        Instant timestamp,
        String path,
        String traceId,
        List<Object> fieldErrors) {

    public static ApiError of(String code, String message, String path, String traceId) {
        return new ApiError(code, message, Instant.now(), path, traceId, List.of());
    }
}
