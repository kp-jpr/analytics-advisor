package gov.rajasthan.smart.srse.config;

import java.util.UUID;

/**
 * Synchronous API failure body: a stable code, the sentence an officer can read,
 * and an id that ties the response to the server log.
 */
public record ApiError(String code, String message, String requestId) {

    public static ApiError of(String code, String message) {
        return new ApiError(code, message, UUID.randomUUID().toString());
    }
}
