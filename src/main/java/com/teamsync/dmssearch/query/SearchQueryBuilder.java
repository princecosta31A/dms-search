package com.teamsync.dmssearch.query;

import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Operator;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import com.teamsync.dmssearch.config.ElasticsearchProperties;
import com.teamsync.dmssearch.dto.request.RequestIdentity;
import com.teamsync.dmssearch.dto.request.SearchCriteria;
import com.teamsync.dmssearch.dto.request.SortKey;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Translates a {@link SearchCriteria} into an Elasticsearch request.
 *
 * <p>The single most important property of this class: <b>every query it
 * produces carries the tenant and workspace filter</b>. There is exactly one
 * method that builds a query, and it adds those two clauses before it looks at
 * anything the caller sent. There is no code path — no flag, no parameter, no
 * "admin" mode — that omits them. {@code SearchQueryBuilderTest} asserts this.
 *
 * <h2>Why {@code filter} and not {@code must}</h2>
 * Clauses in {@code filter} context are not scored and are cached in the node
 * query cache. Authorisation must not influence ranking, and these same two
 * clauses appear on every query from every user in a workspace, so caching them
 * is free throughput.
 */
@Component
@RequiredArgsConstructor
public class SearchQueryBuilder {

    /**
     * Fields kept out of every result.
     *
     * <p>Deliberately an <i>exclude</i> list, not an include list: a field added
     * to the mapping later then flows through automatically, whereas an include
     * list silently drops anything nobody remembered to add.
     *
     * <p>{@code content} is the whole point — it holds a document's full OCR
     * text. {@code path} and {@code bucket} are storage-layer details a search
     * result has no use for. ({@code attr}/{@code folderAttr} are deliberately
     * NOT excluded: callers use them to confirm a hit matched the field they
     * expected.)
     */
    public static final List<String> SOURCE_EXCLUDES = List.of("content", "path", "bucket");

    /** Unique tiebreaker appended to every sort. See {@link #applySort}. */
    private static final String TIEBREAKER_FIELD = "documentId";

    private final ElasticsearchProperties props;

    /**
     * Builds the search request.
     *
     * @param criteria what the caller asked for (already validated)
     * @param identity tenant/workspace from the gateway — the authorisation boundary
     * @param from     offset, or {@code null} when paging by cursor
     * @param searchAfter cursor sort values, or {@code null} for offset paging
     */
    public SearchRequest build(SearchCriteria criteria,
                               RequestIdentity identity,
                               Integer from,
                               List<FieldValue> searchAfter) {

        BoolQuery.Builder bool = new BoolQuery.Builder();

        // ── SECURITY BOUNDARY — first, unconditional, not caller-controllable ──
        bool.filter(termQuery("tenantId", identity.tenantId()));
        bool.filter(termQuery("workspaceId", identity.workspaceId()));

        // ── visibility ──
        if (!criteria.isIncludeDeleted()) {
            bool.filter(boolTermQuery("isDeleted", false));
        }
        if (!criteria.isIncludeArchived()) {
            bool.filter(boolTermQuery("isArchived", false));
        }

        // ── exact-match filters ──
        addTermIfPresent(bool, "documentTypeName", criteria.getDocumentTypeName());
        addTermIfPresent(bool, "documentTypeId", criteria.getDocumentTypeId());
        addTermIfPresent(bool, "folderId", criteria.getFolderId());
        addTermIfPresent(bool, "folderTypeId", criteria.getFolderTypeId());
        addTermIfPresent(bool, "folderTypeName", criteria.getFolderTypeName());
        addTermIfPresent(bool, "folderLifecycleState", criteria.getFolderLifecycleState());
        addTermIfPresent(bool, "fileExt", criteria.getFileExt());
        addTermIfPresent(bool, "validationStatus", criteria.getValidationStatus());

        // ── attribute lookups: exact, on the keyword field ──
        // NOT routed through searchAll. `match` defaults to OR and the analyzer
        // splits on hyphens, so "TOKEN-2345687654" would match a document whose
        // token is "TOKEN-20260819054206" purely because both contain "token".
        // attr is flattened -> stored verbatim, needs the case-insensitive flag.
        // folderAttr is normalized at index time -> plain term is correct and faster.
        addAttributeTerms(bool, "attr",       criteria.getAttributes(),       true);
        addAttributeTerms(bool, "folderAttr", criteria.getFolderAttributes(), false);

        // ── date ranges ──
        addDateRange(bool, "createdAt", criteria.getCreatedFrom(), criteria.getCreatedTo());
        addDateRange(bool, "updatedAt", criteria.getUpdatedFrom(), criteria.getUpdatedTo());

        // ── free text (the only scored clause) ──
        if (criteria.hasFreeText()) {
            String text = criteria.getQ().strip();
            // operator AND: every term must match. With the default OR, a query
            // of "TOKEN-2345687654" matches on the shared word "token" alone.
            //
            // The user's text is passed as a VALUE here, never interpolated into
            // a query_string — otherwise a caller could inject field names or
            // wildcards ("documentId:*") to probe fields they cannot see.
            bool.must(m -> m.match(mm -> mm
                    .field("searchAll")
                    .query(text)
                    .operator(Operator.And)));
        }

        SearchRequest.Builder request = new SearchRequest.Builder()
                // The ALIAS. Never a concrete index name.
                .index(props.getIndex())
                .query(q -> q.bool(bool.build()))
                .size(criteria.getSize())
                .source(s -> s.filter(f -> f.excludes(SOURCE_EXCLUDES)))
                // Bounds the query server-side too: without it a pathological
                // query keeps burning shard CPU after the client has given up.
                .timeout(props.getRequestTimeoutMs() + "ms");

        if (from != null) {
            request.from(from);
        }
        if (searchAfter != null && !searchAfter.isEmpty()) {
            request.searchAfter(searchAfter);
        }

        applySort(request, criteria);
        return request.build();
    }

