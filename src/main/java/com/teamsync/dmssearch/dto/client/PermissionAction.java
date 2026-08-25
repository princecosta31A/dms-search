package com.teamsync.dmssearch.dto.client;

/**
 * Permission actions this service checks.
 *
 * <p>Only {@code FILE_READ} — dms-search never writes, so no other action can
 * ever apply here. document-service's enum carries the full set; copying all of
 * it would just be dead values implying capabilities this service does not have.
 *
 * <p>The name must match permission-service's expected string exactly; it is
 * sent as {@code action} in the validate-action payload.
 */
public enum PermissionAction {
    FILE_READ
}
