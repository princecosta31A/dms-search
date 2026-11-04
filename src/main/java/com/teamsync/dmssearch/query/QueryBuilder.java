package com.teamsync.dmssearch.query;

import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import com.teamsync.dmssearch.dto.request.RequestIdentity;
import com.teamsync.dmssearch.dto.request.SearchCriteria;
import com.teamsync.dmssearch.dto.response.SearchHit;

import java.util.List;

/**
 * Translates a {@link SearchCriteria} into an Elasticsearch request for ONE
 * index mapping.
 *
 * <h2>Why this interface exists</h2>
 * The service must serve two mappings at once:
 *
 * <ul>
 *   <li><b>v1.0</b> — the legacy mapping running in production today
 *       ({@code teamsync-dms-documents}): snake_case fields, {@code attributes}
 *       as a {@code nested} array, {@code folder_type_attributes} as
 *       {@code flattened}, no {@code searchAll}, no archive field.</li>
 *   <li><b>v1.1</b> — the optimized mapping ({@code dms-documents}): camelCase,
 *       {@code attr} as {@code flattened}, {@code folderAttr} as a normalized
 *       object, a dedicated {@code searchAll} field.</li>
 * </ul>
 *
 * These are not two spellings of one query. The attribute lookup alone needs a
 * {@code nested} query on one and a plain {@code term} on the other, and case
 * handling is <i>inverted</i> between them. So each mapping gets its own
 * implementation, with no shared field names and no shared branching — the one
 * thing that would couple them is {@link TenantFilter}, and that is shared
 * deliberately because isolation must have exactly one implementation.
 *
 * <p>Everything outside this interface — executing the search, error handling,
 * cursor paging, partial-result detection — is mapping-agnostic and lives once
 * in {@code SearchService}.
 */
public interface QueryBuilder {

    /**
     * Builds the search request, including the mandatory tenant/workspace scope.
     *
     * @param criteria    what the caller asked for (already validated)
     * @param identity    tenant/workspace from the gateway — the authorisation boundary
     * @param from        offset, or {@code null} when paging by cursor
     * @param searchAfter cursor sort values, or {@code null} for offset paging
     */
    SearchRequest build(SearchCriteria criteria,
                        RequestIdentity identity,
                        Integer from,
                        List<FieldValue> searchAfter);

    /**
     * The DTO Elasticsearch hits deserialise into for this mapping.
     *
     * <p>Returned rather than hardcoded in the executor so that adding a mapping
     * never touches {@code SearchService}.
     */
    Class<? extends SearchHit> documentType();

    /** The API version this builder serves — {@code "1.0"} or {@code "1.1"}. */
    String apiVersion();

    /**
     * Converts a deserialised hit into the object this version puts on the wire.
     *
     * <p>Defaults to the hit itself, which is what v1.1 wants: its
     * {@code DocumentSummary} is already the client-facing shape. v1.0 overrides
     * it, because its DTO has to match the Elasticsearch {@code _source} it is
     * read from and that is NOT the shape its clients parse.
     *
     * <p>Declared here rather than branched on in {@code SearchService} so that
     * the executor still knows nothing about either version. It stays a straight
     * line: build, execute, shape, page.
     *
     * @param hit a hit already deserialised into {@link #documentType()}, with
     *            its score stamped if the query was scored
     */
    default Object toApiShape(SearchHit hit) {
        return hit;
    }
}
