package com.teamsync.dmssearch.controller;

import com.teamsync.dmssearch.config.ElasticsearchProperties;
import com.teamsync.dmssearch.dto.error.SearchErrorCode;
import com.teamsync.dmssearch.dto.request.RequestIdentity;
import com.teamsync.dmssearch.dto.request.SearchCriteria;
import com.teamsync.dmssearch.dto.request.SortKey;
import com.teamsync.dmssearch.dto.response.SearchPage;
import com.teamsync.dmssearch.exception.GlobalExceptionHandler;
import com.teamsync.dmssearch.exception.SearchException;
import com.teamsync.dmssearch.service.PermissionValidationService;
import com.teamsync.dmssearch.service.SearchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.accept.DefaultApiVersionStrategy;
import org.springframework.web.accept.HeaderApiVersionResolver;
import org.springframework.web.accept.SemanticApiVersionParser;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP-layer tests: parameter binding, identity enforcement, and the mapping
 * from {@link SearchException} to status codes and the response envelope.
 *
 * <p>Standalone MockMvc rather than {@code @WebMvcTest} — no Spring context, so
 * these run in milliseconds and cannot be broken by unrelated configuration.
 */
class SearchControllerTest {

    private static final String URL = "/api/search/dms/documents";

    private MockMvc mockMvc;

    /**
     * The v1.0 (legacy mapping) service. Named without a suffix because 1.0 is
     * the DEFAULT version, so every test that sends no {@code X-API-Version}
     * header lands here.
     */
    private SearchService searchService;

    /** The v1.1 (optimized mapping) service. */
    private SearchService optimizedSearchService;

    private PermissionValidationService permissionValidationService;

    @BeforeEach
    void setUp() {
        searchService = mock(SearchService.class);
        optimizedSearchService = mock(SearchService.class);
        // Mocked, so the default is "permission granted" — every existing test
        // exercises the happy path. The denial and outage paths are asserted
        // explicitly below.
        permissionValidationService = mock(PermissionValidationService.class);
        ElasticsearchProperties props = new ElasticsearchProperties();

        mockMvc = MockMvcBuilders
                .standaloneSetup(new SearchController(
                        searchService, optimizedSearchService, props, permissionValidationService))
                .setControllerAdvice(new GlobalExceptionHandler())
                // Required: the controller handlers declare versions, and a
                // mapping with a version cannot resolve without a strategy.
                // Built to mirror WebConfig#configureApiVersioning so these
                // tests exercise the real versioning behaviour rather than a
                // bypass of it.
                .setApiVersionStrategy(apiVersionStrategy())
                .build();

        when(searchService.search(any(), any())).thenReturn(emptyPage());
        when(optimizedSearchService.search(any(), any())).thenReturn(emptyPage());
    }

    /**
     * Mirrors WebConfig: X-API-Version header, semantic parsing, default 1.0.
     *
     * <p>Both versions are registered. 1.0 is the default because the pipeline
     * feeding the v1.1 index is not yet deployed to production, so an
     * unversioned request must reach the index that actually holds documents.
     */
    private static DefaultApiVersionStrategy apiVersionStrategy() {
        DefaultApiVersionStrategy strategy = new DefaultApiVersionStrategy(
                List.of(new HeaderApiVersionResolver("X-API-Version")),
                new SemanticApiVersionParser(),
                false,          // version not required — a default is configured
                "1.0",          // default version — the legacy mapping
                false,          // do not auto-detect supported versions
                null,           // no supported-version predicate
                null);          // no deprecation handler — see ApiVersioningProperties
        strategy.addSupportedVersion("1.0");
        strategy.addSupportedVersion("1.1");
        return strategy;
    }

    private SearchPage emptyPage() {
        return SearchPage.builder()
                .items(List.of()).page(0).size(20)
                .total(0).totalIsExact(true).hasNext(false).tookMs(1)
                .build();
    }

    // ------------------------------------------------------------------
    // identity
    // ------------------------------------------------------------------

    @Test
    @DisplayName("no identity headers -> 401 and the search never runs")
    void missingIdentityRejected() throws Exception {
        mockMvc.perform(get(URL))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value(SearchErrorCode.MISSING_IDENTITY.getCode()));