    /**
     * Applies the requested sort plus a unique tiebreaker.
     *
     * <p>The tiebreaker is not optional. Many documents share a relevance score
     * (and plenty share a {@code createdAt}), and Elasticsearch orders ties
     * arbitrarily — an order that can differ between two identical requests.
     * Paging then repeats documents on one page and skips others entirely, and
     * {@code search_after} has no stable position to resume from.
     * {@code documentId} is unique, so appending it makes ordering total.
     */
    private void applySort(SearchRequest.Builder request, SearchCriteria criteria) {
        SortKey sort = criteria.getSort();

        // Relevance ranking is meaningless without a free-text term — every
        // document would score identically — so fall back to newest-first.
        if (sort == null || (sort == SortKey.RELEVANCE && !criteria.hasFreeText())) {
            sort = criteria.hasFreeText() ? SortKey.RELEVANCE : SortKey.CREATED_AT;
        }

        if (sort == SortKey.RELEVANCE) {
            request.sort(so -> so.score(sc -> sc.order(SortOrder.Desc)));
        } else {
            final SortKey resolved = sort;
            request.sort(so -> so.field(f -> f.field(resolved.field()).order(resolved.order())));
        }

        request.sort(so -> so.field(f -> f.field(TIEBREAKER_FIELD).order(SortOrder.Asc)));
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private void addTermIfPresent(BoolQuery.Builder bool, String field, String value) {
        if (value != null && !value.isBlank()) {
            bool.filter(termQuery(field, value.strip()));
        }
    }

    /**
     * @param caseInsensitive ask Elasticsearch to ignore case when matching.
     *        Required for {@code attr}, which is a {@code flattened} field: flattened
     *        does not accept a {@code normalizer} at all — ES rejects the mapping with
     *        {@code unknown parameter [normalizer] on mapper of type [flattened]} — so
     *        values are stored exactly as ingested (uppercase, e.g. {@code 49AA}) and a
     *        plain term query for {@code 49aa} silently returns zero hits.
     *        <p>Not needed for {@code folderAttr}: it is a normal object whose dynamic
     *        templates apply {@code lowercase_normalizer}, so the terms are already
     *        folded at index time and a plain term query is both correct and faster.
     */
    private void addAttributeTerms(BoolQuery.Builder bool, String prefix,
                                   Map<String, String> values, boolean caseInsensitive) {
        if (values == null || values.isEmpty()) {
            return;
        }
        values.forEach((key, value) -> {
            if (key != null && !key.isBlank() && value != null && !value.isBlank()) {
                String field = prefix + "." + key.strip();
                bool.filter(caseInsensitive
                        ? termQueryIgnoringCase(field, value.strip())
                        : termQuery(field, value.strip()));
            }
        });
    }

    private void addDateRange(BoolQuery.Builder bool, String field, String from, String to) {
        boolean hasFrom = from != null && !from.isBlank();
        boolean hasTo = to != null && !to.isBlank();
        if (!hasFrom && !hasTo) {
            return;
        }
        bool.filter(f -> f.range(r -> r.date(d -> {
            d.field(field);
            if (hasFrom) {
                d.gte(from.strip());
            }
            if (hasTo) {
                d.lte(to.strip());
            }
            return d;
        })));
    }

    private Query termQuery(String field, String value) {
        return Query.of(q -> q.term(t -> t.field(field).value(value)));
    }

    /** Term query that matches regardless of case — see {@link #addAttributeTerms}. */
    private Query termQueryIgnoringCase(String field, String value) {
        return Query.of(q -> q.term(t -> t.field(field).value(value).caseInsensitive(true)));
    }

    private Query boolTermQuery(String field, boolean value) {
        return Query.of(q -> q.term(t -> t.field(field).value(FieldValue.of(value))));
    }

    /** Sort field order used to build and interpret cursors — must match {@link #applySort}. */
    public static List<String> tiebreakerChain(SortKey sort, boolean hasFreeText) {
        List<String> chain = new ArrayList<>();
        SortKey resolved = (sort == null || (sort == SortKey.RELEVANCE && !hasFreeText))
                ? (hasFreeText ? SortKey.RELEVANCE : SortKey.CREATED_AT)
                : sort;
        chain.add(resolved == SortKey.RELEVANCE ? "_score" : resolved.field());
        chain.add(TIEBREAKER_FIELD);
        return chain;
    }
}
