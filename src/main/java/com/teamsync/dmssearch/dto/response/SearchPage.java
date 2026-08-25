package com.teamsync.dmssearch.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * A page of results plus everything a client needs to page through the rest.
 *
 * <p>Pagination metadata lives in the body rather than in {@code Link} headers
 * (RFC 8288). The platform already wraps every response in
 * {@link ApiResponse}, so clients look in the body; offering both would be two
 * mechanisms to keep in step for no gain.
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SearchPage {

    private List<DocumentSummary> items;

    /** Zero-based. Null when paging by cursor. */
    private Integer page;

    private int size;

    /**
     * Matching documents.
     *
     * <p>Elasticsearch stops counting at 10,000 by default, so this is a lower
     * bound once it reaches that. {@link #totalIsExact} says which it is.
     * Counting beyond the cap ({@code track_total_hits: true}) forces a full
     * match scan on every query — a real cost paid on every request to render a
     * number users rarely act on.
     */
    private long total;

    /** False when {@link #total} hit the counting cap — display as "10,000+". */
    private boolean totalIsExact;

    private boolean hasNext;

    /**
     * Opaque cursor for the next page. Present only when {@link #hasNext}.
     *
     * <p>Opaque means opaque: clients echo it back unmodified and never build or
     * parse one. Its encoding is an implementation detail and may change.
     */
    private String nextCursor;

    /** Elasticsearch's own reported query time, in ms — excludes network and
     *  serialisation, so it is the number to look at when tuning queries. */
    private long tookMs;
}
