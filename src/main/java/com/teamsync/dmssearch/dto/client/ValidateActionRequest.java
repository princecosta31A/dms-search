package com.teamsync.dmssearch.dto.client;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request body for {@code POST /api/permission/validate-action}.
 *
 * <p>Field names and types match document-service's {@code ValidateActionRequestDto}
 * exactly — permission-service expects {@code action} as a plain string, not an
 * enum name resolved by Jackson, so the conversion is explicit in {@link #from}.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ValidateActionRequest {

    private String userId;
    private String tenantId;
    private String workspaceId;
    /** Stays a String, exactly as permission-service expects. */
    private String action;

    public static ValidateActionRequest from(String userId, String tenantId,
                                             String workspaceId, PermissionAction action) {
        return ValidateActionRequest.builder()
                .userId(userId)
                .tenantId(tenantId)
                .workspaceId(workspaceId)
                .action(action.toString())
                .build();
    }
}
