package com.teamsync.dmssearch.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.core.search.TotalHitsRelation;
import com.teamsync.dmssearch.config.ElasticsearchProperties;
import com.teamsync.dmssearch.dto.error.SearchErrorCode;
import com.teamsync.dmssearch.dto.request.RequestIdentity;
import com.teamsync.dmssearch.dto.request.SearchCriteria;
import com.teamsync.dmssearch.dto.response.SearchHit;
import com.teamsync.dmssearch.dto.response.SearchPage;
import com.teamsync.dmssearch.exception.SearchException;
import com.teamsync.dmssearch.query.CursorCodec;
import com.teamsync.dmssearch.query.QueryBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * Executes searches and shapes the response.
 *
 * <p>Read-only by construction: this service issues {@code _search} and nothing
 * else. es-ingestion owns document creation; es-update owns mutation.
 *
 * <h2>One executor, two mappings</h2>
 * Deliberately NOT annotated {@code @Service}. Two instances exist — one per API
 * version — created in {@code SearchServiceConfig}, each wired with its own
 * {@link QueryBuilder}:
 *
 * <ul>
 *   <li>v1.0 with {@code LegacyQueryBuilder} over the legacy production index</li>
 *   <li>v1.1 with {@code SearchQueryBuilder} over the optimized index</li>
 * </ul>
 *
 * <p>Nothing in this class knows a field name, a query shape, or which DTO it is
 * deserialising into — the builder supplies all three. What lives here is the
 * part that is identical for any mapping and genuinely hard to get right:
 * distinguishing a rejected query from an unavailable cluster, the offset-window
 * guard, cursor paging, and noticing that Elasticsearch returned PARTIAL results
 * rather than reporting a truncated search as a complete one.
 *
 * <p>Duplicating that per version is what this arrangement avoids. Two copies of
 * this logic would drift, and the half that drifts is the half nobody is
 * currently looking at.
 */
@Slf4j
@RequiredArgsConstructor
public class SearchService {

    private final ElasticsearchClient es;
    private final QueryBuilder queryBuilder;
    private final ElasticsearchProperties props;
    private final CursorCodec cursorCodec;
    private final SearchAuditLogger auditLogger;

    /**
     * Runs the search for whichever mapping this instance was wired with.
     *
     * <p>Delegates immediately so the body can be generic over the result DTO:
     * the builder names its own type, and nothing in here needs to know which.
     */
    public SearchPage search(SearchCriteria criteria, RequestIdentity identity) {
        return doSearch(criteria, identity, queryBuilder.documentType());
    }

    private <T extends SearchHit> SearchPage doSearch(SearchCriteria criteria,
                                                      RequestIdentity identity,
                                                      Class<T> documentType) {
        boolean byCursor = criteria.getCursor() != null && !criteria.getCursor().isBlank();

        Integer from = null;
        List<FieldValue> searchAfter = null;

        if (byCursor) {
            searchAfter = cursorCodec.decode(criteria.getCursor());
        } else {
            // long, not int. page and size are both ints, so page * size silently
            // overflows: page=25,000,000 x size=100 is 2.5 billion, which wraps to
            // -1,794,967,296. A negative offset sails through guardOffsetWindow
            // (it only checks the upper bound) and reaches Elasticsearch, which
            // rejects it — turning a caller's typo into a backend error.
            long offset = (long) criteria.getPage() * criteria.getSize();
            guardOffsetWindow(offset, criteria.getSize());
            from = (int) offset;   // safe: the guard proved it fits the window
        }

        SearchRequest request = queryBuilder.build(criteria, identity, from, searchAfter);

        long startedNanos = System.nanoTime();
        SearchResponse<T> response;
        try {
            response = es.search(request, documentType);
        } catch (ElasticsearchException e) {
            // Elasticsearch ANSWERED — with a status. A 4xx means it read our
            // request and refused it, so the query we built was malformed: an
            // unparseable date, a negative offset, a cursor that does not match
            // the sort. Retrying cannot change that answer, and reporting 503
            // would tell every retry policy in the fleet to send it again.
            //
            // Only the error TYPE is surfaced, never e.getMessage(): Elasticsearch
            // echoes the offending query back in its reason, and that query can
            // carry a caller's search terms.
            auditLogger.logFailure(criteria, identity, e);

            int status = e.status();
            if (status >= 400 && status < 500) {
                String type = (e.error() != null && e.error().type() != null)
                        ? e.error().type() : "unknown";
                log.warn("[Search] Elasticsearch rejected the query — status={} type={}", status, type);
                throw new SearchException(
                        SearchErrorCode.BACKEND_REJECTED_QUERY,
                        SearchErrorCode.BACKEND_REJECTED_QUERY.getDefaultMessage() + " (" + type + ")",
                        null, e);
            }

            // 5xx from Elasticsearch: it is genuinely struggling, retry is right.
            log.error("[Search] Elasticsearch error — status={}", status);
            throw new SearchException(
                    SearchErrorCode.SEARCH_BACKEND_UNAVAILABLE,
                    SearchErrorCode.SEARCH_BACKEND_UNAVAILABLE.getDefaultMessage(),
                    null, e);

        } catch (Exception e) {
            // No answer at all — IOException, connection refused, socket timeout.
            // 503, not 500: the request itself was fine and is worth retrying.
            auditLogger.logFailure(criteria, identity, e);
            throw new SearchException(
                    SearchErrorCode.SEARCH_BACKEND_UNAVAILABLE,
                    SearchErrorCode.SEARCH_BACKEND_UNAVAILABLE.getDefaultMessage(),
                    null, e);
        }
        long elapsedMs = (System.nanoTime() - startedNanos) / 1_000_000;

        SearchPage page = toPage(response, criteria, byCursor);
        auditLogger.logSearch(criteria, identity, page, elapsedMs);
        return page;
    }

