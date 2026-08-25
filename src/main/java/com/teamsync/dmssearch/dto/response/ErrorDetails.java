package com.teamsync.dmssearch.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Structured detail for a failure.
 *
 * <p>{@code reason} is safe to show a user. {@code fields} names the request
 * parameters at fault.
 *
 * <p>Nothing here ever carries the caller's query text or any document content —
 * search terms routinely contain PAN numbers, passport numbers and names, and an
 * error body is one of the easiest places for that to leak into a log
 * aggregator or a browser console.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ErrorDetails {

    /** Human-readable explanation, safe to display. */
    private String reason;

    /** Request parameter names at fault, e.g. ["size", "page"]. */
    private List<String> fields;

    /** Seconds to wait before retrying — set only for 503. */
    private Integer retryAfterSeconds;
}
