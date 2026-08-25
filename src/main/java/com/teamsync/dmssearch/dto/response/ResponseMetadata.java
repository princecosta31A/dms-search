package com.teamsync.dmssearch.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Per-response diagnostics, mirroring document-service's envelope so clients
 * see one consistent shape across the platform.
 *
 * <p>{@code traceId} is the OpenTelemetry trace id — quoting it in a bug report
 * is enough to find every log line for that request across services.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ResponseMetadata {

    private Instant timestamp;
    private String traceId;
    private String correlationId;
}
