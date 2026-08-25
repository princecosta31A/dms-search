package com.teamsync.dmssearch.dto.request;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * A validated, normalised search request — the internal form the query builder
 * consumes, decoupled from HTTP parameter binding.
 *
 * <p>Two distinct kinds of matching live here, and keeping them apart is the
 * point of the whole class:
 *
 * <ul>
 *   <li>{@link #q} is <b>free text</b>. It becomes a {@code match} on
 *       {@code searchAll} with {@code operator: and}.</li>
 *   <li>Everything else is an <b>exact</b> value. Each becomes a {@code term} on
 *       a keyword field.</li>
 * </ul>
 *
 * <p>Routing identifiers through free text is a real defect, not a style
 * preference: {@code match} defaults to OR and the analyzer splits on hyphens,
 * so searching {@code TOKEN-2345687654} matches a document whose token is
 * {@code TOKEN-20260819054206} — they share the token {@code token}. Exact
 * lookups must use {@link #attributes} / {@link #folderAttributes}, never
 * {@link #q}.
 */
@Data
@Builder
public class SearchCriteria {

    /** Free text, matched against {@code searchAll}. Null/blank = match everything. */
    private String q;

    // ---- exact-match filters (keyword fields) ----
    private String documentTypeName;
    private String documentTypeId;
    private String folderId;
    private String folderTypeId;
    private String folderTypeName;
    private String folderLifecycleState;
    private String fileExt;
    private String validationStatus;

    /** {@code attr.<key>=<value>} → {@code term} on {@code attr.<key>} (flattened). */
    @Builder.Default
    private Map<String, String> attributes = Map.of();

    /** {@code folderAttr.<key>=<value>} → {@code term} on {@code folderAttr.<key>}. */
    @Builder.Default
    private Map<String, String> folderAttributes = Map.of();

    // ---- date ranges (ISO-8601; inclusive) ----
    private String createdFrom;
    private String createdTo;
    private String updatedFrom;
    private String updatedTo;

    // ---- visibility ----
    /** Trashed documents are hidden unless explicitly asked for (a Trash view). */
    @Builder.Default
    private boolean includeDeleted = false;

    /** Archived documents are hidden unless explicitly asked for. */
    @Builder.Default
    private boolean includeArchived = false;

    // ---- paging / ordering ----
    private int page;
    private int size;
    /** Opaque cursor for deep paging. Mutually exclusive with {@link #page}. */
    private String cursor;
    private SortKey sort;

    /** True when the caller supplied free text — relevance sorting only makes
     *  sense in that case. */
    public boolean hasFreeText() {
        return q != null && !q.isBlank();
    }
}
