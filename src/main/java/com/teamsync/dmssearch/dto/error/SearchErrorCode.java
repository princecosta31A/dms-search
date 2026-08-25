package com.teamsync.dmssearch.dto.error;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * Stable error codes for dms-search.
 *
 * <p>Format {@code SRCH-<http status>-<sequence>}, mirroring document-service's
 * {@code DOC-4xx-nnn}. Codes are part of the public contract: clients branch on
 * them, so a code's meaning must never be repurposed. Add new ones; don't
 * recycle retired ones.
 */
@Getter
@RequiredArgsConstructor
public enum SearchErrorCode {

    // ---- 400 Bad Request — the caller can fix these ----

    /** page/size arithmetic exceeds the index's max_result_window. */
    PAGINATION_LIMIT_EXCEEDED("SRCH-400-001", HttpStatus.BAD_REQUEST,
            "Pagination Limit Exceeded",
            "Requested page is beyond the maximum offset window. Use the cursor to page deeper."),

    /** Cursor is not decodable, or was not produced by this service. */
    INVALID_CURSOR("SRCH-400-002", HttpStatus.BAD_REQUEST,
            "Invalid Cursor",
            "The supplied cursor is malformed. Cursors are opaque and must be echoed back unmodified."),

    /** A request parameter failed validation (size out of range, bad date, …). */
    INVALID_PARAMETER("SRCH-400-003", HttpStatus.BAD_REQUEST,
            "Invalid Parameter",
            "One or more request parameters are invalid."),

    /** page + cursor supplied together — they are mutually exclusive paging modes. */
    CONFLICTING_PAGINATION("SRCH-400-004", HttpStatus.BAD_REQUEST,
            "Conflicting Pagination",
            "Use either page/size or cursor, not both."),

    /** Sort key is not one this service supports. */
    INVALID_SORT("SRCH-400-005", HttpStatus.BAD_REQUEST,
            "Invalid Sort",
            "Unsupported sort key."),

    /**
     * X-API-Version names a version this service does not serve.
     *
     * <p>Rejected rather than quietly downgraded to the default: a client that
     * asked for 2.0 is written against 2.0's response shape, and handing it 1.0
     * would fail somewhere far less obvious than here.
     */
    UNSUPPORTED_API_VERSION("SRCH-400-006", HttpStatus.BAD_REQUEST,
            "Unsupported API Version",
            "The requested API version is not supported by this service."),

    // ---- 403 Forbidden — authenticated, but not allowed here ----

    /**
     * The caller has no FILE_READ permission in the workspace they asked for.
     *
     * <p>403 and not 404: they are authenticated and the endpoint exists — they
     * simply may not read this workspace. Distinct from 401, which means the
     * identity headers never arrived.
     */
    PERMISSION_DENIED("SRCH-403-001", HttpStatus.FORBIDDEN,
            "Permission Denied",
            "You do not have read permission in this workspace."),

    // ---- 404 Not Found — the ROUTE does not exist ----

    /**
     * No endpoint at this path.
     *
     * <p>Note this never means "no documents matched" — an empty result set is a
     * successful search and returns 200 with an empty array. A 404 here is always
     * a wrong URL, most often a client still calling the retired
     * {@code /api/v1/...} path from before versioning moved to the header.
     */
    NOT_FOUND("SRCH-404-001", HttpStatus.NOT_FOUND,
            "Not Found",
            "No endpoint at this path. Note the API version travels in the X-API-Version header, "
                    + "not the URL — the path is /api/search/documents."),

    // ---- 401 Unauthorized — identity headers absent ----

    /**
     * The gateway injects tenant/workspace/user headers after validating the
     * caller's token. Their absence means the request did not come through the
     * gateway, so it is rejected rather than served unfiltered — an unscoped
     * search would return every tenant's documents.
     */
    MISSING_IDENTITY("SRCH-401-001", HttpStatus.UNAUTHORIZED,
            "Missing Identity",
            "Required identity headers are absent. Requests must be routed through the gateway."),

    // ---- 503 Service Unavailable — a dependency, not us ----

    /**
     * Elasticsearch is unreachable or timed out. Deliberately 503 and not 500:
     * the request is retryable and nothing about it was wrong.
     */
    SEARCH_BACKEND_UNAVAILABLE("SRCH-503-001", HttpStatus.SERVICE_UNAVAILABLE,
            "Search Backend Unavailable",
            "The search backend is temporarily unavailable. Please retry."),

    /**
     * permission-service is unreachable, so workspace membership could not be
     * verified.
     *
     * <p>503 rather than 403 on purpose: the caller may well be authorised — we
     * simply could not find out. Returning 403 would send them debugging
     * permissions that are probably fine. This is the fail-closed path: no
     * answer means no access.
     */
    PERMISSION_BACKEND_UNAVAILABLE("SRCH-503-002", HttpStatus.SERVICE_UNAVAILABLE,
            "Permission Service Unavailable",
            "Could not verify permissions. Please retry."),

    // ---- 500 Internal Server Error — genuinely our fault ----

    INTERNAL_ERROR("SRCH-500-001", HttpStatus.INTERNAL_SERVER_ERROR,
            "Internal Error",
            "An unexpected error occurred.");

    private final String code;
    private final HttpStatus httpStatus;
    private final String title;
    private final String defaultMessage;
}
