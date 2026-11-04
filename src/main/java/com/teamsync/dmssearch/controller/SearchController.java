package com.teamsync.dmssearch.controller;

import com.teamsync.dmssearch.config.ElasticsearchProperties;
import com.teamsync.dmssearch.dto.error.SearchErrorCode;
import com.teamsync.dmssearch.dto.request.RequestIdentity;
import com.teamsync.dmssearch.dto.request.SearchCriteria;
import com.teamsync.dmssearch.dto.request.SortKey;
import com.teamsync.dmssearch.dto.response.ApiResponse;
import com.teamsync.dmssearch.dto.response.SearchPage;
import com.teamsync.dmssearch.exception.SearchException;
import com.teamsync.dmssearch.service.PermissionValidationService;
import com.teamsync.dmssearch.service.SearchService;
import org.springframework.beans.factory.annotation.Qualifier;
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
 * called.
 *
 * <h2>Two versions, two mappings</h2>
 * The same URL and the same query parameters serve two different index mappings,
 * selected by {@code X-API-Version}:
 *
 * <table border="1">
 *   <tr><th></th><th>1.0 (default)</th><th>1.1</th></tr>
 *   <tr><td>index</td><td>legacy, running in production</td>
 *       <td>optimized</td></tr>
 *   <tr><td>response shape</td><td>legacy snake_case, {@code attributes} as a LIST</td>
 *       <td>camelCase, {@code attr} as an OBJECT</td></tr>
 *   <tr><td>archive filter</td><td>unsupported (no such field)</td><td>supported</td></tr>
 *   <tr><td>filter by documentTypeId</td><td>rejected with 400</td><td>supported</td></tr>
 * </table>
 *
 * <p><b>1.0 is the default</b>, and that is not a stylistic choice: the pipeline
 * that populates the optimized index is built but not yet deployed to
 * production, so v1.1 has no data behind it there. A caller that omits the
 * header must reach the index that actually holds documents.
 *
 * <p>The <i>request</i> contract is identical across both versions — same
 * parameters, same validation, same paging. Only the backing index and the
 * <i>response</i> shape differ, which is precisely what makes this a version
 * boundary rather than a silent behaviour change. When production migrates to
 * the optimized mapping, v1.0 and everything in the {@code legacy} package are
 * deleted together.
 *
 * <p><b>Versioning</b> is header-based ({@code X-API-Version}), matching
 * document-service — the path carries no version segment. See
 * {@code WebConfig#configureApiVersioning}.
 *
 * <p>Note {@code GET}, not {@code POST /search}: this is a read. Keeping it a
 * GET means it is cacheable, bookmarkable, and honest about HTTP semantics.
 */
@RestController
@RequestMapping("/api/search")
public class SearchController {

    /** The legacy mapping — what production runs today. */
    private static final String V1_0 = "1.0";

    /** The optimized mapping — built, not yet deployed to production. */
    private static final String V1_1 = "1.1";

    private static final String ATTR_PREFIX = "attr.";
    private static final String FOLDER_ATTR_PREFIX = "folderAttr.";

    private final SearchService legacySearchService;
    private final SearchService optimizedSearchService;
    private final ElasticsearchProperties props;
    private final PermissionValidationService permissionValidationService;

    /**
     * Explicit constructor rather than {@code @RequiredArgsConstructor}: two
     * beans of the same type need {@code @Qualifier}, and Lombok does not copy
     * that annotation onto generated constructor parameters unless configured
     * to. Writing it out keeps the wiring unambiguous and compile-checked.
     */
    public SearchController(@Qualifier("legacySearchService") SearchService legacySearchService,
                            @Qualifier("optimizedSearchService") SearchService optimizedSearchService,
                            ElasticsearchProperties props,
                            PermissionValidationService permissionValidationService) {
        this.legacySearchService = legacySearchService;
        this.optimizedSearchService = optimizedSearchService;
        this.props = props;
        this.permissionValidationService = permissionValidationService;
    }

    // ==================================================================
    // v1.0 — legacy mapping (default)
    // ==================================================================

    /**
     * Search against the LEGACY production index.
     *
     * <p>The parameter list is declared again on the v1.1 handler below rather
     * than factored into a bound object. That duplication is deliberate: the
     * {@code @RequestParam} declarations ARE the HTTP contract, and Spring
     * resolves the version at the handler-mapping level, so each version needs
     * its own mapped method. Everything past parameter binding is shared — both
     * handlers hand off to {@link #executeSearch}.
     */
    @GetMapping(value = "/documents", produces = MediaType.APPLICATION_JSON_VALUE, version = V1_0)
    public ResponseEntity<ApiResponse<SearchPage>> searchDocumentsV10(
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

            @RequestParam Map<String, String> allParams) {

        return executeSearch(legacySearchService,
                tenantId, workspaceId, userId,
                q, documentTypeName, documentTypeId, folderId, folderTypeId, folderTypeName,
                folderLifecycleState, fileExt, validationStatus,
                createdFrom, createdTo, updatedFrom, updatedTo,
                includeDeleted, includeArchived,
                page, size, cursor, sort, allParams);
    }

    // ==================================================================
    // v1.1 — optimized mapping
    // ==================================================================

    /** Search against the OPTIMIZED index. Same request contract as v1.0. */
    @GetMapping(value = "/documents", produces = MediaType.APPLICATION_JSON_VALUE, version = V1_1)
    public ResponseEntity<ApiResponse<SearchPage>> searchDocumentsV11(
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

            @RequestParam Map<String, String> allParams) {

        return executeSearch(optimizedSearchService,
                tenantId, workspaceId, userId,
                q, documentTypeName, documentTypeId, folderId, folderTypeId, folderTypeName,
                folderLifecycleState, fileExt, validationStatus,
                createdFrom, createdTo, updatedFrom, updatedTo,
                includeDeleted, includeArchived,
                page, size, cursor, sort, allParams);
    }

    // ==================================================================
    // shared: identical for every version
    // ==================================================================

    /**
     * Identity resolution, permission check, validation, criteria assembly and
     * response shaping — all version-independent.
     *
     * <p>The ONLY thing a version changes is which {@link SearchService} runs the
     * query. Everything that could be got wrong about authorisation or paging is
     * therefore written once.
     */
    private ResponseEntity<ApiResponse<SearchPage>> executeSearch(
            SearchService searchService,
            String tenantId, String workspaceId, String userId,
            String q, String documentTypeName, String documentTypeId,
            String folderId, String folderTypeId, String folderTypeName,
            String folderLifecycleState, String fileExt, String validationStatus,
            String createdFrom, String createdTo, String updatedFrom, String updatedTo,
            boolean includeDeleted, boolean includeArchived,
            int page, Integer size, String cursor, String sort,
            Map<String, String> allParams) {

        RequestIdentity identity = resolveIdentity(tenantId, workspaceId, userId);

        // Before anything else, and before Elasticsearch is touched. The gateway
        // does NOT inject X-Workspace-Id — it is caller-supplied — so filtering
        // on it enforces the rule without ever verifying the claim. This is what
        // verifies it. No-op while permission.service.enabled is false.
        permissionValidationService.requireFileRead(identity);

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
        // userId is required alongside the other two. It was optional while it
        // was audit-only; it is now the SUBJECT of the permission check, and the
        // old fallback substituted the literal "unknown", which produced two bad
        // outcomes:
        //
        //   1. permission-service answers 404 "User role not found" for that
        //      subject, which maps to 403 "no read permission in this workspace".
        //      The caller is told their PERMISSIONS are wrong when the real fault
        //      is a MISSING HEADER — sending whoever investigates to audit role
        //      assignments for a problem that does not exist.
        //   2. SearchAuditLogger records userId=unknown, leaving a
        //      compliance-relevant search with no subject.
        //
        // Rejecting here names the actual fault, and does it before the search
        // or the permission call runs.
        if (isBlank(userId)) missing.add(RequestIdentity.HEADER_USER);

        if (!missing.isEmpty()) {
            throw new SearchException(
                    SearchErrorCode.MISSING_IDENTITY,
                    "Missing required identity header(s): " + String.join(", ", missing),
                    missing);
        }
        return new RequestIdentity(tenantId.strip(), workspaceId.strip(), userId.strip());
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
     *
     * <p>The prefixes stay {@code attr.} / {@code folderAttr.} on BOTH versions
     * even though the legacy index spells those fields {@code attributes} and
     * {@code folder_type_attributes}. They are the API vocabulary, not field
     * names — each query builder maps them onto its own mapping, so a client
     * changing version does not have to rewrite its query strings.
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
