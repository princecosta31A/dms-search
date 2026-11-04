package com.teamsync.dmssearch.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.ErrorResponse;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.TotalHitsRelation;
import com.teamsync.dmssearch.config.ElasticsearchProperties;
import com.teamsync.dmssearch.dto.error.SearchErrorCode;
import com.teamsync.dmssearch.dto.request.RequestIdentity;
import com.teamsync.dmssearch.dto.request.SearchCriteria;
import com.teamsync.dmssearch.dto.response.DocumentSummary;
import com.teamsync.dmssearch.dto.response.SearchPage;
import com.teamsync.dmssearch.query.CursorCodec;
import com.teamsync.dmssearch.query.SearchQueryBuilder;
import com.teamsync.dmssearch.query.TenantFilter;
import com.teamsync.dmssearch.exception.SearchException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The distinction these tests exist for: Elasticsearch <i>answering "your query is
 * malformed"</i> is not the same as Elasticsearch <i>not answering</i>.
 *
 * <p>Both used to arrive as {@code 503 SEARCH_BACKEND_UNAVAILABLE}, which tells
 * every retry policy in the fleet to re-send a request that can never succeed —
 * an unparseable date will still be unparseable on the tenth attempt. It is the
 * same mistake {@code PermissionValidationService} made with a 404 from
 * permission-service, in a different file.
 */
class SearchServiceTest {

    private static final RequestIdentity IDENTITY =
            new RequestIdentity("tenant-1", "workspace-1", "user-42");

    private ElasticsearchClient es;
    private SearchService service;

    @BeforeEach
    void setUp() {
        es = mock(ElasticsearchClient.class);

        ElasticsearchProperties props = new ElasticsearchProperties();
        props.setIndex("dms-documents");
        props.setMaxResultWindow(10000);
        props.setRequestTimeoutMs(5000);

        service = new SearchService(
                es,
                new SearchQueryBuilder(props, new TenantFilter()),
                props,
                new CursorCodec(),
                mock(SearchAuditLogger.class));
    }

    private SearchCriteria criteria() {
        return SearchCriteria.builder().page(0).size(20).build();
    }

    /** Builds a real ElasticsearchException carrying the given HTTP status. */
    private ElasticsearchException esError(int status, String type) {
        ErrorResponse response = ErrorResponse.of(r -> r
                .status(status)
                .error(c -> c.type(type).reason("simulated")));
        return new ElasticsearchException("search", response);
    }

    // ------------------------------------------------------------------
    // Elasticsearch answered — 4xx means WE built a bad query
    // ------------------------------------------------------------------

    @Test
    @DisplayName("400 from Elasticsearch -> 400 BACKEND_REJECTED_QUERY, not 503")
    void badQueryIsClientError() throws Exception {
        // The exact defect this class was written for. A caller sending
        // ?createdFrom=lastweek gets a parse_exception back from Elasticsearch;
        // answering 503 would have clients retry a doomed request forever.
        doThrow(esError(400, "parse_exception"))
                .when(es).search(any(SearchRequest.class), any(Class.class));

        assertThatThrownBy(() -> service.search(criteria(), IDENTITY))
                .isInstanceOf(SearchException.class)
                .satisfies(e -> assertThat(((SearchException) e).getErrorCode())
                        .isEqualTo(SearchErrorCode.BACKEND_REJECTED_QUERY));
    }

    @Test
    @DisplayName("the Elasticsearch error TYPE is surfaced, the reason is not")
    void surfacesTypeNotReason() throws Exception {
        // Elasticsearch echoes the offending query back inside its reason, and
        // that query carries the caller's search terms — PAN numbers, names.
        // Only the short type name may reach the client.
        doThrow(esError(400, "illegal_argument_exception"))
                .when(es).search(any(SearchRequest.class), any(Class.class));

        assertThatThrownBy(() -> service.search(criteria(), IDENTITY))
                .hasMessageContaining("illegal_argument_exception")
                .hasMessageNotContaining("simulated");
    }

    @Test
    @DisplayName("404 from Elasticsearch is also a client error")
    void otherClientErrorsAreAlsoRejections() throws Exception {
        doThrow(esError(404, "index_not_found_exception"))
                .when(es).search(any(SearchRequest.class), any(Class.class));

        assertThatThrownBy(() -> service.search(criteria(), IDENTITY))
                .satisfies(e -> assertThat(((SearchException) e).getErrorCode())
                        .isEqualTo(SearchErrorCode.BACKEND_REJECTED_QUERY));
    }

    // ------------------------------------------------------------------
    // Elasticsearch is genuinely struggling, or never answered
    // ------------------------------------------------------------------

    @Test
    @DisplayName("503 from Elasticsearch stays 503 — retrying is right")
    void serverErrorStaysUnavailable() throws Exception {
        doThrow(esError(503, "cluster_block_exception"))
                .when(es).search(any(SearchRequest.class), any(Class.class));

        assertThatThrownBy(() -> service.search(criteria(), IDENTITY))
                .satisfies(e -> assertThat(((SearchException) e).getErrorCode())
                        .isEqualTo(SearchErrorCode.SEARCH_BACKEND_UNAVAILABLE));
    }

