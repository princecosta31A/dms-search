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
import org.springframework.web.client.HttpClientErrorException;

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
     * <p><b>Fails closed</b>, but distinguishes two very different failures:
     * <ul>
     *   <li><b>Answered "no"</b> — {@code data: false}, or a 4xx such as
     *       {@code 404 "User role not found"} → <b>403</b>. Final; retrying
     *       cannot change it.</li>
     *   <li><b>No answer</b> — connection refused, timeout, 5xx → <b>503</b>.
     *       The caller may be authorised; we could not find out.</li>
     * </ul>
     * Collapsing these into one status (as this once did, reporting 503 for a
     * 404 denial) tells clients to retry a settled decision and sends them
     * chasing an outage that is not happening.
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

        } catch (HttpClientErrorException e) {
            // A 4xx is permission-service ANSWERING, not failing. The common case
            // is 404 "User role not found" — the user has no role in this
            // workspace, which is a denial and will never change on retry.
            //
            // Reporting 503 here (as this once did) is wrong twice over: it tells
            // the caller to retry a decision that is already final, and it points
            // them at an outage that is not happening. Same distinction
            // document-service draws in PermissionClientFallback — business error
            // vs infrastructure failure.
            log.warn("[Permission] DENIED by permission-service — userId={} tenantId={} "
                            + "workspaceId={} action=FILE_READ status={}",
                    identity.userId(), identity.tenantId(), identity.workspaceId(),
                    e.getStatusCode().value());
            throw new SearchException(
                    SearchErrorCode.PERMISSION_DENIED,
                    "User lacks FILE_READ permission in workspace " + identity.workspaceId(),
                    null, e);

        } catch (Exception e) {
            // No answer at all — connection refused, timeout, 5xx. 503, not 403:
            // the caller may well be authorised, we simply could not find out, and
            // saying "forbidden" would send them debugging permissions that are fine.
            log.error("[Permission] validate-action unreachable — userId={} workspaceId={} type={} msg={}",
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
