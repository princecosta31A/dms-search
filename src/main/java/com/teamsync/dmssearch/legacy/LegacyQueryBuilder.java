package com.teamsync.dmssearch.legacy;

import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.ChildScoreMode;
import co.elastic.clients.elasticsearch._types.query_dsl.Operator;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import com.teamsync.dmssearch.config.ElasticsearchProperties;
import com.teamsync.dmssearch.dto.error.SearchErrorCode;
import com.teamsync.dmssearch.dto.request.RequestIdentity;
import com.teamsync.dmssearch.dto.request.SearchCriteria;
import com.teamsync.dmssearch.dto.request.SortKey;
import com.teamsync.dmssearch.dto.response.SearchHit;
import com.teamsync.dmssearch.exception.SearchException;
import com.teamsync.dmssearch.query.QueryBuilder;
import com.teamsync.dmssearch.query.TenantFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * v1.0 — builds queries against the LEGACY production mapping
 * ({@code teamsync-dms-documents}).
 *
 * <p>Independent of {@code SearchQueryBuilder} (v1.1) by design. They share no
 * field names and no branching; the only common code is {@link TenantFilter},
 * which is shared on purpose so tenant isolation has one implementation rather
 * than two that can drift.
 *
 * <h2>How this mapping differs, and why that is not a rename</h2>
 * <table border="1">
 *   <tr><th>Concern</th><th>legacy (v1.0)</th><th>optimized (v1.1)</th></tr>
 *   <tr><td>document attributes</td><td>{@code nested} array of key/value</td>
 *       <td>{@code flattened} object</td></tr>
 *   <tr><td>folder attributes</td><td>{@code flattened} (no normalizer)</td>
 *       <td>object with {@code lowercase_normalizer}</td></tr>
 *   <tr><td>global search field</td><td>none</td><td>{@code searchAll}</td></tr>
 *   <tr><td>soft delete</td><td>{@code in_trash}</td><td>{@code isDeleted}</td></tr>
 *   <tr><td>archive flag</td><td><b>absent</b></td><td>{@code isArchived}</td></tr>
 * </table>
 *
 * <h2>Case sensitivity is INVERTED between the two mappings</h2>
 * This is the subtlest trap here, and it silently returns zero hits when got
 * wrong. A {@code term} query against a {@code keyword} field DOES get the
 * field normalizer applied to the query value — but {@code flattened} fields
 * cannot declare a normalizer at all. So:
 *
 * <ul>
 *   <li>legacy {@code attributes.value.keyword} HAS a normalizer, so a plain
 *       term is correct and faster. The v1.1 equivalent {@code attr} is
 *       flattened and needs the case-insensitive flag — the opposite.</li>
 *   <li>legacy {@code folder_type_attributes} is flattened, so it needs
 *       {@code caseInsensitive}. The v1.1 equivalent {@code folderAttr} is
 *       normalized and does not — again the opposite.</li>
 * </ul>
 *
 * <p>Several legacy keywords also carry NO normalizer where the v1.1 equivalents
 * do ({@code folder_id}, {@code folder_type_id}, {@code folder_type_name},
 * {@code folder_lifecycle_state}, {@code validation_status}), so those need the
 * flag too. {@code document_type} and {@code document_ext} DO have one.
 *
 * <h2>What v1.0 cannot do</h2>
 * Verified against a live production document, not inferred from the mapping:
 * <ul>
 *   <li><b>Archive filtering</b> — no field exists anywhere. {@code includeArchived}
 *       is ignored rather than faked.</li>
 *   <li><b>Filter by {@code documentTypeId}</b> — absent from the mapping AND
 *       from {@code _source}. Rejected with a clear 400 instead of silently
 *       matching nothing, which would read as "no such documents".</li>
 *   <li><b>Subtree search</b> — {@code path} holds a bare folder id with no
 *       separators, so the {@code path_hierarchy} analyzer yields a single
 *       token. Not exposed.</li>
 *   <li><b>Date and number ranges on attributes</b> — {@code value_date} and
 *       {@code value_number} are mapped but never populated.</li>
 *   <li><b>Free text on underscore-joined segments</b> — the index was written
 *       with the {@code standard} tokenizer, which treats underscore as a
 *       joiner, so a name like
 *       {@code 117-434-260812213869103-20260812_095825975.pdf} indexes its last
 *       segment whole. Searching {@code 095825975} cannot match. Fixing that
 *       needs the index reanalysed, i.e. a reindex — out of scope for v1.0.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class LegacyQueryBuilder implements QueryBuilder {

    public static final String API_VERSION = "1.0";

    /**
     * Fields kept out of every result.
     *
     * <p>Just {@code content}, and it is the whole point: in production it holds
     * the full OCR text of a document, kilobytes per hit. Returning it for 20
     * hits would make one page of results megabytes of data nothing renders.
     *
     * <p>{@code path} and {@code bucket} were excluded here too, and are no
     * longer: they back {@code storageKey} and {@code bucketName}, which
     * document-service's {@code DocumentResponse} returns. Two short keyword
     * fields per hit is a cost worth paying for contract parity; the OCR text is
     * not.
     */
    public static final List<String> SOURCE_EXCLUDES = List.of("content");

    // ── legacy field names ────────────────────────────────────────────────
    private static final String F_TENANT           = "tenant_id";
    private static final String F_WORKSPACE        = "workspace_id";
    private static final String F_IN_TRASH         = "in_trash";
    private static final String F_DOC_TYPE         = "document_type";
    private static final String F_FOLDER_ID        = "folder_id";
    private static final String F_FOLDER_TYPE_ID   = "folder_type_id";
    private static final String F_FOLDER_TYPE_NAME = "folder_type_name";
    private static final String F_LIFECYCLE        = "folder_lifecycle_state";
    private static final String F_DOC_EXT          = "document_ext";
    private static final String F_VALIDATION       = "validation_status";
    private static final String F_CREATED_AT       = "created_at";
    private static final String F_UPDATED_AT       = "updated_at";
    private static final String F_DOC_NAME         = "document_name";
    private static final String F_DOC_NAME_TEXT    = "document_name.text";
    private static final String F_DOC_ID           = "document_id";

    /** The {@code nested} path and its leaves. */
    private static final String N_ATTRS          = "attributes";
    private static final String N_ATTRS_KEY      = "attributes.key";
    private static final String N_ATTRS_VALUE    = "attributes.value";
    private static final String N_ATTRS_VALUE_KW = "attributes.value.keyword";

    /** The {@code flattened} folder-attribute object. */
    private static final String F_FOLDER_ATTRS = "folder_type_attributes";

    /** Unique tiebreaker appended to every sort. */
    private static final String TIEBREAKER_FIELD = F_DOC_ID;

    /**
     * API sort name to legacy field. Kept here, not on {@link SortKey}: that enum
     * is the client-facing contract and must not learn either mapping spelling.
     *
     * <p>{@code document_name} has no normalizer in this mapping, so A-to-Z
     * sorting is case-sensitive on v1.0 (uppercase sorts before lowercase),
     * whereas v1.1 sorts case-insensitively. A known, accepted difference.
     */
    private static final Map<SortKey, String> LEGACY_SORT_FIELD = new EnumMap<>(Map.of(
            SortKey.CREATED_AT, F_CREATED_AT,
            SortKey.UPDATED_AT, F_UPDATED_AT,
            SortKey.FILE_NAME,  F_DOC_NAME));

    private final ElasticsearchProperties props;
    private final TenantFilter tenantFilter;
    private final LegacyDocumentResponseMapper responseMapper;

    @Override
    public Class<? extends SearchHit> documentType() {
        return LegacyDocumentSummary.class;
    }

    /**
     * Reshapes the raw index hit into document-service's
     * {@code DocumentResponse} contract.
     *
     * <p>v1.0 replaces document-service's {@code /search/token},
     * {@code /search/pan} and {@code /search/acknowledgement} endpoints, which
     * all return that shape. Emitting it here makes the move to this service a
     * URL change for existing clients instead of a parser change.
     *
     * <p>The cast is safe: {@code SearchService} only ever passes back a hit it
     * deserialised into {@link #documentType()}, which is this builder's own DTO.
     */
    @Override
    public Object toApiShape(SearchHit hit) {
        return responseMapper.toResponse((LegacyDocumentSummary) hit);
    }

    @Override
    public String apiVersion() {
        return API_VERSION;
    }

    @Override
    public SearchRequest build(SearchCriteria criteria,
                               RequestIdentity identity,
                               Integer from,
                               List<FieldValue> searchAfter) {

        rejectUnsupported(criteria);

        // ── SECURITY BOUNDARY ──
        // The builder cannot be obtained without the tenant/workspace filters
        // already on it — see TenantFilter.
        BoolQuery.Builder bool = tenantFilter.scopedQuery(identity, F_TENANT, F_WORKSPACE);

        // ── visibility ──
        if (!criteria.isIncludeDeleted()) {
            bool.filter(boolTerm(F_IN_TRASH, false));
        }
        // includeArchived is deliberately NOT honoured: this mapping has no
        // archive field. Applying a filter on a non-existent field would match
        // zero documents and make every search look empty.

        // ── exact-match filters ──
        // document_type HAS a normalizer, so a plain term is right: ES folds the
        // query value for a normalized keyword field.
        addTerm(bool, F_DOC_TYPE, criteria.getDocumentTypeName(), false);
        // These have NO normalizer in this mapping, so case must be ignored
        // explicitly or a lowercase input silently matches nothing.
        addTerm(bool, F_FOLDER_ID,        criteria.getFolderId(),             true);
        addTerm(bool, F_FOLDER_TYPE_ID,   criteria.getFolderTypeId(),         true);
        addTerm(bool, F_FOLDER_TYPE_NAME, criteria.getFolderTypeName(),       true);
        addTerm(bool, F_LIFECYCLE,        criteria.getFolderLifecycleState(), true);
        addTerm(bool, F_VALIDATION,       criteria.getValidationStatus(),     true);
        // document_ext HAS a normalizer, but the stored value carries a leading
        // dot, so normalise the input instead of making the caller know that.
        addTerm(bool, F_DOC_EXT, normaliseExtension(criteria.getFileExt()), false);

        // ── attribute lookups ──
        addNestedAttributeTerms(bool, criteria.getAttributes());
        addFolderAttributeTerms(bool, criteria.getFolderAttributes());

        // ── date ranges ──
        addDateRange(bool, F_CREATED_AT, criteria.getCreatedFrom(), criteria.getCreatedTo());
        addDateRange(bool, F_UPDATED_AT, criteria.getUpdatedFrom(), criteria.getUpdatedTo());

        // ── free text (the only scored clause) ──
        if (criteria.hasFreeText()) {
            bool.must(freeText(criteria.getQ().strip()));
        }

        SearchRequest.Builder request = new SearchRequest.Builder()
                // The legacy PRODUCTION index, named directly. v1.1 queries an
                // alias; this index has none, and creating one in production is a
                // separate change this version must not depend on.
                .index(props.getLegacyIndex())
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

    // ------------------------------------------------------------------
    // unsupported parameters
    // ------------------------------------------------------------------

    /**
     * Refuses parameters this mapping physically cannot serve.
     *
     * <p>A 400 naming the alternative, rather than running the query and
     * returning an empty page. An empty page is indistinguishable from "no such
     * documents" and would send the caller hunting for missing data instead of
     * a missing field.
     */
    private void rejectUnsupported(SearchCriteria criteria) {
        if (!isBlank(criteria.getDocumentTypeId())) {
            throw new SearchException(
                    SearchErrorCode.INVALID_PARAMETER,
                    "documentTypeId is not available on API version " + API_VERSION
                            + " (the field is not indexed). Filter by documentTypeName, "
                            + "or use version 1.1.",
                    List.of("documentTypeId"));
        }
    }

    // ------------------------------------------------------------------
    // attributes
    // ------------------------------------------------------------------

    /**
     * Document attribute lookup against a {@code nested} array.
     *
     * <p>One {@code nested} query per pair, with the key and value terms inside
     * the SAME nested query. That is the entire reason the mapping uses
     * {@code nested}: two separate clauses would match a document holding the
     * key in one array entry and the value in a different one, so a document
     * whose {@code POI_Flag} is {@code false} and whose {@code formType} is
     * {@code fn135} would satisfy a search for {@code POI_Flag=fn135}.
     *
     * <p>Matched on {@code value.keyword}, not {@code value}: {@code value} is
     * analysed, so matching there would hit on shared tokens — searching
     * {@code TOKEN-2345687654} would find a document whose token is
     * {@code TOKEN-20260819054206} purely because both contain {@code token}.
     * Both {@code key} and {@code value.keyword} carry
     * {@code lowercase_normalizer}, so plain terms are correct here.
     */
    private void addNestedAttributeTerms(BoolQuery.Builder bool, Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        values.forEach((key, value) -> {
            if (isBlank(key) || isBlank(value)) {
                return;
            }
            Query inner = Query.of(q -> q.bool(b -> b
                    .filter(exactTerm(N_ATTRS_KEY, key.strip()))
                    .filter(exactTerm(N_ATTRS_VALUE_KW, value.strip()))));

            bool.filter(f -> f.nested(n -> n
                    .path(N_ATTRS)
                    .query(inner)
                    // Filter context: nothing is scored, so there is no child
                    // score to combine.
                    .scoreMode(ChildScoreMode.None)));
        });
    }

    /**
     * Folder attribute lookup against the {@code flattened} object.
     *
     * <p>Subfield access on a flattened field is a plain {@code term} on
     * {@code folder_type_attributes.<key>}. Case-insensitive is mandatory:
     * {@code flattened} cannot declare a normalizer — ES rejects the mapping
     * with {@code unknown parameter [normalizer] on mapper of type [flattened]}
     * — so values are indexed exactly as ingested and a term query for
     * {@code abcde1234f} would miss a stored {@code ABCDE1234F}.
     */
    private void addFolderAttributeTerms(BoolQuery.Builder bool, Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        values.forEach((key, value) -> {
            if (isBlank(key) || isBlank(value)) {
                return;
            }
            bool.filter(caseInsensitiveTerm(F_FOLDER_ATTRS + "." + key.strip(), value.strip()));
        });
    }

    // ------------------------------------------------------------------
    // free text
    // ------------------------------------------------------------------

    /**
     * The v1.0 search box. This mapping has no {@code searchAll} field, so the
     * three places a term could live are queried together.
     *
     * <ul>
     *   <li>{@code document_name.text} — analysed, so partial words work.</li>
     *   <li>{@code attributes.value} inside a {@code nested} query — analysed.</li>
     *   <li>{@code folder_type_attributes} — {@code flattened}, therefore
     *       unanalysed: this clause matches only a COMPLETE value. That is
     *       exactly right for the identifiers stored there
     *       ({@code token_number}, {@code acknowledgement_number}), which users
     *       paste in full.</li>
     * </ul>
     *
     * <p>{@code operator(And)} on the analysed clauses: with the default OR, a
     * query of {@code TOKEN-2345687654} matches any document sharing the single
     * token {@code token}.
     *
     * <p>{@code minimumShouldMatch("1")} is required. These are {@code should}
     * clauses nested under a {@code must}, and without it they would only boost
     * scores — the free-text term would not actually filter anything.
     *
     * <p>Scores are NOT comparable with v1.1: that version scores one
     * {@code searchAll} field, this one combines three heterogeneous clauses.
     * Relative ordering within a single v1.0 result set is still meaningful.
     *
     * <p>The text is always passed as a VALUE, never interpolated into a
     * {@code query_string} — otherwise a caller could inject field names or
     * wildcards ({@code document_id:*}) to probe fields they cannot see.
     */
    private Query freeText(String text) {
        Query nameMatch = Query.of(q -> q.match(m -> m
                .field(F_DOC_NAME_TEXT)
                .query(text)
                .operator(Operator.And)));

        Query attrValueMatch = Query.of(q -> q.nested(n -> n
                .path(N_ATTRS)
                .query(inner -> inner.match(m -> m
                        .field(N_ATTRS_VALUE)
                        .query(text)
                        .operator(Operator.And)))
                // Scored clause: let the best-matching attribute drive the score.
                .scoreMode(ChildScoreMode.Max)));

        Query folderAttrMatch = Query.of(q -> q.match(m -> m
                .field(F_FOLDER_ATTRS)
                .query(text)));

        return Query.of(q -> q.bool(b -> b
                .should(nameMatch)
                .should(attrValueMatch)
                .should(folderAttrMatch)
                .minimumShouldMatch("1")));
    }

    // ------------------------------------------------------------------
    // sorting
    // ------------------------------------------------------------------

    /**
     * Applies the requested sort plus a unique tiebreaker.
     *
     * <p>The tiebreaker is not optional. Many documents share a relevance score
     * and plenty share a {@code created_at}; Elasticsearch orders ties
     * arbitrarily, and that order can differ between two identical requests.
     * Paging then repeats documents on one page and skips others, and
     * {@code search_after} has no stable position to resume from.
     * {@code document_id} is unique, so appending it makes ordering total.
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
            final String field = LEGACY_SORT_FIELD.get(sort);
            final SortOrder order = sort.order();
            if (field == null) {
                // Unreachable for the current enum. A new SortKey added without a
                // legacy field would otherwise build a query Elasticsearch
                // rejects at runtime with an unmapped-field error naming nothing
                // the caller can act on.
                throw new SearchException(
                        SearchErrorCode.INVALID_SORT,
                        "sort '" + sort.apiName() + "' is not available on API version "
                                + API_VERSION,
                        List.of("sort"));
            }
            request.sort(so -> so.field(f -> f.field(field).order(order)));
        }

        request.sort(so -> so.field(f -> f.field(TIEBREAKER_FIELD).order(SortOrder.Asc)));
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /**
     * Production stores the extension WITH a leading dot ({@code ".pdf"}), while
     * the optimized index stores it without ({@code "pdf"}). Accept either
     * spelling from the caller and query what this index actually holds, so the
     * same client code works against both versions.
     */
    private String normaliseExtension(String ext) {
        if (isBlank(ext)) {
            return null;
        }
        String trimmed = ext.strip();
        return trimmed.startsWith(".") ? trimmed : "." + trimmed;
    }

    private void addTerm(BoolQuery.Builder bool, String field, String value, boolean ignoreCase) {
        if (isBlank(value)) {
            return;
        }
        String v = value.strip();
        bool.filter(ignoreCase ? caseInsensitiveTerm(field, v) : exactTerm(field, v));
    }

    private void addDateRange(BoolQuery.Builder bool, String field, String from, String to) {
        boolean hasFrom = !isBlank(from);
        boolean hasTo = !isBlank(to);
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

    private Query exactTerm(String field, String value) {
        return Query.of(q -> q.term(t -> t.field(field).value(value)));
    }

    /** Term query that matches regardless of case — see the class javadoc. */
    private Query caseInsensitiveTerm(String field, String value) {
        return Query.of(q -> q.term(t -> t.field(field).value(value).caseInsensitive(true)));
    }

    private Query boolTerm(String field, boolean value) {
        return Query.of(q -> q.term(t -> t.field(field).value(FieldValue.of(value))));
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
