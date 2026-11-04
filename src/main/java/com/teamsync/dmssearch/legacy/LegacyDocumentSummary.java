package com.teamsync.dmssearch.legacy;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.teamsync.dmssearch.dto.response.SearchHit;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * One v1.0 search result — the legacy index's own shape, passed through.
 *
 * <h2>Why snake_case and not translated</h2>
 * v1.0 returns what the legacy mapping holds, verbatim. It is NOT reshaped to
 * look like v1.1. A caller that wants the optimized response structure moves to
 * v1.1, and the differing shape is precisely what makes that a version boundary
 * rather than a silent behaviour change.
 *
 * <p>The sharpest difference: {@code attributes} here is a <b>list</b> of
 * {@code {key, value}} objects, because the legacy mapping types it as
 * {@code nested}. v1.1's {@code attr} is a flat <b>object</b>. No amount of
 * field renaming bridges that, so it is not attempted.
 *
 * <h2>Fields the legacy index does not have</h2>
 * Verified against a live production document, not just the mapping:
 * {@code file_size}, {@code folder_name}, {@code document_type_id} and any
 * archive flag are absent from both the mapping AND {@code _source}. They are
 * therefore omitted entirely rather than declared and always null.
 *
 * <p>{@code content} (full OCR text — kilobytes per document) is excluded at
 * the Elasticsearch level by {@code LegacyQueryBuilder.SOURCE_EXCLUDES}, so it is
 * not declared here. {@code path} and {@code bucket} ARE fetched: they back
 * {@code storageKey} and {@code bucketName} on {@link LegacyDocumentResponse},
 * which document-service's contract includes.
 *
 * <p>Timestamps stay {@code String}: they are already ISO-8601 with offset in
 * Elasticsearch, and this service runs Jackson 2 (deserialising ES responses)
 * beside Jackson 3 (serialising HTTP responses). Text sidesteps every
 * date-format mismatch between the two. The {@code @JsonProperty} annotations
 * are read by both — the annotation package is shared across Jackson versions,
 * so one annotation governs deserialising from ES and serialising to the client.
 *
 * <p>{@code ignoreUnknown = true} is load-bearing: the legacy mapping is
 * {@code "dynamic": "false"}, so documents can carry fields nobody declared.
 * Without this, one such document would fail the entire search.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class LegacyDocumentSummary implements SearchHit {

    @JsonProperty("document_id")
    private String documentId;

    @JsonProperty("tenant_id")
    private String tenantId;

    @JsonProperty("workspace_id")
    private String workspaceId;

    @JsonProperty("user_id")
    private String userId;

    @JsonProperty("document_name")
    private String documentName;

    /** Carries a leading dot in production, e.g. {@code ".pdf"}. */
    @JsonProperty("document_ext")
    private String documentExt;

    @JsonProperty("document_type")
    private String documentType;

    @JsonProperty("folder_id")
    private String folderId;

    @JsonProperty("folder_type_id")
    private String folderTypeId;

    @JsonProperty("folder_type_name")
    private String folderTypeName;

    @JsonProperty("folder_lifecycle_state")
    private String folderLifecycleState;

    /** The legacy soft-delete flag. v1.1 calls this {@code isDeleted}. */
    @JsonProperty("in_trash")
    private Boolean inTrash;

    /** Document attributes — a LIST, because the mapping types them as {@code nested}. */
    @JsonProperty("attributes")
    private List<Attribute> attributes;

    /** Folder attributes — a flat map ({@code flattened} in the mapping). */
    @JsonProperty("folder_type_attributes")
    private Map<String, Object> folderTypeAttributes;

    /** ISO-8601 with offset, e.g. {@code 2026-08-20T10:17:55.113753+05:30}. */
    @JsonProperty("created_at")
    private String createdAt;

    @JsonProperty("updated_at")
    private String updatedAt;

    @JsonProperty("validation_status")
    private String validationStatus;

    @JsonProperty("validation_type")
    private String validationType;

    @JsonProperty("validation_label")
    private String validationLabel;

    @JsonProperty("validation_passed")
    private Boolean validationPassed;

    @JsonProperty("validation_confidence")
    private Float validationConfidence;

    /** Storage object key. Becomes {@code storageKey} on the response. */
    @JsonProperty("path")
    private String path;

    /** Storage bucket. Becomes {@code bucketName} on the response. */
    @JsonProperty("bucket")
    private String bucket;

    /**
     * Relevance score. Null when the query had no free-text term, since
     * everything then scores identically and the number would be noise.
     *
     * <p>Not part of {@code _source} — stamped by {@code SearchService} from the
     * Elasticsearch hit.
     */
    @JsonProperty("score")
    private Double score;

    /**
     * One entry of the legacy {@code nested} attributes array.
     *
     * <p>{@code valueDate} and {@code valueNumber} exist in the mapping but are
     * <b>never populated</b> by the production pipeline — confirmed against live
     * data. They are declared so the shape matches the index, and so it is
     * visible why v1.0 cannot offer date or numeric range queries on attributes:
     * the typed fields were designed in and then never written.
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Attribute {

        @JsonProperty("key")
        private String key;

        @JsonProperty("value")
        private String value;

        @JsonProperty("value_date")
        private String valueDate;

        @JsonProperty("value_number")
        private Double valueNumber;
    }
}
