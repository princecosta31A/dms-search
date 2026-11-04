package com.teamsync.dmssearch.legacy;

import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.json.JsonpMapper;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.teamsync.dmssearch.config.ElasticsearchProperties;
import com.teamsync.dmssearch.dto.request.RequestIdentity;
import com.teamsync.dmssearch.dto.request.SearchCriteria;
import com.teamsync.dmssearch.dto.request.SortKey;
import com.teamsync.dmssearch.exception.SearchException;
import com.teamsync.dmssearch.query.TenantFilter;
import jakarta.json.stream.JsonGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.StringWriter;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for the v1.0 (legacy mapping) query builder.
 *
 * <p>Mirrors {@code SearchQueryBuilderTest} in approach — the request is
 * serialised to JSON and assertions are made against the wire format, so they
 * describe what Elasticsearch actually receives rather than how the builder
 * happens to be written.
 *
 * <p>Two groups of tests matter most here, for different reasons.
 * {@link SecurityBoundary} guards the failure that is not a wrong result but one
 * tenant reading another tenant's documents. {@link MappingDifferences} guards
 * the failures that are SILENT: this mapping needs a {@code nested} query where
 * v1.1 needs a plain term, and its case handling is inverted. Getting either
 * wrong does not raise an error — it returns zero hits, which is
 * indistinguishable from "no such documents".
 */
class LegacyQueryBuilderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final RequestIdentity IDENTITY =
            new RequestIdentity("tenant-alpha", "workspace-one", "user-42");

    private LegacyQueryBuilder builder;
    private ElasticsearchProperties props;

    @BeforeEach
    void setUp() {
        props = new ElasticsearchProperties();
        props.setLegacyIndex("teamsync-dms-documents");
        props.setRequestTimeoutMs(5000);
        // Real TenantFilter, not a mock: it is what puts the scope clauses on
        // every query, and these tests exist to prove they are there.
        builder = new LegacyQueryBuilder(props, new TenantFilter());
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

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

    private JsonNode filters(String body) {
        return tree(body).path("query").path("bool").path("filter");
    }

    /** Reads a term value, accepting both the object and bare-scalar encodings. */
    private String termValue(JsonNode term) {
        return term.has("value") ? term.path("value").asText() : term.asText();
    }

    /**
     * Asserts {@code query.bool.filter[]} holds a term on {@code field} with
     * {@code expectedValue}.
     *
     * <p>Structural, not a substring check: asserting the field name and the
     * value as two independent {@code contains} calls passes just as happily
     * when the value sits under the WRONG field, which is exactly how a scope
     * filter leaks.
     */
    private void assertFilterTerm(String body, String field, String expectedValue) {
        JsonNode array = filters(body);
        assertThat(array.isArray())
                .as("query.bool.filter must be an array. Body: %s", body)
                .isTrue();

        boolean found = false;
        for (JsonNode clause : array) {
            JsonNode term = clause.path("term").path(field);
            if (!term.isMissingNode() && expectedValue.equals(termValue(term))) {
                found = true;
                break;
            }
        }
        assertThat(found)
                .as("query.bool.filter[] must contain term %s = %s. Body: %s",
                        field, expectedValue, body)
                .isTrue();
    }

    /** Finds the term clause for {@code field} in the filter array, or null. */
    private JsonNode findFilterTerm(String body, String field) {
        for (JsonNode clause : filters(body)) {
            JsonNode term = clause.path("term").path(field);
            if (!term.isMissingNode()) {
                return term;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // security boundary
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("security boundary")
    class SecurityBoundary {

        /**
         * Every permutation, including the boring ones. The failure mode being
         * guarded against is a code path that forgets the scope filter, and the
         * only way to catch that is to walk every path.
         */
        @Test
        @DisplayName("tenant_id and workspace_id are filters on every query shape")
        void scopeAppliedToEveryPath() {
            List<SearchCriteria> shapes = List.of(
                    base().build(),
                    base().q("passport").build(),
                    base().documentTypeName("ack_receipt").build(),
                    base().attributes(Map.of("formType", "fn135")).build(),
                    base().folderAttributes(Map.of("token_number", "260812213869103")).build(),
                    base().createdFrom("2026-01-01T00:00:00Z").build(),
                    base().includeDeleted(true).includeArchived(true).build(),
                    base().sort(SortKey.FILE_NAME).build(),
                    base().cursor("opaque").build());

            for (SearchCriteria criteria : shapes) {
                String body = json(builder.build(criteria, IDENTITY, 0, null));
                assertFilterTerm(body, "tenant_id", "tenant-alpha");
                assertFilterTerm(body, "workspace_id", "workspace-one");
            }
        }

        @Test
        @DisplayName("the scope terms are exact, never case-insensitive")
        void scopeIsExactMatch() {
            String body = json(builder.build(base().build(), IDENTITY, 0, null));

            for (String field : List.of("tenant_id", "workspace_id")) {
                JsonNode term = findFilterTerm(body, field);
                assertThat(term).as("%s term must be present", field).isNotNull();
                assertThat(term.path("case_insensitive").asBoolean(false))
                        .as("%s must match exactly — loosening the one clause that "
                                + "must not be loose. Body: %s", field, body)
                        .isFalse();
            }
        }

        @Test
        @DisplayName("a blank identity is refused rather than producing an unscoped query")
        void blankIdentityRefused() {
            RequestIdentity blank = new RequestIdentity("", "workspace-one", "user-42");

            assertThatThrownBy(() -> builder.build(base().build(), blank, 0, null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("unscoped");
        }
    }

    // ------------------------------------------------------------------
    // the silent failures
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("mapping differences")
    class MappingDifferences {

        @Test
        @DisplayName("document attributes use a nested query with key AND value together")
        void attributesUseNestedQuery() {
            String body = json(builder.build(
                    base().attributes(Map.of("formType", "fn135")).build(),
                    IDENTITY, 0, null));

            JsonNode nested = null;
            for (JsonNode clause : filters(body)) {
                if (!clause.path("nested").isMissingNode()) {
                    nested = clause.path("nested");
                    break;
                }
            }

            assertThat(nested)
                    .as("attribute lookup must be a nested query — a plain term on "
                            + "attributes.key cannot work against a nested field. Body: %s", body)
                    .isNotNull();
            assertThat(nested.path("path").asText()).isEqualTo("attributes");

            // The whole reason the mapping uses nested. Two SEPARATE clauses
            // would match a document holding the key in one array entry and the
            // value in a different one, so a document whose POI_Flag is false
            // and whose formType is fn135 would satisfy POI_Flag=fn135.
            JsonNode inner = nested.path("query").path("bool").path("filter");
            assertThat(inner.isArray()).as("Body: %s", body).isTrue();

            boolean keyFound = false;
            boolean valueFound = false;
            for (JsonNode clause : inner) {
                JsonNode key = clause.path("term").path("attributes.key");
                JsonNode value = clause.path("term").path("attributes.value.keyword");
                if (!key.isMissingNode() && "formType".equals(termValue(key))) {
                    keyFound = true;
                }
                if (!value.isMissingNode() && "fn135".equals(termValue(value))) {
                    valueFound = true;
                }
            }
            assertThat(keyFound && valueFound)
                    .as("key and value must sit INSIDE the same nested query. Body: %s", body)
                    .isTrue();
        }

        @Test
        @DisplayName("attribute value matches on value.keyword, not the analysed value")
        void attributeValueUsesKeywordSubfield() {
            String body = json(builder.build(
                    base().attributes(Map.of("systemDocName", "TOKEN-2345687654")).build(),
                    IDENTITY, 0, null));

            // attributes.value is analysed: a match there would hit any document
            // sharing the token "token", so TOKEN-2345687654 would find
            // TOKEN-20260819054206.
            assertThat(body).contains("attributes.value.keyword");
        }

        @Test
        @DisplayName("folder attributes are case-insensitive, document attributes are not")
        void caseHandlingIsInverted() {
            String folderBody = json(builder.build(
                    base().folderAttributes(Map.of("pan_number", "abcde1234f")).build(),
                    IDENTITY, 0, null));

            JsonNode folderTerm = findFilterTerm(folderBody, "folder_type_attributes.pan_number");
            assertThat(folderTerm).as("Body: %s", folderBody).isNotNull();
            // flattened cannot declare a normalizer, so values are indexed
            // exactly as ingested and a lowercase term would miss ABCDE1234F.
            assertThat(folderTerm.path("case_insensitive").asBoolean(false))
                    .as("folder_type_attributes is flattened and MUST be matched "
                            + "case-insensitively. Body: %s", folderBody)
                    .isTrue();

            String docBody = json(builder.build(
                    base().attributes(Map.of("formType", "FN135")).build(),
                    IDENTITY, 0, null));
            // attributes.value.keyword HAS lowercase_normalizer, so Elasticsearch
            // folds the query value itself — the flag would only cost time.
            assertThat(docBody).doesNotContain("case_insensitive");
        }

        @Test
        @DisplayName("keywords without a normalizer are matched case-insensitively")
        void unnormalizedKeywordsIgnoreCase() {
            String body = json(builder.build(
                    base().folderTypeName("Tan-Application")
                            .folderLifecycleState("ACK")
                            .validationStatus("not_applicable")
                            .build(),
                    IDENTITY, 0, null));

            for (String field : List.of("folder_type_name", "folder_lifecycle_state",
                                        "validation_status")) {
                JsonNode term = findFilterTerm(body, field);
                assertThat(term).as("%s must be present. Body: %s", field, body).isNotNull();
                assertThat(term.path("case_insensitive").asBoolean(false))
                        .as("%s has no normalizer in this mapping, so a casing "
                                + "mismatch would silently return nothing. Body: %s", field, body)
                        .isTrue();
            }
        }

        @Test
        @DisplayName("fileExt is queried with the leading dot production stores")
        void fileExtGetsLeadingDot() {
            // Production stores ".pdf"; v1.1 stores "pdf". Accept either from the
            // caller so one client works against both versions.
            assertFilterTerm(json(builder.build(base().fileExt("pdf").build(), IDENTITY, 0, null)),
                    "document_ext", ".pdf");
            assertFilterTerm(json(builder.build(base().fileExt(".pdf").build(), IDENTITY, 0, null)),
                    "document_ext", ".pdf");
        }

        @Test
        @DisplayName("soft delete filters on in_trash, not isDeleted")
        void softDeleteUsesLegacyField() {
            String body = json(builder.build(base().build(), IDENTITY, 0, null));
            assertThat(body).contains("in_trash").doesNotContain("isDeleted");
        }

        @Test
        @DisplayName("includeDeleted=true drops the in_trash filter")
        void includeDeletedDropsFilter() {
            String body = json(builder.build(base().includeDeleted(true).build(), IDENTITY, 0, null));
            assertThat(body).doesNotContain("in_trash");
        }
    }

    // ------------------------------------------------------------------
    // what v1.0 cannot do
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("unsupported capabilities")
    class Unsupported {

        @Test
        @DisplayName("includeArchived is ignored — no archive field exists")
        void archiveFilterNeverApplied() {
            for (boolean includeArchived : List.of(true, false)) {
                String body = json(builder.build(
                        base().includeArchived(includeArchived).build(), IDENTITY, 0, null));

                // Emitting a filter on a field this mapping does not have would
                // match zero documents and make EVERY search look empty.
                assertThat(body)
                        .as("no archive clause may be emitted. Body: %s", body)
                        .doesNotContain("is_archived")
                        .doesNotContain("isArchived");
            }
        }

        @Test
        @DisplayName("documentTypeId is rejected with a clear error, not silently ignored")
        void documentTypeIdRejected() {
            // Running the query anyway would return an empty page, which reads as
            // "no such documents" and sends the caller hunting for missing data
            // instead of a missing field.
            assertThatThrownBy(() -> builder.build(
                    base().documentTypeId("dt-ack_receipt").build(), IDENTITY, 0, null))
                    .isInstanceOf(SearchException.class)
                    .hasMessageContaining("documentTypeId")
                    .hasMessageContaining("1.1");
        }

        @Test
        @DisplayName("a blank documentTypeId is not treated as supplied")
        void blankDocumentTypeIdAccepted() {
            assertThatCode(() -> builder.build(
                    base().documentTypeId("   ").build(), IDENTITY, 0, null))
                    .doesNotThrowAnyException();
        }
    }

    // ------------------------------------------------------------------
    // free text
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("free text")
    class FreeText {

        @Test
        @DisplayName("searches name, nested attribute values and folder attributes together")
        void spansAllThreeSurfaces() {
            String body = json(builder.build(base().q("parteek").build(), IDENTITY, 0, null));

            JsonNode must = tree(body).path("query").path("bool").path("must");
            assertThat(must.isArray()).as("Body: %s", body).isTrue();

            JsonNode should = must.get(0).path("bool").path("should");
            assertThat(should.isArray()).as("Body: %s", body).isTrue();
            assertThat(should).as("three surfaces, since this mapping has no "
                    + "searchAll field. Body: %s", body).hasSize(3);

            assertThat(body)
                    .contains("document_name.text")
                    .contains("attributes.value")
                    .contains("folder_type_attributes");
        }

        @Test
        @DisplayName("minimum_should_match is set so free text actually filters")
        void minimumShouldMatchSet() {
            String body = json(builder.build(base().q("parteek").build(), IDENTITY, 0, null));

            // Without it, should clauses under a must only BOOST scores — the
            // search term would not narrow the result set at all.
            assertThat(tree(body).path("query").path("bool").path("must")
                    .get(0).path("bool").path("minimum_should_match").asText())
                    .as("Body: %s", body)
                    .isEqualTo("1");
        }

        @Test
        @DisplayName("analysed clauses use operator AND")
        void operatorIsAnd() {
            String body = json(builder.build(base().q("ack receipt").build(), IDENTITY, 0, null));
            // With the default OR, "TOKEN-2345687654" matches any document
            // sharing the single token "token".
            assertThat(body).contains("\"operator\":\"and\"");
        }

        @Test
        @DisplayName("no free text means no scored clause at all")
        void noFreeTextNoMust() {
            String body = json(builder.build(base().build(), IDENTITY, 0, null));
            assertThat(tree(body).path("query").path("bool").path("must").isMissingNode())
                    .as("Body: %s", body)
                    .isTrue();
        }
    }

    // ------------------------------------------------------------------
    // request shape
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("request shape")
    class RequestShape {

        @Test
        @DisplayName("queries the legacy index, not the optimized alias")
        void usesLegacyIndex() {
            SearchRequest request = builder.build(base().build(), IDENTITY, 0, null);
            assertThat(request.index()).containsExactly("teamsync-dms-documents");
        }

        @Test
        @DisplayName("content, path and bucket are excluded from _source")
        void heavyFieldsExcluded() {
            String body = json(builder.build(base().build(), IDENTITY, 0, null));
            // content holds the full OCR text — kilobytes per hit, and nothing
            // renders it in a result list.
            assertThat(body).contains("content").contains("path").contains("bucket");
            assertThat(tree(body).path("_source").path("excludes"))
                    .as("Body: %s", body)
                    .hasSize(3);
        }

        @Test
        @DisplayName("every sort ends with the document_id tiebreaker")
        void tiebreakerAlwaysAppended() {
            for (SortKey sort : List.of(SortKey.CREATED_AT, SortKey.UPDATED_AT, SortKey.FILE_NAME)) {
                String body = json(builder.build(base().sort(sort).build(), IDENTITY, 0, null));
                JsonNode sortArray = tree(body).path("sort");

                assertThat(sortArray).as("Body: %s", body).hasSize(2);
                // Ties are ordered arbitrarily, and that order can differ between
                // two identical requests — paging would repeat and skip documents.
                assertThat(sortArray.get(1).has("document_id"))
                        .as("last sort key must be the unique tiebreaker. Body: %s", body)
                        .isTrue();
            }
        }

        @Test
        @DisplayName("sort keys map to legacy field names")
        void sortUsesLegacyFields() {
            assertThat(json(builder.build(base().sort(SortKey.CREATED_AT).build(), IDENTITY, 0, null)))
                    .contains("created_at");
            assertThat(json(builder.build(base().sort(SortKey.UPDATED_AT).build(), IDENTITY, 0, null)))
                    .contains("updated_at");
            assertThat(json(builder.build(base().sort(SortKey.FILE_NAME).build(), IDENTITY, 0, null)))
                    .contains("document_name");
        }

        @Test
        @DisplayName("relevance sort without free text falls back to newest-first")
        void relevanceNeedsFreeText() {
            String body = json(builder.build(base().sort(SortKey.RELEVANCE).build(), IDENTITY, 0, null));
            // Every document would score identically, so _score would order
            // nothing and paging would be unstable.
            assertThat(body).contains("created_at").doesNotContain("_score");
        }

        @Test
        @DisplayName("date ranges use created_at and updated_at")
        void dateRangesUseLegacyFields() {
            String body = json(builder.build(
                    base().createdFrom("2026-01-01T00:00:00Z")
                            .updatedTo("2026-12-31T00:00:00Z")
                            .build(),
                    IDENTITY, 0, null));

            assertThat(body).contains("created_at").contains("updated_at");
            assertThat(body).doesNotContain("createdAt").doesNotContain("updatedAt");
        }

        @Test
        @DisplayName("the API version reported is 1.0 and the DTO is the legacy one")
        void identity() {
            assertThat(builder.apiVersion()).isEqualTo("1.0");
            assertThat(builder.documentType()).isEqualTo(LegacyDocumentSummary.class);
        }
    }
}
