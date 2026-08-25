package com.teamsync.dmssearch.controller;

import com.teamsync.dmssearch.config.ElasticsearchProperties;
import com.teamsync.dmssearch.dto.error.SearchErrorCode;
import com.teamsync.dmssearch.dto.request.RequestIdentity;
import com.teamsync.dmssearch.dto.request.SearchCriteria;
import com.teamsync.dmssearch.dto.request.SortKey;
import com.teamsync.dmssearch.dto.response.ApiResponse;
import com.teamsync.dmssearch.dto.response.SearchPage;
import com.teamsync.dmssearch.exception.SearchException;
import com.teamsync.dmssearch.service.SearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The search API.
 *
 * <p><b>One endpoint, two jobs.</b> It serves both exact identifier lookup
 * (replacing document-service's hardcoded {@code /search/token}, {@code /pan}
 * and {@code /acknowledgement} routes) and free-text discovery. Which query
 * Elasticsearch runs depends on which parameters arrive, not on which URL was
 * called — see {@code SearchQueryBuilder}.
 *
 * <p><b>Versioning</b> is header-based ({@code X-API-Version: 1.0}), matching
 * document-service — the path carries no version segment. See
 * {@code WebConfig#configureApiVersioning}. A default version is configured, so
 * omitting the header resolves to 1.0 rather than failing.
 *
 * <p>Note {@code GET}, not {@code POST /search}: this is a read. Keeping it a
 * GET means it is cacheable, bookmarkable, and honest about HTTP semantics.
 */
@RestController
@RequestMapping("/api/search")
@RequiredArgsConstructor
public class SearchController {

    /** The version this controller serves. Bumped only alongside a real contract change. */
    private static final String API_VERSION = "1.0";

    private static final String ATTR_PREFIX = "attr.";
    private static final String FOLDER_ATTR_PREFIX = "folderAttr.";

    private final SearchService searchService;
    private final ElasticsearchProperties props;

    @GetMapping(value = "/documents", produces = MediaType.APPLICATION_JSON_VALUE, version = API_VERSION)
    public ResponseEntity<ApiResponse<SearchPage>> searchDocuments(
            @RequestHeader(value = RequestIdentity.HEADER_TENANT, required = false) String tenantId,
            @RequestHeader(value = RequestIdentity.HEADER_WORKSPACE, required = false) String workspaceId,
            @RequestHeader(value = RequestIdentity.HEADER_USER, required = false) String userId,

            @RequestParam(required = false) String q,
            @RequestParam(required = false) String documentTypeName,
            @RequestParam(required = false) String documentTypeId,
            @RequestParam(required = false) String folderId,
            @RequestParam(required = false) String folderTypeId,
            @RequestParam(required = false) String folderTypeName,
            @RequestParam(required = false) String folderLifecycleState,
            @RequestParam(required = false) String fileExt,
            @RequestParam(required = false) String validationStatus,

            @RequestParam(required = false) String createdFrom,
            @RequestParam(required = false) String createdTo,
            @RequestParam(required = false) String updatedFrom,
            @RequestParam(required = false) String updatedTo,

            @RequestParam(defaultValue = "false") boolean includeDeleted,
            @RequestParam(defaultValue = "false") boolean includeArchived,

            @RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) String sort,

            // Captures the dynamic attr.* / folderAttr.* parameters, whose keys
            // are tenant-defined and therefore cannot be declared up front.
            @RequestParam Map<String, String> allParams) {

        RequestIdentity identity = resolveIdentity(tenantId, workspaceId, userId);

        int effectiveSize = validateSize(size);
        validatePaging(page, cursor);
        SortKey sortKey = resolveSort(sort);

        SearchCriteria criteria = SearchCriteria.builder()
                .q(q)
                .documentTypeName(documentTypeName)
                .documentTypeId(documentTypeId)
                .folderId(folderId)
                .folderTypeId(folderTypeId)
                .folderTypeName(folderTypeName)
                .folderLifecycleState(folderLifecycleState)
                .fileExt(fileExt)
                .validationStatus(validationStatus)
                .attributes(extractPrefixed(allParams, ATTR_PREFIX))
                .folderAttributes(extractPrefixed(allParams, FOLDER_ATTR_PREFIX))
                .createdFrom(createdFrom)
                .createdTo(createdTo)
                .updatedFrom(updatedFrom)
                .updatedTo(updatedTo)
                .includeDeleted(includeDeleted)
                .includeArchived(includeArchived)
                .page(page)
                .size(effectiveSize)
                .cursor(cursor)
                .sort(sortKey)
                .build();

        SearchPage result = searchService.search(criteria, identity);

        // 200 with an empty list, never 404: "nothing matched" is a successful
        // search, and 404 would mean the endpoint itself does not exist.
        return ResponseEntity.ok()
                // Results are scoped to one caller — never let a shared cache
                // hold them.
                .header("Cache-Control", "private, no-store")
                .body(ApiResponse.success(result, "Search completed successfully"));
    }

    /**
     * The gateway injects these after validating the caller's token. Absent
     * means the request bypassed the gateway, so it is refused rather than
     * served — an unscoped search would return every tenant's documents.
     */
    private RequestIdentity resolveIdentity(String tenantId, String workspaceId, String userId) {
        List<String> missing = new java.util.ArrayList<>();
        if (isBlank(tenantId)) missing.add(RequestIdentity.HEADER_TENANT);
        if (isBlank(workspaceId)) missing.add(RequestIdentity.HEADER_WORKSPACE);
        if (!missing.isEmpty()) {
            throw new SearchException(
                    SearchErrorCode.MISSING_IDENTITY,
                    "Missing required identity header(s): " + String.join(", ", missing),
                    missing);
        }
        // userId is audit-only — access is workspace-scoped, so its absence does
        // not change which documents match.
        return new RequestIdentity(tenantId.strip(), workspaceId.strip(),
                isBlank(userId) ? "unknown" : userId.strip());
    }

    private int validateSize(Integer size) {
        if (size == null) {
            return props.getDefaultPageSize();
        }
        if (size < 1 || size > props.getMaxPageSize()) {
            throw new SearchException(
                    SearchErrorCode.INVALID_PARAMETER,
                    "size must be between 1 and " + props.getMaxPageSize(),
                    List.of("size"));
        }
        return size;
    }

    private void validatePaging(int page, String cursor) {
        if (page < 0) {
            throw new SearchException(
                    SearchErrorCode.INVALID_PARAMETER, "page must not be negative", List.of("page"));
        }
        // Both supplied is ambiguous — silently preferring one would give the
        // caller a page they did not ask for and no indication why.
        if (page > 0 && cursor != null && !cursor.isBlank()) {
            throw new SearchException(
                    SearchErrorCode.CONFLICTING_PAGINATION,
                    SearchErrorCode.CONFLICTING_PAGINATION.getDefaultMessage(),
                    List.of("page", "cursor"));
        }
    }

    private SortKey resolveSort(String sort) {
        if (isBlank(sort)) {
            return null;    // resolved downstream: relevance with q, else newest-first
        }
        return SortKey.fromApiName(sort).orElseThrow(() -> new SearchException(
                SearchErrorCode.INVALID_SORT,
                "Unsupported sort '" + sort + "'. Supported: " + SortKey.supported(),
                List.of("sort")));
    }

    /**
     * Pulls {@code attr.x=y} / {@code folderAttr.x=y} out of the raw parameter
     * map, keyed by the part after the prefix.
     */
    private Map<String, String> extractPrefixed(Map<String, String> allParams, String prefix) {
        Map<String, String> extracted = new LinkedHashMap<>();
        allParams.forEach((key, value) -> {
            if (key.startsWith(prefix) && key.length() > prefix.length()) {
                extracted.put(key.substring(prefix.length()), value);
            }
        });
        return extracted;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
