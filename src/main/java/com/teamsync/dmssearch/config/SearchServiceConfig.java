package com.teamsync.dmssearch.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.teamsync.dmssearch.legacy.LegacyQueryBuilder;
import com.teamsync.dmssearch.query.CursorCodec;
import com.teamsync.dmssearch.query.SearchQueryBuilder;
import com.teamsync.dmssearch.service.SearchAuditLogger;
import com.teamsync.dmssearch.service.SearchService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Builds one {@link SearchService} per API version.
 *
 * <h2>Why two beans instead of one service that branches</h2>
 * The two index mappings need genuinely different queries — a {@code nested}
 * query on one and a plain {@code term} on the other for the same attribute
 * lookup, with case handling inverted between them. A single service choosing
 * between them at runtime would put that fork inside every method, and every
 * future change would have to be reasoned about twice in one place.
 *
 * <p>Instead the fork happens exactly once, here, at wiring time. Each bean is
 * then a straight line: one builder, one index, one result DTO. The controller
 * picks a bean per API version and neither bean knows the other exists.
 *
 * <h2>Why not two copies of SearchService</h2>
 * {@link SearchService} holds no mapping knowledge at all — it executes the
 * request, separates a rejected query from an unavailable cluster, guards the
 * offset window, decodes cursors, and detects partial results. That logic is
 * identical for both mappings and unpleasant to get right, so it is instantiated
 * twice rather than written twice. Two copies would drift, and the one that
 * drifts is the one nobody is watching.
 *
 * <p>The only other shared component is
 * {@code TenantFilter} — the tenant/workspace isolation filter, shared
 * deliberately so that boundary has a single implementation.
 */
@Configuration
public class SearchServiceConfig {

    /**
     * API version 1.0 — the LEGACY mapping, which is what production runs today.
     *
     * <p>{@code @Bean("legacySearchService")} and marked primary-by-default
     * usage in the controller: until the optimized pipeline is deployed, this is
     * the only version with data behind it.
     */
    @Bean("legacySearchService")
    public SearchService legacySearchService(ElasticsearchClient es,
                                            LegacyQueryBuilder legacyQueryBuilder,
                                            ElasticsearchProperties props,
                                            CursorCodec cursorCodec,
                                            SearchAuditLogger auditLogger) {
        return new SearchService(es, legacyQueryBuilder, props, cursorCodec, auditLogger);
    }

    /**
     * API version 1.1 — the OPTIMIZED mapping.
     *
     * <p>Built and dev-tested; the pipeline that populates its index is not yet
     * deployed to production. The bean exists regardless so v1.1 can be
     * exercised against any environment where that index does exist, and so the
     * cutover is a configuration change rather than a code change.
     */
    @Bean("optimizedSearchService")
    public SearchService optimizedSearchService(ElasticsearchClient es,
                                               SearchQueryBuilder searchQueryBuilder,
                                               ElasticsearchProperties props,
                                               CursorCodec cursorCodec,
                                               SearchAuditLogger auditLogger) {
        return new SearchService(es, searchQueryBuilder, props, cursorCodec, auditLogger);
    }
}
