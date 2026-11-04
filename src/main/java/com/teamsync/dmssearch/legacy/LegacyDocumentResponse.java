package com.teamsync.dmssearch.legacy;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * One v1.0 search result, shaped like document-service's {@code DocumentResponse}.
 *
 * <h2>Why this exists</h2>
 * v1.0 replaces document-service's {@code /api/documents/search/token},
 * {@code /search/pan} and {@code /search/acknowledgement} endpoints, all of
 * which return {@code DocumentResponse}. Clients already parse that shape, so
 * emitting it here means the migration to this service is a URL change rather
 * than a response-parsing change.
 *
 * <p>This is a deliberate reversal of the earlier "pass the index shape
 * through" decision. {@link LegacyDocumentSummary} still exists and is still
 * snake_case, because it has to match the Elasticsearch {@code _source} it
 * deserialises from; it is now internal, and
 * {@link LegacyDocumentResponseMapper} converts it to this.
 *
 * <h2>Which fields are here, and why the rest are not</h2>
 * The rule is a straight intersection: a field appears only if the legacy index
 * actually holds it. Everything {@code DocumentResponse} declares that the index
 * does not store is omitted from this class entirely rather than declared and
 * left permanently null:
 *
 * <table border="1">
 *   <tr><th>omitted</th><th>why</th></tr>
 *   <tr><td>{@code displaySize}</td><td>no size field in the legacy mapping or _source</td></tr>
 *   <tr><td>{@code Content-MD5}</td><td>checksum is not indexed</td></tr>
 *   <tr><td>{@code tagIds}</td><td>tags are not indexed</td></tr>
 *   <tr><td>{@code isRootItem}</td><td>derived from a null parent; every legacy
 *       document has a {@code folder_id}, so it would be a constant {@code false}</td></tr>
 *   <tr><td>{@code templateValidation}</td><td>the index has
 *       {@code validation_status} ({@code "VALIDATED"}), not document-service's
 *       {@code "1"}/{@code "0"}/{@code "-1"} code, and has no
 *       {@code validatedAt}. Same name, different value domain — mapping it
 *       would hand clients a status they cannot interpret</td></tr>
 *   <tr><td>{@code score}</td><td>{@code DocumentResponse} has no such field.
 *       Result ordering still reflects relevance</td></tr>
 * </table>
 *
 * <p>Absent is absent, not null: {@code NON_EMPTY} inclusion means a field with
 * no value never reaches the wire. That matches document-service, which omits
 * {@code tagIds} the same way for a document with no tags.
 *
 * <h2>Wire names are not field names</h2>
 * Copied exactly from {@code DocumentResponse}: {@code documentId} serialises as
 * {@code id} and {@code parentId} as {@code folderId}. Field declaration order
 * is also kept, so the JSON key order matches too.
 *
 * <p>Timestamps stay {@link String}. They are already ISO-8601 with offset in
 * Elasticsearch, and this service runs Jackson 2 (reading ES) beside Jackson 3
 * (writing HTTP); text sidesteps every date-format mismatch between the two.
 * document-service types them {@code Instant}, which serialises to the same
 * ISO-8601 text, so the wire value is equivalent.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public class LegacyDocumentResponse {

    /** From {@code document_id}. Serialises as {@code id}, as in document-service. */
    @JsonProperty("id")
    private String documentId;

    /** From {@code document_name}. */
    private String documentName;

    /**
     * From {@code user_id}.
     *
     * <p>The nearest thing the legacy index holds. If {@code user_id} turns out
     * to be the uploader rather than the owner, this field is wrong in the same
     * way the index is wrong, and the fix belongs in the ingestion pipeline —
     * not in a translation here that would invent an owner.
     */
    private String ownedBy;

    /** From {@code workspace_id}. */
    private String workspaceId;

    /** From {@code folder_id}. Serialises as {@code folderId}, as in document-service. */
    @JsonProperty("folderId")
    private String parentId;

    /** From {@code tenant_id}. */
    private String tenantId;

    /**
     * From {@code document_ext}, with the leading dot removed.
     *
     * <p>The legacy index stores {@code ".pdf"}; document-service returns
     * {@code "pdf"}. Matching the field name without matching the value would
     * break any client that compares it.
     */
    private String extension;

    /** From {@code document_type} and {@code attributes}. */
    private DocumentTypeValue documentType;

    /** From {@code created_at} — ISO-8601 with offset. */
    private String createdAt;

    /** From {@code updated_at} — ISO-8601 with offset. */
    private String updatedAt;

    /** From {@code path}. */
    private String storageKey;

    /** From {@code bucket}. */
    private String bucketName;

    /**
     * The document's own doc-type view — mirrors document-service's
     * {@code DocumentTypeValueResponse}.
     *
     * <p>{@code documentTypeId} is NOT declared. document-service has it but
     * marks it {@code @JsonIgnore}, so it never reaches the wire there either,
     * and the legacy index does not store it at all.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public static class DocumentTypeValue {

        /** From {@code document_type}. */
        private String documentTypeName;

        /**
         * Attribute values, flattened from the index's {@code nested} array.
         *
         * <p>{@code [{key,value},…]} in Elasticsearch becomes
         * {@code {key: value, …}} here, because that is the shape
         * document-service returns. See
         * {@link LegacyDocumentResponseMapper} for what that costs.
         */
        private Map<String, Object> attributes;
    }
}
