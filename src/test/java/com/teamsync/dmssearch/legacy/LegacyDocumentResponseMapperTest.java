package com.teamsync.dmssearch.legacy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

/**
 * Tests for the v1.0 index-shape to contract-shape mapping.
 *
 * <p>Assertions are made against the serialised JSON, not the Java object,
 * wherever the wire format is the actual contract. {@code documentId} must
 * appear as {@code id} and {@code parentId} as {@code folderId}; an object-level
 * test would pass while clients broke.
 */
class LegacyDocumentResponseMapperTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private LegacyDocumentResponseMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new LegacyDocumentResponseMapper();
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /** A summary populated the way the legacy index actually holds a document. */
    private LegacyDocumentSummary production() {
        LegacyDocumentSummary src = new LegacyDocumentSummary();
        src.setDocumentId("DOC-TEST-0001");
        src.setTenantId("6a841cbde32f87eb8577a5cc");
        src.setWorkspaceId("6a842029e3ac57df8b8c58cf");
        src.setUserId("700aed14-841a-4be2-a974-aa4c889f8b39");
        src.setDocumentName("260812213869103-20260812_095825975.pdf");
        src.setDocumentExt(".pdf");
        src.setDocumentType("PAN_CARD");
        src.setFolderId("260812213869103");
        src.setFolderTypeId("FT-5001");
        src.setFolderTypeName("Loan_Application");
        src.setFolderLifecycleState("ACTIVE");
        src.setInTrash(false);
        src.setCreatedAt("2026-08-20T10:17:55.113+05:30");
        src.setUpdatedAt("2026-08-21T11:00:00.000+05:30");
        src.setValidationStatus("VALIDATED");
        src.setValidationPassed(true);
        src.setValidationConfidence(0.97f);
        src.setPath("260812213869103");
        src.setBucket("teamsync-docs");
        src.setAttributes(attributes("pan_number", "ABCDE1234F", "customer_name", "Ramesh Kumar"));
        return src;
    }

    private List<LegacyDocumentSummary.Attribute> attributes(String... keysAndValues) {
        List<LegacyDocumentSummary.Attribute> out = new ArrayList<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            LegacyDocumentSummary.Attribute a = new LegacyDocumentSummary.Attribute();
            a.setKey(keysAndValues[i]);
            a.setValue(keysAndValues[i + 1]);
            out.add(a);
        }
        return out;
    }

    private JsonNode wire(LegacyDocumentResponse response) throws Exception {
        return JSON.readTree(JSON.writeValueAsString(response));
    }

    // ==================================================================
    @Nested
    @DisplayName("field mapping")
    class FieldMapping {

        @Test
        @DisplayName("every field the legacy index holds is carried across")
        void allAvailableFieldsMapped() {
            LegacyDocumentResponse out = mapper.toResponse(production());

            assertThat(out.getDocumentId()).isEqualTo("DOC-TEST-0001");
            assertThat(out.getDocumentName()).isEqualTo("260812213869103-20260812_095825975.pdf");
            assertThat(out.getTenantId()).isEqualTo("6a841cbde32f87eb8577a5cc");
            assertThat(out.getWorkspaceId()).isEqualTo("6a842029e3ac57df8b8c58cf");
            assertThat(out.getParentId()).isEqualTo("260812213869103");
            assertThat(out.getCreatedAt()).isEqualTo("2026-08-20T10:17:55.113+05:30");
            assertThat(out.getUpdatedAt()).isEqualTo("2026-08-21T11:00:00.000+05:30");
            assertThat(out.getStorageKey()).isEqualTo("260812213869103");
            assertThat(out.getBucketName()).isEqualTo("teamsync-docs");
        }

        @Test
        @DisplayName("ownedBy comes from user_id")
        void ownedByFromUserId() {
            assertThat(mapper.toResponse(production()).getOwnedBy())
                    .isEqualTo("700aed14-841a-4be2-a974-aa4c889f8b39");
        }

        @Test
        @DisplayName("wire names match document-service: id and folderId")
        void wireNamesMatchContract() throws Exception {
            JsonNode json = wire(mapper.toResponse(production()));

            // These two are the whole reason this test asserts on JSON rather
            // than on getters: the Java names are documentId and parentId.
            assertThat(json.path("id").asText()).isEqualTo("DOC-TEST-0001");
            assertThat(json.path("folderId").asText()).isEqualTo("260812213869103");
            assertThat(json.has("documentId")).as("must serialise as id").isFalse();
            assertThat(json.has("parentId")).as("must serialise as folderId").isFalse();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("value translation")
    class ValueTranslation {

        @Test
        @DisplayName("the leading dot is stripped from the extension")
        void extensionLosesLeadingDot() {
            // The index stores ".pdf"; document-service returns "pdf". Matching
            // the field name without matching the value would break any client
            // that compares it.
            assertThat(mapper.toResponse(production()).getExtension()).isEqualTo("pdf");
        }

        @Test
        @DisplayName("an extension already without a dot is left alone")
        void extensionWithoutDotUnchanged() {
            LegacyDocumentSummary src = production();
            src.setDocumentExt("pdf");
            assertThat(mapper.toResponse(src).getExtension()).isEqualTo("pdf");
        }

        @Test
        @DisplayName("a blank extension becomes absent, not an empty string")
        void blankExtensionOmitted() throws Exception {
            LegacyDocumentSummary src = production();
            src.setDocumentExt("   ");
            assertThat(wire(mapper.toResponse(src)).has("extension")).isFalse();
        }

        @Test
        @DisplayName("the nested attributes array is flattened to a map")
        void attributesFlattened() throws Exception {
            JsonNode attrs = wire(mapper.toResponse(production()))
                    .path("documentType").path("attributes");

            // [{key,value},...] in Elasticsearch, {key: value} on the wire.
            assertThat(attrs.isObject()).as("must be an object, not an array").isTrue();
            assertThat(attrs.path("pan_number").asText()).isEqualTo("ABCDE1234F");
            assertThat(attrs.path("customer_name").asText()).isEqualTo("Ramesh Kumar");
        }

        @Test
        @DisplayName("attribute order is preserved")
        void attributeOrderPreserved() {
            // LinkedHashMap, so the same document serialises identically every
            // time rather than in hash order that can shift between JVMs.
            assertThat(mapper.toResponse(production()).getDocumentType().getAttributes().keySet())
                    .containsExactly("pan_number", "customer_name");
        }

        @Test
        @DisplayName("duplicate attribute keys collapse, last one wins")
        void duplicateKeysCollapse() {
            // A map cannot hold two values for one key, and neither can
            // document-service's Map<String, Object> contract. Documented
            // behaviour, asserted so it cannot change silently.
            LegacyDocumentSummary src = production();
            src.setAttributes(attributes("token_number", "TOKEN-1", "token_number", "TOKEN-2"));

            assertThat(mapper.toResponse(src).getDocumentType().getAttributes())
                    .containsExactly(entry("token_number", "TOKEN-2"));
        }

        @Test
        @DisplayName("null and blank attribute keys are dropped")
        void unusableAttributeKeysDropped() {
            LegacyDocumentSummary src = production();
            LegacyDocumentSummary.Attribute nullKey = new LegacyDocumentSummary.Attribute();
            nullKey.setValue("orphan");
            LegacyDocumentSummary.Attribute blankKey = new LegacyDocumentSummary.Attribute();
            blankKey.setKey("  ");
            blankKey.setValue("orphan");

            List<LegacyDocumentSummary.Attribute> attrs =
                    new ArrayList<>(attributes("pan_number", "ABCDE1234F"));
            attrs.addAll(Arrays.asList(nullKey, blankKey, null));
            src.setAttributes(attrs);

            // A blank key would serialise as "": a JSON key no client can address.
            assertThat(mapper.toResponse(src).getDocumentType().getAttributes())
                    .containsOnlyKeys("pan_number");
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("fields the legacy index does not hold")
    class OmittedFields {

        @Test
        @DisplayName("unavailable contract fields are absent from the JSON, not null")
        void unavailableFieldsAbsent() throws Exception {
            JsonNode json = wire(mapper.toResponse(production()));

            // The intersection rule: a field appears only if the index holds it.
            // NON_EMPTY means absent rather than null, which is how
            // document-service itself omits tagIds for an untagged document.
            assertThat(json.has("displaySize")).as("no size field in the legacy index").isFalse();
            assertThat(json.has("Content-MD5")).as("checksum is not indexed").isFalse();
            assertThat(json.has("tagIds")).as("tags are not indexed").isFalse();
            assertThat(json.has("isRootItem")).as("would be a constant false").isFalse();
            assertThat(json.has("templateValidation"))
                    .as("legacy validation_status has a different value domain").isFalse();
            assertThat(json.has("score")).as("not part of the DocumentResponse contract").isFalse();
        }

        @Test
        @DisplayName("no snake_case field leaks through")
        void noSnakeCaseLeaks() throws Exception {
            String json = JSON.writeValueAsString(mapper.toResponse(production()));

            // The whole point of the mapper. A new field added to
            // LegacyDocumentSummary and wired straight through would show up here.
            assertThat(json)
                    .doesNotContain("document_id")
                    .doesNotContain("document_name")
                    .doesNotContain("document_ext")
                    .doesNotContain("folder_id")
                    .doesNotContain("tenant_id")
                    .doesNotContain("in_trash")
                    .doesNotContain("created_at");
        }

        @Test
        @DisplayName("the documentType block is omitted when there is no type and no attributes")
        void documentTypeBlockOmitted() throws Exception {
            LegacyDocumentSummary src = production();
            src.setDocumentType(null);
            src.setAttributes(List.of());

            // Matches document-service's buildDocumentType, which returns null
            // rather than an empty object.
            assertThat(wire(mapper.toResponse(src)).has("documentType")).isFalse();
        }

        @Test
        @DisplayName("the documentType block survives a type name with no attributes")
        void documentTypeKeptWithNameOnly() throws Exception {
            LegacyDocumentSummary src = production();
            src.setAttributes(null);

            JsonNode block = wire(mapper.toResponse(src)).path("documentType");
            assertThat(block.path("documentTypeName").asText()).isEqualTo("PAN_CARD");
            assertThat(block.has("attributes")).as("an empty map must be omitted").isFalse();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("degenerate input")
    class DegenerateInput {

        @Test
        @DisplayName("a null summary maps to null rather than throwing")
        void nullSummary() {
            // SearchService skips null hit sources, so this should be
            // unreachable. Mapping to null keeps an unexpected one from taking
            // down a whole page of otherwise valid results.
            assertThat(mapper.toResponse(null)).isNull();
        }

        @Test
        @DisplayName("an empty summary serialises to an empty object")
        void emptySummary() throws Exception {
            // NON_EMPTY on every field, so nothing is emitted. Proves no field
            // has a non-null default that would fabricate data.
            assertThat(wire(mapper.toResponse(new LegacyDocumentSummary())).isEmpty()).isTrue();
        }
    }
}
