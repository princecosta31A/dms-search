package com.teamsync.dmssearch.exception;

import com.teamsync.dmssearch.dto.error.SearchErrorCode;
import com.teamsync.dmssearch.dto.response.ApiResponse;
import com.teamsync.dmssearch.dto.response.ErrorDetails;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.accept.InvalidApiVersionException;
import org.springframework.web.accept.MissingApiVersionException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * Turns every exception into the platform response envelope.
 *
 * <p>Two rules run through all of it:
 *
 * <ol>
 *   <li><b>Never echo caller input.</b> Search terms and filter values are PII;
 *       an error body reaches browser consoles and log aggregators, so it
 *       carries parameter <i>names</i> and never their values.</li>
 *   <li><b>Never leak internals.</b> An Elasticsearch exception can quote the
 *       generated query, index names and mapping details. Callers get a stable
 *       error code and a neutral sentence; the detail goes to the log, keyed by
 *       the same {@code traceId} the caller received.</li>
 * </ol>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(SearchException.class)
    public ResponseEntity<ApiResponse<Void>> handleSearchException(SearchException ex) {
        SearchErrorCode code = ex.getErrorCode();

        ErrorDetails details = ErrorDetails.builder()
                .reason(ex.getMessage())
                .fields(ex.getFields())
                .retryAfterSeconds(code == SearchErrorCode.SEARCH_BACKEND_UNAVAILABLE ? 5 : null)
                .build();

        // 5xx is our problem and gets a stack trace. 4xx is the caller's and
        // gets one line — a misspelled sort key should not fill the log with
        // traces.
        if (code.getHttpStatus().is5xxServerError()) {
            log.error("request.failed code={} status={} msg={}",
                    code.getCode(), code.getHttpStatus().value(), ex.getMessage(), ex);
        } else {
            log.info("request.rejected code={} status={} fields={}",
                    code.getCode(), code.getHttpStatus().value(), ex.getFields());
        }

        ResponseEntity.BodyBuilder response = ResponseEntity.status(code.getHttpStatus());
        if (code == SearchErrorCode.SEARCH_BACKEND_UNAVAILABLE) {
            // Tells well-behaved clients and proxies when to come back, instead
            // of leaving them to hammer a struggling cluster.
            response.header(HttpHeaders.RETRY_AFTER, "5");
        }

        return response.body(ApiResponse.error(
                code.getHttpStatus().value(), code.getCode(), code.getTitle(), details));
    }

    /** A parameter of the wrong type, e.g. {@code page=abc}. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        SearchErrorCode code = SearchErrorCode.INVALID_PARAMETER;
        log.info("request.rejected code={} param={}", code.getCode(), ex.getName());

        // The parameter NAME only. ex.getValue() is caller input.
        ErrorDetails details = ErrorDetails.builder()
                .reason("Parameter '" + ex.getName() + "' has an invalid value.")
                .fields(List.of(ex.getName()))
                .build();

        return ResponseEntity.status(code.getHttpStatus())
                .body(ApiResponse.error(code.getHttpStatus().value(), code.getCode(), code.getTitle(), details));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HandlerMethodValidationException.class})
    public ResponseEntity<ApiResponse<Void>> handleValidation(Exception ex) {
        SearchErrorCode code = SearchErrorCode.INVALID_PARAMETER;
        log.info("request.rejected code={} type={}", code.getCode(), ex.getClass().getSimpleName());

        ErrorDetails details = ErrorDetails.builder()
                .reason(code.getDefaultMessage())
                .build();

        return ResponseEntity.status(code.getHttpStatus())
                .body(ApiResponse.error(code.getHttpStatus().value(), code.getCode(), code.getTitle(), details));
    }

    /**
     * Exceptions Spring itself raises with a status already attached — an unknown
     * path (404), an unsupported {@code X-API-Version}, an unsupported media type,
     * a method not allowed, and so on.
     *
     * <p>What matters here is the {@link ErrorResponse} <b>interface</b>, not any
     * base class. Spring's status-carrying exceptions do not share one hierarchy:
     * {@code ResponseStatusException} extends {@code ErrorResponseException},
     * while {@code NoResourceFoundException} extends {@code ServletException} and
     * merely implements {@code ErrorResponse}. Keying on either base class alone
     * lets the other fall through to the catch-all and be reported as 500 —
     * which is exactly what a request for an unknown path did until this was
     * widened.
     *
     * <p>The point: Spring already decided the correct status. Discarding it
     * tells clients, and their retry policies, that a request they got wrong was
     * the server's fault.
     */
    @ExceptionHandler({ErrorResponseException.class, NoResourceFoundException.class})
    public ResponseEntity<ApiResponse<Void>> handleErrorResponse(Exception ex) {
        HttpStatusCode status = ((ErrorResponse) ex).getStatusCode();

        boolean versionProblem = ex instanceof InvalidApiVersionException
                || ex instanceof MissingApiVersionException;

        SearchErrorCode code;
        String reason;
        if (versionProblem) {
            code = SearchErrorCode.UNSUPPORTED_API_VERSION;
            // The rejected version is deliberately NOT echoed — it is caller-supplied
            // input, and the supported list is what makes the error actionable anyway.
            reason = "Supported versions: 1.0. Send X-API-Version, or omit it to use the default.";
        } else if (status.value() == 404) {
            code = SearchErrorCode.NOT_FOUND;
            reason = code.getDefaultMessage();
        } else if (status.is4xxClientError()) {
            code = SearchErrorCode.INVALID_PARAMETER;
            reason = code.getDefaultMessage();
        } else {
            code = SearchErrorCode.INTERNAL_ERROR;
            reason = code.getDefaultMessage();
        }

        if (status.is5xxServerError()) {
            log.error("request.failed code={} status={} type={}",
                    code.getCode(), status.value(), ex.getClass().getSimpleName(), ex);
        } else {
            log.info("request.rejected code={} status={} type={}",
                    code.getCode(), status.value(), ex.getClass().getSimpleName());
        }

        ErrorDetails details = ErrorDetails.builder()
                .reason(reason)
                .fields(versionProblem ? List.of("X-API-Version") : null)
                .build();

        return ResponseEntity.status(status)
                .body(ApiResponse.error(status.value(), code.getCode(), code.getTitle(), details));
    }

    /**
     * Anything unanticipated. The caller gets a neutral message and the trace id
     * already present in {@code requestInfo}; the real cause goes to the log.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception ex) {
        // Defence in depth: Spring's status-carrying exceptions share no common
        // base class, only the ErrorResponse interface, so a new one could land
        // here and be mislabelled a 500. Honour the status it already carries.
        if (ex instanceof ErrorResponse) {
            return handleErrorResponse(ex);
        }

        SearchErrorCode code = SearchErrorCode.INTERNAL_ERROR;
        log.error("request.failed.unexpected type={} msg={}",
                ex.getClass().getName(), ex.getMessage(), ex);

        ErrorDetails details = ErrorDetails.builder()
                .reason(code.getDefaultMessage())
                .build();

        return ResponseEntity.status(code.getHttpStatus())
                .body(ApiResponse.error(code.getHttpStatus().value(), code.getCode(), code.getTitle(), details));
    }
}