        // The critical half: an unscoped search must never reach Elasticsearch.
        verify(searchService, never()).search(any(), any());
    }

    @Test
    @DisplayName("workspace header alone is not enough")
    void partialIdentityRejected() throws Exception {
        mockMvc.perform(get(URL).header(RequestIdentity.HEADER_WORKSPACE, "ws-1"))
                .andExpect(status().isUnauthorized());
        verify(searchService, never()).search(any(), any());
    }

    @Test
    @DisplayName("identity reaches the service exactly as sent")
    void identityPassedThrough() throws Exception {
        mockMvc.perform(get(URL)
                        .header(RequestIdentity.HEADER_TENANT, "tenant-x")
                        .header(RequestIdentity.HEADER_WORKSPACE, "ws-y")
                        .header(RequestIdentity.HEADER_USER, "user-z"))
                .andExpect(status().isOk());

        ArgumentCaptor<RequestIdentity> captor = ArgumentCaptor.forClass(RequestIdentity.class);
        verify(searchService).search(any(), captor.capture());
        assertThat(captor.getValue().tenantId()).isEqualTo("tenant-x");
        assertThat(captor.getValue().workspaceId()).isEqualTo("ws-y");
        assertThat(captor.getValue().userId()).isEqualTo("user-z");
    }

    @Test
    @DisplayName("a missing user header is rejected — it is the permission subject, not audit data")
    void userHeaderIsRequired() throws Exception {
        // This test previously asserted 200, on the reasoning that userId does not
        // affect which documents match. That was true while userId was audit-only.
        // It is now the subject of the permission check, and the old fallback
        // substituted "unknown" — which permission-service answers with 404, which
        // maps to 403 "no read permission in this workspace". A missing header
        // would have been reported to the caller as a permissions problem.
        mockMvc.perform(get(URL)
                        .header(RequestIdentity.HEADER_TENANT, "t")
                        .header(RequestIdentity.HEADER_WORKSPACE, "w"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value(SearchErrorCode.MISSING_IDENTITY.getCode()))
                // The response must NAME the header, so a gateway misconfiguration
                // is diagnosable from the error alone.
                .andExpect(jsonPath("$.details.fields").value(hasItem(RequestIdentity.HEADER_USER)));

        // And nothing downstream ran.
        verify(permissionValidationService, never()).requireFileRead(any());
        verify(searchService, never()).search(any(), any());
    }

    // ------------------------------------------------------------------
    // workspace permission
    // ------------------------------------------------------------------

    @Test
    @DisplayName("permission is checked BEFORE Elasticsearch is touched")
    void permissionCheckedBeforeSearch() throws Exception {
        mockMvc.perform(withIdentity(get(URL))).andExpect(status().isOk());

        // Order matters: checking after would still return the right status but
        // would have already run the query, so an unauthorised caller's search
        // load hits the cluster regardless.
        InOrder order = inOrder(permissionValidationService, searchService);
        order.verify(permissionValidationService).requireFileRead(any());
        order.verify(searchService).search(any(), any());
    }

    @Test
    @DisplayName("permission denied -> 403 and the search never runs")
    void permissionDeniedRejected() throws Exception {
        doThrow(new SearchException(SearchErrorCode.PERMISSION_DENIED,
                "User lacks FILE_READ permission in workspace ws-other"))
                .when(permissionValidationService).requireFileRead(any());

        mockMvc.perform(withIdentity(get(URL)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(SearchErrorCode.PERMISSION_DENIED.getCode()));

        // The half that matters: a caller without permission must not reach
        // Elasticsearch at all.
        verify(searchService, never()).search(any(), any());
    }

    @Test
    @DisplayName("permission-service unreachable -> 503, not 403, and no search")
    void permissionBackendDownFailsClosed() throws Exception {
        // Fails CLOSED: no answer means no access. And 503 rather than 403,
        // because the caller may well be authorised — we could not find out, so
        // telling them "forbidden" sends them debugging the wrong thing.
        doThrow(new SearchException(SearchErrorCode.PERMISSION_BACKEND_UNAVAILABLE))
                .when(permissionValidationService).requireFileRead(any());

        mockMvc.perform(withIdentity(get(URL)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error")
                        .value(SearchErrorCode.PERMISSION_BACKEND_UNAVAILABLE.getCode()));

        verify(searchService, never()).search(any(), any());
    }

    @Test
    @DisplayName("the workspace checked is the one from the header, not from any parameter")
    void permissionCheckedAgainstHeaderWorkspace() throws Exception {
        // A caller must not be able to pass the check for one workspace and then
        // have the query run against another.
        mockMvc.perform(get(URL)
                        .header(RequestIdentity.HEADER_TENANT, "tenant-1")
                        .header(RequestIdentity.HEADER_WORKSPACE, "workspace-real")
                        .header(RequestIdentity.HEADER_USER, "user-1")
                        .param("folderId", "workspace-spoofed"))
                .andExpect(status().isOk());

        ArgumentCaptor<RequestIdentity> captor = ArgumentCaptor.forClass(RequestIdentity.class);
        verify(permissionValidationService).requireFileRead(captor.capture());
        assertThat(captor.getValue().workspaceId()).isEqualTo("workspace-real");
    }

    // ------------------------------------------------------------------
    // API versioning
    // ------------------------------------------------------------------

    @Test
    @DisplayName("X-API-Version: 1.0 resolves the endpoint")
    void explicitVersionAccepted() throws Exception {
        mockMvc.perform(withIdentity(get(URL)).header("X-API-Version", "1.0"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("X-API-Version: 1.1 resolves the endpoint")
    void version11Accepted() throws Exception {
        mockMvc.perform(withIdentity(get(URL)).header("X-API-Version", "1.1"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("1.0 queries the legacy mapping and never the optimized one")
    void version10RoutesToLegacy() throws Exception {
        mockMvc.perform(withIdentity(get(URL)).header("X-API-Version", "1.0"))
                .andExpect(status().isOk());

        // The whole point of the version split. Routing 1.0 at the optimized
        // index would search an index production does not populate and return
        // an empty page that looks like "no documents found".
        verify(searchService).search(any(), any());
        verify(optimizedSearchService, never()).search(any(), any());
    }

    @Test
    @DisplayName("1.1 queries the optimized mapping and never the legacy one")
    void version11RoutesToOptimized() throws Exception {
        mockMvc.perform(withIdentity(get(URL)).header("X-API-Version", "1.1"))
                .andExpect(status().isOk());

        verify(optimizedSearchService).search(any(), any());
        verify(searchService, never()).search(any(), any());
    }

    @Test
    @DisplayName("omitting the version header queries the LEGACY mapping")
    void defaultVersionRoutesToLegacy() throws Exception {
        // Not merely "a default resolves" — WHICH default. Production ingests
        // into the legacy index only, so an unversioned caller must reach it.
        // If this ever flips to the optimized service, every client that has not
        // opted in starts getting empty results.
        mockMvc.perform(withIdentity(get(URL)))
                .andExpect(status().isOk());

        verify(searchService).search(any(), any());
        verify(optimizedSearchService, never()).search(any(), any());
    }

    @Test
    @DisplayName("omitting the version header falls back to the default")
    void versionHeaderOptional() throws Exception {
        // A default is configured on purpose, so curl and browser exploration do
        // not need the header. Clients wanting stability should still send it.
        mockMvc.perform(withIdentity(get(URL)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("an unsupported version is rejected, not silently served as 1.0")
    void unsupportedVersionRejected() throws Exception {
        // Silently serving 1.0 to a client that asked for 2.0 would hand it a
        // response shape it is not written for.
        mockMvc.perform(withIdentity(get(URL)).header("X-API-Version", "9.9"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("the path carries no version segment")
    void pathIsUnversioned() {
        // Version travels in the header, matching document-service. If this ever
        // reads /api/v1/... the platform convention has drifted.
        assertThat(URL).isEqualTo("/api/search/dms/documents");
        assertThat(URL).doesNotContain("/v1");
    }

    // ------------------------------------------------------------------
    // parameter binding
    // ------------------------------------------------------------------

    @Test
    @DisplayName("attr.* and folderAttr.* params are split out by prefix")
    void dynamicAttributeParamsExtracted() throws Exception {
        mockMvc.perform(withIdentity(get(URL)
                        .param("attr.pan_number", "ABCDE1234F")
                        .param("attr.name", "Ravi")
                        .param("folderAttr.token_number", "TOKEN-1")
                        .param("q", "something")))
                .andExpect(status().isOk());

        SearchCriteria criteria = capturedCriteria();
        assertThat(criteria.getAttributes())
                .containsEntry("pan_number", "ABCDE1234F")
                .containsEntry("name", "Ravi");
        assertThat(criteria.getFolderAttributes()).containsEntry("token_number", "TOKEN-1");
        // the prefixes themselves must not leak into the maps
        assertThat(criteria.getAttributes()).doesNotContainKey("attr.pan_number");
    }

    @Test
    @DisplayName("ordinary params are not mistaken for attributes")
    void ordinaryParamsNotTreatedAsAttributes() throws Exception {
        mockMvc.perform(withIdentity(get(URL).param("documentTypeName", "passport")))
                .andExpect(status().isOk());

        SearchCriteria criteria = capturedCriteria();
        assertThat(criteria.getDocumentTypeName()).isEqualTo("passport");
        assertThat(criteria.getAttributes()).isEmpty();
    }

    @Test
    @DisplayName("visibility flags default to hiding trashed and archived")
    void visibilityDefaults() throws Exception {
        mockMvc.perform(withIdentity(get(URL))).andExpect(status().isOk());

        SearchCriteria criteria = capturedCriteria();
        assertThat(criteria.isIncludeDeleted()).isFalse();
        assertThat(criteria.isIncludeArchived()).isFalse();
    }

    @Test
    @DisplayName("sort is parsed into the enum")
    void sortParsed() throws Exception {
        mockMvc.perform(withIdentity(get(URL).param("sort", "createdAt")))
                .andExpect(status().isOk());
        assertThat(capturedCriteria().getSort()).isEqualTo(SortKey.CREATED_AT);
    }

    // ------------------------------------------------------------------
    // validation
    // ------------------------------------------------------------------

    @Test
    @DisplayName("size above the maximum -> 400 naming the field")
    void sizeTooLarge() throws Exception {
        mockMvc.perform(withIdentity(get(URL).param("size", "5000")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(SearchErrorCode.INVALID_PARAMETER.getCode()))
                .andExpect(jsonPath("$.details.fields[0]").value("size"));
    }

    @Test
    @DisplayName("size of zero -> 400")
    void sizeZero() throws Exception {
        mockMvc.perform(withIdentity(get(URL).param("size", "0")))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("negative page -> 400")
    void negativePage() throws Exception {
        mockMvc.perform(withIdentity(get(URL).param("page", "-1")))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("page and cursor together -> 400 rather than silently picking one")
    void conflictingPagination() throws Exception {
        mockMvc.perform(withIdentity(get(URL).param("page", "2").param("cursor", "abc")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(SearchErrorCode.CONFLICTING_PAGINATION.getCode()));
    }

    @Test
    @DisplayName("unsupported sort -> 400 listing what is supported")
    void invalidSort() throws Exception {
        mockMvc.perform(withIdentity(get(URL).param("sort", "nonsense")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(SearchErrorCode.INVALID_SORT.getCode()))
                .andExpect(jsonPath("$.details.reason").value(
                        org.hamcrest.Matchers.containsString("Supported")));
    }

    // ------------------------------------------------------------------
    // responses
    // ------------------------------------------------------------------

    @Test
    @DisplayName("no matches is 200 with an empty list, never 404")
    void noMatchesIsOk() throws Exception {
        mockMvc.perform(withIdentity(get(URL).param("q", "nothingmatchesthis")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isArray())
                .andExpect(jsonPath("$.data.total").value(0));
    }

    @Test
    @DisplayName("results are marked private and uncacheable")
    void cacheControlIsPrivate() throws Exception {
        // Results are scoped to one caller; a shared cache holding them would
        // serve one tenant's documents to another.
        mockMvc.perform(withIdentity(get(URL)))
                .andExpect(header().string("Cache-Control", "private, no-store"));
    }

    @Test
    @DisplayName("the response carries the platform envelope")
    void envelopeShape() throws Exception {
        mockMvc.perform(withIdentity(get(URL)))
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.data").exists())
                .andExpect(jsonPath("$.requestInfo.timestamp").exists());
    }

    @Test
    @DisplayName("an unreachable backend is 503 with Retry-After, not 500")
    void backendDownIs503() throws Exception {
        when(searchService.search(any(), any()))
                .thenThrow(new SearchException(SearchErrorCode.SEARCH_BACKEND_UNAVAILABLE));

        mockMvc.perform(withIdentity(get(URL)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "5"))
                .andExpect(jsonPath("$.error").value(SearchErrorCode.SEARCH_BACKEND_UNAVAILABLE.getCode()));
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder withIdentity(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder) {
        return builder
                .header(RequestIdentity.HEADER_TENANT, "tenant-1")
                .header(RequestIdentity.HEADER_WORKSPACE, "workspace-1")
                .header(RequestIdentity.HEADER_USER, "user-1");
    }

    private SearchCriteria capturedCriteria() {
        ArgumentCaptor<SearchCriteria> captor = ArgumentCaptor.forClass(SearchCriteria.class);
        verify(searchService).search(captor.capture(), any());
        return captor.getValue();
    }
}
