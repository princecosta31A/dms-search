package com.teamsync.dmssearch.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.core.search.TotalHitsRelation;
import com.teamsync.dmssearch.config.ElasticsearchProperties;
import com.teamsync.dmssearch.dto.error.SearchErrorCode;
import com.teamsync.dmssearch.dto.request.RequestIdentity;
import com.teamsync.dmssearch.dto.request.SearchCriteria;
import com.teamsync.dmssearch.dto.response.DocumentSummary;
import com.teamsync.dmssearch.dto.response.SearchPage;
import com.teamsync.dmssearch.exception.SearchException;
import com.teamsync.dmssearch.query.CursorCodec;
import com.teamsync.dmssearch.query.SearchQueryBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Executes searches and shapes the response.
 *
 * <p>Read-only by construction: this service issues {@code _search} and nothing
 * else. es-ingestion owns document creation; es-update owns mutation.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SearchService {

    private final ElasticsearchClient es;
    private final SearchQueryBuilder queryBuilder;
    private final ElasticsearchProperties props;
    private final CursorCodec cursorCodec;
    private final SearchAuditLogger auditLogger;

    public SearchPage search(SearchCriteria criteria, RequestIdentity identity) {
        boolean byCursor = criteria.getCursor() != null && !criteria.getCursor().isBlank();

        Integer from = null;
        List<FieldValue> searchAfter = null;

        if (byCursor) {
            searchAfter = cursorCodec.decode(criteria.getCursor());
        } else {
            from = criteria.getPage() * criteria.getSize();
            guardOffsetWindow(from, criteria.getSize());
        }

        SearchRequest request = queryBuilder.build(criteria, identity, from, searchAfter);

        long startedNanos = System.nanoTime();
        SearchResponse<DocumentSummary> response;
        try {
            response = es.search(request, DocumentSummary.class);
        } catch (Exception e) {
            // 503, not 500: the request was fine and is worth retrying. Returning
            // 500 would tell clients (and their retry policies) the opposite.
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
    private void guardOffsetWindow(int from, int size) {
        long window = (long) from + size;
        if (window > props.getMaxResultWindow()) {
            throw new SearchException(
                    SearchErrorCode.PAGINATION_LIMIT_EXCEEDED,
                    "page * size + size must not exceed " + props.getMaxResultWindow()
                            + " (requested " + window + "). Use the cursor to page deeper.",
                    List.of("page", "size"));
        }
    }

    private SearchPage toPage(SearchResponse<DocumentSummary> response,
                              SearchCriteria criteria,
                              boolean byCursor) {

        List<Hit<DocumentSummary>> hits = response.hits().hits();
        List<DocumentSummary> items = new ArrayList<>(hits.size());

        for (Hit<DocumentSummary> hit : hits) {
            DocumentSummary doc = hit.source();
            if (doc == null) {
                continue;
            }
            // Only meaningful when something was actually scored; without free
            // text every document scores identically and the number is noise.
            if (criteria.hasFreeText()) {
                doc.setScore(hit.score());
            }
            items.add(doc);
        }

        long total = 0;
        boolean exact = true;
        if (response.hits().total() != null) {
            total = response.hits().total().value();
            exact = response.hits().total().relation() == TotalHitsRelation.Eq;
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
                .hasNext(hasNext)
                .nextCursor(nextCursor)
                .tookMs(response.took())
                .build();
    }
}
