package com.teamsync.dmssearch.dto.request;

/**
 * The caller's security context, taken from headers that gateway-service injects
 * after validating the token.
 *
 * <p>{@code tenantId} and {@code workspaceId} are <b>not</b> ordinary filters —
 * they are the authorisation boundary. They are applied to every query and can
 * never be supplied, overridden or disabled by a caller. See
 * {@code SearchQueryBuilder} where they are added before anything else.
 *
 * <p>{@code userId} is carried for audit logging only; it does not affect which
 * documents match, because access is workspace-scoped: a user with a role in a
 * workspace may see every document in it.
 *
 * <p><b>This trusts the gateway.</b> The headers are only meaningful if this
 * service is unreachable except through it — otherwise any caller can set
 * {@code X-Tenant-Id} and read another tenant's documents. Enforce that with a
 * NetworkPolicy or mesh mTLS, not by convention.
 */
public record RequestIdentity(String tenantId, String workspaceId, String userId) {

    public static final String HEADER_TENANT = "X-Tenant-Id";
    public static final String HEADER_WORKSPACE = "X-Workspace-Id";
    public static final String HEADER_USER = "X-User-Id";
}
