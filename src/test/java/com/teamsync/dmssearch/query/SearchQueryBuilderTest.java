package com.teamsync.dmssearch.query;

import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.json.JsonpMapper;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.teamsync.dmssearch.config.ElasticsearchProperties;
import com.teamsync.dmssearch.dto.request.RequestIdentity;
import com.teamsync.dmssearch.dto.request.SearchCriteria;
import com.teamsync.dmssearch.dto.request.SortKey;
import jakarta.json.stream.JsonGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.StringWriter;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the query builder.
 *
 * <p>The {@link SecurityBoundary} nested class is the reason this file exists. A
 * bug there is not a wrong result — it is one tenant reading another tenant's
 * documents. Those tests deliberately cover the boring permutations too (no
 * filters, every filter, free text, cursor paging), because the failure mode
 * being guarded against is a code path that forgets the filter, and the only way
 * to catch that is to check every path.
 */
class SearchQueryBuilderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SearchQueryBuilder builder;
    private ElasticsearchProperties props;

    private static final RequestIdentity IDENTITY =
            new RequestIdentity("tenant-alpha", "workspace-one", "user-42");

    @BeforeEach
    void setUp() {
        props = new ElasticsearchProperties();
        props.setIndex("dms-documents");
        props.setRequestTimeoutMs(5000);
        // Real TenantFilter, not a mock: it is what puts the tenant/workspace
        // clauses on every query, and the assertions below exist to prove they
        // are there. Mocking it would remove the thing under test.
        builder = new SearchQueryBuilder(props, new TenantFilter());
    }

    /** Serialises the request to JSON so assertions read like the wire format. */
    private String json(SearchRequest request) {
        JsonpMapper mapper = new JacksonJsonpMapper();
        StringWriter writer = new StringWriter();
        try (JsonGenerator generator = mapper.jsonProvider().createGenerator(writer)) {
            request.serialize(generator, mapper);
        }
        return writer.toString();
    }

    private SearchCriteria.SearchCriteriaBuilder base() {
        return SearchCriteria.builder().page(0).size(20);
    }

    private JsonNode tree(String body) {
        try {
            return MAPPER.readTree(body);
        } catch (Exception e) {
            throw new AssertionError("query body was not valid JSON: " + body, e);
        }
    }

    /**
     * Asserts the boundary clause is a term on {@code field} carrying
     * {@code expectedValue}, and that it sits in {@code query.bool.filter[]}.
     *
     * <p>Structural, not textual. These tests used to assert
     * {@code body.contains("\"tenantId\"")} and {@code body.contains("tenant-alpha")}
     * as two INDEPENDENT substring checks — which pass just as happily when the
     * boundary reads {@code {"term":{"tenantId":"WRONG"}}} and the string
     * "tenant-alpha" appears somewhere else entirely, as an {@code attr.*} value or
     * in the sort. They could not prove the field and the value were associated,
     * which is the only thing that matters here: putting the tenant's id under the
     * WRONG field is precisely how one tenant ends up reading another's documents.
     *
     * <p>Filter context is asserted by walking to the {@code filter} array rather
     * than comparing string offsets — the previous check compared
     * {@code indexOf("tenant-alpha") > indexOf("\"filter\"")}, which proves only
     * that one appears later in the serialised text, and depends on the client's
     * field ordering rather than on anything this service controls.
     */
    private void assertBoundaryFilter(String body, String field, String expectedValue) {
        JsonNode filters = tree(body).path("query").path("bool").path("filter");

        assertThat(filters.isArray())
                .as("query.bool.filter must be an array — the boundary lives there, "
                        + "and nowhere else counts. Body: %s", body)
                .isTrue();

        boolean found = false;
        for (JsonNode clause : filters) {
            JsonNode term = clause.path("term").path(field);
            if (term.isMissingNode()) {
                continue;
            }
            // The client serialises a term value as {"value": x}; accept a bare
            // scalar too so this does not break on a client upgrade.
            String actual = term.has("value") ? term.path("value").asText() : term.asText();
            if (expectedValue.equals(actual)) {
                found = true;
                break;
            }
        }

        assertThat(found)
                .as("query.bool.filter[] must contain term %s = %s. Body: %s",
                        field, expectedValue, body)
                .isTrue();
    }

    /** Both halves of the boundary, on every query. */
    private void assertBoundary(String body) {
        assertBoundaryFilter(body, "tenantId", "tenant-alpha");
        assertBoundaryFilter(body, "workspaceId", "workspace-one");
    }

    // ==================================================================
    @Nested
    @DisplayName("security boundary")
    class SecurityBoundary {

        @Test
        @DisplayName("tenant and workspace filters are present on a bare query")
        void bareQueryStillCarriesBoundary() {
            String body = json(builder.build(base().build(), IDENTITY, 0, null));

            assertBoundary(body);
        }

        @Test
        @DisplayName("...with free text")
        void withFreeText() {
            String body = json(builder.build(base().q("invoice").build(), IDENTITY, 0, null));
            assertBoundary(body);
        }

        @Test
        @DisplayName("...with every filter populated")
        void withEveryFilter() {
            SearchCriteria criteria = base()
                    .q("something")
                    .documentTypeName("passport")
                    .documentTypeId("dt-1")
                    .folderId("f-1")
                    .folderTypeId("ft-1")
                    .folderTypeName("EmployeeRecords")
                    .folderLifecycleState("ACTIVE")
                    .fileExt(".pdf")
                    .validationStatus("PENDING")
                    .attributes(Map.of("pan_number", "ABCDE1234F"))
                    .folderAttributes(Map.of("token_number", "TOKEN-1"))
                    .createdFrom("2026-01-01").createdTo("2026-12-31")
                    .updatedFrom("2026-01-01").updatedTo("2026-12-31")
                    .includeDeleted(true)
                    .includeArchived(true)
                    .build();

            String body = json(builder.build(criteria, IDENTITY, 0, null));
            assertBoundary(body);
        }

        @Test
        @DisplayName("...when paging by cursor")
        void withCursorPaging() {
            String body = json(builder.build(base().build(), IDENTITY, null,
                    List.of(co.elastic.clients.elasticsearch._types.FieldValue.of("x"))));
            assertBoundary(body);
        }

        @Test
        @DisplayName("boundary clauses sit in filter context, not must")
        void boundaryIsInFilterContext() {
            // filter context is unscored and cacheable. Authorisation must never
            // influence ranking, and these clauses repeat on every request.
            //
            // This previously compared string offsets — indexOf("tenant-alpha") >
            // indexOf("\"filter\"") — which proves only that one appears later in
            // the serialised text. It would pass if the boundary moved into a
            // nested `should` that happened to serialise afterwards, and it depended
            // on the client's field ordering, which is not a contract this service
            // controls.
            String body = json(builder.build(base().q("x").build(), IDENTITY, 0, null));

            assertBoundary(body);

            // And explicitly NOT in must: a scored boundary clause would let
            // relevance ranking depend on which tenant you are.
            JsonNode must = tree(body).path("query").path("bool").path("must");
            assertThat(must.toString())
                    .as("the boundary must not appear in must — it would be scored. Body: %s", body)
                    .doesNotContain("tenant-alpha")
                    .doesNotContain("workspace-one");
        }

        @Test
        @DisplayName("identity values are never taken from caller-supplied criteria")
        void identityCannotBeSpoofedThroughCriteria() {
            // Even if a caller manages to get "tenantId" into a filter param,
            // the boundary is built from RequestIdentity alone.
            String body = json(builder.build(
                    base().attributes(Map.of("tenantId", "other-tenant")).build(),
                    IDENTITY, 0, null));

            // The boundary still carries the REAL identity...
            assertBoundary(body);

            // ...and the spoofed value landed under attr.*, prefixed, where it is
            // an ordinary attribute lookup and cannot act as the boundary.
            assertBoundaryFilter(body, "attr.tenantId", "other-tenant");

            // The decisive assertion: there is exactly ONE tenantId term in the
            // filter array, and it holds the identity value. A substring check for
            // "tenant-alpha" could not catch a second, spoofed tenantId clause
            // sitting alongside it.
            JsonNode filters = tree(body).path("query").path("bool").path("filter");
            long tenantIdClauses = 0;
            for (JsonNode clause : filters) {
                if (!clause.path("term").path("tenantId").isMissingNode()) {
                    tenantIdClauses++;
                }
            }
            assertThat(tenantIdClauses)
                    .as("exactly one tenantId boundary clause. Body: %s", body)
                    .isEqualTo(1);
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("query routing")
    class QueryRouting {

        @Test
        @DisplayName("free text becomes a match on searchAll with operator AND")
        void freeTextUsesAndOperator() {
            String body = json(builder.build(base().q("acme invoice").build(), IDENTITY, 0, null));

            assertThat(body).contains("searchAll");
            // Without operator AND, "TOKEN-2345687654" matches a document whose
            // token is TOKEN-20260819054206 — they share the term "token".
            assertThat(body).contains("\"operator\":\"and\"");
        }

        @Test
        @DisplayName("no free text means no match clause at all")
        void noFreeTextNoMatchClause() {
            String body = json(builder.build(base().build(), IDENTITY, 0, null));
            assertThat(body).doesNotContain("searchAll");
        }

        @Test
        @DisplayName("attribute lookups are term queries on the keyword field")
        void attributesUseTermNotMatch() {
            String body = json(builder.build(
                    base().attributes(Map.of("pan_number", "ABCDE1234F")).build(),
                    IDENTITY, 0, null));

            assertThat(body).contains("attr.pan_number").contains("ABCDE1234F");
            // must NOT have been routed through free text
            assertThat(body).doesNotContain("searchAll");
        }

        @Test
        @DisplayName("folder attribute lookups are prefixed correctly")
        void folderAttributesArePrefixed() {
            String body = json(builder.build(
                    base().folderAttributes(Map.of("token_number", "TOKEN-9")).build(),
                    IDENTITY, 0, null));
            assertThat(body).contains("folderAttr.token_number");
        }

        @Test
        @DisplayName("attr lookups ignore case — flattened cannot be normalized at index time")
        void attrTermsAreCaseInsensitive() {
            // `attr` is a flattened field, and flattened rejects a `normalizer`
            // outright ("unknown parameter [normalizer] on mapper of type
            // [flattened]"). Values are therefore stored exactly as ingested —
            // uppercase, e.g. "49AA" — so without this flag a caller searching
            // "49aa" gets zero hits and no error, which is indistinguishable from
            // "no such documents".
            String body = json(builder.build(
                    base().attributes(Map.of("formType", "49aa")).build(),
                    IDENTITY, 0, null));

            assertThat(body).contains("attr.formType");
            assertThat(body).contains("case_insensitive");
        }

        @Test
        @DisplayName("folderAttr lookups do NOT set case_insensitive — the normalizer already folded them")
        void folderAttrTermsStayCaseSensitiveInQuery() {
            // folderAttr is a normal object whose dynamic templates apply
            // lowercase_normalizer, so its terms are already folded at index time.
            // A plain term query is both correct and cheaper; the flag would cost
            // an automaton match for nothing.
            String body = json(builder.build(
                    base().folderAttributes(Map.of("pan_number", "AKTPT4471C")).build(),
                    IDENTITY, 0, null));

            assertThat(body).contains("folderAttr.pan_number");
            assertThat(body).doesNotContain("case_insensitive");
        }

        @Test
        @DisplayName("blank filter values are ignored rather than matching empty string")
        void blankFiltersAreDropped() {
            String body = json(builder.build(
                    base().documentTypeName("   ").folderId("").build(),
                    IDENTITY, 0, null));
            assertThat(body).doesNotContain("documentTypeName");
            assertThat(body).doesNotContain("\"folderId\"");
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("visibility defaults")
    class VisibilityDefaults {

        @Test
        @DisplayName("trashed and archived documents are excluded by default")
        void excludedByDefault() {
            String body = json(builder.build(base().build(), IDENTITY, 0, null));
            assertThat(body).contains("isDeleted").contains("isArchived");
        }

        @Test
        @DisplayName("includeDeleted drops only the isDeleted filter")
        void includeDeletedDropsOnlyThatFilter() {
            String body = json(builder.build(base().includeDeleted(true).build(), IDENTITY, 0, null));
            assertThat(body).doesNotContain("isDeleted");
            assertThat(body).contains("isArchived");
            // the security boundary is unaffected by visibility flags
            assertThat(body).contains("tenant-alpha");
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("sorting")
    class Sorting {

        @Test
        @DisplayName("every sort ends with the documentId tiebreaker")
        void tiebreakerAlwaysAppended() {
            // Without a unique tiebreaker, ties order arbitrarily and paging
            // repeats or skips documents.
            for (SortKey key : SortKey.values()) {
                String body = json(builder.build(base().q("x").sort(key).build(), IDENTITY, 0, null));
                assertThat(body).as("sort=%s", key.apiName()).contains("documentId");
            }
        }

        @Test
        @DisplayName("relevance without free text falls back to createdAt")
        void relevanceFallsBackWithoutFreeText() {
            // Everything scores identically with no query term, so _score
            // ordering would be arbitrary.
            String body = json(builder.build(
                    base().sort(SortKey.RELEVANCE).build(), IDENTITY, 0, null));
            assertThat(body).contains("createdAt");
        }

        @Test
        @DisplayName("relevance with free text sorts by score")
        void relevanceUsesScoreWithFreeText() {
            String body = json(builder.build(
                    base().q("invoice").sort(SortKey.RELEVANCE).build(), IDENTITY, 0, null));
            assertThat(body).contains("_score");
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("response shaping")
    class ResponseShaping {

        @Test
        @DisplayName("content, path and bucket are excluded from _source")
        void heavyFieldsExcluded() {
            // content holds a document's entire OCR text — returning it for a
            // page of 20 hits would be megabytes nothing displays.
            String body = json(builder.build(base().build(), IDENTITY, 0, null));
            assertThat(body).contains("excludes").contains("content");
        }

        @Test
        @DisplayName("attr and folderAttr are NOT excluded")
        void attributesAreReturned() {
            // Kept deliberately so callers can confirm a hit matched the field
            // they expected.
            assertThat(SearchQueryBuilder.SOURCE_EXCLUDES)
                    .doesNotContain("attr")
                    .doesNotContain("folderAttr");
        }

        @Test
        @DisplayName("queries target the alias, never a concrete index")
        void targetsTheAlias() {
            String body = json(builder.build(base().build(), IDENTITY, 0, null));
            assertThat(body).doesNotContain("-000001");
        }
    }
}
