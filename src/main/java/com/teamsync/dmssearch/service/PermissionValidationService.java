package com.teamsync.dmssearch.service;

import com.teamsync.dmssearch.client.PermissionClient;
import com.teamsync.dmssearch.config.PermissionProperties;
import com.teamsync.dmssearch.dto.client.PermissionAction;
import com.teamsync.dmssearch.dto.client.PermissionResponse;
import com.teamsync.dmssearch.dto.client.ValidateActionRequest;
import com.teamsync.dmssearch.dto.error.SearchErrorCode;
import com.teamsync.dmssearch.dto.request.RequestIdentity;
import com.teamsync.dmssearch.exception.SearchException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Verifies the caller may read in the workspace they claim.
 *
 * <h2>Why this exists</h2>
 * The gateway derives {@code tenantId} and {@code userId} from the token, so
 * neither can be forged. It does <b>not</b> inject {@code workspaceId} — a user
 * belongs to several workspaces and chooses one per request, so that header is
 * caller-supplied.
 *
 * <p>Without this check, an authenticated user reads any workspace inside their
 * own tenant by changing one header. The tenant boundary holds; the workspace
 * boundary does not. Filtering on {@code workspaceId} enforces the rule but
 * never verifies the claim, which is exactly the gap this closes.
 *
 * <p>Mirrors document-service's {@code PermissionValidationService}, including
 * the {@code permission.service.enabled} toggle, so both behave the same way.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PermissionValidationService {

    private final PermissionClient permissionClient;
    private final PermissionProperties props;

    /**
     * Throws unless the caller has {@code FILE_READ} in the workspace.
     *
     * <p><b>Fails closed.</b> If permission-service is unreachable the request is
     * rejected with 503, never allowed through. An authorisation check that
     * defaults to "yes" when the authoriser is down is not a check — and the
     * failure would be invisible, since every search would keep succeeding.
     */
    public void requireFileRead(RequestIdentity identity) {
        if (!props.isEnabled()) {
            // Deliberately WARN, not DEBUG: while this is off, workspace
            // isolation is not enforced. That should be visible in any
            // environment's logs rather than something you have to know to look for.
            log.warn("Permission service is DISABLED — workspace membership NOT verified. "
                    + "Any caller may read any workspace within their tenant.");
            return;
        }

        ValidateActionRequest request = ValidateActionRequest.from(
                identity.userId(), identity.tenantId(), identity.workspaceId(),
                PermissionAction.FILE_READ);

        PermissionResponse<Boolean> response;
        try {
            response = permissionClient.validateAction(request);
        } catch (Exception e) {
            // Unreachable/timeout — 503, not 403. The caller may well be
            // authorised; we simply cannot tell, and saying "forbidden" would
            // send them off debugging their own permissions.
            log.error("[Permission] validate-action failed — userId={} workspaceId={} type={} msg={}",
                    identity.userId(), identity.workspaceId(),
                    e.getClass().getSimpleName(), e.getMessage());
            throw new SearchException(
                    SearchErrorCode.PERMISSION_BACKEND_UNAVAILABLE,
                    SearchErrorCode.PERMISSION_BACKEND_UNAVAILABLE.getDefaultMessage(),
                    null, e);
        }

        // A null body or null payload is not "allowed" — it is an answer we did
        // not get. Treat it exactly like a denial.
        boolean granted = response != null
                && response.getData() != null
                && response.getData();

        if (!granted) {
            log.warn("[Permission] DENIED — userId={} tenantId={} workspaceId={} action=FILE_READ",
                    identity.userId(), identity.tenantId(), identity.workspaceId());
            throw new SearchException(
                    SearchErrorCode.PERMISSION_DENIED,
                    "User lacks FILE_READ permission in workspace " + identity.workspaceId(),
                    null);
        }

        log.debug("[Permission] granted — userId={} workspaceId={}",
                identity.userId(), identity.workspaceId());
    }
}