    @Test
    @DisplayName("no answer at all (IOException) -> 503")
    void unreachableIsUnavailable() throws Exception {
        // The caller's query may be perfectly valid — we could not find out.
        doThrow(new IOException("Connection refused"))
                .when(es).search(any(SearchRequest.class), any(Class.class));

        assertThatThrownBy(() -> service.search(criteria(), IDENTITY))
                .satisfies(e -> assertThat(((SearchException) e).getErrorCode())
                        .isEqualTo(SearchErrorCode.SEARCH_BACKEND_UNAVAILABLE));
    }

    // ------------------------------------------------------------------
    // Pagination guard
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a page number large enough to overflow int is rejected, not sent to Elasticsearch")
    void hugePageNumberCannotOverflowPastTheGuard() throws Exception {
        // page * size was computed as int * int: 25,000,000 x 100 = 2.5 billion,
        // which wraps to -1,794,967,296. A guard checking only the upper bound
        // waved that through, Elasticsearch rejected the negative offset, and a
        // caller's typo surfaced as a backend failure.
        SearchCriteria huge = SearchCriteria.builder().page(25_000_000).size(100).build();

        assertThatThrownBy(() -> service.search(huge, IDENTITY))
                .isInstanceOf(SearchException.class)
                .satisfies(e -> assertThat(((SearchException) e).getErrorCode())
                        .isEqualTo(SearchErrorCode.PAGINATION_LIMIT_EXCEEDED));

        // And it never reached the cluster.
        verify(es, never()).search(any(SearchRequest.class), any(Class.class));
    }

    @Test
    @DisplayName("the last reachable page is still allowed")
    void lastPageWithinTheWindowIsAllowed() throws Exception {
        // from=9980 + size=20 lands exactly on max_result_window. Off-by-one here
        // would silently cost callers the final page.
        when(es.search(any(SearchRequest.class), any(Class.class)))
                .thenReturn(responseWith(false, 10000, false));

        SearchCriteria lastPage = SearchCriteria.builder().page(499).size(20).build();

        assertThat(service.search(lastPage, IDENTITY)).isNotNull();
    }

    // ------------------------------------------------------------------
    // Elasticsearch answered, but did not finish
    // ------------------------------------------------------------------

    /**
     * Builds a REAL SearchResponse rather than a mock.
     *
     * <p>The Elasticsearch client's response types are immutable with final
     * accessors — {@code TotalHits.value()} cannot be stubbed, and Mockito fails
     * with UnfinishedStubbingException rather than saying so plainly. Building the
     * genuine object through its builder is both possible and a truer test.
     */
    private SearchResponse<DocumentSummary> responseWith(boolean timedOut, long total, boolean exactTotal) {
        return SearchResponse.of(r -> r
                .took(42)
                .timedOut(timedOut)
                .shards(s -> s.total(4).successful(4).failed(0))
                .hits(h -> h
                        .hits(List.of())
                        .total(t -> t
                                .value(total)
                                .relation(exactTotal ? TotalHitsRelation.Eq : TotalHitsRelation.Gte))));
    }

    @Test
    @DisplayName("a completed search reports complete=true")
    void completedSearchIsComplete() throws Exception {
        when(es.search(any(SearchRequest.class), any(Class.class)))
                .thenReturn(responseWith(false, 800, true));

        SearchPage page = service.search(criteria(), IDENTITY);

        assertThat(page.isComplete()).isTrue();
        assertThat(page.isTotalIsExact()).isTrue();
        assertThat(page.getTotal()).isEqualTo(800);
    }

    @Test
    @DisplayName("a timed-out search reports complete=false AND forces totalIsExact=false")
    void timedOutSearchCannotClaimAnExactTotal() throws Exception {
        // The defect this guards. Elasticsearch does not abort on timeout — it
        // returns what the shards had so far, with timed_out: true. Reporting
        // "800 results, exact" from a search that covered a quarter of the data
        // is a confident lie the caller has no way to detect.
        //
        // Note Elasticsearch itself still says the count is exact (Eq) here; it is
        // exact for the shards that answered. Across the whole index it is not.
        when(es.search(any(SearchRequest.class), any(Class.class)))
                .thenReturn(responseWith(true, 800, true));

        SearchPage page = service.search(criteria(), IDENTITY);

        assertThat(page.isComplete()).isFalse();
        assertThat(page.isTotalIsExact())
                .as("a search that did not finish cannot have counted exactly")
                .isFalse();
    }

    @Test
    @DisplayName("a capped total on a COMPLETED search is not treated as a failure")
    void cappedTotalIsStillComplete() throws Exception {
        // The healthy broad-search case: Elasticsearch finished, it simply stopped
        // counting at 10,000. complete and totalIsExact answer different questions,
        // and conflating them would flag every large search as broken.
        when(es.search(any(SearchRequest.class), any(Class.class)))
                .thenReturn(responseWith(false, 10000, false));

        SearchPage page = service.search(criteria(), IDENTITY);

        assertThat(page.isComplete()).isTrue();
        assertThat(page.isTotalIsExact()).isFalse();
    }
}
