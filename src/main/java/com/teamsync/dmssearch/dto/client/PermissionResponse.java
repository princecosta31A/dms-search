package com.teamsync.dmssearch.dto.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * permission-service's response envelope, mirroring its {@code PermissionResponseDTO}
 * field for field — its own shape, not this platform's {@code ApiResponse}.
 *
 * <pre>
 * { "httpStatus": 200, "message": "Access Allowed", "data": true,
 *   "path": "/validate-action", "timestamp": "2026-08-26T16:30:00+05:30" }
 * </pre>
 *
 * <p>An earlier version of this class declared {@code boolean success} and
 * {@code String code}. <b>Neither field exists</b> — they were assumed, never
 * verified against permission-service. Under Jackson 3 that was fatal rather than
 * merely wrong: Boot 4 compiles with {@code -parameters}, so Jackson auto-detects
 * Lombok's all-args constructor as a properties-based creator, and a primitive
 * creator argument with nothing to bind to fails the whole parse
 * ({@code Cannot map null into type boolean}). Every search 503'd.
 *
 * <p>Boxed types throughout, deliberately: this envelope crosses a service
 * boundary, so a field that stops being sent should leave a {@code null} to
 * reason about rather than abort deserialization.
 *
 * <p>{@code ignoreUnknown} matters for the same reason — permission-service is a
 * separate codebase on its own release cycle, and a field added there must not
 * start failing every search in this one.
 *
 * @param <T> payload — {@code Boolean} for validate-action
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public class PermissionResponse<T> {

    /** Echoes the HTTP status in the body; the transport status is what we act on. */
    private Integer httpStatus;
    private String message;
    private T data;
    private String path;
    private String timestamp;
}
