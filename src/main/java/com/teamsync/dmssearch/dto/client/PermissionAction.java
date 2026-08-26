package com.teamsync.dmssearch.dto.client;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Permission actions this service checks.
 *
 * <p>Only {@code FILE_READ} — dms-search never writes, so no other action can
 * apply here. document-service's enum carries the full set; copying all of it
 * would be dead values implying capabilities this service does not have.
 *
 * <p><b>The wire value is {@code "file.read"}, not {@code "FILE_READ"}.</b>
 * permission-service matches on the dotted form, so {@link #toString()} is
 * overridden to return it — exactly as document-service's enum does. Sending the
 * enum's own name instead produced {@code 404 "User role not found"}, which looks
 * like a missing role rather than an unrecognised action.
 */
@Getter
@RequiredArgsConstructor
public enum PermissionAction {

    FILE_READ("file.read");

    private final String value;

    @Override
    public String toString() {
        return value;   // serialised as "file.read"
    }
}
