package com.teamsync.dmssearch.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.trace.Span;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * The platform response envelope, matching document-service field-for-field.
 *
 * <p>This is deliberately NOT RFC 9457 (Problem Details). RFC 9457 is the better
 * standard in the abstract, but every existing service and client on this
 * platform speaks this envelope; a single service emitting
 * {@code application/problem+json} would force clients to handle two error
 * shapes. Adopting 9457 is a platform-wide migration, not a per-service choice.
 *
 * @param <T> payload type
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResponse<T> {

    /** HTTP status code, repeated in the body for clients that only read JSON. */
    private int status;

    /** Human-readable summary. */
    private String message;

    /** Payload on success. */
    private T data;

    /** Stable machine-readable error code on failure, e.g. {@code SRCH-400-001}. */
    private String error;

    /** Structured failure detail. */
    private ErrorDetails details;

    private ResponseMetadata requestInfo;

    // ------------------------------------------------------------------
    // Factories
    // ------------------------------------------------------------------

    public static <T> ApiResponse<T> success(T data, String message) {
        return ApiResponse.<T>builder()
                .status(200)
                .message(message)
                .data(data)
                .requestInfo(metadata())
                .build();
    }

    public static <T> ApiResponse<T> error(int status, String errorCode, String message, ErrorDetails details) {
        return ApiResponse.<T>builder()
                .status(status)
                .message(message)
                .error(errorCode)
                .details(details)
                .requestInfo(metadata())
                .build();
    }

    /**
     * Builds the per-response diagnostics.
     *
     * <p>The correlation id is taken from OpenTelemetry baggage so it survives
     * across service hops; when absent (a request that did not come through the
     * gateway, or baggage propagation being off) a fresh one is generated rather
     * than left null — an unqueryable response is worse than a non-correlated one.
     */
    private static ResponseMetadata metadata() {
        String traceId = Span.current().getSpanContext().getTraceId();
        String correlationId = Baggage.current().getEntryValue("correlation.id");
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }
        return ResponseMetadata.builder()
                .timestamp(Instant.now())
                .traceId(traceId)
                .correlationId(correlationId)
                .build();
    }
}