    /**
     * Rejects an offset that Elasticsearch would refuse anyway, with an error the
     * caller can act on.
     *
     * <p>Letting it through surfaces a raw {@code search_phase_execution_exception}
     * as a 500, which reads like a server fault rather than "use the cursor".
     *
     * <p>The limit is not arbitrary: with 4 shards, {@code from=9980} makes every
     * shard return 10,000 hits to the coordinating node so it can sort 40,000 and
     * discard 39,980. That cost is why deep paging needs {@code search_after}.
     */
    private void guardOffsetWindow(long from, int size) {
        // Rejects a negative offset too. It cannot arrive from a valid request —
        // page and size are validated non-negative — but it is exactly what an
        // overflow upstream would produce, and a guard that only checks the upper
        // bound would wave it through to Elasticsearch.
        long window = from + size;
        if (from < 0 || window > props.getMaxResultWindow()) {
            throw new SearchException(
                    SearchErrorCode.PAGINATION_LIMIT_EXCEEDED,
                    "page * size + size must not exceed " + props.getMaxResultWindow()
                            + " (requested " + window + "). Use the cursor to page deeper.",
                    List.of("page", "size"));
        }
    }

    private <T extends SearchHit> SearchPage toPage(SearchResponse<T> response,
                                                    SearchCriteria criteria,
                                                    boolean byCursor) {

        List<Hit<T>> hits = response.hits().hits();
        // Object, not T: the builder decides what goes on the wire, and for v1.0
        // that is NOT the DTO the hit deserialised into. See
        // QueryBuilder#toApiShape.
        List<Object> items = new ArrayList<>(hits.size());

        for (Hit<T> hit : hits) {
            T doc = hit.source();
            if (doc == null) {
                continue;
            }
            // Only meaningful when something was actually scored; without free
            // text every document scores identically and the number is noise.
            // Stamped BEFORE shaping, so a version whose wire DTO carries the
            // score can copy it across.
            if (criteria.hasFreeText()) {
                doc.setScore(hit.score());
            }
            items.add(queryBuilder.toApiShape(doc));
        }

        // Elasticsearch's timeout is best-effort: it does not abort the search, it
        // returns whatever the shards reported so far and raises this flag. Ignoring
        // it means a search that covered a quarter of the data is served as the whole
        // answer — HTTP 200, documents on screen, a total that is simply wrong.
        boolean complete = !Boolean.TRUE.equals(response.timedOut());

        long total = 0;
        boolean exact = true;
        if (response.hits().total() != null) {
            total = response.hits().total().value();
            exact = response.hits().total().relation() == TotalHitsRelation.Eq;
        }

        // A search that did not finish cannot have produced an exact count. Without
        // this, a truncated search reports "800 results, exact" — a confident lie,
        // and the one case a caller has no way to detect.
        if (!complete) {
            exact = false;
            log.warn("[Search] Elasticsearch timed out — partial results returned. "
                    + "hits={} reportedTotal={} tookMs={}. Repeated occurrences mean the "
                    + "query is too broad or the cluster is under-provisioned.",
                    hits.size(), total, response.took());
        }

        // A full page implies there may be more. Elasticsearch does not report
        // "has more", and asking for size+1 to find out would mean trimming on
        // every page; a full page is the conventional, cheap signal.
        boolean hasNext = hits.size() == criteria.getSize();

        String nextCursor = null;
        if (hasNext) {
            List<FieldValue> lastSort = hits.get(hits.size() - 1).sort();
            if (lastSort != null && !lastSort.isEmpty()) {
                nextCursor = cursorCodec.encode(lastSort);
            }
        }

        return SearchPage.builder()
                .items(items)
                .page(byCursor ? null : criteria.getPage())
                .size(criteria.getSize())
                .total(total)
                .totalIsExact(exact)
                .complete(complete)
                .hasNext(hasNext)
                .nextCursor(nextCursor)
                .tookMs(response.took())
                .build();
    }
}
