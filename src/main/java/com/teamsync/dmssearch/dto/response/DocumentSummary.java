package com.teamsync.dmssearch.dto.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * One search result, deserialised straight from the Elasticsearch {@code _source}.
 *
 * <p>This is a <b>summary</b>, not the full document: enough to render a result
 * list without a second round-trip. A client that needs everything fetches the
 * document from document-service by {@code documentId}.
 *
 * <p>{@code content} (full OCR text), {@code path} and {@code bucket} are
 * excluded at the Elasticsearch level, not merely omitted here — see
 * {@code SearchQueryBuilder#SOURCE_EXCLUDES}. A 50-page PDF's text is ~200 KB;
 * returning it for 20 hits would make one page of results several megabytes of
 * data nothing displays.
 *
 * <p>Timestamps stay {@code String}. They are already ISO-8601 with offset in
 * Elasticsearch, and this service runs two Jackson versions side by side
 * (Jackson 2 deserialising ES responses, Jackson 3 serialising HTTP responses).
 * Keeping them as text sidesteps every date-format and timezone mismatch between
 * the two, and hands the client exactly what is stored.
 *
 * <p>{@code @JsonIgnoreProperties(ignoreUnknown = true)} is required: a field
 * added to the index mapping before this DTO is updated would otherwise make
 * every search fail outright.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class DocumentSummary {

    private String documentId;

    private String tenantId;
    private String workspaceId;
    private String userId;

    private String fileName;
    private String fileExt;
    private Long fileSize;

    private String documentTypeId;
    private String documentTypeName;

    private String folderId;
    private String folderName;
    private String folderTypeId;
    private String folderTypeName;
    private String folderLifecycleState;

    private Boolean isDeleted;
    private Boolean isArchived;

    /** ISO-8601 with offset, e.g. {@code 2026-08-24T17:01:54.882074+05:30}. */
    private String createdAt;
    private String updatedAt;

    private String validationStatus;
    private Boolean validationPassed;
    private String validationType;
    private String validationLabel;
    private Float validationConfidence;

    /** Document attributes (ES {@code flattened}) — kept so callers can verify a
     *  match came from the field they expected. */
    private Map<String, Object> attr;

    /** Folder attributes (ES dynamic object). */
    private Map<String, Object> folderAttr;

    /** Relevance score. Null when the query had no free-text term, since
     *  everything then scores identically and the number would be noise. */
    private Double score;
}
