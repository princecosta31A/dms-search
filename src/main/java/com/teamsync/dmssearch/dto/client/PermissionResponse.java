package com.teamsync.dmssearch.dto.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * permission-service's response envelope — its own shape, not this platform's
 * {@code ApiResponse}.
 *
 * <p>{@code ignoreUnknown} matters here: permission-service is a separate
 * codebase on its own release cycle, and a field added there must not start
 * failing every search in this one.
 *
 * @param <T> payload — {@code Boolean} for validate-action
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public class PermissionResponse<T> {

    private boolean success;
    /** "0" for ok, otherwise a business error code. */
    private String code;
    private String message;
    private T data;
    private String path;
    private String timestamp;
}
