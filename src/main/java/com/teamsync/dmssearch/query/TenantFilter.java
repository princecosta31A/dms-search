package com.teamsync.dmssearch.query;

import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import com.teamsync.dmssearch.dto.request.RequestIdentity;
import org.springframework.stereotype.Component;

/**
 * The authorisation boundary — the one piece of code both API versions share.
 *
 * <h2>Why this is shared when nothing else is</h2>
 * The legacy (v1.0) and optimized (v1.1) query builders are deliberately
 * independent: different field names, different query shapes, different result
 * DTOs. Duplicating the tenant filter alongside them would mean <b>two</b>
 * places that must enforce isolation, and the day one of them drifts is the day
 * one tenant reads another's documents. That is the single worst failure this
 * service can have, so it gets exactly one implementation.
 *
 * <h2>Why it hands back a seeded builder</h2>
 * It does not offer an "add the filters" method — a method you can forget to
 * call. It owns construction of the {@link BoolQuery.Builder} itself, so a query
 * builder <i>cannot</i> start assembling a query that lacks the scope clauses.
 * The filters are already in the object it receives.
 *
 * <h2>Why {@code filter} and not {@code must}</h2>
 * Clauses in filter context are not scored and are cached in the node query
 * cache. Authorisation must never influence ranking, and these two clauses are
 * identical for every user in a workspace, so caching them is free throughput.
 *
 * <p>Field names are parameters because the two mappings spell them differently
 * ({@code tenantId}/{@code workspaceId} vs {@code tenant_id}/{@code workspace_id}).
 * The <i>names</i> vary per mapping; the <i>guarantee</i> does not.
 *
 * <p>Exact {@code term} queries, never case-insensitive: these values come from
 * the gateway and are opaque identifiers. A case-insensitive match here would be
 * slower and looser on the one clause that must be neither. A casing mismatch
 * yields zero results — failing closed, which is the correct direction for a
 * security filter to fail.
 */
@Component
public class TenantFilter {

    /**
     * Returns a {@link BoolQuery.Builder} that already carries the tenant and
     * workspace filters. Callers add their own clauses on top; there is no way
     * to obtain a builder from here without them.
     *
     * @param identity      tenant/workspace resolved from gateway headers
     * @param tenantField   the mapping's tenant field name
     * @param workspaceField the mapping's workspace field name
     */
    public BoolQuery.Builder scopedQuery(RequestIdentity identity,
                                         String tenantField,
                                         String workspaceField) {

        if (identity == null
                || isBlank(identity.tenantId())
                || isBlank(identity.workspaceId())) {
            // Defence in depth. SearchController already rejects a request with
            // missing identity headers, so this cannot normally fire — but an
            // unscoped search would return every tenant's documents, and that
            // must not be reachable through a future caller that forgets the
            // check upstream.
            throw new IllegalStateException(
                    "Refusing to build an unscoped query: tenant/workspace identity is missing");
        }

        BoolQuery.Builder bool = new BoolQuery.Builder();
        bool.filter(exactTerm(tenantField, identity.tenantId()));
        bool.filter(exactTerm(workspaceField, identity.workspaceId()));
        return bool;
    }

    private Query exactTerm(String field, String value) {
        return Query.of(q -> q.term(t -> t.field(field).value(value)));
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
