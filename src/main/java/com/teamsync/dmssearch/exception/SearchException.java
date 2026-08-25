package com.teamsync.dmssearch.exception;

import com.teamsync.dmssearch.dto.error.SearchErrorCode;
import lombok.Getter;

import java.util.List;

/**
 * Every failure this service raises deliberately, carrying the
 * {@link SearchErrorCode} that decides both the HTTP status and the machine-readable
 * code clients branch on.
 *
 * <p>Messages here are shown to callers, so they must never echo the search
 * query or any document content — those routinely contain PAN numbers, passport
 * numbers and names.
 */
@Getter
public class SearchException extends RuntimeException {

    private final SearchErrorCode errorCode;
    private final List<String> fields;

    public SearchException(SearchErrorCode errorCode) {
        this(errorCode, errorCode.getDefaultMessage(), null, null);
    }

    public SearchException(SearchErrorCode errorCode, String message) {
        this(errorCode, message, null, null);
    }

    public SearchException(SearchErrorCode errorCode, String message, List<String> fields) {
        this(errorCode, message, fields, null);
    }

    public SearchException(SearchErrorCode errorCode, String message, List<String> fields, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.fields = fields;
    }
}
